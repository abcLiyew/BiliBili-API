package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SearchService} 直接单测（fixture 驱动）。
 *
 * <p>覆盖四件事：
 * <ol>
 *   <li><b>结构解析正确</b>：{@code search/all/v2} 的 {@code result} 是 12 个异构分组，
 *       取视频必须走 {@code section("video")} 的 {@code data}，取错层级会得到空表却<b>不报错</b>；</li>
 *   <li><b>高亮剥离生效</b>：{@code getCleanTitle()} / {@code getCleanUname()} 是"给人看"的入口；</li>
 *   <li><b>参数校验在发请求之前</b>：空关键词不该白打一次出站（用 hitCount 断言真的没发）；</li>
 *   <li><b>失败形态</b>：业务码非 0、{@code data} 缺失、响应不是 JSON 都要变成
 *       {@link BilibiliException}，不能漏成 NPE 或 fastjson 异常。</li>
 * </ol>
 *
 * <p>⚠️ 两个搜索端点都走 {@code getSigned}（决定见 {@link SearchService} 类注释），
 * 所以每个用例都要先把 {@code nav} 注册好 —— 否则会先卡在"取不到 WBI 密钥"。
 */
@DisplayName("SearchService：综合搜索 + 分类搜索")
class SearchServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String ALL_PATH = "/x/web-interface/wbi/search/all/v2";
    private static final String TYPE_PATH = "/x/web-interface/wbi/search/type";

    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
    }

    @AfterEach
    void tearDown() {
        mock.close();
        WbiKeyStore.invalidate();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    // ================================================================
    // 综合搜索
    // ================================================================

    @Nested
    @DisplayName("searchAll")
    class SearchAllTest {

        @Test
        @DisplayName("解析 12 个分组，video 分组有内容（真实夹具）")
        void happyPath() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            SearchAllResult data = SearchService.INSTANCE.searchAll("测试", 1);

            assertNotNull(data.getResult());
            assertEquals(12, data.getResult().size(), "真实响应有 12 个分组");
            assertEquals(1, data.getPage());
            assertFalse(data.videos().isEmpty(),
                    "video 分组必须有内容 —— 取错层级会得到空表且不报错，这正是本用例的意义");
            assertNotNull(data.section("video"));
            assertNull(data.section("no-such-type"));
        }

        @Test
        @DisplayName("🔴 只有 video 分组有内容：其余 11 组是空数组（文档没写，实测如此）")
        void onlyVideoIsPopulated() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));
            SearchAllResult data = SearchService.INSTANCE.searchAll("测试", 1);

            long populated = data.getResult().stream()
                    .filter(s -> s.getData() != null && !s.getData().isEmpty()).count();
            assertEquals(1, populated,
                    "综合搜索的 12 组里实测只有 video 稳定有内容 —— 要用户结果必须走 searchUsers");
            assertTrue(data.users().isEmpty(),
                    "本夹具下 users() 为空，别在文档里承诺\"综合搜索顺带给用户\"");
        }

        @Test
        @DisplayName("关键词为空 → 抛 BilibiliException，且一次出站都没发")
        void blankKeyword() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.searchAll("   ", 1));
            assertTrue(e.getMessage().contains("关键词不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(ALL_PATH), "参数校验必须在发请求之前 —— 空关键词白打一次还伤出口信誉");
        }

        @Test
        @DisplayName("页码 < 1 被夹到 1（page=0 会拿到与预期不同的结果）")
        void pageNormalized() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            SearchService.INSTANCE.searchAll("测试", 0);

            assertTrue(mock.requestUri(ALL_PATH).contains("page=1"),
                    "实际：" + mock.requestUri(ALL_PATH));
        }
    }

    // ================================================================
    // 分类搜索
    // ================================================================

    @Nested
    @DisplayName("searchVideos / searchUsers")
    class SearchTypeTest {

        @Test
        @DisplayName("视频搜索：解析出 3 条，且 cleanTitle 已剥离高亮标签")
        void videos() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-video.json"));

            SearchTypeResult<SearchVideo> page = SearchService.INSTANCE.searchVideos("测试", 1);

            assertEquals(3, page.getResult().size());
            assertEquals(1000, page.getNumResults());
            assertEquals(1, page.getPage());
            assertEquals(3, page.size());

            SearchVideo first = page.getResult().get(0);
            assertTrue(first.getTitle().contains("<em class=\"keyword\">"),
                    "服务端原始 title 里带高亮标签（这是本用例的前提）：" + first.getTitle());
            assertFalse(first.getCleanTitle().contains("<em"),
                    "cleanTitle 必须已剥离：" + first.getCleanTitle());
            assertEquals(HighlightExpectation.CLEAN_FIRST_TITLE, first.getCleanTitle());
            assertNotNull(first.getBvid());
        }

        @Test
        @DisplayName("用户搜索：解析出 3 条，cleanUname 与原始一致（本样本无高亮）")
        void users() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-user.json"));

            SearchTypeResult<SearchUser> page = SearchService.INSTANCE.searchUsers("测试", 1);

            assertEquals(3, page.getResult().size());
            SearchUser first = page.getResult().get(0);
            assertNotNull(first.getUname());
            assertEquals(first.getUname(), first.getCleanUname(),
                    "本样本的 uname 不含高亮，clean 版应逐字相同（不是被清空）");
            assertNotNull(first.getMid());
        }

        @Test
        @DisplayName("两个方法发出的 search_type 不同（video / bili_user）")
        void searchTypeParam() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-video.json"));

            SearchService.INSTANCE.searchVideos("测试", 1);
            assertTrue(mock.requestUri(TYPE_PATH).contains("search_type=video"),
                    "实际：" + mock.requestUri(TYPE_PATH));

            SearchService.INSTANCE.searchUsers("测试", 1);
            assertTrue(mock.requestUri(TYPE_PATH).contains("search_type=bili_user"),
                    "实际：" + mock.requestUri(TYPE_PATH));
        }

        @Test
        @DisplayName("关键词为空 → 抛异常且没发请求")
        void blankKeyword() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.searchVideos("", 1));
            assertTrue(e.getMessage().contains("关键词不能为空"));
            assertEquals(0, mock.hitCount(TYPE_PATH));
        }
    }

    // ================================================================
    // 失败形态
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("业务码非 0 → BilibiliException 带业务码（不是静默空结果）")
        void businessCode() throws Exception {
            mock.register(ALL_PATH, "{\"code\":-412,\"message\":\"请求被拦截\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.searchAll("测试", 1));
            assertEquals(-412, e.getCode());
            assertTrue(e.getMessage().contains("综合搜索失败"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("code=0 但 data 为空 → 也抛（data 为空不是\"搜不到\"，是响应形状不对）")
        void nullData() throws Exception {
            mock.register(ALL_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.searchAll("测试", 1));
            assertTrue(e.getMessage().contains("综合搜索"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("响应不是 JSON → BilibiliException（带响应原文片段，排障要用）")
        void notJson() throws Exception {
            mock.register(ALL_PATH, "<html>502 Bad Gateway</html>");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.searchAll("测试", 1));
            assertTrue(e.getMessage().contains("无法解析"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("502 Bad Gateway"), "原文片段必须带上：" + e.getMessage());
            // 分类短语走 description 字段（BilibiliException 的第二参），不在 message 里
            assertEquals("响应形状不符", e.getDescription());
        }

        @Test
        @DisplayName("HTTP 非 2xx → BilibiliException 带 HTTP 状态")
        void httpError() {
            mock.registerStatus(ALL_PATH, 500, "");
            // 500 属可重试：BilibiliHttp 会退避重试几轮才返回，这里只断言最终语义
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.searchAll("测试", 1));
            assertNotNull(e.getMessage());
        }
    }

    /** 期望值集中放这里，避免散落在断言里看不出"它来自哪" */
    private static final class HighlightExpectation {
        /** 与 {@code search-type-video.json} 第一条 title 对应（逐字） */
        static final String CLEAN_FIRST_TITLE =
                "【2026最新】B站最全最细的软件测试教程，7天从零基础小白到精通软件测试，学完即上岗！";

        private HighlightExpectation() {
        }
    }
}

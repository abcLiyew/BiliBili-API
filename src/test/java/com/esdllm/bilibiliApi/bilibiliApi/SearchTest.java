package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Search 门面回归测试</b>（B3 批新增的第 7 个门面）。
 *
 * <p>门面自身<b>没有业务逻辑</b>，它只是"Service → 调用方"之间的边界层，价值全在两件事上，
 * 所以本测试也只钉这两件：
 * <ol>
 *   <li><b>泛型实参不能写错</b>：{@code searchVideos} 必须回 {@code SearchTypeResult<SearchVideo>}、
 *       {@code searchUsers} 回 {@code SearchTypeResult<SearchUser>}。三者在字节码里被擦成同一个类，
 *       编译期看不见 —— 所以这里靠"把返回值当强类型用"来守：泛型一旦写错（例如两个方法
 *       共用了一个 raw 返回），下面的赋值与取字段就会失败，而不是静默地让调用方拿错类型；</li>
 *   <li><b>异常边界</b>：{@link BilibiliException} 是 <b>runtime</b> 异常，门面若不 catch 它会
 *       原样逃出去。这里断言拿到的是 {@link IOException}、{@code getCause()} 就是原异常、
 *       且 {@code getMessage()} 保留了内层文案 —— "包装成 IOException"是 XatiiBot 的既有契约，
 *       不能悄悄变成"吃掉异常"或"换掉文案"（换掉文案等于把排障线索一起丢掉）。</li>
 * </ol>
 *
 * <p>解析层面的细节（12 个分组的结构、高亮标签剥离、页码夹取、非 JSON 响应）在
 * {@code SearchServiceTest} 里已经逐条断言过，<b>这里不重复造那些夹具断言</b>。
 *
 * <p>⚠️ 两个搜索端点实测<b>免签名</b>，但 {@code SearchService} 仍走 {@code getSigned}
 * （理由见该类注释），所以每个用例都先把 {@code nav} 注册好，否则会先卡在"取不到 WBI 密钥"。
 */
@DisplayName("门面：Search（搜索）")
class SearchTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String ALL_PATH = "/x/web-interface/wbi/search/all/v2";
    private static final String TYPE_PATH = "/x/web-interface/wbi/search/type";

    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private MockBiliServer mock;
    private Search search;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
        search = new Search();
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
    // 委托与泛型
    // ================================================================

    @Nested
    @DisplayName("委托与泛型实参")
    class DelegationTest {

        @Test
        @DisplayName("searchVideos 回 SearchTypeResult<SearchVideo>，元素可当 SearchVideo 用")
        void videos() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-video.json"));

            SearchTypeResult<SearchVideo> page = search.searchVideos("测试", 1);

            // 泛型实参若被写错，下面这两行编译期就会塌
            SearchVideo first = page.getResult().get(0);
            assertFalse(first.getCleanTitle().contains("<em"),
                    "cleanTitle 必须是剥过高亮标签的：" + first.getCleanTitle());
            assertEquals(3, page.size(), "本页 3 条（夹具定值）");
            assertEquals(1, page.getPage());
        }

        @Test
        @DisplayName("searchUsers 回 SearchTypeResult<SearchUser>，且与 videos 是两个类型（不共用 raw 返回）")
        void users() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-user.json"));

            SearchTypeResult<SearchUser> page = search.searchUsers("测试", 1);

            SearchUser first = page.getResult().get(0);
            assertNotNull(first.getUname());
            assertNotNull(first.getMid());
            assertEquals(3, page.size());
        }

        @Test
        @DisplayName("searchAll 回 SearchAllResult，videos() 是取值主路（12 分组里只有它稳定有内容）")
        void all() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            SearchAllResult result = search.searchAll("测试", 1);

            assertFalse(result.videos().isEmpty(), "综合搜索的主路是 videos()");
            assertNotNull(result.getResult(), "12 个分组原样保留");
        }

        @Test
        @DisplayName("门面照传页码：page=0 被归一成 1（不把 0 原样发出去）")
        void pageNormalized() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            search.searchAll("测试", 0);

            assertTrue(mock.requestUri(ALL_PATH).contains("page=1"),
                    "实际：" + mock.requestUri(ALL_PATH));
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("空关键词：包装成 IOException，内层消息保留，且一个出站都没发")
        void blankKeyword() {
            IOException e = assertThrows(IOException.class, () -> search.searchVideos("  ", 1));

            assertTrue(e.getMessage().contains("关键词不能为空"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause(),
                    "cause 必须是原 BilibiliException —— 丢了 cause 就等于丢了堆栈");
            // 本地参数校验在发请求之前，所以搜索端点一次都没被命中
            assertEquals(0, mock.hitCount(TYPE_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH), "连 WBI 密钥都不该去取");
        }

        @Test
        @DisplayName("业务码非 0：也走 IOException，且业务码与文案都没丢")
        void businessCode() throws Exception {
            mock.register(TYPE_PATH, "{\"code\":-412,\"message\":\"请求被拦截\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> search.searchUsers("测试", 1));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-412, cause.getCode(), "业务码要能被调用方读到，否则无法区分风控与参数错");
            assertTrue(e.getMessage().contains("用户搜索失败"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("请求被拦截"), "服务端原话要带上：" + e.getMessage());
        }

        @Test
        @DisplayName("响应不是 JSON：包成 IOException，而不是漏出 fastjson 异常")
        void notJson() throws Exception {
            mock.register(TYPE_PATH, "<html>502 Bad Gateway</html>");

            IOException e = assertThrows(IOException.class, () -> search.searchVideos("测试", 1));

            assertInstanceOf(BilibiliException.class, e.getCause());
            assertTrue(e.getMessage().contains("无法解析"), "实际：" + e.getMessage());
        }
    }
}

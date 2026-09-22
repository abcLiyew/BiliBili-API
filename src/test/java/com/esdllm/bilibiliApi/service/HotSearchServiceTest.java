package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.search.HotSearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code SearchService#getHotSearch}</b>（热搜榜）的回归测试（2026-09-22 B2 批 #3）。
 *
 * <p>本文件守三件光看代码看不出来事：
 * <ol>
 *   <li>🔴 <b>榜单在 {@code data.trending} 里</b>，不是 {@code data} 本身 ——
 *       只映射 {@code data} 会拿到一个空对象，而且<b>不报错</b>。</li>
 *   <li>🔴 <b>{@code trackid} 已超出 {@code long} 范围</b>
 *       （实测 {@code 12414231099029457647} &gt; {@code 9223372036854775807}）。
 *       当成数字反序列化会溢出，所以这里刻意断言"它确实 parse 不成 Long"。</li>
 *   <li>🔴 <b>JSON 键 {@code goto} 在 Java 里是保留字</b>，字段只能叫 {@code goTo}。
 *       这条映射靠 fastjson 的大小写不敏感回退接上 —— 所以必须有一个用例
 *       <b>带着非空 {@code goto} 值</b>来证明它真的接上了（真实响应里这个值恒为空串，
 *       光用夹具是测不出来的：null 与 "" 之外的第三种可能"没映射"会被 "" 掩盖）。</li>
 * </ol>
 */
@DisplayName("服务：SearchService#getHotSearch（热搜榜）")
class HotSearchServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String SQUARE_PATH = "/x/web-interface/search/square";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    // ================================================================
    // 形状
    // ================================================================

    @Nested
    @DisplayName("响应形状")
    class ShapeTest {

        @Test
        @DisplayName("★ 榜单在 data.trending 里（不是 data 本身）")
        void trendingIsNested() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            HotSearch data = SearchService.INSTANCE.getHotSearch(10);

            assertNotNull(data.getTrending(), "★ data 顶层只有 trending 这一个键");
            assertEquals("bilibili热搜", data.getTrending().getTitle());
            assertEquals(3, data.getTrending().getList().size(), "夹具裁自真实响应（原 10 条）");
        }

        @Test
        @DisplayName("条目字段：keyword / show_name / icon / heat_score")
        void itemFields() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            HotSearch.Item first = SearchService.INSTANCE.getHotSearch(10).getTrending().getList().get(0);

            assertEquals("深度复盘IG战胜JDG晋级世界赛", first.getKeyword());
            assertEquals("深度复盘IG战胜JDG晋级世界赛", first.getShow_name(),
                    "本批实测 show_name 与 keyword 相同；运营改词的榜可能不同，所以两个字段都留着");
            assertEquals(2976633L, first.getHeat_score());
            assertTrue(first.getIcon().startsWith("http://i0.hdslb.com/"),
                    "实际：" + first.getIcon());
        }

        @Test
        @DisplayName("★ 热度递减（榜单是有序的，别自己再排）")
        void orderedByHeat() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            var list = SearchService.INSTANCE.getHotSearch(10).getTrending().getList();

            assertTrue(list.get(0).getHeat_score() > list.get(1).getHeat_score());
            assertTrue(list.get(1).getHeat_score() > list.get(2).getHeat_score());
        }

        @Test
        @DisplayName("top_list 实测是空数组 —— 是空列表而不是 null")
        void topList() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            var top = SearchService.INSTANCE.getHotSearch(10).getTrending().getTop_list();

            assertNotNull(top, "★ 空数组会填成空 List，不是 null（null 只出现在键不存在时）");
            assertTrue(top.isEmpty());
        }

        @Test
        @DisplayName("★ trackid 是字符串，而且【parse 不成 long】—— 这是它必须是 String 的实证")
        void trackidOverflowsLong() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            String trackid = SearchService.INSTANCE.getHotSearch(10).getTrending().getTrackid();

            assertEquals("12414231099029457647", trackid);
            assertThrows(NumberFormatException.class, () -> Long.parseLong(trackid),
                    "★ 实测值 " + trackid + " 已超过 Long.MAX_VALUE(9223372036854775807)。"
                            + "若按数值反序列化会溢出成负数 —— 所以本库把它留成字符串，"
                            + "调用方也别 parseLong");
        }

        @Test
        @DisplayName("★ JSON 键 goto → 字段 goTo：用非空值证明这条映射真的接上了")
        void gotoSegmentMapsToGoToField() throws Exception {
            mock.register(SQUARE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{\"trending\":{"
                    + "\"title\":\"t\",\"trackid\":\"1\",\"list\":["
                    + "{\"keyword\":\"k\",\"show_name\":\"s\",\"icon\":\"\",\"uri\":\"bilibili://a\","
                    + "\"goto\":\"av\",\"heat_score\":1}],\"top_list\":[]}}}");

            HotSearch.Item item = SearchService.INSTANCE.getHotSearch(10).getTrending().getList().get(0);

            assertEquals("av", item.getGoTo(),
                    "★ 字段叫 goTo（goto 是 Java 保留字），靠大小写不敏感回退匹配接上。"
                            + "拿不到值就说明这条回退在某处失效了 —— 调用方会静默看到 null");
            assertEquals("bilibili://a", item.getUri());
            assertTrue(hasField(HotSearch.Item.class, "goTo"),
                    "字段名必须是 goTo（不能是 goto —— 那不是合法 Java 标识符）");
        }

        private static boolean hasField(Class<?> owner, String name) {
            try {
                Field f = owner.getDeclaredField(name);
                return f.getType() == String.class;
            } catch (NoSuchFieldException e) {
                return false;
            }
        }
    }

    // ================================================================
    // 请求形状
    // ================================================================

    @Nested
    @DisplayName("请求形状")
    class RequestTest {

        @Test
        @DisplayName("limit 透传；≤0 按 10；过大的值夹到 50")
        void limit() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            SearchService.INSTANCE.getHotSearch(5);
            assertTrue(mock.requestUri(SQUARE_PATH).contains("limit=5"),
                    "实际：" + mock.requestUri(SQUARE_PATH));

            SearchService.INSTANCE.getHotSearch(0);
            assertTrue(mock.requestUri(SQUARE_PATH).contains("limit=" + SearchService.DEFAULT_HOT_LIMIT),
                    "实际：" + mock.requestUri(SQUARE_PATH));

            SearchService.INSTANCE.getHotSearch(5000);
            assertTrue(mock.requestUri(SQUARE_PATH).contains("limit=" + SearchService.MAX_HOT_LIMIT),
                    "★ 荒谬值夹紧后再发出去，而不是把 5000 交给服务端去猜。实际："
                            + mock.requestUri(SQUARE_PATH));
        }

        @Test
        @DisplayName("★ 一次 nav 都不打：本端点不需要 WBI 签名（与 searchAll/searchVideos 不同）")
        void noSigning() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            SearchService.INSTANCE.getHotSearch(10);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 本端点的响应里连签名相关字段都没有，给它签名只是白算一次");
        }

        @Test
        @DisplayName("Referer 用站根（本端点对 Referer 不敏感）")
        void referer() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            SearchService.INSTANCE.getHotSearch(10);

            assertEquals("https://www.bilibili.com/", mock.requestHeader(SQUARE_PATH, "Referer"));
        }
    }

    // ================================================================
    // 失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("★ code=0 但榜单为空：抛异常（该端点匿名可用，空榜单不能再用'缺凭据'解释）")
        void emptyBoardThrows() {
            mock.register(SQUARE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"trending\":{\"title\":\"t\",\"trackid\":\"1\",\"list\":[],\"top_list\":[]}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.getHotSearch(10));

            assertEquals(0, e.getCode());
            assertTrue(e.getMessage().contains("trending.list 为空"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("形状"),
                    "★ 文案要点出这条与该端点无关的原因（形状变了/风控），"
                            + "而不是让人去查凭据。实际：" + e.getDescription());
        }

        @Test
        @DisplayName("trending 整个缺失：也抛（同样是'没给出数据'）")
        void missingTrending() {
            mock.register(SQUARE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{}}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.getHotSearch(10)).getCode());
        }

        @Test
        @DisplayName("data 为 null：抛异常")
        void nullData() {
            mock.register(SQUARE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.getHotSearch(10)).getCode());
        }

        @Test
        @DisplayName("HTTP 412 风控：码值保住 412")
        void http412() {
            mock.registerStatus(SQUARE_PATH, 412, "");

            assertEquals(412, assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.getHotSearch(10)).getCode());
        }

        @Test
        @DisplayName("raw JSON 里不存在的键不影响：未映射字段由 fastjson 直接忽略")
        void unknownKeysIgnored() throws Exception {
            mock.register(SQUARE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{\"trending\":{"
                    + "\"title\":\"t\",\"trackid\":\"1\",\"brand_new_key\":{\"a\":1},"
                    + "\"list\":[{\"keyword\":\"k\",\"heat_score\":9,\"future\":true}],"
                    + "\"top_list\":[]}}}");

            HotSearch data = SearchService.INSTANCE.getHotSearch(10);

            assertEquals("k", data.getTrending().getList().get(0).getKeyword());
            assertNull(data.getTrending().getList().get(0).getIcon(),
                    "★ 键不存在时是 null（不是空串）—— 判空要用 == null || isBlank");
        }
    }
}

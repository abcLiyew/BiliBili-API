package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Wbi 门面回归测试</b>（第 10 个门面：把签名能力对外交付）。
 *
 * <p>算法口径本身在 {@code WbiSignerTest} 里已经逐条锁死（编码三口径、{@code w_rid} 自指、
 * MD5 向量），<b>这里不重复</b>。本测试只钉门面独有的三件事：
 * <ol>
 *   <li><b>端到端串得起来</b>：{@code nav} 下发示例密钥 → 门面算出签名，结果必须与文档黄金用例
 *       <b>逐字相同</b>。这一条把"取密钥 / 重排 / 排序 / 编码 / 拼接"五步一次串通 ——
 *       任何一步错，期望值都对不上；</li>
 *   <li><b>出站次数</b>：密钥一天只取一次，{@code invalidateKeys()} 之后才重取 ——
 *       这是门面相对"自己去取 key + 自己算"的真正增量，必须可断言；</li>
 *   <li><b>失败语义</b>：取不到密钥时是 {@link IOException}（可重试），而不是 null 或运行时异常；
 *       并且失败后进入冷却、<b>不再反复撞 nav</b>。</li>
 * </ol>
 */
@DisplayName("门面：Wbi（WBI 签名）")
class WbiTest {

    private static final String NAV_PATH = "/x/web-interface/nav";

    /**
     * nav 的夹具：<b>未登录</b>（{@code code=-101}）但照常下发 {@code data.wbi_img}。
     *
     * <p>这两个文件名就是文档正文给出的示例密钥对，所以下面的黄金用例可以直接对上文档值。
     * 顺带钉住"密钥与登录态无关"：{@code code=-101} 时密钥照样有值，判空只能判 {@code wbi_img}。
     */
    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private static final String IMG_KEY = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45";

    /** 文档 PHP demo 注释里的黄金用例：{@code wts} 一固定，{@code w_rid} 就是确定值 */
    private static final long GOLDEN_WTS = 1700384803L;
    private static final String GOLDEN_QUERY =
            "bar=514&baz=1919810&foo=114&wts=1700384803&w_rid=4614cb98d60a43e50c3a3033fe3d116b";

    private MockBiliServer mock;
    private Wbi wbi;

    @BeforeEach
    void setUp() {
        // 密钥是静态缓存，跨用例必须清 —— 否则"只打一次 nav"这类断言会被上一个用例的缓存污染
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
        wbi = new Wbi();
    }

    @AfterEach
    void tearDown() {
        mock.close();
        WbiKeyStore.invalidate();
    }

    /** 文档黄金用例用的那组参数（刻意乱序，顺带钉"入参顺序无关"） */
    private static Map<String, String> goldenParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("foo", "114");
        params.put("bar", "514");
        params.put("baz", "1919810");
        return params;
    }

    // ================================================================
    // 端到端：与文档黄金用例对齐
    // ================================================================

    @Nested
    @DisplayName("端到端签名（对齐文档黄金用例）")
    class SigningTest {

        @Test
        @DisplayName("nav 下发示例密钥 → 签出的 query 与文档定值逐字相同")
        void goldenVector() throws Exception {
            assertEquals(GOLDEN_QUERY, wbi.signQuery(goldenParams(), GOLDEN_WTS),
                    "五步（取密钥/重排/排序/编码/拼接）任一步错，这里都对不上");
        }

        @Test
        @DisplayName("离线重载：结果与在线一致，且一次 nav 都不打")
        void offlineOverload() throws Exception {
            String offline = wbi.signQuery(goldenParams(), IMG_KEY, SUB_KEY, GOLDEN_WTS);

            assertEquals(GOLDEN_QUERY, offline);
            assertEquals(0, mock.hitCount(NAV_PATH),
                    "自带密钥的重载不该产生任何出站 —— 它存在的意义正是绕开 nav");
        }

        @Test
        @DisplayName("带空格的值编成 %20（不是 +）：这条错一次，服务端只回 -403")
        void percentTwentyNotPlus() throws Exception {
            String query = wbi.signQuery(Map.of("keyword", "one one four"), GOLDEN_WTS);

            assertTrue(query.contains("keyword=one%20one%20four"), "实际：" + query);
            assertFalse(query.contains("+"),
                    "出现 + 说明走了 form 编码器口径，服务端必然判签名错：" + query);
        }

        @Test
        @DisplayName("不带 wts 的重载用当前秒（不是 0、也不是毫秒）")
        void currentSecondByDefault() throws Exception {
            long before = System.currentTimeMillis() / 1000L;
            String query = wbi.signQuery(Map.of("mid", "946974"));

            long wts = Long.parseLong(query.substring(query.indexOf("wts=") + 4, query.indexOf("&w_rid=")));
            assertTrue(wts >= before && wts <= System.currentTimeMillis() / 1000L + 1,
                    "wts 应是秒级当前时间：" + wts);
            // 毫秒级会被服务端认为签名过期；10 位这个形状是秒级最直接的判据
            assertEquals(10, String.valueOf(wts).length(), "秒级时间戳应为 10 位：" + wts);
        }
    }

    // ================================================================
    // 出站次数：密钥缓存
    // ================================================================

    @Nested
    @DisplayName("密钥缓存（出站次数可断言）")
    class KeyCacheTest {

        @Test
        @DisplayName("连签两次只打一次 nav（当天密钥复用）")
        void fetchedOncePerDay() throws Exception {
            wbi.signQuery(Map.of("mid", "1"), GOLDEN_WTS);
            wbi.signQuery(Map.of("mid", "2"), GOLDEN_WTS);

            assertEquals(1, mock.hitCount(NAV_PATH),
                    "密钥按天轮换，不缓存就会每次签名都多打一次 nav（连发请求正是 B 站风控的诱因）");
        }

        @Test
        @DisplayName("invalidateKeys() 之后重取一次（签名被服务端拒时的排障手法）")
        void invalidateForcesRefetch() throws Exception {
            wbi.signQuery(Map.of("mid", "1"), GOLDEN_WTS);
            assertEquals(1, mock.hitCount(NAV_PATH));

            wbi.invalidateKeys();
            wbi.signQuery(Map.of("mid", "1"), GOLDEN_WTS);

            assertEquals(2, mock.hitCount(NAV_PATH), "丢缓存后必须真的重取，否则这个方法没有意义");
        }
    }

    // ================================================================
    // 失败形态
    // ================================================================

    @Nested
    @DisplayName("失败形态（取不到密钥 → IOException，可重试）")
    class FailureTest {

        @Test
        @DisplayName("nav 形状变了（响应里没有 data.wbi_img）→ IOException，文案要说清原因与冷却")
        void shapeChanged() {
            mock.register(NAV_PATH, "{\"code\":0,\"data\":{}}");

            IOException e = assertThrows(IOException.class, () -> wbi.signQuery(Map.of("mid", "1")));

            assertTrue(e.getMessage().contains("取不到 WBI 密钥"), "实际：" + e.getMessage());
            // 调用方要据此判断"稍后重试"，所以冷却时长必须写在文案里，而不是只给一句"失败"
            assertTrue(e.getMessage().contains("冷却"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("nav 返回非 2xx（404）→ 同样是 IOException，不漏出运行时异常")
        void nonTwoHundred() {
            mock.registerStatus(NAV_PATH, 404, "{\"code\":-404,\"message\":\"not found\"}");

            assertThrows(IOException.class, () -> wbi.signQuery(Map.of("mid", "1")));
        }

        @Test
        @DisplayName("失败后进入冷却：紧接着的第二次调用直接快失败，不再撞 nav")
        void failureCooldown() {
            mock.registerStatus(NAV_PATH, 404, "{\"code\":-404,\"message\":\"not found\"}");

            assertThrows(IOException.class, () -> wbi.signQuery(Map.of("mid", "1")));
            int afterFirst = mock.hitCount(NAV_PATH);

            assertThrows(IOException.class, () -> wbi.signQuery(Map.of("mid", "1")));

            assertEquals(afterFirst, mock.hitCount(NAV_PATH),
                    "冷却期内不该再撞 nav —— 出口正被风控时反复重试只会让情况更糟");
        }
    }

    // ================================================================
    // signedUrl
    // ================================================================

    @Nested
    @DisplayName("signedUrl")
    class SignedUrlTest {

        @Test
        @DisplayName("把签名挂到 URL 上：形如 base?<签名后的 query>")
        void appends() throws Exception {
            String url = wbi.signedUrl("https://api.bilibili.com/x/space/wbi/acc/info", goldenParams());

            assertTrue(url.startsWith("https://api.bilibili.com/x/space/wbi/acc/info"
                    + "?bar=514&baz=1919810&foo=114&wts="), "实际：" + url);
            assertTrue(url.contains("&w_rid="), "实际：" + url);
        }

        @Test
        @DisplayName("baseUrl 自带 query → 当场 IllegalArgumentException，且一个出站都不发")
        void baseUrlWithQueryRejected() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> wbi.signedUrl("https://api.bilibili.com/x/a?mid=1", Map.of("mid", "2")));

            assertTrue(e.getMessage().contains("漏签"), "要把后果讲清楚：" + e.getMessage());
            assertEquals(0, mock.hitCount(NAV_PATH), "这是参数校验，应在取密钥之前就失败");
        }

        @Test
        @DisplayName("baseUrl 为空 → IllegalArgumentException")
        void blankBaseUrlRejected() {
            assertThrows(IllegalArgumentException.class, () -> wbi.signedUrl("  ", Map.of("mid", "1")));
            assertThrows(IllegalArgumentException.class, () -> wbi.signedUrl(null, Map.of("mid", "1")));
        }
    }
}

package com.esdllm.bilibiliApi.http;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import com.esdllm.bilibiliApi.sign.WbiSigner;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WBI 签名出口（{@link BilibiliHttp#getSigned}）测试。
 *
 * <p><b>为什么这一层必须单测</b>：签名请求的成败全在 query 里，而它一旦拼错，
 * 服务端只会回一个 {@code -403 访问权限不足} —— 与服务端"真·权限不足"完全同形，
 * 从响应侧<b>一个字都反推不出来</b>。所以判据只能是"把发出去的 query 读回来，用同一份密钥复算一遍"。
 * 为此 {@code MockBiliServer} 增加了 {@code requestUri(前缀)}。
 *
 * <p>覆盖三段行为：
 * <ol>
 *   <li>query 逐字节正确（参数 + {@code wts} + {@code w_rid} 可复算）；</li>
 *   <li><b>签名被拒时重签一次</b> —— 且只在"密钥真的变了"时才重发
 *       （盲目重发会把同一次失败打两遍，还多消耗一代出口信誉）；</li>
 *   <li>取不到密钥时<b>明确失败</b>，而不是"发一个没签名的请求试试"。</li>
 * </ol>
 */
@DisplayName("BilibiliHttp 签名出口")
class BilibiliHttpSignedTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String ACC_PATH = "/x/space/wbi/acc/info";
    private static final String ACC_URL = "https://api.bilibili.com/x/space/wbi/acc/info";

    private static final String IMG_A = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB_A = "4932caff0ff746eab6f01bf08b70ac45";
    private static final String IMG_B = "aaaaaaaa11111111bbbbbbbb22222222";
    private static final String SUB_B = "cccccccc33333333dddddddd44444444";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
        WbiKeyStore.invalidate();
    }

    private static String navBody(String img, String sub) {
        return "{\"code\":-101,\"message\":\"账号未登录\",\"data\":{\"isLogin\":false,\"wbi_img\":{"
                + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/" + img + ".png\","
                + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/" + sub + ".png\"}}}";
    }

    /** 把 URI 的 query 部分拆成有序键值表（不做 URL 解码 —— 我们要比对编码后的原文） */
    private static Map<String, String> queryOf(String uri) {
        Map<String, String> pairs = new LinkedHashMap<>();
        int q = uri.indexOf('?');
        if (q < 0) {
            return pairs;
        }
        for (String pair : uri.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            pairs.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
        }
        return pairs;
    }

    // ================================================================
    // 1) query 正确性
    // ================================================================

    @Nested
    @DisplayName("query 拼装")
    class QueryTest {

        @Test
        @DisplayName("发出去的 query 能用同一份密钥逐字节复算（参数 + wts + w_rid）")
        void queryIsReproducible() {
            mock.register(NAV_PATH, navBody(IMG_A, SUB_A));
            mock.register(ACC_PATH, "{\"code\":0,\"data\":{\"mid\":946974}}");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("mid", "946974");

            HttpResponse<String> response = BilibiliHttp.getSigned(
                    ACC_URL, params, "application/json, text/plain, */*", "https://space.bilibili.com/946974");
            assertEquals(200, response.getStatus());

            String uri = mock.requestUri(ACC_PATH);
            String query = uri.substring(uri.indexOf('?') + 1);
            Map<String, String> pairs = queryOf(uri);

            assertEquals("946974", pairs.get("mid"), "原始参数必须原样发出：" + query);
            assertNotNull(pairs.get("wts"), "query 里必须有 wts：" + query);
            assertNotNull(pairs.get("w_rid"), "query 里必须有 w_rid：" + query);
            assertEquals(32, pairs.get("w_rid").length());

            // ★ 核心断言：整串 query 必须与"用同一份密钥、同一个时间戳再签一次"完全一致。
            //   差一个字符（编码口径、排序、拼接顺序）就会与服务端算出的 w_rid 不同。
            String expected = WbiSigner.sign(params, IMG_A, SUB_A, Long.parseLong(pairs.get("wts")));
            assertEquals(expected, query, "query 与服务端口径不一致 —— 线上会得到一个查不出原因的 -403");
        }

        @Test
        @DisplayName("参数值带空格：编成 %20（用 + 会被服务端判签名错，线上已验证）")
        void spaceIsPercent20OnTheWire() {
            mock.register(NAV_PATH, navBody(IMG_A, SUB_A));
            mock.register(ACC_PATH, "{\"code\":0,\"data\":{\"mid\":1}}");

            BilibiliHttp.getSigned(ACC_URL, Map.of("keyword", "one one four"),
                    "application/json", "https://www.bilibili.com/");

            String uri = mock.requestUri(ACC_PATH);
            assertTrue(uri.contains("keyword=one%20one%20four"), "实际：" + uri);
            assertFalse(uri.contains("one+one+four"), "空格绝不能编成 +：" + uri);
        }

        @Test
        @DisplayName("端点 URL 已含 query 时用 & 衔接（不是再拼一个 ?）")
        void signedUrlJoinsWithAmpersand() {
            WbiKeyStore.WbiKeys keys = new WbiKeyStore.WbiKeys(IMG_A, SUB_A, 0L);
            String url = BilibiliHttp.signedUrl("https://api.bilibili.com/x/a?p=1", Map.of("mid", "2"), keys);
            assertTrue(url.startsWith("https://api.bilibili.com/x/a?p=1&mid=2&wts="), "实际：" + url);
            assertEquals(1, url.chars().filter(c -> c == '?').count(), "只能有一个问号：" + url);
        }

        @Test
        @DisplayName("签名后走的仍是 get()：Referer / Accept 覆盖生效（签名不是特权通道）")
        void stillRespectsRefererAndAccept() {
            mock.register(NAV_PATH, navBody(IMG_A, SUB_A));
            mock.register(ACC_PATH, "{\"code\":0,\"data\":{}}");

            BilibiliHttp.getSigned(ACC_URL, Map.of("mid", "1"),
                    "application/json, text/plain, */*", "https://space.bilibili.com/946974");

            assertEquals("https://space.bilibili.com/946974",
                    mock.requestHeader(ACC_PATH, "Referer"));
            assertTrue(mock.requestHeader(ACC_PATH, "Accept").contains("application/json"));
        }
    }

    // ================================================================
    // 2) 签名被拒 → 重签一次
    // ================================================================

    @Nested
    @DisplayName("签名被拒后的重签")
    class ResignTest {

        @Test
        @DisplayName("密钥变了 → 丢缓存重取并重发一次（结果用第二次的响应）")
        void resignsWhenKeysChanged() {
            mock.registerSequence(NAV_PATH, navBody(IMG_A, SUB_A), navBody(IMG_B, SUB_B));
            mock.registerSequence(ACC_PATH,
                    "{\"code\":-403,\"message\":\"访问权限不足\"}",
                    "{\"code\":0,\"data\":{\"mid\":946974}}");

            HttpResponse<String> response = BilibiliHttp.getSigned(
                    ACC_URL, Map.of("mid", "946974"), "application/json", "https://www.bilibili.com/");

            assertEquals(200, response.getStatus());
            assertTrue(response.getBody().contains("\"code\":0"),
                    "重签后应当采用第二次的响应，实际：" + response.getBody());
            assertEquals(2, mock.hitCount(ACC_PATH), "密钥换了就必须重发一次");
            assertEquals(2, mock.hitCount(NAV_PATH), "应当先把 nav 重取一次");
        }

        @Test
        @DisplayName("重取到的密钥与缓存一致 → 不重发（问题不在签名，重发只会把失败打两遍）")
        void doesNotResignWhenKeysUnchanged() {
            mock.register(NAV_PATH, navBody(IMG_A, SUB_A));
            mock.register(ACC_PATH, "{\"code\":-403,\"message\":\"访问权限不足\"}");

            HttpResponse<String> response = BilibiliHttp.getSigned(
                    ACC_URL, Map.of("mid", "946974"), "application/json", "https://www.bilibili.com/");

            assertTrue(response.getBody().contains("-403"), "原样返回，让上层按业务码报错");
            assertEquals(1, mock.hitCount(ACC_PATH), "密钥没变就不该重发");
            assertEquals(2, mock.hitCount(NAV_PATH), "重取 nav 是对 -403 的正常反应（复核一次密钥）");
        }

        @Test
        @DisplayName("v_voucher 也触发重签（文档所述的另一形态）")
        void vVoucherTriggersResign() {
            mock.registerSequence(NAV_PATH, navBody(IMG_A, SUB_A), navBody(IMG_B, SUB_B));
            mock.registerSequence(ACC_PATH,
                    "{\"code\":0,\"data\":{\"v_voucher\":\"voucher_abc\"}}",
                    "{\"code\":0,\"data\":{\"mid\":946974}}");

            HttpResponse<String> response = BilibiliHttp.getSigned(
                    ACC_URL, Map.of("mid", "946974"), "application/json", "https://www.bilibili.com/");
            assertEquals(2, mock.hitCount(ACC_PATH));
            assertTrue(response.getBody().contains("946974"));
        }

        @Test
        @DisplayName("looksLikeSignRejected 的判据：v_voucher / -403 为真，-101 与空体为假")
        void rejectionJudgement() {
            mock.register("/x/probe-voucher", "{\"code\":0,\"data\":{\"v_voucher\":\"voucher_x\"}}");
            mock.register("/x/probe-403", "{\"code\":-403,\"message\":\"访问权限不足\"}");
            mock.register("/x/probe-101", "{\"code\":-101,\"message\":\"账号未登录\"}");
            mock.register("/x/probe-plain", "{\"code\":0,\"data\":{}}");

            assertTrue(BilibiliHttp.looksLikeSignRejected(BilibiliHttp.get(mock.baseUrl() + "/x/probe-voucher")));
            assertTrue(BilibiliHttp.looksLikeSignRejected(BilibiliHttp.get(mock.baseUrl() + "/x/probe-403")));
            assertFalse(BilibiliHttp.looksLikeSignRejected(BilibiliHttp.get(mock.baseUrl() + "/x/probe-101")),
                    "-101 是「缺登录」，重签没有意义 —— 别把它也当成签名被拒");
            assertFalse(BilibiliHttp.looksLikeSignRejected(BilibiliHttp.get(mock.baseUrl() + "/x/probe-plain")));
        }
    }

    // ================================================================
    // 3) 取不到密钥 → 明确失败
    // ================================================================

    @Nested
    @DisplayName("取不到密钥")
    class NoKeysTest {

        @Test
        @DisplayName("nav 没有 wbi_img → BilibiliException，且不去发那个没签名的请求")
        void failsFastWithoutKeys() {
            mock.register(NAV_PATH, "{\"code\":0,\"data\":{}}");
            mock.register(ACC_PATH, "{\"code\":0,\"data\":{\"mid\":1}}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> BilibiliHttp.getSigned(
                    ACC_URL, Map.of("mid", "946974"), "application/json", "https://www.bilibili.com/"));

            assertTrue(e.getMessage().contains("取不到 img_key/sub_key"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(ACC_PATH),
                    "\"发一个没签名的请求试试\"只会拿到 -403 并让人以为端点坏了 —— 宁可在这里明确失败");
        }

        @Test
        @DisplayName("url 为空 → BilibiliException（参数校验先于取密钥）")
        void blankUrl() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> BilibiliHttp.getSigned("  ", Map.of("mid", "1")));
            assertTrue(e.getMessage().contains("url 不能为空"), "实际：" + e.getMessage());
        }
    }
}

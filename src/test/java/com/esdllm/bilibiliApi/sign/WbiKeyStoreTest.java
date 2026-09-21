package com.esdllm.bilibiliApi.sign;

import com.esdllm.bilibiliApi.http.MockBiliServer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WbiKeyStore} 测试：<b>nav 响应的解析分支 + 缓存/失败冷却</b>。
 *
 * <p>这个类最要紧的一条判据是：<b>取密钥看 {@code data.wbi_img} 在不在，不看 {@code code}</b>。
 * 未登录时 nav 返回 {@code code=-101}，而 {@code wbi_img} 照常有值 —— 密钥与登录态无关。
 * 如果谁按 {@code code != 0} 判"取不到密钥"，那么所有需要签名的端点会集体在未登录时报
 * "取不到 WBI 密钥"，把"需登录"伪装成"基础设施问题"，极难查。本测试把这条钉死。
 *
 * <p>注意：{@link WbiKeyStore} 是<b>进程级静态状态</b>，所以每个用例前后都必须
 * {@link WbiKeyStore#invalidate()}，否则用例之间会互相污染（顺序一变就红）。
 */
@DisplayName("WbiKeyStore：nav 解析 + 缓存 + 失败冷却")
class WbiKeyStoreTest {

    private static final String NAV_PATH = "/x/web-interface/nav";

    /** 真实形状：未登录（{@code code=-101}）但 {@code wbi_img} 有值 */
    private static final String NAV_LOGGED_OUT = """
            {"code":-101,"message":"账号未登录","ttl":1,
             "data":{"isLogin":false,
                     "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
                                "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}
            """;

    @BeforeEach
    void resetBefore() {
        WbiKeyStore.invalidate();
    }

    @AfterEach
    void resetAfter() {
        WbiKeyStore.invalidate();
    }

    // ================================================================
    // parseNav：纯解析分支（不发请求）
    // ================================================================

    @Nested
    @DisplayName("parseNav")
    class ParseNavTest {

        @Test
        @DisplayName("🔴 未登录 code=-101 但 wbi_img 有值 → 必须解析成功（密钥与登录态无关）")
        void loggedOutStillHasKeys() {
            WbiKeyStore.WbiKeys keys = WbiKeyStore.parseNav(NAV_LOGGED_OUT);
            assertNotNull(keys, "未登录也必须有密钥 —— 按 code 判会把这个场景判死");
            assertEquals("7cd084941338484aae1ad9425b84077c", keys.imgKey());
            assertEquals("4932caff0ff746eab6f01bf08b70ac45", keys.subKey());
            assertTrue(keys.fetchedAtMillis() > 0);
        }

        @Test
        @DisplayName("解析出的密钥能算出预期的 mixin_key（与 WbiSigner 对上）")
        void keysFeedSigner() {
            WbiKeyStore.WbiKeys keys = WbiKeyStore.parseNav(NAV_LOGGED_OUT);
            assertNotNull(keys);
            assertEquals("ea1db124af3c7062474693fa704f4ff8",
                    WbiSigner.mixinKey(keys.imgKey(), keys.subKey()));
        }

        @Test
        @DisplayName("url 带 query / fragment 也能截对（形状哪天变了不至于截错）")
        void urlWithQueryAndHash() {
            String body = """
                    {"code":0,"data":{"wbi_img":{
                      "img_url":"https://i0.hdslb.com/bfs/wbi/aaaa1111bbbb2222cccc3333dddd4444.png?x=1",
                      "sub_url":"https://i0.hdslb.com/bfs/wbi/eeee5555ffff6666aaaa7777bbbb8888.png#frag"}}}
                    """;
            WbiKeyStore.WbiKeys keys = WbiKeyStore.parseNav(body);
            assertNotNull(keys);
            assertEquals("aaaa1111bbbb2222cccc3333dddd4444", keys.imgKey());
            assertEquals("eeee5555ffff6666aaaa7777bbbb8888", keys.subKey());
        }

        @Test
        @DisplayName("url 没有扩展名时按最后一段路径取（不强行 split('.')）")
        void urlWithoutExtension() {
            String body = """
                    {"code":0,"data":{"wbi_img":{
                      "img_url":"/bfs/wbi/deadbeefdeadbeefdeadbeefdeadbeef",
                      "sub_url":"/bfs/wbi/feedfacefeedfacefeedfacefeedface"}}}
                    """;
            WbiKeyStore.WbiKeys keys = WbiKeyStore.parseNav(body);
            assertNotNull(keys);
            assertEquals("deadbeefdeadbeefdeadbeefdeadbeef", keys.imgKey());
        }

        @Test
        @DisplayName("没有 data.wbi_img → null（不抛）")
        void missingWbiImg() {
            assertNull(WbiKeyStore.parseNav("{\"code\":-101,\"message\":\"账号未登录\",\"data\":{\"isLogin\":false}}"));
            assertNull(WbiKeyStore.parseNav("{\"code\":0,\"data\":{}}"));
            assertNull(WbiKeyStore.parseNav("{\"code\":0}"));
        }

        @Test
        @DisplayName("wbi_img 里缺一个字段 → null（不能拿半个 key 去签名）")
        void halfKeys() {
            assertNull(WbiKeyStore.parseNav(
                    "{\"data\":{\"wbi_img\":{\"img_url\":\"/bfs/wbi/aaa.png\"}}}"));
            assertNull(WbiKeyStore.parseNav(
                    "{\"data\":{\"wbi_img\":{\"sub_url\":\"/bfs/wbi/bbb.png\"}}}"));
            assertNull(WbiKeyStore.parseNav(
                    "{\"data\":{\"wbi_img\":{\"img_url\":\"\",\"sub_url\":\"/bfs/wbi/bbb.png\"}}}"));
        }

        @Test
        @DisplayName("非 JSON / 空体 / null → null（不抛）")
        void badBodies() {
            assertNull(WbiKeyStore.parseNav(null));
            assertNull(WbiKeyStore.parseNav(""));
            assertNull(WbiKeyStore.parseNav("   "));
            assertNull(WbiKeyStore.parseNav("<html>404</html>"));
            assertNull(WbiKeyStore.parseNav("null"));
        }

        @Test
        @DisplayName("summary() 只截前 8 位（密钥是公共值，但日志里截断更好比对）")
        void summaryTruncates() {
            WbiKeyStore.WbiKeys keys = WbiKeyStore.parseNav(NAV_LOGGED_OUT);
            assertNotNull(keys);
            String summary = keys.summary();
            assertTrue(summary.contains("7cd08494"), summary);
            assertFalse(summary.contains("7cd084941338484aae1ad9425b84077c"), "不该打全：" + summary);
        }
    }

    // ================================================================
    // get()：缓存 + 跨日 + 失败冷却（走 MockBiliServer）
    // ================================================================

    @Nested
    @DisplayName("get() 的缓存与失败语义")
    class GetTest {

        @Test
        @DisplayName("首次打 nav，第二次命中缓存 —— nav 只发一次")
        void cachedAcrossCalls() {
            try (MockBiliServer mock = MockBiliServer.start()) {
                mock.register(NAV_PATH, NAV_LOGGED_OUT);

                WbiKeyStore.WbiKeys first = WbiKeyStore.get();
                assertNotNull(first);
                assertTrue(WbiKeyStore.isCached());
                assertEquals(1, mock.hitCount(NAV_PATH), "第一次必须真的发了 nav");

                WbiKeyStore.WbiKeys second = WbiKeyStore.get();
                assertNotNull(second);
                assertEquals(first.imgKey(), second.imgKey());
                assertEquals(1, mock.hitCount(NAV_PATH),
                        "第二次必须命中缓存 —— 每次签名都打一次 nav 会把出口玩坏（也白慢）");
            }
        }

        @Test
        @DisplayName("nav 形状不对 → 返回 null 且不抛；冷却期内不再重发（快速失败）")
        void failureReturnsNullAndCoolsDown() {
            try (MockBiliServer mock = MockBiliServer.start()) {
                // 200 但 payload 里没有 wbi_img —— 用 200 而不是 5xx：
                // 5xx 会被 BilibiliHttp 判成可重试并退避重试，白白拉长测试
                mock.register(NAV_PATH, "{\"code\":0,\"data\":{}}");

                assertNull(WbiKeyStore.get(), "取不到密钥必须返回 null 而不是抛异常");
                assertFalse(WbiKeyStore.isCached());
                assertEquals(1, mock.hitCount(NAV_PATH));

                assertNull(WbiKeyStore.get(), "冷却期内应当直接快失败");
                assertEquals(1, mock.hitCount(NAV_PATH),
                        "失败后 30 秒内不该再撞 nav —— 连续失败时每个请求都撞一次只会更糟");
            }
        }

        @Test
        @DisplayName("invalidate() 后重新可取（并清掉失败冷却）")
        void invalidateResets() {
            try (MockBiliServer mock = MockBiliServer.start()) {
                mock.register(NAV_PATH, NAV_LOGGED_OUT);

                assertNotNull(WbiKeyStore.get());
                assertEquals(1, mock.hitCount(NAV_PATH));

                WbiKeyStore.invalidate();
                assertFalse(WbiKeyStore.isCached());

                assertNotNull(WbiKeyStore.get());
                assertEquals(2, mock.hitCount(NAV_PATH), "invalidate 之后必须重新取一次");
            }
        }

        @Test
        @DisplayName("冷却时长常量是 30 秒（写死它，改小了要有人看一眼）")
        void cooldownConstant() {
            assertEquals(30_000L, WbiKeyStore.FAILURE_COOLDOWN_MS);
        }
    }
}

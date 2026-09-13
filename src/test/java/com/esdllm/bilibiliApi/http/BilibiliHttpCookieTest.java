package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kong.unirest.Unirest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cookie 注入与合并的回归锁定。
 *
 * <p>背景（2026-09-13 实测）：{@code x/polymer/web-dynamic/v1/feed/space} 匿名已无法通过 ——
 * 不带指纹 Cookie 返回 HTTP 412，带上 {@code buvid3/buvid4} 后变成 {@code code=-352}（风控），
 * 补 {@code dm_img_*} 客户端指纹参数、换代理出口都过不去。唯一可行的路是注入真实登录 Cookie，
 * 因此 {@link HttpPolicy#setCookie(String)} + {@link BilibiliHttp#composeCookie(String)} 必须行为稳定。
 *
 * <p>本测试<b>不联网</b>：{@code composeCookie} 是纯函数，直接断言合并结果。
 */
@DisplayName("Cookie 注入：合并规则与打码")
class BilibiliHttpCookieTest {

    @AfterEach
    void restore() {
        HttpPolicy.reset();
    }

    @Test
    @DisplayName("两边都没有 Cookie 时返回 null（不能发出空的 Cookie 头）")
    void 都为null() {
        assertNull(BilibiliHttp.composeCookie(null));
        assertNull(BilibiliHttp.composeCookie(""));
    }

    @Test
    @DisplayName("只注入用户 Cookie 时原样使用")
    void 只有用户cookie() {
        HttpPolicy.setCookie("SESSDATA=abc; bili_jct=def");
        assertTrue(HttpPolicy.hasCookie());
        assertEquals("SESSDATA=abc; bili_jct=def", BilibiliHttp.composeCookie(null));
        // 有用户 Cookie、没有指纹时也不能丢
        assertEquals("SESSDATA=abc; bili_jct=def",
                BilibiliHttp.composeCookie("  "));
    }

    @Test
    @DisplayName("只有匿名指纹时原样使用（不影响原有匿名路径）")
    void 只有指纹cookie() {
        assertEquals("buvid3=AAA; buvid4=BBB",
                BilibiliHttp.composeCookie("buvid3=AAA; buvid4=BBB"));
    }

    @Test
    @DisplayName("★ 用户 Cookie 的键优先：同名键不被指纹值覆盖")
    void 用户cookie优先() {
        HttpPolicy.setCookie("buvid3=USER; SESSDATA=abc");
        String merged = BilibiliHttp.composeCookie("buvid3=ANON; buvid4=BBB");
        assertEquals("buvid3=USER; SESSDATA=abc; buvid4=BBB", merged);
        assertFalse(merged.contains("ANON"), "用户提供的 buvid3 被指纹值覆盖了");
    }

    @Test
    @DisplayName("指纹只补用户没有的键，且保持用户键在前")
    void 指纹补缺() {
        HttpPolicy.setCookie("SESSDATA=abc");
        assertEquals("SESSDATA=abc; buvid3=AAA; buvid4=BBB",
                BilibiliHttp.composeCookie("buvid3=AAA; buvid4=BBB"));
    }

    @Test
    @DisplayName("setCookie 归一化：去空白、丢空片段；空输入等于未注入")
    void 归一化() {
        HttpPolicy.setCookie("  SESSDATA=abc ;;  bili_jct=def  ");
        assertEquals("SESSDATA=abc; bili_jct=def", HttpPolicy.getCookie());

        HttpPolicy.setCookie("   ");
        assertFalse(HttpPolicy.hasCookie());
        assertNull(HttpPolicy.getCookie());

        HttpPolicy.setCookie(null);
        assertFalse(HttpPolicy.hasCookie());
    }

    @Test
    @DisplayName("clearCookie 之后回到只用匿名指纹")
    void 清除() {
        HttpPolicy.setCookie("SESSDATA=abc");
        HttpPolicy.clearCookie();
        assertFalse(HttpPolicy.hasCookie());
        assertEquals("buvid3=AAA", BilibiliHttp.composeCookie("buvid3=AAA"));
    }

    @Test
    @DisplayName("describe() 只输出 Cookie 的键名，绝不泄露值")
    void 描述里打码() {
        HttpPolicy.setCookie("SESSDATA=super-secret; bili_jct=token");
        String described = HttpPolicy.describe();
        assertTrue(described.contains("SESSDATA,bili_jct"), described);
        assertFalse(described.contains("super-secret"), "describe() 泄露了 Cookie 值：" + described);
        assertFalse(described.contains("token"), "describe() 泄露了 Cookie 值：" + described);
    }

    @Test
    @DisplayName("未注入时 describe() 明确写「仅匿名指纹」")
    void 未注入的描述() {
        assertTrue(HttpPolicy.describe().contains("仅匿名指纹"), HttpPolicy.describe());
    }

    @Test
    @DisplayName("★ 必须关闭 Unirest 的 cookie 自动管理：否则 Set-Cookie 回放会与显式 Cookie 头叠加")
    void cookie管理必须关闭() {
        // 触发 BilibiliHttp 的静态初始化（库内所有出站都走它，所以这里必然是最早时机）
        assertNotNull(BilibiliHttp.class);
        assertFalse(Unirest.config().getEnabledCookieManagement(),
                "Unirest 的 cookie 管理必须关闭：它会把响应的 Set-Cookie 回放到后续请求，"
                        + "与 composeCookie 拼出的 Cookie 头叠加（同名键重复/身份串味），"
                        + "实测表现为『Cookie 有效但动态 feed 稳定 412』");
    }

    // ——————————————— 设备指纹来源判定（2026-09-13 新增）———————————————

    @Test
    @DisplayName("hasCookieKey 按键名匹配，不区分大小写；cookieProvidesDeviceId 认 buvid3/buvid4 任一")
    void 指纹来源判定() {
        HttpPolicy.setCookie("SESSDATA=abc; BUVID3=AAA");
        assertTrue(HttpPolicy.hasCookieKey("buvid3"), "键名匹配应当不区分大小写");
        assertTrue(HttpPolicy.hasCookieKey("SESSDATA"));
        assertFalse(HttpPolicy.hasCookieKey("buvid4"));
        assertTrue(HttpPolicy.cookieProvidesDeviceId(), "有了 buvid3 就算自带设备指纹");

        HttpPolicy.setCookie("SESSDATA=abc; buvid4=BBB");
        assertTrue(HttpPolicy.cookieProvidesDeviceId(), "只有 buvid4 也算");

        HttpPolicy.setCookie("SESSDATA=abc; bili_jct=def");
        assertFalse(HttpPolicy.cookieProvidesDeviceId(), "只有登录态、没有 buvid 时需要匿名指纹来补");
        assertEquals("SESSDATA,bili_jct", HttpPolicy.cookieKeys());

        HttpPolicy.clearCookie();
        assertFalse(HttpPolicy.cookieProvidesDeviceId());
        assertEquals("", HttpPolicy.cookieKeys());
    }

    @Test
    @DisplayName("★ 注入的 Cookie 自带 buvid → 不再打指纹接口（省一次请求，也避开启动时连发）")
    void 自带指纹时跳过领取() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register("/x/frontend/finger/spi", "{\"code\":0,\"data\":{\"b_3\":\"ANON\",\"b_4\":\"ANON4\"}}");
            HttpPolicy.setCookie("buvid3=USER; buvid4=USER4; SESSDATA=abc");

            AnonymousSession.Identity rotated = AnonymousSession.rotate();

            assertFalse(rotated.hasCookie(), "应当跳过匿名指纹领取，而不是真去领一次");
            assertEquals("", rotated.cookie());
            assertEquals(0, mock.hitCount("/x/frontend/finger/spi"),
                    "带 buvid 的登录 Cookie 下不该再打指纹接口：领来的值会被用户 Cookie 覆盖，纯属白跑");
            // 出站 Cookie 与"领了再合并"完全一致，只是少了一次请求
            assertEquals("buvid3=USER; buvid4=USER4; SESSDATA=abc", BilibiliHttp.composeCookie(rotated.cookie()));
        }
    }

    @Test
    @DisplayName("未注入（或 Cookie 不含 buvid）时仍然领取匿名指纹：原有匿名路径不变")
    void 无指纹时仍领取() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register("/x/frontend/finger/spi", "{\"code\":0,\"data\":{\"b_3\":\"ANON\",\"b_4\":\"ANON4\"}}");

            HttpPolicy.setCookie("SESSDATA=abc");
            AnonymousSession.Identity rotated = AnonymousSession.rotate();

            assertEquals(1, mock.hitCount("/x/frontend/finger/spi"), "只有登录态时必须领指纹来补 buvid");
            assertTrue(rotated.hasCookie());
            assertEquals("SESSDATA=abc; buvid3=ANON; buvid4=ANON4",
                    BilibiliHttp.composeCookie(rotated.cookie()));
        }
    }
}

package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出站 User-Agent / Client Hints 的<b>真实发送</b>回归：配置改了不算，要看请求里到底有没有。
 *
 * <p><b>为什么必须专门测"真发出去了"</b>：{@link AnonymousSession} 的身份是<b>缓存</b>的。
 * 若把 UA 固化在"生成身份"那一刻，那么对任何"已经跑过一次请求"的进程，
 * {@code HttpPolicy.setUserAgent(...)} 都是<b>静默无效</b> —— 代码看着对、日志看着也像对，
 * 只有抓请求头才能发现。这类"设了没反应"的缺陷查起来最费劲，所以在这里钉死。
 *
 * <p>顺便锁住"默认行为不变"：未显式指定 UA 时，UA 仍来自身份池，且不凭空多出 CH 头。
 */
@DisplayName("出站 UA / Client Hints：显式指定必须真的发出")
class BilibiliHttpUserAgentTest {

    private static final String VIEW = "/x/web-interface/view?bvid=";
    private static final String VIEW_BODY = "{\"code\":0,\"message\":\"OK\",\"data\":{\"aid\":1}}";
    private static final String SPI = "/x/frontend/finger/spi";
    private static final String SPI_BODY = "{\"code\":0,\"data\":{\"b_3\":\"ANON\",\"b_4\":\"ANON4\"}}";

    @AfterEach
    void restore() {
        HttpPolicy.reset();
        RateLimiter.reset();
    }

    @Test
    @DisplayName("★ 身份已被缓存后设 UA，依然立刻生效（\"设了没反应\"最容易出在这里）")
    void explicitUserAgentSurvivesIdentityCache() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(SPI, SPI_BODY);
            mock.register(VIEW, VIEW_BODY);

            // 先打一次：把匿名身份"用热"，此后 current() 走缓存、不再重新生成
            BilibiliHttp.get("https://api.bilibili.com" + VIEW + "BV1");
            assertNotNull(mock.requestHeader(VIEW, "User-Agent"), "第一次请求就该带上 UA");

            String chosen = UserAgentPool.edgeWindows("140.0.0.0");
            HttpPolicy.setUserAgent(chosen);
            BilibiliHttp.get("https://api.bilibili.com" + VIEW + "BV2");

            assertEquals(chosen, mock.requestHeader(VIEW, "User-Agent"),
                    "显式指定的 UA 没出现在请求头里 —— 说明它被缓存的身份盖掉了");
        }
    }

    @Test
    @DisplayName("★ 显式 UA 会配套发出 Client Hints，且与 UA 同源（自称 Edge 要有佐证）")
    void explicitUserAgentSendsClientHints() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(VIEW, VIEW_BODY);
            HttpPolicy.setUserAgent(UserAgentPool.edgeWindows("140.0.0.0"));

            BilibiliHttp.get("https://api.bilibili.com" + VIEW + "BV1");

            String chUa = mock.requestHeader(VIEW, "Sec-CH-UA");
            assertNotNull(chUa,
                    "真实 Edge 在 HTTPS 下必带 Sec-CH-UA；只有 UA 字符串时服务端认不出是什么设备");
            assertTrue(chUa.contains("Microsoft Edge"), chUa);
            assertTrue(Objects.requireNonNull(mock.requestHeader(VIEW, "User-Agent")).contains("Edg/140.0.0.0"),
                    "CH 说是 Edge、UA 也得说是 Edge（两边不同源比不发更可疑）");
            assertEquals("?0", mock.requestHeader(VIEW, "Sec-CH-UA-Mobile"));
            assertEquals("\"Windows\"", mock.requestHeader(VIEW, "Sec-CH-UA-Platform"));
        }
    }

    @Test
    @DisplayName("未显式指定时行为不变：UA 仍来自身份池，且不凭空发出 Client Hints")
    void keepsDefaultWhenUnset() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(SPI, SPI_BODY);
            mock.register(VIEW, VIEW_BODY);
            HttpPolicy.clearUserAgent();

            AnonymousSession.rotate();
            BilibiliHttp.get("https://api.bilibili.com" + VIEW + "BV1");

            String sent = mock.requestHeader(VIEW, "User-Agent");
            boolean inPool = false;
            for (int i = 0; i < UserAgentPool.size(); i++) {
                if (UserAgentPool.at(i).equals(sent)) {
                    inPool = true;
                    break;
                }
            }
            assertTrue(inPool, "未指定 UA 时应来自身份池，实际：" + sent);
            assertNull(mock.requestHeader(VIEW, "Sec-CH-UA"),
                    "匿名路径不该凭空多出 CH 头 —— 默认行为必须与改造前一致");
        }
    }

    @Test
    @DisplayName("身份轮换不会把显式 UA 换掉（\"刚登录成功就掉登录\"的成因之一）")
    void rotationKeepsExplicitUserAgent() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(SPI, SPI_BODY);
            mock.register(VIEW, VIEW_BODY);
            String chosen = UserAgentPool.edgeWindows("140.0.0.0");
            HttpPolicy.setUserAgent(chosen);

            // 换一副指纹：默认行为会连 UA 一起换；显式指定后必须稳住 ——
            // 凭据是跟某一副面孔绑定的，UA 一变，"刚登录成功"就可能变成"-101"
            AnonymousSession.rotate();
            BilibiliHttp.get("https://api.bilibili.com" + VIEW + "BV1");

            assertEquals(chosen, mock.requestHeader(VIEW, "User-Agent"));
        }
    }
}

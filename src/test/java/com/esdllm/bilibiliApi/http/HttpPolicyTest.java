package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpPolicy} 的确定性单测：退避序列、抖动边界、封顶、参数下限、代理判定。
 *
 * <p>HttpPolicy 是全局静态配置，测试必须用完即还（{@link #restore()}），
 * 否则会污染同一次测试运行里的其它用例（尤其是联网冒烟）。
 */
@DisplayName("HttpPolicy：退避/限流/代理策略")
class HttpPolicyTest {

    @AfterEach
    void restore() {
        HttpPolicy.reset();
        RateLimiter.reset();
    }

    @Test
    @DisplayName("退避随尝试次数增长，且始终封顶在 maxDelay 之内")
    void backoffGrowsAndCaps() {
        HttpPolicy.setBaseDelayMs(400L);
        HttpPolicy.setMultiplier(2.0d);
        HttpPolicy.setMaxDelayMs(4_000L);
        HttpPolicy.setJitterRatio(0.0d);

        assertEquals(400L, HttpPolicy.backoffMillis(1));
        assertEquals(800L, HttpPolicy.backoffMillis(2));
        assertEquals(1_600L, HttpPolicy.backoffMillis(3));
        // 再往后必须被 maxDelay 截住，不能无限增长
        assertEquals(4_000L, HttpPolicy.backoffMillis(10));
        assertEquals(4_000L, HttpPolicy.backoffMillis(50));
    }

    @Test
    @DisplayName("抖动落在 [capped*(1-jitter), capped]，且同一次尝试有波动")
    void jitterWithinRange() {
        HttpPolicy.setBaseDelayMs(1_000L);
        HttpPolicy.setMultiplier(2.0d);
        HttpPolicy.setMaxDelayMs(10_000L);
        HttpPolicy.setJitterRatio(0.3d);

        long capped = 1_000L;
        long lower = (long) (capped * 0.7d);
        boolean sawDifferentValue = false;
        long previous = HttpPolicy.backoffMillis(1);
        for (int i = 0; i < 200; i++) {
            long value = HttpPolicy.backoffMillis(1);
            assertTrue(value >= lower && value <= capped,
                    "抖动越界：" + value + " 不在 [" + lower + ", " + capped + "]");
            if (value != previous) {
                sawDifferentValue = true;
            }
        }
        assertTrue(sawDifferentValue, "抖动应产生波动，否则多个调用方会形成整齐的重试波峰");
    }

    @Test
    @DisplayName("jitter=0 时退避是确定的（便于排障与断言）")
    void deterministicBackoffWhenJitterOff() {
        HttpPolicy.setJitterRatio(0.0d);
        HttpPolicy.setBaseDelayMs(300L);
        HttpPolicy.setMultiplier(3.0d);
        long first = HttpPolicy.backoffMillis(2);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, HttpPolicy.backoffMillis(2));
        }
        assertEquals(900L, first);
    }

    @Test
    @DisplayName("最大尝试次数有下限 1，不会退化成 0 次请求")
    void maxAttemptsFloorIsOne() {
        HttpPolicy.setMaxAttempts(0);
        assertEquals(1, HttpPolicy.getMaxAttempts());
        HttpPolicy.setMaxAttempts(-5);
        assertEquals(1, HttpPolicy.getMaxAttempts());
    }

    @Test
    @DisplayName("抖动比例被钳制在 0~1")
    void jitterRatioClamped() {
        HttpPolicy.setJitterRatio(-1.0d);
        assertEquals(0.0d, HttpPolicy.getJitterRatio());
        HttpPolicy.setJitterRatio(9.0d);
        assertEquals(1.0d, HttpPolicy.getJitterRatio());
    }

    @Test
    @DisplayName("代理需主机与端口同时合法才生效")
    void proxyDetection() {
        HttpPolicy.clearProxy();
        assertFalse(HttpPolicy.hasProxy());

        HttpPolicy.setProxy("127.0.0.1", 7897);
        assertTrue(HttpPolicy.hasProxy());

        // 非法端口视为未配置，避免把请求发到一个无意义的地址
        HttpPolicy.setProxy("127.0.0.1", 0);
        assertFalse(HttpPolicy.hasProxy());
        HttpPolicy.setProxy("127.0.0.1", 70000);
        assertFalse(HttpPolicy.hasProxy());

        // 空白主机名同样不生效
        HttpPolicy.setProxy("   ", 8080);
        assertFalse(HttpPolicy.hasProxy());
    }

    @Test
    @DisplayName("reset 后回到默认值，且 describe 能反映当前策略")
    void resetAndDescribe() {
        HttpPolicy.setMaxAttempts(9);
        HttpPolicy.setMinRequestIntervalMs(5L);
        HttpPolicy.reset();
        assertEquals(HttpPolicy.DEFAULT_MAX_ATTEMPTS, HttpPolicy.getMaxAttempts());
        assertEquals(HttpPolicy.DEFAULT_MIN_INTERVAL_MS, HttpPolicy.getMinRequestIntervalMs());

        String description = HttpPolicy.describe();
        assertTrue(description.contains("最小间隔"), "摘要应包含限流信息：" + description);
        assertTrue(description.contains("直连") || description.contains("代理"), description);
    }

    // ---------------------------------------------------------------- User-Agent

    @Test
    @DisplayName("未指定 UA 时跟随身份；指定后固定不变（登录场景的关键）")
    void explicitUserAgentOverridesIdentity() {
        HttpPolicy.clearUserAgent();
        assertFalse(HttpPolicy.hasUserAgent());
        assertNull(HttpPolicy.getUserAgent());

        // 未指定：用身份自带的那一个
        assertEquals("身份A", HttpPolicy.userAgentFor("身份A"));
        assertEquals(UserAgentPool.defaultAgent(), HttpPolicy.userAgentFor(null),
                "身份没 UA 时也必须回落到池内默认，绝不能发出空 UA");
        assertEquals(UserAgentPool.defaultAgent(), HttpPolicy.userAgentFor("   "));

        HttpPolicy.setUserAgent(UserAgentPool.edgeWindows("140.0.0.0"));
        assertTrue(HttpPolicy.hasUserAgent());
        // ★ 关键：身份换成哪一个，出站 UA 都必须是显式值 ——
        //   否则"登录成功后恰好又轮换一次"就会把 UA 换掉，表现为"刚登录成功就 -101"
        assertEquals(UserAgentPool.edgeWindows("140.0.0.0"), HttpPolicy.userAgentFor("身份A"));
        assertEquals(UserAgentPool.edgeWindows("140.0.0.0"), HttpPolicy.userAgentFor("身份B"));
        assertEquals(UserAgentPool.edgeWindows("140.0.0.0"), HttpPolicy.userAgentFor(null));
    }

    @Test
    @DisplayName("空白 UA 等价于清除；reset 后回到跟随身份池（测试间不能串味）")
    void userAgentClearing() {
        HttpPolicy.setUserAgent("Mozilla/5.0 (Windows NT 10.0) Edg/140.0.0.0");
        assertTrue(HttpPolicy.hasUserAgent());

        HttpPolicy.setUserAgent("   ");
        assertFalse(HttpPolicy.hasUserAgent(), "空白应等价于清除，而不是发一个空白 UA");

        HttpPolicy.setUserAgent("Mozilla/5.0 (X)");
        HttpPolicy.clearUserAgent();
        assertFalse(HttpPolicy.hasUserAgent());

        HttpPolicy.setUserAgent("Mozilla/5.0 (Y)");
        HttpPolicy.reset();
        assertFalse(HttpPolicy.hasUserAgent(),
                "reset 必须把 UA 一起还原，否则会污染同一次测试运行里的其它用例（尤其联网冒烟）");
    }

    @Test
    @DisplayName("★ Edge 的 UA 会配套启用 Client Hints（只发 UA 字符串＝自报家门却无佐证）")
    void edgeUserAgentSendsClientHints() {
        HttpPolicy.setUserAgent(UserAgentPool.edgeWindows("140.0.0.0"));

        Map<String, String> hints = HttpPolicy.clientHints();
        assertEquals("?0", hints.get("Sec-CH-UA-Mobile"));
        assertEquals("\"Windows\"", hints.get("Sec-CH-UA-Platform"));
        String chUa = hints.get("Sec-CH-UA");
        assertNotNull(chUa, "Edge 的 UA 必须配套 Sec-CH-UA，否则服务端认不出是什么设备");
        assertTrue(chUa.contains("Microsoft Edge"), chUa);
        assertTrue(chUa.contains("v=\"140\""),
                "CH 里只放主版本号（真实浏览器就是 v=\"140\" 而非 v=\"140.0.0.0\"）：" + chUa);

        // clientHints() 是 public API，交出去的必须是只读视图 ——
        // 否则调用方一个 put 就改掉了全体出站的形状，且没有任何报错
        assertThrows(UnsupportedOperationException.class,
                () -> HttpPolicy.clientHints().put("Sec-CH-UA", "伪造"),
                "clientHints() 不能返回可变 Map");
    }

    @Test
    @DisplayName("Chrome 的 UA 不带 Edge 品牌；Safari 干脆不发 CH（硬造反而更假）")
    void clientHintsDifferByBrowser() {
        HttpPolicy.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36");
        assertTrue(HttpPolicy.clientHints().get("Sec-CH-UA").contains("Google Chrome"));
        assertFalse(HttpPolicy.clientHints().get("Sec-CH-UA").contains("Microsoft Edge"));

        HttpPolicy.setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/17.4 Safari/605.1.15");
        assertTrue(HttpPolicy.clientHints().isEmpty(), "Safari 不支持 Client Hints，不能给它硬造一套");

        HttpPolicy.clearUserAgent();
        assertTrue(HttpPolicy.clientHints().isEmpty(), "未指定 UA 时不该凭空多出 CH 头");
    }

    @Test
    @DisplayName("describe 能看出 UA 是显式指定还是跟随身份池（排障要看得出）")
    void describeIncludesUserAgent() {
        HttpPolicy.clearUserAgent();
        assertTrue(HttpPolicy.describe().contains("跟随身份池"), HttpPolicy.describe());

        HttpPolicy.setUserAgent(UserAgentPool.edgeWindows("140.0.0.0"));
        assertTrue(HttpPolicy.describe().contains("Edg/140.0.0.0"),
                "排障时要能一眼看出实际生效的 UA：" + HttpPolicy.describe());
    }
}

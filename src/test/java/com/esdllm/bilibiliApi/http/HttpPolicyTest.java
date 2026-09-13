package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void 退避序列增长且封顶() {
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
    void 抖动在预期区间内() {
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
    void 关闭抖动时退避确定() {
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
    void 尝试次数下限为1() {
        HttpPolicy.setMaxAttempts(0);
        assertEquals(1, HttpPolicy.getMaxAttempts());
        HttpPolicy.setMaxAttempts(-5);
        assertEquals(1, HttpPolicy.getMaxAttempts());
    }

    @Test
    @DisplayName("抖动比例被钳制在 0~1")
    void 抖动比例被钳制() {
        HttpPolicy.setJitterRatio(-1.0d);
        assertEquals(0.0d, HttpPolicy.getJitterRatio());
        HttpPolicy.setJitterRatio(9.0d);
        assertEquals(1.0d, HttpPolicy.getJitterRatio());
    }

    @Test
    @DisplayName("代理需主机与端口同时合法才生效")
    void 代理判定() {
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
    void 重置与摘要() {
        HttpPolicy.setMaxAttempts(9);
        HttpPolicy.setMinRequestIntervalMs(5L);
        HttpPolicy.reset();
        assertEquals(HttpPolicy.DEFAULT_MAX_ATTEMPTS, HttpPolicy.getMaxAttempts());
        assertEquals(HttpPolicy.DEFAULT_MIN_INTERVAL_MS, HttpPolicy.getMinRequestIntervalMs());

        String description = HttpPolicy.describe();
        assertTrue(description.contains("最小间隔"), "摘要应包含限流信息：" + description);
        assertTrue(description.contains("直连") || description.contains("代理"), description);
    }
}

package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimiter} 的时序单测。
 *
 * <p>只断言"至少等了多久"这种下界（不会因机器快而失败），
 * 上界留得比较宽松，避免 CI 抖动导致假红。
 */
@DisplayName("RateLimiter：最小请求间隔")
class RateLimiterTest {

    @AfterEach
    void restore() {
        HttpPolicy.reset();
        RateLimiter.reset();
    }

    @Test
    @DisplayName("间隔为 0 时不等待（关闭限流）")
    void noWaitWhenDisabled() {
        HttpPolicy.setMinRequestIntervalMs(0L);
        RateLimiter.reset();

        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            RateLimiter.acquire();
        }
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 500L, "关闭限流后不应有明显等待，实测 " + elapsed + "ms");
    }

    @Test
    @DisplayName("连续取名额被摊平到最小间隔（10 次至少花 9 个间隔）")
    void consecutivePermitsPaced() {
        long interval = 120L;
        HttpPolicy.setMinRequestIntervalMs(interval);
        RateLimiter.reset();

        int times = 10;
        long start = System.currentTimeMillis();
        for (int i = 0; i < times; i++) {
            RateLimiter.acquire();
        }
        long elapsed = System.currentTimeMillis() - start;

        long expectedFloor = interval * (times - 1);
        // 留 20% 余量吸收 sleep 精度与调度抖动
        assertTrue(elapsed >= expectedFloor * 0.8,
                "限流未生效：期望至少约 " + expectedFloor + "ms，实测 " + elapsed + "ms");
    }

    @Test
    @DisplayName("reset 后首个名额不再等待")
    void resetsImmediately() {
        HttpPolicy.setMinRequestIntervalMs(600L);
        RateLimiter.acquire();      // 占用排期
        RateLimiter.reset();        // 清空排期

        long start = System.currentTimeMillis();
        RateLimiter.acquire();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 300L, "reset 后应立即放行，实测 " + elapsed + "ms");
    }
}

package com.esdllm.bilibiliApi.http;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局最小请求间隔闸门（单机限流）。
 *
 * <p><b>为什么必须有限流</b>：B 站风控除了看身份，也看<b>请求密度</b>。
 * 一次"看某个 UP 的动态"在代码里往往是「拉列表 → 逐条查详情 → 抓配图」，
 * 不加约束时几十毫秒内能打出十几个请求 —— 这是最典型的风控触发姿势。
 * 限流不是"减少功能"，而是"让功能能长期稳定跑"。
 *
 * <p><b>实现</b>：一个"发车时刻表"（{@code nextAllowedAt}）。线程取名额时，
 * 若当前时间早于排期，就阻塞到排期；无论是否等待，都会把排期往后推一个间隔。
 * 因此并发调用会被自动摊平，匀速出站。
 *
 * <p><b>注意</b>：锁只覆盖"取号"这一小段，{@code sleep} 在锁外执行 ——
 * 否则所有线程都会被串行化到一把锁上，吞吐会崩。
 *
 * <p>限流间隔取 {@link HttpPolicy#getMinRequestIntervalMs()}，设为 0 即关闭。
 */
@Slf4j
public final class RateLimiter {

    /** 仅保护 nextAllowedAt 的读改写，不覆盖 sleep */
    private static final Object LOCK = new Object();

    /** 下一次允许发车的时刻（毫秒时间戳） */
    private static long nextAllowedAt;

    private RateLimiter() {
    }

    /**
     * 取一个放行名额，必要时阻塞到允许发车。
     *
     * <p>被中断时不吞异常、不抛异常：只恢复中断标志并立即放行 ——
     * 限流是"优化项"，绝不能因为线程被中断就让调用方拿不到响应。
     */
    public static void acquire() {
        long interval = HttpPolicy.getMinRequestIntervalMs();
        if (interval <= 0L) {
            return;
        }

        long waitMs;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now >= nextAllowedAt) {
                nextAllowedAt = now + interval;
                return;
            }
            waitMs = nextAllowedAt - now;
            nextAllowedAt += interval;
        }
        // 锁外等待，避免把并发退化成串行
        if (waitMs > 0L) {
            log.debug("限流：等待 {}ms 后出站（最小间隔 {}ms）", waitMs, interval);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 清空排期。测试或"刚发生过长时间停顿"后可调用，避免不必要的首次等待 */
    public static void reset() {
        synchronized (LOCK) {
            nextAllowedAt = 0L;
        }
    }
}

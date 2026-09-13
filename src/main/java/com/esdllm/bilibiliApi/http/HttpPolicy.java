package com.esdllm.bilibiliApi.http;

import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP 层统一策略：<b>重试退避 / 限流 / 身份轮换 / 代理</b>。
 *
 * <p>把所有"抗压"参数集中在一处，改动只影响这一个类。默认值都偏保守：
 * <ul>
 *   <li><b>重试退避</b>：最多 {@value #DEFAULT_MAX_ATTEMPTS} 次尝试，
 *       退避 {@code base * multiplier^(n-1)} 并叠加抖动，封顶 {@code maxDelay}。
 *       抖动很关键 —— 多个调用方同时退避时，若间隔完全一致会形成"整齐的重试波峰"，
 *       反而更容易被风控识别。</li>
 *   <li><b>限流</b>：全局最小请求间隔 {@value #DEFAULT_MIN_INTERVAL_MS}ms，
 *       把所有出站请求摊平，避免"列表 + 逐条详情 + 抓图"连着打把自己推近风控线。</li>
 *   <li><b>身份轮换</b>：仅对<b>风控码</b>生效，且最多 {@value #DEFAULT_MAX_ROTATIONS} 次。
 *       注意这与"盲重试"有本质区别：盲重试是拿<b>已被标记的指纹</b>再撞一次，
 *       只会加重风控；轮换是换一个全新匿名身份再试。</li>
 *   <li><b>代理</b>：默认直连。可用 {@link #setProxy(String, int)} 显式指定，
 *       或启动时用系统属性 {@code -Dbili.proxy.host=} / {@code -Dbili.proxy.port=} 注入。
 *       本库<b>不内置任何代理地址</b>（保持"零凭据"定位）。</li>
 * </ul>
 *
 * <p>所有字段都是 {@code volatile}，可运行期调整，线程安全。
 */
public final class HttpPolicy {

    // ---------------------------------------------------------------- 默认值

    /** 单个请求的最大尝试次数（含首次） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 首次退避基准（毫秒） */
    public static final long DEFAULT_BASE_DELAY_MS = 400L;
    /** 单次退避上限（毫秒） */
    public static final long DEFAULT_MAX_DELAY_MS = 4_000L;
    /** 退避倍率 */
    public static final double DEFAULT_MULTIPLIER = 2.0d;
    /** 抖动比例：实际退避落在 {@code [capped*(1-jitter), capped]} */
    public static final double DEFAULT_JITTER_RATIO = 0.3d;
    /** 全局最小请求间隔（毫秒），0 或负数表示关闭限流 */
    public static final long DEFAULT_MIN_INTERVAL_MS = 400L;
    /** 建连超时（毫秒） */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 6_000;
    /** 读取超时（毫秒） */
    public static final int DEFAULT_SOCKET_TIMEOUT_MS = 12_000;
    /** 命中风控时最多轮换几次身份 */
    public static final int DEFAULT_MAX_ROTATIONS = 1;

    /** 系统属性名：代理主机 */
    public static final String PROP_PROXY_HOST = "bili.proxy.host";
    /** 系统属性名：代理端口 */
    public static final String PROP_PROXY_PORT = "bili.proxy.port";

    // ---------------------------------------------------------------- 可变配置

    private static volatile int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    private static volatile long baseDelayMs = DEFAULT_BASE_DELAY_MS;
    private static volatile long maxDelayMs = DEFAULT_MAX_DELAY_MS;
    private static volatile double multiplier = DEFAULT_MULTIPLIER;
    private static volatile double jitterRatio = DEFAULT_JITTER_RATIO;
    private static volatile long minRequestIntervalMs = DEFAULT_MIN_INTERVAL_MS;
    private static volatile boolean rotateOnRiskControl = true;
    private static volatile int maxRotations = DEFAULT_MAX_ROTATIONS;
    private static volatile int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private static volatile int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;

    private static volatile String proxyHost = readStringProperty(PROP_PROXY_HOST);
    private static volatile int proxyPort = readIntProperty(PROP_PROXY_PORT);

    private HttpPolicy() {
    }

    // ---------------------------------------------------------------- 重试退避

    public static int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 设置最大尝试次数。
     *
     * @param attempts 含首次在内的总次数，小于 1 时按 1 处理
     */
    public static void setMaxAttempts(int attempts) {
        maxAttempts = Math.max(1, attempts);
    }

    public static long getBaseDelayMs() {
        return baseDelayMs;
    }

    public static void setBaseDelayMs(long millis) {
        baseDelayMs = Math.max(0L, millis);
    }

    public static long getMaxDelayMs() {
        return maxDelayMs;
    }

    public static void setMaxDelayMs(long millis) {
        maxDelayMs = Math.max(0L, millis);
    }

    public static double getMultiplier() {
        return multiplier;
    }

    public static void setMultiplier(double value) {
        multiplier = value < 1.0d ? 1.0d : value;
    }

    public static double getJitterRatio() {
        return jitterRatio;
    }

    /**
     * 设置抖动比例。
     *
     * @param ratio 取值 0~1；0 表示不抖动
     */
    public static void setJitterRatio(double ratio) {
        jitterRatio = Math.max(0.0d, Math.min(1.0d, ratio));
    }

    /**
     * 计算第 {@code attempt} 次失败后的退避时长。
     *
     * @param attempt 已失败的次数（从 1 开始）
     * @return 毫秒数，已封顶并叠加抖动
     */
    public static long backoffMillis(int attempt) {
        int n = Math.max(1, attempt);
        double raw = baseDelayMs * Math.pow(multiplier, n - 1);
        long capped = (long) Math.min(raw, (double) maxDelayMs);
        if (jitterRatio <= 0.0d || capped <= 0L) {
            return capped;
        }
        long span = (long) (capped * jitterRatio);
        if (span <= 0L) {
            return capped;
        }
        return capped - ThreadLocalRandom.current().nextLong(span + 1L);
    }

    /** 该次退避是否值得等待（退避为 0 时上层可跳过 sleep） */
    public static boolean backoffEnabled() {
        return baseDelayMs > 0L && maxAttempts > 1;
    }

    // ---------------------------------------------------------------- 限流

    public static long getMinRequestIntervalMs() {
        return minRequestIntervalMs;
    }

    /**
     * 设置全局最小请求间隔。
     *
     * @param millis 毫秒；0 或负数表示关闭限流（不建议，见类注释）
     */
    public static void setMinRequestIntervalMs(long millis) {
        minRequestIntervalMs = millis;
    }

    // ---------------------------------------------------------------- 身份轮换

    public static boolean isRotateOnRiskControl() {
        return rotateOnRiskControl;
    }

    public static void setRotateOnRiskControl(boolean value) {
        rotateOnRiskControl = value;
    }

    public static int getMaxRotations() {
        return maxRotations;
    }

    public static void setMaxRotations(int times) {
        maxRotations = Math.max(0, times);
    }

    // ---------------------------------------------------------------- 超时

    public static int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public static void setConnectTimeoutMs(int millis) {
        connectTimeoutMs = Math.max(1, millis);
    }

    public static int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public static void setSocketTimeoutMs(int millis) {
        socketTimeoutMs = Math.max(1, millis);
    }

    // ---------------------------------------------------------------- 代理

    public static String getProxyHost() {
        return proxyHost;
    }

    public static int getProxyPort() {
        return proxyPort;
    }

    /** 是否配置了可用代理（主机非空且端口合法） */
    public static boolean hasProxy() {
        String host = proxyHost;
        return host != null && !host.isBlank() && proxyPort > 0 && proxyPort <= 65535;
    }

    /**
     * 显式指定代理。
     *
     * @param host 主机名或 IP
     * @param port 端口
     */
    public static void setProxy(String host, int port) {
        proxyHost = host == null ? null : host.trim();
        proxyPort = port;
    }

    /** 取消代理，恢复直连 */
    public static void clearProxy() {
        proxyHost = null;
        proxyPort = 0;
    }

    // ---------------------------------------------------------------- 整体

    /** 把全部参数恢复为默认值（注意：代理会重新按系统属性读取，而非默认直连） */
    public static void reset() {
        maxAttempts = DEFAULT_MAX_ATTEMPTS;
        baseDelayMs = DEFAULT_BASE_DELAY_MS;
        maxDelayMs = DEFAULT_MAX_DELAY_MS;
        multiplier = DEFAULT_MULTIPLIER;
        jitterRatio = DEFAULT_JITTER_RATIO;
        minRequestIntervalMs = DEFAULT_MIN_INTERVAL_MS;
        rotateOnRiskControl = true;
        maxRotations = DEFAULT_MAX_ROTATIONS;
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;
        proxyHost = readStringProperty(PROP_PROXY_HOST);
        proxyPort = readIntProperty(PROP_PROXY_PORT);
    }

    /** 一行摘要，便于排障时确认实际生效的策略 */
    public static String describe() {
        return "HttpPolicy{尝试=" + maxAttempts
                + ", 退避=" + baseDelayMs + "ms*x" + multiplier + "封顶" + maxDelayMs + "ms"
                + ", 抖动=" + jitterRatio
                + ", 最小间隔=" + minRequestIntervalMs + "ms"
                + ", 风控轮换=" + rotateOnRiskControl + "(最多" + maxRotations + "次)"
                + ", 超时=" + connectTimeoutMs + "/" + socketTimeoutMs + "ms"
                + ", 代理=" + (hasProxy() ? proxyHost + ":" + proxyPort : "直连")
                + "}";
    }

    // ---------------------------------------------------------------- 内部

    private static String readStringProperty(String key) {
        try {
            return System.getProperty(key);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static int readIntProperty(String key) {
        String raw = readStringProperty(key);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

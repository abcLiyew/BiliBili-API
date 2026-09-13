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
 *   <li><b>身份轮换</b>：对<b>风控码</b>生效，且最多 {@value #DEFAULT_MAX_ROTATIONS} 次；
 *       此外动态列表<b>空 items</b>（静默风控，见 {@link #isRotateOnEmptyFeed()}）也轮换一次。
 *       注意这与"盲重试"有本质区别：盲重试是拿<b>已被标记的指纹</b>再撞一次，
 *       只会加重风控；轮换是换一个全新匿名身份再试。</li>
 *   <li><b>代理</b>：默认直连。可用 {@link #setProxy(String, int)} 显式指定，
 *       或启动时用系统属性 {@code -Dbili.proxy.host=} / {@code -Dbili.proxy.port=} 注入。
 *       本库<b>不内置任何代理地址</b>（保持"零凭据"定位）。</li>
 *   <li><b>Cookie</b>：默认只有匿名指纹。部分端点匿名已无法通过 —— 实测
 *       {@code x/polymer/web-dynamic/v1/feed/space} 不带指纹 Cookie 返回 <b>HTTP 412</b>，
 *       带上 {@code buvid3/buvid4} 后变成 {@code code=-352}（风控），补全套
 *       {@code dm_img_*}/{@code web_location} 客户端指纹参数、换代理出口也都过不去。
 *       此时可用 {@link #setCookie(String)} 或启动时系统属性 {@code -Dbili.cookie=}
 *       注入<b>真实登录 Cookie</b>（浏览器里的 {@code SESSDATA} 等）。
 *       注入后与指纹 Cookie 合并，<b>用户 Cookie 的键优先</b>。
 *       本库<b>不内置任何凭据</b>，也不落盘。</li>
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
    /** 系统属性名：真实登录 Cookie（可选；为空表示只用匿名指纹） */
    public static final String PROP_COOKIE = "bili.cookie";

    // ---------------------------------------------------------------- 可变配置

    private static volatile int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    private static volatile long baseDelayMs = DEFAULT_BASE_DELAY_MS;
    private static volatile long maxDelayMs = DEFAULT_MAX_DELAY_MS;
    private static volatile double multiplier = DEFAULT_MULTIPLIER;
    private static volatile double jitterRatio = DEFAULT_JITTER_RATIO;
    private static volatile long minRequestIntervalMs = DEFAULT_MIN_INTERVAL_MS;
    private static volatile boolean rotateOnRiskControl = true;
    private static volatile boolean rotateOnEmptyFeed = true;
    private static volatile int maxRotations = DEFAULT_MAX_ROTATIONS;
    private static volatile int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private static volatile int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;

    private static volatile String proxyHost = readStringProperty(PROP_PROXY_HOST);
    private static volatile int proxyPort = readIntProperty(PROP_PROXY_PORT);

    /**
     * 调用方注入的<b>真实登录 Cookie</b>（如 {@code SESSDATA=xxx; bili_jct=xxx}）。
     *
     * <p>为空表示只走匿名指纹；非空时由 {@code BilibiliHttp} 与指纹 Cookie 合并成最终
     * {@code Cookie} 头，且<b>本值里的键优先</b>。
     */
    private static volatile String cookie = normalizeCookie(readStringProperty(PROP_COOKIE));

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

    /**
     * 动态列表返回<b>空 items</b>时，是否换一副身份重试一次。
     *
     * <p><b>为什么需要这个开关</b>：{@code v1/feed/space} 有一种失败形态与"业务码"无关 ——
     * 实测 {@code code=0} 但 {@code data.items=[]}（静默空），<b>无法与"该 UP 真没发动态"区分</b>。
     * {@link BilibiliHttp} 的分类只看业务码，因此这类"语义空"只能在列表服务层兜底
     * （{@code DynamicService.getInfoList}）：空则换身份再取一次。
     *
     * <p>默认开启。副作用是"确实没有动态的 UP"每次轮询都会消耗一代身份
     * （指纹接口每次调用一次，代价很低），若在意可关掉。
     *
     * @return 默认 {@code true}
     */
    public static boolean isRotateOnEmptyFeed() {
        return rotateOnEmptyFeed;
    }

    public static void setRotateOnEmptyFeed(boolean value) {
        rotateOnEmptyFeed = value;
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

    // ---------------------------------------------------------------- Cookie

    /**
     * 当前注入的真实登录 Cookie（未注入时为 {@code null}）。
     *
     * @return 归一化后的 Cookie 字符串，形如 {@code SESSDATA=xxx; bili_jct=xxx}
     */
    public static String getCookie() {
        return cookie;
    }

    /**
     * 注入真实登录 Cookie。
     *
     * <p><b>什么时候需要</b>：调用某端点持续拿到 {@code -352} 或 412，且确认不是网络问题时。
     * 实测 {@code v1/feed/space} 就属于这种 —— 匿名指纹只能把 412 变成 200+(-352)。
     * Cookie 从浏览器开发者工具里复制（Cookie 请求头整串），至少含 {@code SESSDATA}。
     *
     * <p>注入后本值会与匿名指纹 Cookie 合并，<b>本值里的键优先</b>；
     * 传 {@code null} 或空白等价于 {@link #clearCookie()}。
     *
     * @param rawCookie 完整 Cookie 字符串
     */
    public static void setCookie(String rawCookie) {
        cookie = normalizeCookie(rawCookie);
    }

    /** 清空注入的 Cookie，回到"只用匿名指纹" */
    public static void clearCookie() {
        cookie = null;
    }

    /** 是否已注入真实登录 Cookie */
    public static boolean hasCookie() {
        String value = cookie;
        return value != null && !value.isEmpty();
    }

    /**
     * 注入的登录 Cookie 里是否带了某个键（键名不区分大小写）。
     *
     * <p>给"要不要再去领匿名指纹"用：见 {@link #cookieProvidesDeviceId()}。
     *
     * @param key Cookie 键名，如 {@code buvid3} / {@code SESSDATA}
     * @return 是否包含该键
     */
    public static boolean hasCookieKey(String key) {
        String value = cookie;
        if (value == null || value.isEmpty() || key == null || key.isEmpty()) {
            return false;
        }
        for (String pair : value.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            String name = (eq < 0 ? trimmed : trimmed.substring(0, eq)).trim();
            if (name.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注入的 Cookie 是否<b>自带设备指纹</b>（{@code buvid3} 或 {@code buvid4}）。
     *
     * <p><b>为什么需要这个判断</b>（2026-09-13 实测）：{@code AnonymousSession} 领来的匿名指纹
     * 在合并时是"只补用户没有的键"（用户 Cookie 优先），所以当用户 Cookie 已经带 {@code buvid3} 时，
     * 那一次指纹请求<b>拿到的值根本不会被用到</b> —— 纯属白打一次请求，而且它恰好排在业务请求前
     * 几百毫秒，正是 B 站风控最敏感的"连发"形态（实测 1 秒内 3 个请求即 412）。
     *
     * <p>注意本判断只说"要不要去领"：<b>最终发出的 Cookie 与领不领完全无关</b>
     * （带上/不带都在合并结果里表现为用户自己的 buvid），因此跳过不改变请求内容。
     *
     * @return 是否需要由匿名指纹来提供设备指纹（{@code true} 表示用户 Cookie 已提供，可跳过领取）
     */
    public static boolean cookieProvidesDeviceId() {
        return hasCookieKey("buvid3") || hasCookieKey("buvid4");
    }

    /**
     * 当前注入 Cookie 的<b>键名</b>列表（逗号分隔，值一律不出），未注入时为空串。
     *
     * <p>供出站身份日志使用：排障时"到底发了哪几个键"比"配置里写了哪几个键"更有价值
     * （典型误判是把"配置已注入"当成"请求真的带上了"）。
     *
     * @return 形如 {@code buvid3,buvid4,SESSDATA}
     */
    public static String cookieKeys() {
        return hasCookie() ? maskCookie(cookie) : "";
    }

    // ---------------------------------------------------------------- 整体

    /** 把全部参数恢复为默认值（注意：代理与 Cookie 会重新按系统属性读取，而非强制清空） */
    public static void reset() {
        maxAttempts = DEFAULT_MAX_ATTEMPTS;
        baseDelayMs = DEFAULT_BASE_DELAY_MS;
        maxDelayMs = DEFAULT_MAX_DELAY_MS;
        multiplier = DEFAULT_MULTIPLIER;
        jitterRatio = DEFAULT_JITTER_RATIO;
        minRequestIntervalMs = DEFAULT_MIN_INTERVAL_MS;
        rotateOnRiskControl = true;
        rotateOnEmptyFeed = true;
        maxRotations = DEFAULT_MAX_ROTATIONS;
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;
        proxyHost = readStringProperty(PROP_PROXY_HOST);
        proxyPort = readIntProperty(PROP_PROXY_PORT);
        cookie = normalizeCookie(readStringProperty(PROP_COOKIE));
    }

    /** 一行摘要，便于排障时确认实际生效的策略 */
    public static String describe() {
        return "HttpPolicy{尝试=" + maxAttempts
                + ", 退避=" + baseDelayMs + "ms*x" + multiplier + "封顶" + maxDelayMs + "ms"
                + ", 抖动=" + jitterRatio
                + ", 最小间隔=" + minRequestIntervalMs + "ms"
                + ", 风控轮换=" + rotateOnRiskControl + "(最多" + maxRotations + "次)"
                + ", 空列表轮换=" + rotateOnEmptyFeed
                + ", 超时=" + connectTimeoutMs + "/" + socketTimeoutMs + "ms"
                + ", 代理=" + (hasProxy() ? proxyHost + ":" + proxyPort : "直连")
                + ", Cookie=" + (hasCookie() ? "已注入(" + maskCookie(cookie) + ")" : "仅匿名指纹")
                + "}";
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 归一化 Cookie：去首尾空白、去掉空片段。
     *
     * @param raw 原始 Cookie 字符串
     * @return 归一化结果；空白输入返回 {@code null}
     */
    private static String normalizeCookie(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : raw.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(trimmed);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 只暴露 Cookie 的键名，值一律打码 —— 日志里不能出现凭据。
     *
     * @param raw Cookie 字符串
     * @return 形如 {@code SESSDATA,bili_jct}（没有 {@code =} 的片段原样保留）
     */
    private static String maskCookie(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String pair : raw.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String key = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        return sb.toString();
    }

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

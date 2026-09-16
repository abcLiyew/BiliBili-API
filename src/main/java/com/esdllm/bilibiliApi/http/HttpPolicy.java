package com.esdllm.bilibiliApi.http;

import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 *       注入<b>真实登录 Cookie</b>（浏览器里的 {@code SESSDATA} 等）；
 *       也可用 {@code -Dbili.cookieFile=<路径>} 从文件读 —— Cookie 太长、含 {@code ;} / {@code =}，
 *       命令行传易错。文件内容就是 Cookie <b>请求头整串</b>，<b>由调用方自行准备</b>、来源不限。
 *       注入后与指纹 Cookie 合并，<b>用户 Cookie 的键优先</b>。
 *       本库<b>不内置任何凭据</b>，也不落盘。</li>
 *   <li><b>User-Agent</b>：默认<b>跟着身份走</b> —— {@code UserAgentPool} 的第 0 个是 Windows Edge
 *       （{@code Edg/131}），其余为 Chrome / macOS Safari，<b>轮换一次就换一副面孔</b>。
 *       需要"固定一副面孔"时用 {@link #setUserAgent(String)} 显式指定，之后所有出站都用它、
 *       不再随轮换变动。<b>登录场景建议显式指定</b>：凭据与面孔绑定，轮换会让
 *       "刚登录成功"变成"马上 {@code -101}"；而且把 UA 对齐到你过验/扫码的那个浏览器，
 *       能消掉"同一验证会话里两台设备"这类不一致特征（见 {@link #setUserAgent(String)}）。</li>
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
    /**
     * 系统属性名：真实登录 Cookie 的<b>文件</b>路径（可选）。
     *
     * <p>与 {@value #PROP_COOKIE} 二选一，<b>内联串优先</b>。文件内容就是 Cookie <b>请求头整串</b>
     * （形如 {@code name=value; name=value}），<b>由调用方自行准备</b> —— 本库只消费、不获取。
     * 理由见 {@link #readCookieProperty()}。
     */
    public static final String PROP_COOKIE_FILE = "bili.cookieFile";
    /** 系统属性名：显式 User-Agent（可选；为空表示跟随身份池） */
    public static final String PROP_USER_AGENT = "bili.userAgent";

    // ---------------------------------------------------------------- 可变配置

    @Getter
    private static volatile int maxAttempts = DEFAULT_MAX_ATTEMPTS;
    @Getter
    private static volatile long baseDelayMs = DEFAULT_BASE_DELAY_MS;
    @Getter
    private static volatile long maxDelayMs = DEFAULT_MAX_DELAY_MS;
    @Getter
    private static volatile double multiplier = DEFAULT_MULTIPLIER;
    @Getter
    private static volatile double jitterRatio = DEFAULT_JITTER_RATIO;
    /**
     * -- SETTER --
     *  设置全局最小请求间隔。
     *
     * @param millis 毫秒；0 或负数表示关闭限流（不建议，见类注释）
     */
    @Setter
    @Getter
    private static volatile long minRequestIntervalMs = DEFAULT_MIN_INTERVAL_MS;
    @Setter
    @Getter
    private static volatile boolean rotateOnRiskControl = true;
    /**
     * -- GETTER --
     *  动态列表返回<b>空 items</b>时，是否换一副身份重试一次。
     *  <p><b>为什么需要这个开关</b>：
     *  有一种失败形态与"业务码"无关 ——
     *  实测
     *  但
     * （静默空），<b>无法与"该 UP 真没发动态"区分</b>。
     * <p>
     *  的分类只看业务码，因此这类"语义空"只能在列表服务层兜底
     *  （
     * ）：空则换身份再取一次。
     *  <p>默认开启。副作用是"确实没有动态的 UP"每次轮询都会消耗一代身份
     *  （指纹接口每次调用一次，代价很低），若在意可关掉。
     *
     * @return 默认 {@code true}
     */
    @Setter
    @Getter
    private static volatile boolean rotateOnEmptyFeed = true;
    @Getter
    private static volatile int maxRotations = DEFAULT_MAX_ROTATIONS;
    @Getter
    private static volatile int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    @Getter
    private static volatile int socketTimeoutMs = DEFAULT_SOCKET_TIMEOUT_MS;

    @Getter
    private static volatile String proxyHost = readStringProperty(PROP_PROXY_HOST);
    @Getter
    private static volatile int proxyPort = readIntProperty(PROP_PROXY_PORT);

    /**
     * 调用方注入的<b>真实登录 Cookie</b>（如 {@code SESSDATA=xxx; bili_jct=xxx}）。
     *
     * <p>为空表示只走匿名指纹；非空时由 {@code BilibiliHttp} 与指纹 Cookie 合并成最终
     * {@code Cookie} 头，且<b>本值里的键优先</b>。
     * -- GETTER --
     *  当前注入的真实登录 Cookie（未注入时为
     * ）。
     *
     * @return 归一化后的 Cookie 字符串，形如 {@code SESSDATA=xxx; bili_jct=xxx}

     */
    @Getter
    private static volatile String cookie = normalizeCookie(readCookieProperty());

    /**
     * 调用方<b>显式指定</b>的 User-Agent（{@code null} / 空白表示跟随身份池）。
     *
     * <p>说明见 {@link #setUserAgent(String)}。默认值来自系统属性
     * {@value #PROP_USER_AGENT}，未设置时为 {@code null} ——
     * 也就是"改造前的行为"（UA 跟着 {@link AnonymousSession} 的世代走）。
     * -- GETTER --
     *  当前显式指定的 User-Agent；未指定时为
     * （= 跟随身份池）。
     *
     * @return UA 字符串或 {@code null}

     */
    @Getter
    private static volatile String userAgent = readStringProperty(PROP_USER_AGENT);

    /**
     * 与 {@link #userAgent} <b>配套</b>的 Client Hints 请求头（{@code Sec-CH-UA} 一族）。
     *
     * <p>由 {@link #deriveClientHints(String)} 从 UA 推导，两者必须同源 ——
     * "UA 说是 Edge、CH 说是 Chrome"比不发 CH 更可疑。空表表示不发（默认 / Safari 系）。
     */
    private static volatile Map<String, String> clientHints = deriveClientHints(userAgent);

    private HttpPolicy() {
    }

    // ---------------------------------------------------------------- 重试退避

    /**
     * 设置最大尝试次数。
     *
     * @param attempts 含首次在内的总次数，小于 1 时按 1 处理
     */
    public static void setMaxAttempts(int attempts) {
        maxAttempts = Math.max(1, attempts);
    }

    public static void setBaseDelayMs(long millis) {
        baseDelayMs = Math.max(0L, millis);
    }

    public static void setMaxDelayMs(long millis) {
        maxDelayMs = Math.max(0L, millis);
    }

    public static void setMultiplier(double value) {
        multiplier = Math.max(value, 1.0d);
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

    // ---------------------------------------------------------------- 身份轮换

    public static void setMaxRotations(int times) {
        maxRotations = Math.max(0, times);
    }

    // ---------------------------------------------------------------- 超时

    public static void setConnectTimeoutMs(int millis) {
        connectTimeoutMs = Math.max(1, millis);
    }

    public static void setSocketTimeoutMs(int millis) {
        socketTimeoutMs = Math.max(1, millis);
    }

    // ---------------------------------------------------------------- 代理

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
     * 注入真实登录 Cookie。
     *
     * <p><b>什么时候需要</b>：调用某端点持续拿到 {@code -352} 或 412，且确认不是网络问题时。
     * 实测 {@code v1/feed/space} 就属于这种 —— 匿名指纹只能把 412 变成 200+(-352)。
     * Cookie 从浏览器开发者工具里复制（Cookie 请求头整串），至少含 {@code SESSDATA}。
     *
     * <p>注入后本值会与匿名指纹 Cookie 合并，<b>本值里的键优先</b>；
     * 传 {@code null} 或空白等价于 {@link #clearCookie()}。
     *
     * <p><b>💡 一个不显眼但有用的用法：只注入设备身份，不含登录态</b>
     * （{@code setCookie("buvid3=…; buvid4=…")}）。
     * 此时 {@link #cookieProvidesDeviceId()} 为真，{@link AnonymousSession} 会跳过
     * "向 {@code frontend/finger/spi} 领一套全新随机指纹"那一步，出站带的就是<b>你指定的那台设备</b>。
     *
     * <p><b>为什么可能要这么做</b>（2026-09-16 真机引出，<b>属于首要假设、尚未证实</b>）：
     * 本库每次运行都领一套<b>全新随机</b>的 {@code buvid3}，因此在 B 站眼里每一次都是
     * <b>一台从没见过的设备</b>。这与两个已确证的观测同时吻合：
     * ① 登录提醒恒写<b>「未知设备」</b>（真人用 Edge 登录时写「Edge」）；
     * ② 密码登录恒返回 {@code data.status=2}「本次登录环境存在风险, 需使用手机号进行验证或绑定」，
     * 而<b>短信登录成功</b>（它本身就是"手机号验证"，正好满足这条要求）。
     * 若假设成立，把浏览器自己的 {@code buvid3}/{@code buvid4} 交进来（等于"同一台设备换一个客户端登录"），
     * 提醒就应当认出这台设备、密码登录也不再被要求二次验证。
     *
     * <p>这与本库既有的"注入你自己的 Cookie"是同一类操作 —— 用的都是<b>你自己的身份</b>，
     * 不是伪造。判别方法（A/B）：同一台机器、同一个浏览器，只切换这一个变量各登录一次，看提醒与 {@code status}。
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

    // ---------------------------------------------------------------- User-Agent

    /**
     * 是否已显式指定 User-Agent。
     *
     * @return 指定过返回 {@code true}
     */
    public static boolean hasUserAgent() {
        String value = userAgent;
        return value != null && !value.isBlank();
    }

    /**
     * 显式指定出站 User-Agent：<b>固定不变、不随身份轮换</b>。
     *
     * <p><b>为什么需要这个开关</b>（2026-09-16，登录链路真机实测引出）：
     * 本库的 UA 默认<b>跟着身份走</b> —— {@link UserAgentPool} 里第 0 个是 Windows Edge，
     * 另几个是 Chrome / macOS Safari，<b>身份轮换一次就连 UA 一起换</b>。
     * 这对"匿名抓数据"是<b>对的</b>（UA 与指纹同代才自洽，见 {@code UserAgentPool} 的说明），
     * 但对<b>登录</b>是错的：凭据是跟"某一副面孔"绑定的，登录成功后若又发生一次轮换，
     * 后续请求就换了 UA，表现是"刚登录成功，转头就 -101"。
     *
     * <p>指定后<b>所有出站</b>（含领指纹、登录、短链、抓图）都用这一个值，直到
     * {@link #clearUserAgent()} 或 {@link #reset()}。
     *
     * <p><b>传给什么值</b>：最省事的是"你自己的浏览器" ——
     * 地址栏敲 {@code javascript:navigator.userAgent} 回车，把结果原样给它，
     * 这样库发的 UA 与你在浏览器里过极验/扫码时<b>天然一致</b>
     * （不一致本身就是"同一会话两台设备"的风险特征）。也可以用
     * {@link UserAgentPool#edgeWindows(String)} 按版本号拼一个。
     *
     * <p>⚠️ <b>别用陈年版本号</b>：本库内置默认值停在 {@code Edg/131}（2024 年末），
     * 作为匿名面孔够用，但拿它去登录，等于自报"我是一年多没更新的客户端" ——
     * 这正是登录场景应该覆盖它的理由。
     *
     * <p><b>会一并启用 Client Hints</b>：本方法同时按新 UA 推导 {@code Sec-CH-UA} 一族
     * （见 {@link #clientHints()}），两者同源。理由只是<b>把出站形状对齐真实浏览器</b> ——
     * Chromium 系在 HTTPS 下一定带这族头，少了它，请求头就与真人浏览器不一致。
     *
     * <p>⚠️ <b>但它不是「未知设备」的解药</b>（2026-09-16 真机把原先的推断否掉了）：
     * 曾据"走本库登录 → B 站提醒写「未知设备」、真人用 Edge → 写「Edge」"推断
     * "只换 UA 字符串不够、补上 CH 就会认出来"。真机补齐 UA + CH 后重跑，
     * 密码登录与短信登录的提醒<b>依旧写「未知设备」</b> ⇒ <b>这条提醒不由 UA / CH 决定</b>。
     * 当前的首要假设在 {@link #setCookie(String)} 的说明里（判据是"这是不是一台我认识的设备"，
     * 设备身份在 B 站侧是 {@code buvid3} / {@code buvid4}，而本库每次运行都领一套全新的）。
     * 别再照"换个 UA 就好了"的思路往下找 —— 这条弯路已经走过两次。
     *
     * <p>Safari / Firefox 的 UA 不会得到 CH（它们本来就不发，
     * 硬造一套比不发更假）—— 见 {@link #deriveClientHints(String)}。
     *
     * @param value UA 字符串；{@code null} 或空白等价于 {@link #clearUserAgent()}
     */
    public static void setUserAgent(String value) {
        String normalized = (value == null || value.isBlank()) ? null : value.trim();
        userAgent = normalized;
        clientHints = deriveClientHints(normalized);
    }

    /** 清除显式 UA 与配套的 Client Hints，回到"跟随身份池"（即改造前的行为） */
    public static void clearUserAgent() {
        userAgent = null;
        clientHints = Map.of();
    }

    /**
     * 当前应附加的 Client Hints（{@code Sec-CH-UA} / {@code Sec-CH-UA-Mobile} /
     * {@code Sec-CH-UA-Platform}），由显式 UA 推导而来。
     *
     * <p>出站代码统一遍历它加头，从而"UA 与 CH 同源"只有一个实现点。
     *
     * @return 头名 → 头值（保序）；未显式指定 UA、或 UA 属于不发 CH 的浏览器时为空表
     */
    public static Map<String, String> clientHints() {
        return clientHints;
    }

    /**
     * 本次出站<b>实际</b>要用的 UA：有显式值用它，否则用身份自带的那一个。
     *
     * <p>所有出站代码都经这里取 UA，于是"显式优先于身份池"只有<b>一个</b>实现点。
     *
     * <p><b>为什么发送那一刻才取，而不是在身份里存好</b>：身份是<b>缓存</b>的
     * （{@link AnonymousSession#current()}）。调用方经常是"先跑过一次请求、再
     * {@code setUserAgent(...)}、再跑登录" —— 此时身份早已生成，若 UA 在生成身份时就固化下来，
     * 显式设置将<b>静默不生效</b>，而这类"设了没反应"的缺陷最难查。
     *
     * @param identityAgent 身份自带的 UA，可为 {@code null}
     * @return 实际要发出去的 UA；两处都没有时回落到池内默认面孔
     */
    public static String userAgentFor(String identityAgent) {
        String override = userAgent;
        if (override != null && !override.isBlank()) {
            return override;
        }
        return (identityAgent == null || identityAgent.isBlank())
                ? UserAgentPool.defaultAgent() : identityAgent;
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
        cookie = normalizeCookie(readCookieProperty());
        userAgent = readStringProperty(PROP_USER_AGENT);
        clientHints = deriveClientHints(userAgent);
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
                + ", UA=" + (hasUserAgent() ? "显式(" + userAgent + ")" : "跟随身份池")
                + ", ClientHints=" + (clientHints.isEmpty() ? "无" : String.join(",", clientHints.keySet()))
                + ", Cookie=" + (hasCookie() ? "已注入(" + maskCookie(cookie) + ")" : "仅匿名指纹")
                + "}";
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 从显式 UA 推导配套的 Client Hints。
     *
     * <p><b>为什么要配套发</b>：真实 Chromium 系浏览器在 HTTPS 下<b>一定</b>带 {@code Sec-CH-UA} 一族，
     * 少发就等于让出站形状与真人浏览器不一致。这里只是<b>对齐形状</b>。
     *
     * <p>⚠️ <b>它并不能解释「未知设备」</b>：曾据真机现象（本库登录 → 提醒写「未知设备」；
     * 真人用 Edge → 写「Edge」）推断"补上 CH 就能被认出来"，随即被真机否定 ——
     * 补齐 UA + CH 后重跑，提醒<b>依旧</b>写「未知设备」。详见
     * {@link #setUserAgent(String)} 与该方法的说明，别再把这条当线索。
     *
     * <p><b>Safari / Firefox 返回空表</b>：它们本来就不发 CH（Safari 至今不支持，
     * Firefox 只在特定条件下发）。给它们硬造一套，比不发更假。
     *
     * @param ua 显式 UA；{@code null} / 空白返回空表
     * @return 头名 → 头值（保序）
     */
    private static Map<String, String> deriveClientHints(String ua) {
        if (ua == null || ua.isBlank()) {
            return Map.of();
        }
        String lower = ua.toLowerCase(Locale.ROOT);
        // 判据是 Chrome/ 而不是 "safari/"：Safari 的 UA 是 Version/17.4 Safari/605.1.15，无 Chrome/
        if (!lower.contains("chrome/")) {
            return Map.of();
        }
        String version = chromeMajor(ua);
        if (version.isEmpty()) {
            return Map.of();
        }
        boolean edge = lower.contains("edg/");
        String brand = edge
                ? "\"Microsoft Edge\";v=\"" + version + "\""
                : "\"Google Chrome\";v=\"" + version + "\"";

        Map<String, String> hints = new LinkedHashMap<>();
        // grease 品牌是 Chromium 刻意塞进 CH 的"占位品牌"（各家各版本取值不同，本身不承载信息）。
        // 少了它反而是异常形态，所以照给一个。
        hints.put("Sec-CH-UA",
                brand + ", \"Chromium\";v=\"" + version + "\", \"Not=A?Brand\";v=\"24\"");
        hints.put("Sec-CH-UA-Mobile", lower.contains("mobile") ? "?1" : "?0");
        hints.put("Sec-CH-UA-Platform", "\"" + platformOf(lower) + "\"");
        // 只读视图：clientHints() 是 public，不能把可变 Map 交出去（调用方一个 put 就改了全局出站形状）
        return Collections.unmodifiableMap(hints);
    }

    /**
     * 取 UA 里 {@code Chrome/x.y.z.w} 的<b>主版本号</b>。
     *
     * <p>CH 里只放主版本（真实浏览器就是这么发的：{@code v="140"} 而不是 {@code v="140.0.0.0"}）。
     *
     * @param ua UA 字符串
     * @return 主版本，如 {@code "140"}；取不到返回空串
     */
    private static String chromeMajor(String ua) {
        int at = ua.indexOf("Chrome/");
        if (at < 0) {
            return "";
        }
        int start = at + "Chrome/".length();
        int end = start;
        while (end < ua.length()
                && (Character.isDigit(ua.charAt(end)) || ua.charAt(end) == '.')) {
            end++;
        }
        String full = ua.substring(start, end);
        int dot = full.indexOf('.');
        return dot < 0 ? full : full.substring(0, dot);
    }

    /**
     * 从 UA 判断平台（CH 的合法取值：{@code Windows} / {@code macOS} / {@code Linux} /
     * {@code Android} / {@code iOS} / {@code Chrome OS}）。
     *
     * <p>认不出来时返回空串 —— 那正是浏览器"不愿说"时的合法形态；
     * 猜一个平台出来反而是在撒谎。
     *
     * @param lowerUa 已小写的 UA
     * @return 平台名；未知时为空串
     */
    private static String platformOf(String lowerUa) {
        if (lowerUa.contains("windows")) {
            return "Windows";
        }
        if (lowerUa.contains("android")) {
            return "Android";
        }
        if (lowerUa.contains("iphone") || lowerUa.contains("ipad")) {
            return "iOS";
        }
        if (lowerUa.contains("crkey") || lowerUa.contains("cros")) {
            return "Chrome OS";
        }
        if (lowerUa.contains("macintosh") || lowerUa.contains("mac os x")) {
            return "macOS";
        }
        if (lowerUa.contains("linux")) {
            return "Linux";
        }
        return "";
    }

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
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(trimmed);
        }
        return sb.isEmpty() ? null : sb.toString();
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

    /**
     * 读"启动时注入的登录 Cookie"，支持两种形态（前者优先）：
     * 内联串 {@value #PROP_COOKIE} → 文件 {@value #PROP_COOKIE_FILE}。
     *
     * <p><b>为什么还要"文件"这一种</b>：Cookie 长度动辄几百字符、且含 {@code ;} / {@code =} 与
     * 大量敏感值。塞进命令行会在 {@code cmd} / PowerShell / IDE "VM options" 三套转义规则之间
     * 反复出错，而且它会出现在进程列表里。文件形态把这份<b>由调用方提供的</b>凭据放到磁盘上，
     * 绕开全部转义问题：文件内容就是 Cookie 请求头整串（尾部换行会被归一化掉）。
     *
     * <p>两种形态都在<b>类初始化</b>与 {@link #reset()} 时被读取；{@link #setCookie(String)}
     * 是运行时入口，优先级最高。
     *
     * <p>⚠️ 文件读不出来（不存在 / 路径非法 / 无权限）一律按"未配置"处理、<b>不抛异常</b>：
     * 本方法在类初始化阶段被调用，抛出去会连类都加载不了。代价是"配了却不生效"不会自己报错，
     * 请用 {@link #describe()} 自查（未生效时它会显示"仅匿名指纹"）。
     *
     * @return Cookie 字符串；两种形态都没有（或都读不出）返回 {@code null}
     */
    private static String readCookieProperty() {
        String inline = readStringProperty(PROP_COOKIE);
        if (inline != null && !inline.isBlank()) {
            return inline;
        }
        String path = readStringProperty(PROP_COOKIE_FILE);
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(path.trim()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("[HttpPolicy] " + PROP_COOKIE_FILE + "=" + path
                    + " 读不到，按未注入 Cookie 处理：" + e.getClass().getSimpleName());
            return null;
        }
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

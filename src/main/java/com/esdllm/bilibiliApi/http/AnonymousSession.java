package com.esdllm.bilibiliApi.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;

/**
 * 匿名会话：领取、缓存并<b>轮换</b> B 站的设备指纹 Cookie（{@code buvid3} / {@code buvid4}）。
 *
 * <p><b>为什么需要它</b>（本机实测，2026-09）：动态接口的风控主要看有没有设备指纹 Cookie，
 * 而不是 User-Agent。同一个 {@code v1/opus/detail?id=} 请求：
 * <pre>
 * 不带 Cookie（任意 UA，试过 Edge / Chrome 90~140 / Firefox / Safari）→ 绝大多数 code=-352
 * 带上匿名领到的 buvid3                                                  → code=0
 * </pre>
 * 之前还出现过"某些 Chrome 版本号能过、相邻版本号不能过"的假象 —— 那只是风控随机放行的噪声，
 * 真正起作用的变量是 buvid3。所以这里<b>不靠猜 UA</b>，而是走 B 站官方的匿名指纹接口。
 *
 * <p><b>不需要用户凭据</b>：{@code x/frontend/finger/spi} 是匿名接口，返回全新的 buvid3/buvid4，
 * 与登录态无关。因此本库保持"零凭据"定位，登录 Cookie 仍是可选增强。
 *
 * <p><b>身份轮换</b>：一个 {@link Identity} 是"指纹 Cookie + 与它同代的 UA"的不可变组合。
 * 指纹被风控标记后，{@link #rotate()} 会领一套全新身份（同时换 UA，保持自洽），
 * 由 {@link BilibiliHttp} 在命中风控码时自动调用（次数受
 * {@link HttpPolicy#getMaxRotations()} 约束）；此外动态列表出现<b>空 items</b>
 * 这一"静默风控"形态时，由 {@code DynamicService.getInfoList} 主动调用一次
 * （受 {@link HttpPolicy#isRotateOnEmptyFeed()} 控制）。
 *
 * <p><b>失败不阻塞</b>：领不到就给出一个"无 Cookie 身份"（由调用方按错误码处理），
 * 绝不因为拿不到指纹就让整个功能不可用。
 */
@Slf4j
public final class AnonymousSession {

    /** 匿名设备指纹接口 */
    private static final String SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi";

    private static final Object LOCK = new Object();

    /** 当前身份；null 表示尚未尝试领取 */
    private static volatile Identity identity;

    private AnonymousSession() {
    }

    /**
     * 一代匿名身份。
     *
     * @param cookie     可直接塞进 {@code Cookie} 头的字符串，形如 {@code buvid3=xxx; buvid4=yyy}；
     *                   领取失败时为空串
     * @param userAgent  与本代指纹配套的 UA
     * @param generation 代数，从 1 开始，每次轮换 +1
     * @param createdAt  领取时刻（毫秒时间戳）
     */
    public record Identity(String cookie, String userAgent, int generation, long createdAt) {

        /** 本代是否真的拿到了指纹 */
        public boolean hasCookie() {
            return cookie != null && !cookie.isEmpty();
        }
    }

    /**
     * 取当前身份；首次调用会同步领取（约几十毫秒），之后走缓存。
     *
     * @return 当前身份，永不为 null
     */
    public static Identity current() {
        Identity cached = identity;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (identity == null) {
                identity = obtain(1);
            }
            return identity;
        }
    }

    /**
     * 取可直接塞进 {@code Cookie} 头的字符串。
     *
     * @return Cookie 头；领取失败时返回空串（调用方按"无 Cookie"处理）
     */
    public static String cookieHeader() {
        return current().cookie();
    }

    /**
     * 取与当前指纹配套的 User-Agent。
     *
     * @return UA 字符串
     */
    public static String userAgent() {
        return current().userAgent();
    }

    /**
     * 当前身份的代数。
     *
     * @return 从 1 开始；发生轮换会递增
     */
    public static int generation() {
        return current().generation();
    }

    /** 当前身份是否拿到了指纹（拿不到说明指纹接口不可达，请求会退化为无 Cookie） */
    public static boolean isAvailable() {
        return current().hasCookie();
    }

    /**
     * 轮换身份：重新领取一套指纹，并换用与它同代的 UA。
     *
     * <p>典型用法是"被风控后换一副面孔再试"，由 {@link BilibiliHttp} 自动触发；
     * 也可由调用方在长时间运行后主动调用，避免单个指纹被长期累积标记。
     *
     * @return 轮换后的新身份
     */
    public static Identity rotate() {
        synchronized (LOCK) {
            Identity previous = identity;
            int next = (previous == null ? 1 : previous.generation()) + 1;
            Identity rotated = obtain(next);
            identity = rotated;
            log.info("匿名身份已轮换：第 {} 代 → 第 {} 代（指纹{}）",
                    previous == null ? 0 : previous.generation(), rotated.generation(),
                    fingerprintState(rotated));
            return rotated;
        }
    }

    /**
     * 一张身份里"指纹从哪来"的一句话描述，仅用于日志。
     *
     * <p>三种形态要分清楚，否则排障时会误以为"轮换没生效"：
     * <ul>
     *   <li>{@code 已领取} —— 真的向指纹接口领到了；</li>
     *   <li>{@code 由登录 Cookie 提供} —— 用户注入的 Cookie 自带 buvid，<b>按设计</b>跳过了领取，
     *       此时轮换不会改变出站身份（要换指纹得先把 Cookie 里的 buvid 去掉）；</li>
     *   <li>{@code 未取得，退化为无 Cookie} —— 指纹接口异常，请求会不带指纹（更易被风控）。</li>
     * </ul>
     */
    private static String fingerprintState(Identity identity) {
        if (identity.hasCookie()) {
            return "已领取";
        }
        if (HttpPolicy.cookieProvidesDeviceId()) {
            return "由登录 Cookie 提供（轮换不改变出站身份）";
        }
        return "未取得，退化为无 Cookie";
    }

    /**
     * 清空缓存，下次调用重新领取。
     *
     * @deprecated 语义上等同 {@link #rotate()}；保留只为兼容既有调用，
     *         新代码请直接用 {@code rotate()}（名字更准确地表达了"换一副面孔"）。
     */
    @Deprecated
    public static void reset() {
        rotate();
    }

    // ------------------------------------------------------------------ 内部

    private static Identity obtain(int generation) {
        // 显式指定过 UA 时，"领指纹"这一跳也要用同一个 —— 它本身就是一次出站请求，
        // 若这里还用池里的旧面孔，会出现"指纹请求一个 UA、业务请求另一个 UA"的自相矛盾。
        String agent = HttpPolicy.userAgentFor(UserAgentPool.at(generation - 1));

        // —————————— 登录 Cookie 已自带设备指纹时，不必再领 anonymous 指纹 ——————————
        //
        // 合并规则是"用户 Cookie 优先，指纹只补缺"（见 BilibiliHttp.composeCookie），
        // 所以用户 Cookie 已经带 buvid3 时，这次领来的指纹根本进不了最终 Cookie 头 ——
        // 唯一的效果是**多打一次请求**，而且它排在业务请求前几百毫秒，
        // 正是 B 站风控最敏感的连发形态（2026-09-13 实测：1 秒内 3 个请求即触发 412，
        // 而服务端每轮轮询都吃 412 的场景正是"指纹 + feed"贴在一起）。
        //
        // 跳过不改变出站内容：最终 Cookie 仍是用户那几对键；这里只是不再白跑一趟。
        if (HttpPolicy.cookieProvidesDeviceId()) {
            log.info("注入的登录 Cookie 已自带设备指纹（{}），跳过匿名指纹领取（第 {} 代）",
                    HttpPolicy.cookieKeys(), generation);
            return new Identity("", agent, generation, System.currentTimeMillis());
        }

        String cookie = "";
        try {
            // 指纹接口本身也是一次出站请求，纳入限流，避免与业务请求叠加把密度打高
            RateLimiter.acquire();
            // 测试钩子：让本机 mock server 也能接管指纹接口。
            // 否则单测里每次 rotate() 都会真的打 api.bilibili.com —— 既慢又不可控。
            // applyTestBaseUrl 是纯字符串函数（同包可见），不构成与 BilibiliHttp 的行为循环：
            // 行为循环指 BilibiliHttp.get → AnonymousSession.current → BilibiliHttp.get，
            // 而这里只是复用它的 URL 改写，不触发任何请求。
            String body = Unirest.get(BilibiliHttp.applyTestBaseUrl(SPI_URL))
                    .header("User-Agent", agent)
                    .header("Referer", BilibiliEndpoint.referer)
                    .connectTimeout(HttpPolicy.getConnectTimeoutMs())
                    .socketTimeout(HttpPolicy.getSocketTimeoutMs())
                    .asString()
                    .getBody();
            cookie = parseCookie(body);
        } catch (Exception e) {
            log.warn("领取匿名设备指纹异常（{}），本次按无 Cookie 继续", e.toString());
        }

        if (cookie.isEmpty()) {
            log.warn("第 {} 代匿名身份未取得设备指纹，请求将退化为无 Cookie（可能触发风控）", generation);
        } else {
            log.info("已领取匿名设备指纹（第 {} 代，buvid3 前 8 位：{}...），后续请求将自动携带",
                    generation, preview(cookie));
        }
        return new Identity(cookie, agent, generation, System.currentTimeMillis());
    }

    /** 把指纹接口的响应解析成 Cookie 头；任何异常/异常码都返回空串 */
    private static String parseCookie(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        JSONObject json;
        try {
            json = JSON.parseObject(body);
        } catch (Exception e) {
            log.warn("领取匿名设备指纹失败：响应不是合法 JSON（前 120 字：{}）", preview(body));
            return "";
        }
        if (json == null || json.getIntValue("code") != 0) {
            log.warn("领取匿名设备指纹失败：{}", preview(body));
            return "";
        }
        JSONObject data = json.getJSONObject("data");
        if (data == null) {
            return "";
        }
        String buvid3 = data.getString("b_3");
        String buvid4 = data.getString("b_4");

        StringBuilder cookie = new StringBuilder();
        if (buvid3 != null && !buvid3.isEmpty()) {
            cookie.append("buvid3=").append(buvid3);
        }
        if (buvid4 != null && !buvid4.isEmpty()) {
            if (!cookie.isEmpty()) {
                cookie.append("; ");
            }
            cookie.append("buvid4=").append(buvid4);
        }
        return cookie.toString();
    }

    /** 从 Cookie 头里截出 buvid3 的值前 8 位，仅用于日志辨识，不泄露完整指纹 */
    private static String preview(String cookie) {
        int at = cookie.indexOf("buvid3=");
        if (at < 0) {
            return cookie.length() > 120 ? cookie.substring(0, 120) : cookie;
        }
        int start = at + "buvid3=".length();
        int end = cookie.indexOf(';', start);
        String value = end < 0 ? cookie.substring(start) : cookie.substring(start, end);
        return value.length() > 8 ? value.substring(0, 8) : value;
    }
}

package com.esdllm.bilibiliApi.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一的 GET 出口：<b>伪装 + 指纹 + 限流 + 重试退避 + 身份轮换 + 代理</b>全在这一处。
 *
 * <p><b>本类是唯一出口</b>：5 个门面经 {@code service/*}、渲染链路经 {@code RenderModelLoader}、
 * 短链经 {@code ShortLinkService}，最终都汇聚到这里。
 * <b>新增网络调用请一律经由本类</b>，不要绕过它自己发请求 ——
 * 绕过就意味着那条链路没有指纹、没有限流、没有重试。
 *
 * <p>唯一例外是 {@link AnonymousSession}：它要提供"当前身份"，若再反过来调本类会形成
 * 循环依赖，因此它自己发那一次指纹请求（见 {@code AnonymousSession#obtain}）。
 *
 * <p>改造前所有的出站都塞在 {@code ApiBase} 里（只发 User-Agent 与 Accept、不带任何 Cookie），
 * 这正是动态类接口被判风控（{@code -352} / {@code 412}）的直接原因。
 * {@code ApiBase} 已在 P3 退场（仅留 {@code @Deprecated} 兼容壳），能力全部收进本类。
 *
 * <p><b>重试策略的核心是"分类"，不是"多试几次"</b>（见 {@link #classify}）：
 * <table border="1">
 *   <caption>结果分类与动作</caption>
 *   <tr><th>分类</th><th>典型</th><th>动作</th></tr>
 *   <tr><td>{@code OK}</td><td>HTTP 2xx 且 code=0</td><td>立即返回</td></tr>
 *   <tr><td>{@code RETRY}</td><td>网关 5xx（502/503/504）、408/425/429、瞬态业务码、网络异常</td>
 *       <td>退避后重试</td></tr>
 *   <tr><td>{@code RISK_CONTROL}</td><td>412 / -352 / -509 / -412</td>
 *       <td><b>不盲重试</b>；换一副身份后重试，最多 {@link HttpPolicy#getMaxRotations()} 次</td></tr>
 *   <tr><td>{@code NO_RETRY}</td><td>4xx（请求本身有问题）、500、4101139（参数名错）、
 *       4101105（id 不存在）、-403 等</td>
 *       <td>原样返回，交给上层做语义化报错</td></tr>
 * </table>
 * 这条规则很重要：{@code 4101139} 这类错误重试一万次结果都一样，而风控码盲重试
 * 只会让指纹被标记得更深。两者都必须被排除在退避重试之外。
 *
 * <p><b>返回值语义保持不变</b>：能把响应拿到手就返回响应（哪怕是错误状态），
 * 让调用方沿用 {@code ErrorMapper.forHttpStatus} / {@code ResponseParserSupport.unwrap}
 * 做语义化错误；只有在<b>一次响应都没拿到</b>（纯网络异常）时才抛出 ——
 * 这与改造前 {@code Unirest.asString()} 抛异常的行为一致，调用方的 {@code catch} 不用改。
 */
@Slf4j
public final class BilibiliHttp {

    /**
     * 测试钩子：把生产 URL 的 {@code scheme://host[:port]} 替换成这里的 base URL。
     *
     * <p>仅用于本地 HttpServer / WireMock 类测试；生产路径（默认 {@code null}）不做任何替换，
     * 行为与改造前完全一致。设值后必须配套 {@link #clearTestBaseUrl()} 还原，避免污染其他测试。
     *
     * <p><b>示例</b>：把 {@code https://api.bilibili.com/x/web-interface/view?bvid=BV1xx}
     * 改成 {@code http://127.0.0.1:8080/x/web-interface/view?bvid=BV1xx}，让本地 server 返回 fixture。
     */
    private static volatile String testBaseUrl;

    static {
        // —————————— 关闭 Unirest 的 cookie 自动管理（2026-09-13 实测定位的真 bug）——————————
        //
        // Unirest 3.13.2 的 Config 默认 getEnabledCookieManagement() == true，即它自带一个
        // cookie 罐：任何响应里的 Set-Cookie 都会被存下来，并在后续请求里自动回放。
        //
        // 本类是"Cookie 唯一出口"（见 composeCookie：用户 Cookie 优先 + 匿名指纹补缺），
        // 若再叠加 cookie 罐，后续请求的 Cookie 头就会变成
        // "我们显式拼的那串 + 罐里回放的那串"，出现同名键重复 / 身份串味。
        //
        // 实测后果（XatiiBot 推送链路，2026-09-13）：
        //   先调直播接口（其响应带 Set-Cookie）→ 紧接着调动态 feed →
        //   **稳定 HTTP 412**（B 站风控页），Cookie 有效也没用；
        //   关闭 cookie 管理后同一序列立刻恢复正常（13 条动态 + 长图渲染成功）。
        //
        // 必须在**任何请求发出前**执行：Unirest 的 Config 在客户端建好后再改会抛
        // UnirestConfigException。库内所有出站都汇聚到本类，因此类初始化就是最早时机。
        try {
            if (Unirest.config().getEnabledCookieManagement()) {
                Unirest.config().enableCookieManagement(false);
                log.info("已关闭 Unirest 的 cookie 自动管理：Cookie 一律由 BilibiliHttp 显式组装");
            }
        } catch (Exception e) {
            // 已有其它代码先建好了客户端 —— 不致命，但要留下痕迹，否则又会变成"Cookie 分明对却 412"
            log.warn("关闭 Unirest cookie 管理失败（可能已有客户端先行建立），Cookie 头可能被 cookie 罐叠加：{}",
                    e.toString());
        }
    }

    /** 设置测试 base URL（{@code null} 表示关闭）。生产代码不应调用。 */
    public static void setTestBaseUrl(String baseUrl) {
        testBaseUrl = baseUrl;
    }

    /** 关闭测试 base URL 改写。生产代码不应调用。 */
    public static void clearTestBaseUrl() {
        testBaseUrl = null;
    }

    private BilibiliHttp() {
    }

    /**
     * 发起 GET 并返回响应原文，自动执行限流、重试退避、身份轮换与代理。
     *
     * @param url 完整地址
     * @return 最后一次拿到的响应（可能是错误状态，交由调用方语义化）
     */
    public static HttpResponse<String> get(String url) {
        int maxAttempts = HttpPolicy.getMaxAttempts();
        int rotations = 0;
        HttpResponse<String> lastResponse = null;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RateLimiter.acquire();

            try {
                HttpResponse<String> response = send(url, AnonymousSession.current());
                lastResponse = response;
                lastError = null;

                Verdict verdict = classify(response);
                if (verdict == Verdict.OK) {
                    return response;
                }

                if (verdict == Verdict.RISK_CONTROL) {
                    // 已达最后一次尝试时不再轮换：换了也没机会用上，白白消耗一代身份
                    boolean canStillRetry = attempt < maxAttempts;
                    if (canStillRetry && HttpPolicy.isRotateOnRiskControl()
                            && rotations < HttpPolicy.getMaxRotations()) {
                        rotations++;
                        AnonymousSession.Identity rotated = AnonymousSession.rotate();
                        log.warn("命中风控（HTTP {}，业务码 {}），已轮换匿名身份至第 {} 代后重试第 {}/{} 次：{}",
                                response.getStatus(), businessCode(response.getBody()),
                                rotated.generation(), attempt + 1, maxAttempts, url);
                        continue;
                    }
                    log.warn("命中风控且轮换次数已用尽（{} 次），不再重试：{}", rotations, url);
                    return response;
                }

                if (verdict == Verdict.NO_RETRY) {
                    return response;
                }

                // Verdict.RETRY：可退避重试
                if (attempt >= maxAttempts) {
                    log.warn("已达最大尝试次数 {}，返回最后一次响应（HTTP {}，业务码 {}）：{}",
                            maxAttempts, response.getStatus(), businessCode(response.getBody()), url);
                    return response;
                }
                backoff(attempt, "HTTP " + response.getStatus() + "，业务码 " + businessCode(response.getBody()));
            } catch (RuntimeException e) {
                // 纯网络层失败：一次响应都没拿到
                lastError = e;
                lastResponse = null;
                if (attempt >= maxAttempts) {
                    break;
                }
                backoff(attempt, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        if (lastResponse != null) {
            return lastResponse;
        }
        // 保持与改造前一致：网络异常以 RuntimeException 抛出，调用方既有 catch 不受影响
        throw lastError != null ? lastError : new IllegalStateException("请求失败且未获得响应：" + url);
    }

    // ------------------------------------------------------------------ 发送

    private static HttpResponse<String> send(String url, AnonymousSession.Identity identity) {
        url = applyTestBaseUrl(url);
        GetRequest request = Unirest.get(url)
                .header("User-Agent", identity.userAgent())
                .header("Accept", BilibiliEndpoint.accept)
                .header("Referer", BilibiliEndpoint.referer)
                .connectTimeout(HttpPolicy.getConnectTimeoutMs())
                .socketTimeout(HttpPolicy.getSocketTimeoutMs());

        String cookie = composeCookie(identity.cookie());
        if (cookie != null && !cookie.isEmpty()) {
            request.header("Cookie", cookie);
        }
        logOutgoingIdentity(identity, cookie);
        if (HttpPolicy.hasProxy()) {
            request.proxy(HttpPolicy.getProxyHost(), HttpPolicy.getProxyPort());
        }
        return request.asString();
    }

    /** 上一次打印过的出站身份签名：只在"身份构成变化"时打一行，避免每轮刷屏 */
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_IDENTITY_SIGNATURE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 打印一次"这一次到底以什么身份出站"（<b>只打印键名，值一律不出</b>）。
     *
     * <p><b>为什么值得专门打这一行</b>（2026-09-13 排障教训）：服务端持续 412 时，
     * 日志里只有 {@code HttpPolicy.describe()} 那句"Cookie=已注入(buvid3,buvid4,SESSDATA)"——
     * 它证明的是<b>配置里有</b>，而不是<b>请求真的带上了</b>。两者之间隔着合并、代理、
     * 连接层好几道，排障时极易把时间花在"Cookie 是不是失效"上。
     * 本行直接把"实际发出的键名 + 指纹来源"写进日志，一次就能分辨：
     * <ul>
     *   <li>{@code 未携带任何 Cookie} → 是拼装/注入的问题，与风控无关；</li>
     *   <li>{@code 键=[buvid3,buvid4,SESSDATA]，来源=登录 Cookie} → 请求形状没问题，
     *       412 只能归因于出口 IP / 指纹本身被标记。</li>
     * </ul>
     *
     * <p>只在签名变化时打印（首次必然打印），因此长期运行不会刷屏。
     *
     * @param identity       本代身份（提供匿名指纹与 UA）
     * @param composedCookie 实际写进 {@code Cookie} 头的内容，可为 {@code null}
     */
    private static void logOutgoingIdentity(AnonymousSession.Identity identity, String composedCookie) {
        boolean hasCookie = composedCookie != null && !composedCookie.isEmpty();
        String source = HttpPolicy.cookieProvidesDeviceId()
                ? "登录 Cookie"
                : (identity.hasCookie() ? "匿名指纹" : "无");
        String signature = keysOf(composedCookie) + "|" + source;
        if (signature.equals(LAST_IDENTITY_SIGNATURE.getAndSet(signature))) {
            return;
        }
        if (!hasCookie) {
            log.warn("出站身份：未携带任何 Cookie（匿名端点会被判 412）；指纹来源={}", source);
            return;
        }
        log.info("出站身份：Cookie 键=[{}]，设备指纹来源={}，UA=\"{}\"",
                keysOf(composedCookie), source, brief(identity.userAgent(), 40));
    }

    /** 取 Cookie 的键名（逗号分隔，值不出），纯日志用 */
    private static String keysOf(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : cookie.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String key = (eq < 0 ? trimmed : trimmed.substring(0, eq)).trim();
            if (key.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        return sb.toString();
    }

    /** 截断长文本，避免 UA 之类的长串把日志撑爆 */
    private static String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /**
     * 组装最终发给 B 站的 {@code Cookie} 头。
     *
     * <p>规则：<b>调用方注入的真实登录 Cookie 优先</b>（见 {@link HttpPolicy#setCookie(String)}），
     * 指纹 Cookie（{@code buvid3}/{@code buvid4}）只用来<b>补用户没带的键</b>。
     *
     * <p>为什么不是简单拼接：两串里可能都有 {@code buvid3}。直接粘成
     * {@code buvid3=A; ...; buvid3=B} 后，B 站取到哪个由服务端实现决定，
     * 可能出现"用户 Cookie 明明带对了却被指纹值覆盖"的诡异风控 —— 必须按键去重。
     *
     * @param anonymousCookie 匿名指纹 Cookie，可为 {@code null}
     * @return 合并后的 Cookie 头；两串都为空时返回 {@code null}
     */
    static String composeCookie(String anonymousCookie) {
        String userCookie = HttpPolicy.getCookie();
        boolean hasUser = userCookie != null && !userCookie.isEmpty();
        boolean hasAnonymous = anonymousCookie != null && !anonymousCookie.isEmpty();
        if (!hasUser) {
            return hasAnonymous ? anonymousCookie : null;
        }
        if (!hasAnonymous) {
            return userCookie;
        }

        Map<String, String> merged = new LinkedHashMap<>();
        putCookiePairs(merged, userCookie, false);
        // 只补用户没有的键：putIfAbsent 语义
        putCookiePairs(merged, anonymousCookie, true);
        return String.join("; ", merged.values());
    }

    /**
     * 把一串 Cookie 拆成 {@code key=value} 放进 map。
     *
     * @param target       目标 map（保持插入顺序）
     * @param raw          Cookie 字符串
     * @param keepExisting 为 {@code true} 时只补不覆盖（匿名指纹用），否则覆盖（用户 Cookie 用）
     */
    private static void putCookiePairs(Map<String, String> target, String raw, boolean keepExisting) {
        for (String pair : raw.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String key = (eq < 0 ? trimmed : trimmed.substring(0, eq)).trim();
            if (key.isEmpty()) {
                continue;
            }
            if (keepExisting) {
                target.putIfAbsent(key, trimmed);
            } else {
                target.put(key, trimmed);
            }
        }
    }

    // ------------------------------------------------------------------ 关重定向（短链专用）

    /**
     * <b>关掉自动重定向</b>发一次 GET，返回原始响应 —— 只为读中间跳转的
     * {@code Location} header（短链解析用）。
     *
     * <p><b>为什么单独一个方法</b>：Unirest 会自动跟随 302，拿不到中间那一跳的
     * {@code Location}；必须改用 Apache HttpClient 的 {@code disableRedirectHandling()}。
     * 该能力原先在 {@code ApiBase.getHttpResponseNotRedirect} 里，2026-09-13（P3）迁到本类 ——
     * 让"出站"只剩本类这一个出口，Apache 依赖也不出 {@code http} 包。
     *
     * <p>与 {@link #get(String)} 的区别：
     * <ul>
     *   <li><b>不</b>走重试/退避（读 Location 是一次性动作，重试无意义）；</li>
     *   <li><b>不</b>做业务码分类（这里只关心 HTTP 头）；</li>
     *   <li>仍然带 UA / Accept / Referer / 指纹 Cookie / 超时 / 代理 —— 短链也在 B 站域名下，
     *       不该成为一条裸奔的旁路。</li>
     * </ul>
     *
     * @param url 完整地址
     * @return Apache HttpClient 的响应（调用方自取 header）；网络失败抛 {@link IOException}
     */
    public static org.apache.http.HttpResponse getNoRedirect(String url) throws IOException {
        url = applyTestBaseUrl(url);
        HttpGet request = new HttpGet(url);
        request.setHeader("User-Agent", AnonymousSession.userAgent());
        request.setHeader("Accept", BilibiliEndpoint.accept);
        request.setHeader("Referer", BilibiliEndpoint.referer);
        String cookie = composeCookie(AnonymousSession.cookieHeader());
        if (cookie != null && !cookie.isEmpty()) {
            request.setHeader("Cookie", cookie);
        }

        RequestConfig.Builder config = RequestConfig.custom()
                .setConnectTimeout(HttpPolicy.getConnectTimeoutMs())
                .setConnectionRequestTimeout(HttpPolicy.getConnectTimeoutMs())
                .setSocketTimeout(HttpPolicy.getSocketTimeoutMs());

        HttpClientBuilder builder = HttpClientBuilder.create()
                .disableRedirectHandling()
                .setDefaultRequestConfig(config.build());
        if (HttpPolicy.hasProxy()) {
            builder.setProxy(new HttpHost(HttpPolicy.getProxyHost(), HttpPolicy.getProxyPort()));
        }

        HttpClient client = builder.build();
        return client.execute(request);
    }

    /**
     * 取短链跳转的真实地址（即 {@code Location} header）。
     *
     * <p>只暴露"拿 Location"这一件事，让调用方不必碰 Apache 类型。
     *
     * @param url 短链地址
     * @return {@code Location} 的值；header 不存在 / 网络失败 / 解析异常时返回 {@code null}
     */
    public static String getLocation(String url) {
        try {
            org.apache.http.HttpResponse response = getNoRedirect(url);
            org.apache.http.Header location = response.getFirstHeader("Location");
            return location == null ? null : location.getValue();
        } catch (Exception e) {
            log.debug("读取跳转地址失败：{}（{}）", url, e.toString());
            return null;
        }
    }

    /**
     * 把 URL 的 {@code scheme://host[:port]} 替换为 {@link #testBaseUrl}（测试钩子用）。
     * 路径 + query + fragment 原样保留。
     *
     * @param url 完整 URL
     * @return 测试 base URL 未设置时返回原 URL；设置后替换前缀
     */
    static String applyTestBaseUrl(String url) {
        String base = testBaseUrl;
        if (base == null || base.isEmpty() || url == null) {
            return url;
        }
        int protoEnd = url.indexOf("://");
        if (protoEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', protoEnd + 3);
        if (pathStart < 0) {
            return base;
        }
        return base + url.substring(pathStart);
    }

    /**
     * 测试钩子的公开版本：与 {@link #applyTestBaseUrl(String)} 一致。
     *
     * <p>存在的意义是供<b>其它包的测试</b>验证"URL 是否会被改写"；
     * 包内的 {@code getNoRedirect} / {@code send} 直接用 {@code applyTestBaseUrl}。
     */
    public static String rewriteForTest(String url) {
        return applyTestBaseUrl(url);
    }

    // ------------------------------------------------------------------ 分类

    /** 一次尝试的结果分类（包内可见，便于单测直接验证分类逻辑） */
    enum Verdict {
        /** 成功，直接返回 */
        OK,
        /** 瞬态失败，可退避重试 */
        RETRY,
        /** 风控，只允许换身份重试 */
        RISK_CONTROL,
        /** 重试无意义，原样返回 */
        NO_RETRY
    }

    /**
     * 判定一次响应该怎么处理。
     *
     * @param response Unirest 响应
     * @return 结果分类
     */
    static Verdict classify(HttpResponse<String> response) {
        int status = response.getStatus();

        // HTTP 412 是风控页（实测返回的是 HTML，不是 JSON）——必须先按状态码判
        if (status == 412) {
            return Verdict.RISK_CONTROL;
        }
        if (status < 200 || status >= 300) {
            return httpVerdict(status);
        }

        Integer code = businessCode(response.getBody());
        if (code == null || code == 0) {
            // 非 JSON 或业务成功：不在这里下结论，交给上层解析并报错
            return Verdict.OK;
        }
        return verdictOf(code);
    }

    private static Verdict verdictOf(int code) {
        if (ErrorMapper.isRiskControl(code)) {
            return Verdict.RISK_CONTROL;
        }
        return ErrorMapper.retryable(code) ? Verdict.RETRY : Verdict.NO_RETRY;
    }

    /**
     * HTTP 状态码的重试判定。
     *
     * <p><b>为什么不直接复用 {@link ErrorMapper}</b>：{@code ErrorMapper} 的名单按<b>业务码</b>构成
     * （其中的 {@code -404} 是"无此项"业务码，与 HTTP 404 不是一回事）。HTTP 层有自己成熟的分类：
     * 4xx 是"请求本身有问题"，重试只是浪费；5xx 里网关类 502/503/504 是瞬态，值得退避。
     *
     * <p>两个刻意的例外：
     * <ul>
     *   <li>{@code 408/425/429} 虽然是 4xx，但语义是"超时 / 太早 / 被限流"，属瞬态，值得重试；</li>
     *   <li>{@code 500} 归为不可重试 —— 实测它来自服务端的确定性 JS 异常
     *       （传错参数时 {@code Cannot read property 'only_fans' of undefined}），
     *       重试一万次结果都一样。</li>
     * </ul>
     *
     * @param status HTTP 状态码
     * @return 结果分类
     */
    private static Verdict httpVerdict(int status) {
        if (status == 412) {
            return Verdict.RISK_CONTROL;
        }
        if (status == 408 || status == 425 || status == 429) {
            return Verdict.RETRY;
        }
        if (status == 500) {
            return Verdict.NO_RETRY;
        }
        if (status > 500) {
            return Verdict.RETRY;
        }
        return Verdict.NO_RETRY;
    }

    /**
     * 尽量从响应体里取出 B 站业务码。
     *
     * @param body 响应体
     * @return 业务码；不是 JSON 或没有 code 字段时返回 {@code null}
     */
    static Integer businessCode(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        char first = body.charAt(0);
        if (first != '{' && first != '[' && first != ' ' && first != '\n' && first != '\r' && first != '\t') {
            return null;   // 明显不是 JSON（如风控 HTML），不必尝试解析
        }
        try {
            JSONObject json = JSON.parseObject(body);
            return json == null ? null : json.getInteger("code");
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 退避

    private static void backoff(int failedAttempt, String reason) {
        long delay = HttpPolicy.backoffMillis(failedAttempt);
        if (delay <= 0L) {
            return;
        }
        log.debug("第 {} 次尝试失败（{}），退避 {}ms 后重试", failedAttempt, reason, delay);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

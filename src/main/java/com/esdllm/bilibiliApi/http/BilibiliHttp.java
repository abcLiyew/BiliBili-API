package com.esdllm.bilibiliApi.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import com.esdllm.bilibiliApi.sign.WbiSigner;
import kong.unirest.GetRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.Setter;
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
     * -- SETTER --
     * 设置测试 base URL（
     *  表示关闭）。生产代码不应调用。

     */
    @Setter
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
        return get(url, null, null);
    }

    /**
     * 与 {@link #get(String)} 相同，但可覆盖 {@code Accept} / {@code Referer}。
     *
     * <p><b>为什么要能覆盖</b>（2026-09-13 真机 412 排查）：本类默认发的是"文档型"形状
     * （{@link BilibiliEndpoint#accept} + 站根 Referer），它适合"用户点开一个页面"的场景；
     * 但 B 站 web 前端请求 {@code /x/...} 这类 JSON 接口时，发的是
     * {@code application/json} + <b>当前页面</b>的 Referer。
     *
     * <p>形状不一致本身不会报错，它只会让请求在低信誉出口（机房 IP）上更像"非浏览器客户端" ——
     * 而风控恰恰是按这个打分的。所以"对齐真实客户端"是这类端点该做的事，而不是碰运气。
     *
     * <p>刻意只开放这两个头、并<b>直接替换</b>而不是追加：Unirest 的 {@code header()} 语义是追加，
     * 追加会出现同名头两份（`Accept: text/html...` + `Accept: application/json...`），
     * 那比不覆盖更糟。
     *
     * @param url     完整地址
     * @param accept  覆盖 {@code Accept}；{@code null}/空白表示用默认
     * @param referer 覆盖 {@code Referer}；{@code null}/空白表示用默认
     * @return 最后一次拿到的响应
     */
    public static HttpResponse<String> get(String url, String accept, String referer) {
        return execute(url, identity -> send(url, identity, accept, referer));
    }

    /**
     * 发起 <b>POST（{@code application/x-www-form-urlencoded}）</b>并返回响应原文。
     *
     * <p>与 {@link #get(String, String, String)} 走<b>同一套</b>限流 / 重试退避 / 风控轮换 /
     * 指纹 / 代理 —— 见 {@link #execute}。账号登录（密码 / 短信）全部是 POST，缺了这条出口，
     * 那两条链路就只能绕过本类自己发请求，等于同时失去上面五项。
     *
     * <p><b>为什么用 form 而不是 JSON</b>：passport 域的登录接口都只吃
     * {@code application/x-www-form-urlencoded}（与 B 站前端
     * {@code curl --data-urlencode} 的形状一致）。
     *
     * @param url  完整地址
     * @param form 表单字段（值不得为 null；按插入顺序提交）
     * @return 最后一次拿到的响应
     */
    public static HttpResponse<String> postForm(String url, Map<String, String> form) {
        return postForm(url, form, null, null);
    }

    /**
     * 与 {@link #postForm(String, Map)} 相同，但可覆盖 {@code Accept} / {@code Referer}。
     *
     * @param url     完整地址
     * @param form    表单字段，可为 {@code null}（按空表单发）
     * @param accept  覆盖 {@code Accept}；{@code null}/空白表示用默认
     * @param referer 覆盖 {@code Referer}；{@code null}/空白表示用默认
     * @return 最后一次拿到的响应
     */
    public static HttpResponse<String> postForm(String url, Map<String, String> form,
                                                String accept, String referer) {
        Map<String, String> safeForm = form == null ? Map.of() : form;
        return execute(url, identity -> sendPost(url, identity, safeForm, accept, referer));
    }

    /** 一次出站尝试：把"用哪一代身份发"交给调用方决定，其余（重试/轮换/退避）由 {@link #execute} 统一管 */
    @FunctionalInterface
    private interface Sender {
        HttpResponse<String> send(AnonymousSession.Identity identity);
    }

    // ------------------------------------------------------------------ 带 WBI 签名的 GET

    /**
     * 发起<b>带 WBI 签名</b>的 GET（{@code Accept} 用 JSON 形状、Referer 用站根）。
     *
     * <p>等价于 {@code getSigned(url, params, BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer)}。
     *
     * @param url    不含 query 的端点地址（形如 {@code BilibiliEndpoint.accInfoUrl}）
     * @param params 要被签名的参数表
     * @return 最后一次拿到的响应
     */
    public static HttpResponse<String> getSigned(String url, Map<String, String> params) {
        return getSigned(url, params, BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
    }

    /**
     * 发起<b>带 WBI 签名</b>的 GET，并可覆盖 {@code Accept} / {@code Referer}。
     *
     * <p>签名本身是纯计算（见 {@link WbiSigner}），密钥来自 {@link WbiKeyStore}（当天缓存，
     * 命中缓存时<b>不产生额外出站</b>）。拼好 query 之后走的仍是 {@link #get(String, String, String)}
     * —— 也就是说签名请求与普通请求共享同一套<b>限流 / 重试退避 / 风控身份轮换 / 指纹 / 代理</b>。
     * 这一点很重要：签名是"服务端要求的参数"，不是"可以绕过抗压层的特权通道"。
     *
     * <p><b>签名被拒时自动重签一次</b>：本地缓存的密钥可能已经过期（跨日边界，或 B 站临时轮换），
     * 表现是 {@code -403 访问权限不足}，或文档提到的 {@code data.v_voucher}。这时丢缓存重取
     * {@code nav} 再签一次 —— <b>最多一次</b>，因为再失败就说明不是密钥的问题（参数/权限/风控）。
     *
     * <p>⚠️ {@code -403} 在本库有<b>两种成因</b>（缺签名 <b>或</b> 资源权限不足，见
     * {@code ErrorMapper} 的说明）。这里只把它当作"可能是密钥过期"的触发器，<b>不做语义判断</b>：
     * 重签一次的成本是一次请求，而误判成"权限问题"会让一批接口在密钥换日那天集体失效。
     * 也只有本方法（签名出口）会做这件事 —— {@link #get(String)} 的 {@code -403} 一律原样返回。
     *
     * @param url     不含 query 的端点地址
     * @param params  要被签名的参数表（可为空表：那样只剩 {@code wts} 参与签名）
     * @param accept  覆盖 {@code Accept}；{@code null}/空白表示用默认
     * @param referer 覆盖 {@code Referer}；{@code null}/空白表示用默认
     * @return 最后一次拿到的响应
     * @throws BilibiliException {@code url} 为空、或取不到 WBI 密钥（{@code nav} 不可达/被风控/形状已变）
     */
    public static HttpResponse<String> getSigned(String url, Map<String, String> params,
                                                 String accept, String referer) {
        if (url == null || url.isBlank()) {
            throw new BilibiliException("WBI 签名请求失败：url 不能为空");
        }
        WbiKeyStore.WbiKeys keys = WbiKeyStore.get();
        if (keys == null) {
            // 密钥取不到就无法签名，"发一个没签名的请求试试"只会拿到 -403 并让人以为端点坏了 ——
            // 宁可在这里明确失败，把原因写在消息里
            throw new BilibiliException("WBI 签名失败：取不到 img_key/sub_key"
                    + "（nav 不可达、被风控，或响应形状已变）");
        }
        HttpResponse<String> response = get(signedUrl(url, params, keys), accept, referer);
        if (!looksLikeSignRejected(response)) {
            return response;
        }

        log.warn("签名被拒（HTTP {}，业务码 {}），疑似密钥已过期，重新取 nav 后重签一次：{}",
                response.getStatus(), businessCode(response.getBody()), url);
        WbiKeyStore.invalidate();
        WbiKeyStore.WbiKeys refreshed = WbiKeyStore.get();
        if (refreshed == null || sameKeys(keys, refreshed)) {
            // 密钥根本没变 ⇒ 重发只会得到同一份失败。原样返回，让上层按业务码报错
            log.warn("重新取到的 WBI 密钥与缓存一致，不再重发（问题不在签名）：{}", url);
            return response;
        }
        log.info("WBI 密钥已更新（{} → {}），用新密钥重签：{}", keys.summary(), refreshed.summary(), url);
        return get(signedUrl(url, params, refreshed), accept, referer);
    }

    /** 把签名后的 query 拼到端点地址上（已含 query 的地址用 {@code &} 衔接） */
    static String signedUrl(String url, Map<String, String> params, WbiKeyStore.WbiKeys keys) {
        String query = WbiSigner.sign(params, keys.imgKey(), keys.subKey());
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    /** 两份密钥是否相同（都按 32 位 hex 比较，没必要更聪明） */
    private static boolean sameKeys(WbiKeyStore.WbiKeys first, WbiKeyStore.WbiKeys second) {
        return first.imgKey().equals(second.imgKey()) && first.subKey().equals(second.subKey());
    }

    /**
     * 这次响应是否<b>可能是</b>签名被拒（触发一次重签用，不代表结论）。
     *
     * <p>两个判据：body 里出现文档所述的 {@code v_voucher}（服务端为"签名缺失/错误"留的内部 id），
     * 或业务码为 {@code -403}。后者在别处也可能意味着"资源权限不足"，这里刻意宽松 ——
     * 见 {@link #getSigned} 的说明。
     *
     * @param response 响应
     * @return true 表示值得丢缓存重签一次
     */
    static boolean looksLikeSignRejected(HttpResponse<String> response) {
        String body = response.getBody();
        if (body == null || body.isEmpty()) {
            return false;
        }
        if (body.contains("v_voucher")) {
            return true;
        }
        Integer code = businessCode(body);
        return code != null && code == -403;
    }

    /**
     * 重试 / 退避 / 风控轮换的<b>统一循环</b> —— GET 与 POST 共用一份，避免两套策略各自漂移。
     *
     * <p>策略表见类注释；这里只强调一条：{@code RISK_CONTROL} 时<b>不盲重试</b>，
     * 而是换一副身份再试（最多 {@link HttpPolicy#getMaxRotations()} 次）。
     *
     * @param url    仅用于日志与最终异常消息
     * @param sender 单次发送动作（每轮拿到当前身份）
     * @return 最后一次拿到的响应（可能是错误状态，交由调用方语义化）
     */
    private static HttpResponse<String> execute(String url, Sender sender) {
        int maxAttempts = HttpPolicy.getMaxAttempts();
        int rotations = 0;
        HttpResponse<String> lastResponse = null;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RateLimiter.acquire();

            try {
                HttpResponse<String> response = sender.send(AnonymousSession.current());
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

    private static HttpResponse<String> send(String url, AnonymousSession.Identity identity,
                                             String accept, String referer) {
        url = applyTestBaseUrl(url);
        boolean customHead = (accept != null && !accept.isBlank()) || (referer != null && !referer.isBlank());
        GetRequest request = Unirest.get(url)
                .header("User-Agent", HttpPolicy.userAgentFor(identity.userAgent()))
                .header("Accept", accept == null || accept.isBlank() ? BilibiliEndpoint.accept : accept)
                .header("Referer", referer == null || referer.isBlank() ? BilibiliEndpoint.referer : referer)
                .connectTimeout(HttpPolicy.getConnectTimeoutMs())
                .socketTimeout(HttpPolicy.getSocketTimeoutMs());

        String cookie = composeCookie(identity.cookie());
        if (cookie != null && !cookie.isEmpty()) {
            request.header("Cookie", cookie);
        }
        // 显式指定过 UA 时，配套的 Client Hints 一起发（真实 Chromium 系在 HTTPS 下必带这族头，
        // 少发就是出站形状与真人浏览器不一致）。⚠️ 这只是对齐形状：2026-09-16 真机已否定
        // "补上 CH 就会被认成 Edge"——补齐后登录提醒依旧写「未知设备」，别再把这条当线索
        //（真正的判据见 HttpPolicy.setCookie 的说明）。未显式指定时是空表。
        HttpPolicy.clientHints().forEach(request::header);
        logOutgoingIdentity(identity, cookie, customHead ? "JSON+页面 Referer" : "默认文档头");
        if (HttpPolicy.hasProxy()) {
            request.proxy(HttpPolicy.getProxyHost(), HttpPolicy.getProxyPort());
        }
        return request.asString();
    }

    /**
     * 发一次 POST（{@code application/x-www-form-urlencoded}）。
     *
     * <p><b>为什么用 Unirest 的 {@code fields(...)} 而不是自己拼 body</b>：
     * 在没调 {@code multiPartContent()} 的前提下，Unirest 会把字段编码成
     * {@code application/x-www-form-urlencoded} 并设置好 {@code Content-Type}；
     * 而自己拼串走 {@code body(String)} 会被强制成 {@code text/plain}
     * （{@code StringBody} 自带的内容类型），B 站登录接口不认这个形状。
     *
     * <p>与 {@link #send} 一样：UA / Accept / Referer / 指纹 Cookie / 超时 / 代理 一个不少，
     * POST 不该成为绕过这套伪装的旁路。
     *
     * @param url      完整地址（未做测试 base 改写）
     * @param identity 本次使用的身份
     * @param form     表单字段
     * @param accept   覆盖 {@code Accept}，可为 {@code null}
     * @param referer  覆盖 {@code Referer}，可为 {@code null}
     * @return Unirest 响应
     */
    private static HttpResponse<String> sendPost(String url, AnonymousSession.Identity identity,
                                                 Map<String, String> form, String accept, String referer) {
        String target = applyTestBaseUrl(url);
        boolean customHead = (accept != null && !accept.isBlank()) || (referer != null && !referer.isBlank());
        HttpRequestWithBody request = Unirest.post(target)
                .header("User-Agent", HttpPolicy.userAgentFor(identity.userAgent()))
                .header("Accept", accept == null || accept.isBlank() ? BilibiliEndpoint.accept : accept)
                .header("Referer", referer == null || referer.isBlank() ? BilibiliEndpoint.referer : referer)
                .connectTimeout(HttpPolicy.getConnectTimeoutMs())
                .socketTimeout(HttpPolicy.getSocketTimeoutMs());

        String cookie = composeCookie(identity.cookie());
        if (cookie != null && !cookie.isEmpty()) {
            request.header("Cookie", cookie);
        }
        // 与 send() 同理：UA 与 CH 同源发送（只是对齐形状，不是「未知设备」的解药 ——
        // 真机已否定该推断，见 HttpPolicy.setUserAgent 的说明）
        HttpPolicy.clientHints().forEach(request::header);
        logOutgoingIdentity(identity, cookie, customHead ? "JSON+页面 Referer(POST)" : "默认文档头(POST)");
        if (HttpPolicy.hasProxy()) {
            request.proxy(HttpPolicy.getProxyHost(), HttpPolicy.getProxyPort());
        }
        Map<String, Object> fields = new LinkedHashMap<>(form);
        return request.fields(fields).asString();
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
     * @param requestShape  请求头形状的短标签（"默认文档头" / "JSON+页面 Referer"），
     *                      用来在日志里区分"发的是哪一套形状"
     */
    private static void logOutgoingIdentity(AnonymousSession.Identity identity, String composedCookie,
                                            String requestShape) {
        boolean hasCookie = composedCookie != null && !composedCookie.isEmpty();
        String source = HttpPolicy.cookieProvidesDeviceId()
                ? "登录 Cookie"
                : (identity.hasCookie() ? "匿名指纹" : "无");
        String ua = HttpPolicy.userAgentFor(identity.userAgent());
        Map<String, String> hints = HttpPolicy.clientHints();
        String hintNames = hints.isEmpty() ? "无" : String.join(",", hints.keySet());
        // 签名里必须带上 UA 与 CH：UA 改了却因为"身份没变"而不重打日志，
        // 排障时就会看到一行**过期**的 UA —— 比没有日志更误导（"我明明设了"）
        String signature = keysOf(composedCookie) + "|" + source + "|" + requestShape
                + "|" + ua + "|" + hintNames;
        if (signature.equals(LAST_IDENTITY_SIGNATURE.getAndSet(signature))) {
            return;
        }
        if (!hasCookie) {
            log.warn("出站身份：未携带任何 Cookie（匿名端点会被判 412）；指纹来源={}，请求头={}，"
                            + "UA=\"{}\"，ClientHints={}",
                    source, requestShape, brief(ua, 40), hintNames);
            return;
        }
        log.info("出站身份：Cookie 键=[{}]，设备指纹来源={}，请求头={}，UA=\"{}\"，ClientHints={}",
                keysOf(composedCookie), source, requestShape, brief(ua, 40), hintNames);
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
            if (!sb.isEmpty()) {
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
        request.setHeader("User-Agent", HttpPolicy.userAgentFor(AnonymousSession.userAgent()));
        request.setHeader("Accept", BilibiliEndpoint.accept);
        request.setHeader("Referer", BilibiliEndpoint.referer);
        String cookie = composeCookie(AnonymousSession.cookieHeader());
        if (cookie != null && !cookie.isEmpty()) {
            request.setHeader("Cookie", cookie);
        }
        HttpPolicy.clientHints().forEach(request::setHeader);

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

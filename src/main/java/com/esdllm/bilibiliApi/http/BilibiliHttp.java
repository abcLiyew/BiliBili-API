package com.esdllm.bilibiliApi.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一的 GET 出口：<b>伪装 + 指纹 + 限流 + 重试退避 + 身份轮换 + 代理</b>全在这一处。
 *
 * <p><b>本类是唯一出口</b>：门面里的 {@code ApiBase.getCloseableHttpResponse} 已经委托到这里，
 * 因此 {@code CardInfo} / {@code BilibiliClient} / {@code Live} / {@code Dynamic} 四个门面
 * 与渲染链路走的是同一套策略。<b>新增网络调用请一律经由本类</b>，
 * 不要绕过它自己发请求 —— 绕过就意味着那条链路没有指纹、没有限流、没有重试。
 *
 * <p>改造前的 {@code ApiBase} 只发 User-Agent 与 Accept、不带任何 Cookie，
 * 这正是动态类接口被判风控（{@code -352} / {@code 412}）的直接原因。
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
        GetRequest request = Unirest.get(url)
                .header("User-Agent", identity.userAgent())
                .header("Accept", BilibiliConfig.accept)
                .header("Referer", BilibiliConfig.referer)
                .connectTimeout(HttpPolicy.getConnectTimeoutMs())
                .socketTimeout(HttpPolicy.getSocketTimeoutMs());

        String cookie = identity.cookie();
        if (cookie != null && !cookie.isEmpty()) {
            request.header("Cookie", cookie);
        }
        if (HttpPolicy.hasProxy()) {
            request.proxy(HttpPolicy.getProxyHost(), HttpPolicy.getProxyPort());
        }
        return request.asString();
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

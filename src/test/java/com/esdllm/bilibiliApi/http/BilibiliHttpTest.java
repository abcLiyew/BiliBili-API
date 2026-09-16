package com.esdllm.bilibiliApi.http;

import kong.unirest.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BilibiliHttp} 的离线单测：只验证<b>不该联网的那部分</b> ——
 * 业务码提取与"结果分类"。
 *
 * <p>分类是整个重试策略的大脑，也是最容易写错的地方：
 * 把风控码当可重试会造成"盲重试加重风控"，把参数错当成可重试会白白重试三轮。
 * 这里用动态代理造一个假的 {@link HttpResponse}，不产生任何网络流量。
 */
@DisplayName("BilibiliHttp：业务码提取与重试分类")
class BilibiliHttpTest {

    @AfterEach
    void restore() {
        HttpPolicy.reset();
        RateLimiter.reset();
    }

    /**
     * 把动态代理“钉”在一个不带类型参数的接口上。
     *
     * <p>{@link Proxy#newProxyInstance(Class, Constructor, InvocationHandler)} 的返回类型是 {@code Object}，直接强转成
     * {@code HttpResponse<String>} 属于<b>未经检查的转换</b>：泛型信息在运行时已被擦除，
     * 虚拟机只能校验"这是一个 HttpResponse"，校验不了"它的 body 是 String"，
     * 于是编译器给出 unchecked 警告。改成强转这个非泛型的子接口后，转换是<b>受检</b>的
     * （代理对象确实实现了它，运行时可校验），再向上转型为 {@code HttpResponse<String>}
     * 属于安全的父类型引用，不再产生任何警告。
     */
    private interface StubResponse extends HttpResponse<String> {
    }

    /** 造一个只回答 getStatus/getBody 的假响应 */
    private static HttpResponse<String> response(int status, String body) {
        return (StubResponse) Proxy.newProxyInstance(
                StubResponse.class.getClassLoader(),
                new Class<?>[]{StubResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStatus" -> status;
                    case "getBody" -> body;
                    case "toString" -> "StubResponse{" + status + ", " + body + "}";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    // ------------------------------------------------------------ 业务码提取

    @Test
    @DisplayName("能从 JSON 体里取到 code，非 JSON 一律返回 null（不抛异常）")
    void extractsBusinessCode() {
        assertEquals(0, BilibiliHttp.businessCode("{\"code\":0,\"data\":{}}").intValue());
        assertEquals(-352, BilibiliHttp.businessCode("{\"code\":-352,\"message\":\"风控\"}").intValue());
        assertEquals(4101139, BilibiliHttp.businessCode("{\"code\":4101139}").intValue());
        // 前面有空白也要认
        assertEquals(7, BilibiliHttp.businessCode("  \n {\"code\":7}").intValue());

        assertNull(BilibiliHttp.businessCode(null));
        assertNull(BilibiliHttp.businessCode(""));
        // 风控页是 HTML，必须以"拿不到业务码"收场，而不是解析异常
        assertNull(BilibiliHttp.businessCode("<html><body>412</body></html>"));
        assertNull(BilibiliHttp.businessCode("plain text"));
        // 是 JSON 但没有 code 字段
        assertNull(BilibiliHttp.businessCode("{\"data\":1}"));
    }

    // ------------------------------------------------------------ 分类

    @Test
    @DisplayName("HTTP 412 判为风控（响应体是 HTML 也要判对）")
    void http412IsRiskControl() {
        assertEquals(BilibiliHttp.Verdict.RISK_CONTROL,
                BilibiliHttp.classify(response(412, "<html>blocked</html>")));
    }

    @Test
    @DisplayName("业务码 -352/-509/-412 判为风控")
    void riskControlBusinessCode() {
        assertEquals(BilibiliHttp.Verdict.RISK_CONTROL, BilibiliHttp.classify(response(200, "{\"code\":-352}")));
        assertEquals(BilibiliHttp.Verdict.RISK_CONTROL, BilibiliHttp.classify(response(200, "{\"code\":-509}")));
        assertEquals(BilibiliHttp.Verdict.RISK_CONTROL, BilibiliHttp.classify(response(200, "{\"code\":-412}")));
    }

    @Test
    @DisplayName("code=0 判为成功")
    void success() {
        assertEquals(BilibiliHttp.Verdict.OK, BilibiliHttp.classify(response(200, "{\"code\":0,\"data\":{}}")));
    }

    @Test
    @DisplayName("4101139/4101105/-403 判为不可重试（重试多少次结果都一样）")
    void nonRetryableBusinessCode() {
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(200, "{\"code\":4101139}")));
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(200, "{\"code\":4101105}")));
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(200, "{\"code\":-403}")));
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(200, "{\"code\":-101}")));
    }

    @Test
    @DisplayName("网关类 5xx 判为可重试；500 与 4xx 判为不可重试")
    void statusCodeClassification() {
        assertEquals(BilibiliHttp.Verdict.RETRY, BilibiliHttp.classify(response(502, "")));
        assertEquals(BilibiliHttp.Verdict.RETRY, BilibiliHttp.classify(response(503, "<html>maintenance</html>")));
        assertEquals(BilibiliHttp.Verdict.RETRY, BilibiliHttp.classify(response(504, "")));
        // 500 实测来自服务端确定性 JS 异常，重试无益
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(500, "")));
        // 4xx 是请求本身有问题：路径错/参数错/无权限，重试只是浪费
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(404, "")));
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(403, "")));
        assertEquals(BilibiliHttp.Verdict.NO_RETRY, BilibiliHttp.classify(response(400, "")));
    }

    @Test
    @DisplayName("408/425/429 虽是 4xx 但语义为瞬态，判为可重试")
    void transient4xxRetryable() {
        assertEquals(BilibiliHttp.Verdict.RETRY, BilibiliHttp.classify(response(408, "")));
        assertEquals(BilibiliHttp.Verdict.RETRY, BilibiliHttp.classify(response(425, "")));
        assertEquals(BilibiliHttp.Verdict.RETRY, BilibiliHttp.classify(response(429, "")));
    }

    @Test
    @DisplayName("2xx 但响应体不是 JSON：不在这一层下结论，交给上层报错")
    void nonJson2xx() {
        assertEquals(BilibiliHttp.Verdict.OK, BilibiliHttp.classify(response(200, "")));
        assertEquals(BilibiliHttp.Verdict.OK, BilibiliHttp.classify(response(200, "<html>ok</html>")));
    }
}

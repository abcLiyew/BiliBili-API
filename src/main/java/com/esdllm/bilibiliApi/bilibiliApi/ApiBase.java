package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.http.AnonymousSession;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;

/**
 * 请求接口基类。
 *
 * <p><b>改造说明（2026-09-13）</b>：以前这里只发 {@code User-Agent} 与 {@code Accept}，
 * 不带 Cookie、不带 Referer —— 这是动态类接口被判风控（{@code -352} / {@code 412}）的直接原因。
 * 现在 {@link #getCloseableHttpResponse(String)} <b>委托给统一出口 {@link BilibiliHttp}</b>，
 * 于是共用它的 4 个门面（{@code CardInfo} / {@code BilibiliClient} / {@code Live} / {@code Dynamic}）
 * 一次性获得了：<b>设备指纹 Cookie、Referer、超时、限流、重试退避、身份轮换、代理</b>。
 *
 * <p>这样做而不是逐个改门面，是因为"出口只有一个"才好统一施策略 ——
 * 否则每加一条抗压规则都要改 N 个地方，漏一个就出现"裸奔的门面"。
 *
 * <p><b>公开签名逐字未变</b>（含 {@code throws IOException}），门面契约不受影响。
 */
@Slf4j
public class ApiBase {

    /** @deprecated 仅保留以兼容既有引用；实际出站 UA 由 {@code UserAgentPool} 按当前身份决定 */
    @Deprecated
    public static final String userAgent = BilibiliConfig.userAgent;

    /** @deprecated 仅保留以兼容既有引用；实际出站 Accept 见 {@link BilibiliConfig#accept} */
    @Deprecated
    public static final String accept = BilibiliConfig.accept;

    /**
     * 获取 http 响应（走统一出口，自动带指纹、限流与重试）。
     *
     * @param url 请求地址
     * @return http 响应
     */
    public static kong.unirest.HttpResponse<String> getCloseableHttpResponse(String url) {
        return BilibiliHttp.get(url);
    }

    /**
     * 获取 http 响应，不重定向。
     *
     * <p>唯一用途是解析短链：读取 {@code Location} 头拿到真实地址，因此必须关掉自动重定向。
     * 同样补上了 UA / Referer / 指纹 / 超时 / 代理 —— 短链服务也属 B 站域名，
     * 没必要让它成为一条"裸奔"的旁路。
     *
     * @param url 请求地址
     * @return http 响应
     * @throws IOException I/O 异常
     */
    public static HttpResponse getHttpResponseNotRedirect(String url) throws IOException {
        HttpGet request = new HttpGet(url);
        request.setHeader("User-Agent", AnonymousSession.userAgent());
        request.setHeader("Accept", BilibiliConfig.accept);
        request.setHeader("Referer", BilibiliConfig.referer);
        String cookie = AnonymousSession.cookieHeader();
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
}

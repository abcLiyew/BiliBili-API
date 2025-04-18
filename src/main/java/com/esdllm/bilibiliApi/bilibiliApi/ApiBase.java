package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.config.BilibiliConfig;
import kong.unirest.Unirest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;

import java.io.IOException;

/**
 * 请求接口基类
 */
public class ApiBase {
    public static final String userAgent = BilibiliConfig.userAgent;
    public static final String accept = BilibiliConfig.accept;

    /**
     * 获取http响应
     * @param url 请求地址
     * @return http响应
     */
    public static kong.unirest.HttpResponse<String> getCloseableHttpResponse(String url) {
        return Unirest.get(url)
                .header("User-Agent", ApiBase.userAgent)
                .accept(ApiBase.accept)
                .asString();

    }

    /**
     * 获取http响应，不重定向
     * @param url 请求地址
     * @return http响应
     * @throws IOException I/O异常
     */
    public static HttpResponse getHttpResponseNotRedirect(String url) throws IOException {
        HttpClient client = HttpClientBuilder.create().disableRedirectHandling().build();
        return client.execute(new HttpGet(url));
    }
}

package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;

import java.io.IOException;

/**
 * 请求接口基类 —— <b>已退场的兼容壳</b>。
 *
 * <p><b>P3（2026-09-13）起本类的实现已全部搬空</b>，只剩转发：
 * <ul>
 *   <li>{@link #getCloseableHttpResponse(String)} → {@link BilibiliHttp#get(String)}
 *       （门面在 P1 就已改走 {@code service/*}，此方法仅历史引用可解析）；</li>
 *   <li>{@link #getHttpResponseNotRedirect(String)} → {@link BilibiliHttp#getNoRedirect(String)}
 *       （"关重定向读 Location"的能力在 P3 迁入 {@code BilibiliHttp}，
 *       使 {@code org.apache.http.*} 依赖不再出 {@code http} 包）。</li>
 * </ul>
 *
 * <p><b>为什么保留而不是直接删</b>：本仓库是公开库
 * （{@code github.com/abcLiyew/BiliBili-API}），删除 {@code public} 类属破坏性变更。
 * 已知消费方 XatiiBot 不引用本类（P1 已 grep 核对），但外部使用者无从确认 ——
 * 因此保留为 {@code @Deprecated} 壳，实现收敛，签名不动。
 *
 * <p><b>新代码不要再用本类</b>：直接调 {@link BilibiliHttp}，或对应的 {@code service.*Service}。
 *
 * @author 饿死的流浪猫
 * @deprecated 出站能力已统一收敛到 {@link BilibiliHttp}（唯一出口）。本类仅为兼容保留，
 *         计划在下一个大版本移除。
 */
@Deprecated
public class ApiBase {

    /** @deprecated 仅保留以兼容既有引用；实际出站 UA 由 {@code UserAgentPool} 按当前身份决定 */
    @Deprecated
    public static final String userAgent = BilibiliEndpoint.userAgent;

    /** @deprecated 仅保留以兼容既有引用；实际出站 Accept 见 {@link BilibiliEndpoint#accept} */
    @Deprecated
    public static final String accept = BilibiliEndpoint.accept;

    /**
     * 获取 http 响应（走统一出口，自动带指纹、限流与重试）。
     *
     * @param url 请求地址
     * @return http 响应
     * @deprecated 实现已转发到 {@link BilibiliHttp#get(String)}；请直接调它或 {@code service.*Service}
     */
    @Deprecated
    public static kong.unirest.HttpResponse<String> getCloseableHttpResponse(String url) {
        return BilibiliHttp.get(url);
    }

    /**
     * 获取 http 响应，不重定向（读 {@code Location} header 用）。
     *
     * @param url 请求地址
     * @return Apache HttpClient 响应（调用方自取 header）
     * @throws IOException I/O 异常
     * @deprecated 实现已转发到 {@link BilibiliHttp#getNoRedirect(String)}；
     *         若只想要跳转地址，直接用 {@link BilibiliHttp#getLocation(String)} 更简洁
     */
    @Deprecated
    public static org.apache.http.HttpResponse getHttpResponseNotRedirect(String url) throws IOException {
        return BilibiliHttp.getNoRedirect(url);
    }
}
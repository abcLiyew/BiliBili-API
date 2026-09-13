package com.esdllm.bilibiliApi.parse;

import com.esdllm.bilibiliApi.exception.BilibiliException;

import java.util.HashSet;
import java.util.Set;

/**
 * 错误码 → 语义化异常的映射，并回答「这个错误能不能重试」。
 *
 * <p><b>为什么必须区分可重试与不可重试</b>：重试退避与风控天然对立。
 * {@code 412} / {@code -352} / {@code -509} 是 B 站的风控码，
 * <b>每重试一次就离小黑屋更近一步</b>；{@code 4101139}（参数名错误）与
 * {@code 4101105}（id 不存在）这类错误则重试一万次也还是同样的结果。
 * 这些都必须被上层拦截器的重试策略排除。
 *
 * <p><b>实测记录（2026-09-13，本机 curl）</b>：
 * <ul>
 *   <li>{@code 4101139} 的文案是"请求数据发生错误，请刷新或稍后重试"，看着像权限/登录不足，
 *       实际是<b>参数名写错</b>（{@code v1/detail} 的正确参数名是 {@code id}，
 *       传 {@code rid} 或 {@code dynamic_id} 都会得到它）。排障时先怀疑参数名。</li>
 *   <li>{@code 4101105} = 目标 id 不存在。</li>
 *   <li>传 {@code rid} 的<b>值</b>（如 {@code 409000337}）会让服务端直接 {@code 500}，
 *       错误信息是 JS 异常 {@code Cannot read property 'only_fans' of undefined}。</li>
 * </ul>
 */
public final class ErrorMapper {

    /**
     * 风控类错误码。
     *
     * <p>它们与"不可重试"的关系需要说清楚：风控码一律<b>不可盲重试</b> ——
     * 拿同一个已被标记的指纹再撞一次只会加重标记。但它们也不是"只能放弃"：
     * 换一副面孔（{@link #isRiskControl(int)} → {@code AnonymousSession#rotate()}）
     * 再试<b>有限次</b>是有意义的，这正是 {@code BilibiliHttp} 的策略。
     *
     * <p>⚠️ <b>声明顺序有意义</b>：静态字段按文本顺序初始化，{@link #NO_RETRY} 的初始化
     * 依赖本字段，本字段必须写在它前面，否则 {@code buildNoRetry()} 读到 null
     * 会让整个类以 {@code ExceptionInInitializerError} 挂掉（实测踩过）。
     */
    private static final Set<Integer> RISK_CONTROL = Set.of(412, -352, -509, -412);

    /**
     * 明确<b>不可重试</b>的错误码。
     *
     * <ul>
     *   <li>风控类：{@code 412}（HTTP 风控页）、{@code -352}、{@code -509}、{@code -412}</li>
     *   <li>鉴权类：{@code -101}（账号未登录）、{@code -403}（访问权限不足）</li>
     *   <li>请求本身有问题：{@code -400}（请求错误）、{@code -404}（无此项）</li>
     *   <li>动态接口的两个"错误码陷阱"：{@code 4101139}、{@code 4101105}</li>
     *   <li>服务端自身异常：{@code 500}</li>
     * </ul>
     */
    private static final Set<Integer> NO_RETRY = buildNoRetry();

    private ErrorMapper() {
    }

    /** 不可重试集 = 风控码 ∪ 鉴权失败 ∪ 请求本身的问题 ∪ 服务端异常 */
    private static Set<Integer> buildNoRetry() {
        Set<Integer> all = new HashSet<>(RISK_CONTROL);
        all.addAll(Set.of(
                // 鉴权
                -101, -403,
                // 请求/目标本身的问题
                -400, -404, 4101139, 4101105,
                // 服务端异常
                500));
        return Set.copyOf(all);
    }

    /**
     * 该错误码是否属于<b>风控拦截</b>。
     *
     * <p>调用方据此决定"是否可以换身份重试"：风控码不参与普通退避重试，
     * 只允许走 {@code AnonymousSession#rotate()} 的有限次轮换。
     *
     * @param code B 站返回的业务码，或 HTTP 状态码
     * @return true 表示是风控码
     */
    public static boolean isRiskControl(int code) {
        return RISK_CONTROL.contains(code);
    }

    /**
     * 该错误码是否值得重试。
     *
     * @param code B 站返回的业务码，或 HTTP 状态码
     * @return true 表示可以退避后重试；false 表示重试没有意义甚至有害
     */
    public static boolean retryable(int code) {
        return code != 0 && !NO_RETRY.contains(code);
    }

    /**
     * 把业务错误码映射成带 code 的 {@link BilibiliException}。
     *
     * @param code    B 站业务码
     * @param message B 站返回的 message/msg，可能为空
     * @param what    正在做的事，用于拼出可读的失败描述，如"获取动态详情"
     */
    public static BilibiliException toException(int code, String message, String what) {
        String detail = (message == null || message.isBlank()) ? "无错误信息" : message.trim();
        String hint = retryable(code) ? "" : "（该错误不可重试）";
        String text = String.format("%s失败：code=%d，%s%s", what, code, detail, hint);
        return new BilibiliException(code, text, detail);
    }

    /**
     * 把 HTTP 状态码映射成异常。用于请求层在解析 body 之前的早期拦截。
     *
     * @param status HTTP 状态码
     * @param what   正在做的事
     */
    public static BilibiliException forHttpStatus(int status, String what) {
        if (status == 412) {
            return new BilibiliException(412,
                    what + "失败：HTTP 412，命中 B 站风控。"
                            + "该端点可能需要有效 Cookie，或应改用其它端点；重试会加重风控，请勿自动重试",
                    "HTTP 412 风控");
        }
        if (status < 200 || status >= 300) {
            return new BilibiliException(status, what + "失败：HTTP " + status, "HTTP " + status);
        }
        return null;
    }
}

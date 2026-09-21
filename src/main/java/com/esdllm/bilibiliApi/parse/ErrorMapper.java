package com.esdllm.bilibiliApi.parse;

import com.esdllm.bilibiliApi.exception.BilibiliException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
     * 登录类错误码的<b>人话解释</b>。
     *
     * <p>{@link #toException} 会把它追加到异常消息末尾。存在的理由：登录这几个码的错误文案
     * 本身毫无信息量，不知道上下文就只能反复试 —— 例如 {@code 2400 登录秘钥错误}
     * 实际含义是"你取 RSA 公钥到现在已经超过 20 秒了"，不知道这条就会去怀疑账号密码；
     * {@code 2406 验证极验服务出错} 多半是"这份 {@code validate} 已经用过一次"。
     *
     * <p>⚠️ <b>声明顺序有意义</b>：本字段必须在 {@link #NO_RETRY} <b>之前</b> ——
     * {@code buildNoRetry()} 读它的 keySet，声明在后会在类初始化时读到 null
     * （与 {@link #RISK_CONTROL} 同一条规矩，实测踩过 {@code ExceptionInInitializerError}）。
     */
    private static final Map<Integer, String> LOGIN_HINTS = buildLoginHints();

    private static Map<Integer, String> buildLoginHints() {
        Map<Integer, String> hints = new HashMap<>();
        // —— 密码 / 通用 ——
        hints.put(-653, "账号或密码为空，检查参数是否漏传");
        hints.put(-629, "账号或密码错误；注意 username 要填手机号或邮箱本身，不是昵称");
        hints.put(-662, "提交超时，多半是取 RSA 公钥后拖太久才提交，请重取 key 后立刻提交");
        hints.put(-2001, "缺少必要参数；第三方登录会返回它（本库未支持）");
        hints.put(-2100, "账号被风控，需先用手机号完成二次验证；可改走短信登录链路");
        hints.put(2400, "登录秘钥错误：RSA 公钥的 hash 只有 20 秒寿命，"
                + "必须在取到 key 后立刻提交，中间不要插入用户交互或其它请求");
        hints.put(2406, "极验校验失败：validate 是一次性的（用过即废），"
                + "密码登录与发送短信必须各过各的极验，重试也要重新过验");
        hints.put(86000, "服务端 RSA 解密失败：密码密文与当前公钥不匹配，"
                + "多半是用了过期的 key 或盐");
        // —— 短信 ——
        hints.put(1002, "手机号格式错误；tel 不含国际冠字码，冠字码单独用 cid 传（大陆为 86）");
        hints.put(1003, "验证码已发送：同一手机号 60 秒内不能重发，请等冷却结束");
        hints.put(1006, "短信验证码不正确");
        hints.put(1007, "短信验证码已过期（有效期 5 分钟），需要重新发送");
        hints.put(1025, "该手机号有永久封禁记录，无法用于登录或注册");
        hints.put(86203, "短信发送次数已达上限，需换时间再试");
        return Map.copyOf(hints);
    }

    /**
     * 明确<b>不可重试</b>的错误码。
     *
     * <ul>
     *   <li>风控类：{@code 412}（HTTP 风控页）、{@code -352}、{@code -509}、{@code -412}</li>
     *   <li>鉴权类：{@code -101}（账号未登录）、{@code -403}（访问权限不足）</li>
     *   <li>请求本身有问题：{@code -400}（请求错误）、{@code -404}（无此项）</li>
     *   <li>动态接口的两个"错误码陷阱"：{@code 4101139}、{@code 4101105}</li>
     *   <li>服务端自身异常：{@code 500}</li>
     *   <li>登录动作类：{@link #LOGIN_HINTS} 的全部键（见 {@link #buildNoRetry()} 的说明）</li>
     * </ul>
     */
    private static final Set<Integer> NO_RETRY = buildNoRetry();

    private ErrorMapper() {
    }

    /** 不可重试集 = 风控码 ∪ 鉴权失败 ∪ 请求本身的问题 ∪ 服务端异常 ∪ 登录状态码 ∪ 登录动作码 */
    private static Set<Integer> buildNoRetry() {
        Set<Integer> all = new HashSet<>(RISK_CONTROL);
        all.addAll(Set.of(
                // 鉴权
                -101, -403,
                // 请求/目标本身的问题
                -400, -404, 4101139, 4101105,
                // 服务端异常
                500));
        // 扫码登录的三种"进行中/已失效"状态码。它们按文档走 data.code、外层 code 恒为 0，
        // 正常路径根本不会进到这里；但一旦哪天外层也开始返回它们，默认的"未知码可重试"
        // 会让每轮轮询都退避重试 3 次（最坏 4s 一次），登录流程直接卡成幻灯片。
        // 防御性收进本集合：这些是状态而不是错误，重试不会让二维码"活过来"。
        all.addAll(Set.of(86101, 86090, 86038));
        // 登录动作（提交密码/短信/极验）的错误码：一律不可重试，理由有两层 ——
        // ① 它们全是确定性的（密码错就是错，validate 用过就是用过），重试一万次结果一样；
        // ② 自动重试"账号+密码+极验"这个组合，形态上就是撞库，会直接触发账号风控。
        // 因此把这些码与它们的解释表合并到一处维护：LOGIN_HINTS 是唯一来源，不会漏。
        all.addAll(LOGIN_HINTS.keySet());
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
     * <p>消息由三段拼成：{@code what失败：code=N，<服务端原话><是否可重试>。<人话解释>}。
     * 最后那段解释来自 {@link #LOGIN_HINTS}（只覆盖登录类码）——
     * 因为那几个码的服务端文案（"登录秘钥错误"、"验证极验服务出错"）无法自解释，
     * 而它们的真实成因往往是"hash 已过期 20 秒"这种调用顺序问题。
     *
     * @param code    B 站业务码
     * @param message B 站返回的 message/msg，可能为空
     * @param what    正在做的事，用于拼出可读的失败描述，如"获取动态详情"
     */
    public static BilibiliException toException(int code, String message, String what) {
        String detail = (message == null || message.isBlank()) ? "无错误信息" : message.trim();
        String hint = retryable(code) ? "" : "（该错误不可重试）";
        String extra = LOGIN_HINTS.get(code);
        String text = String.format("%s失败：code=%d，%s%s", what, code, detail, hint)
                + (extra == null ? "" : "。" + extra);
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

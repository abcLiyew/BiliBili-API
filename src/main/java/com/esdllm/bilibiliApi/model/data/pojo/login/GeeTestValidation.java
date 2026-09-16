package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Getter;

/**
 * <b>输入端</b>极验验证结果：调用方在浏览器里过完极验后，把结果打包回传给本库。
 *
 * <p>本库<b>不含任何浏览器 / 打码逻辑</b>：B 站登录用的是极验 v3（交互式，提交时要带本地 JS
 * 生成的轨迹指纹 {@code w}），本地无法伪造，因此"过验"这一步的责任在调用方 ——
 * 用极验官方 JS 拿 {@code gt}/{@code challenge} 初始化，用户滑完/点完，
 * 再从 {@code instance.getValidate()} 取三个值：
 * <pre>{@code
 * var r = captchaObj.getValidate();
 * // r.geetest_challenge / r.geetest_validate / r.geetest_seccode
 * }</pre>
 * 然后：
 * <pre>{@code
 * GeeTestValidation v = GeeTestValidation.of(validate);          // seccode 自动补 |jordan
 * // 或者显式给两个值（推荐：JS 原样给什么就传什么）
 * GeeTestValidation v = GeeTestValidation.of(validate, seccode);
 * }</pre>
 *
 * <p><b>三个必须知道的时效性约束</b>（都写在 {@code BilibiliEndpoint} 的端点注释里）：
 * <ol>
 *   <li>{@code validate} 是<b>一次性</b>的 —— 用过一次即失效，再用返回 {@code 2406}。
 *       所以"密码登录"和"发短信"要<b>各过各的</b>，不能共用一份；失败重试也必须重新过验。</li>
 *   <li>它与 {@code token} 绑定同一次 {@code /captcha} 申请 —— 重新申请过验证码后，旧结果作废。</li>
 *   <li>密码登录侧的 {@code hash} 只有 <b>20 秒</b>寿命，过完验要尽快提交，别让用户慢慢填表单。</li>
 * </ol>
 *
 * <p><b>为什么这是不可变类而不是 {@code @Data} POJO</b>：{@code seccode} 的拼接规则
 * （{@code validate + "|jordan"}）是极验的固定约定，写在工厂方法里就只存在一处；
 * 若做成 setter 可改的 POJO，调用方很容易只填 {@code validate} 而留空 {@code seccode}，
 * 那种错误在服务端只表现为一句"验证极验服务出错"，排查成本极高。
 *
 * @author 饿死的流浪猫
 */
@Getter
public final class GeeTestValidation {

    /**
     * 极验约定：{@code seccode = validate + "|jordan"}。
     *
     * <p>这个后缀是极验 SDK 写死的固定串，不是 B 站特有的。能自动补，就不该让调用方记。
     */
    public static final String SECCODE_SUFFIX = "|jordan";

    /**
     * -- GETTER --
     * 极验
     * ，提交登录时用
     */
    private final String validate;
    /**
     * -- GETTER --
     * 极验
     * （
     * ），提交登录时用
     */
    private final String seccode;
    /**
     * -- GETTER --
     *  调用方指定的
     * ；未指定时为
     * 。
     *  <p>为
     *  表示"用
     *  里申请到的那个"，
     *  见
     * 。
     */
    private final String challenge;

    private GeeTestValidation(String validate, String seccode, String challenge) {
        this.validate = validate;
        this.seccode = seccode;
        this.challenge = challenge;
    }

    /**
     * 只给 {@code validate}，{@code seccode} 按极验约定自动补。
     *
     * @param validate 极验 {@code getValidate().geetest_validate}
     * @return 验证结果
     * @throws IllegalArgumentException {@code validate} 为空白（调用方忘了过验）
     */
    public static GeeTestValidation of(String validate) {
        String value = require(validate);
        return new GeeTestValidation(value, value + SECCODE_SUFFIX, null);
    }

    /**
     * 显式给 {@code validate} 与 {@code seccode}（浏览器回什么就传什么，最稳）。
     *
     * @param validate 极验 {@code geetest_validate}
     * @param seccode  极验 {@code geetest_seccode}
     * @return 验证结果
     * @throws IllegalArgumentException 任一为空白
     */
    public static GeeTestValidation of(String validate, String seccode) {
        return new GeeTestValidation(require(validate), require(seccode), null);
    }

    /**
     * 带上 JS 回显的 {@code challenge}，返回一个新的验证结果。
     *
     * <p><b>什么时候需要它</b>：极验过验后回显的 {@code geetest_challenge} 与
     * {@code /captcha} 申请到的 {@code challenge} <b>常常不是同一个值</b>。
     * 官方文档把 {@code challenge} 归为"申请验证码接口处获取"，但真实前端会把 JS 回显的那个
     * 一并提交。不指定时本库用申请时的 {@code challenge}（文档口径），
     * 若服务端报 {@code 2406}，先试把 JS 回显值经这里传进来。
     *
     * @param challenge JS 回显的 {@code geetest_challenge}；空白则忽略
     * @return 新的验证结果（本类不可变，原对象不受影响）
     */
    public GeeTestValidation withChallenge(String challenge) {
        if (challenge == null || challenge.isBlank()) {
            return this;
        }
        return new GeeTestValidation(validate, seccode, challenge.trim());
    }

    /**
     * 是否显式指定了 {@code challenge}。
     *
     * @return true 表示调用方给了 JS 回显的 challenge，应优先使用它
     */
    public boolean hasChallenge() {
        return challenge != null && !challenge.isBlank();
    }

    /**
     * 脱敏输出：只报长度。
     *
     * <p>{@code validate} 能换一次登录提交，等价于"一次性登录凭证"，
     * 不该出现在日志里（与 {@code LoginCredential.toString()} 同一考量）。
     */
    @Override
    public String toString() {
        return "GeeTestValidation{validate=" + validate.length() + "字符, seccode=" + seccode.length()
                + "字符, challenge=" + (hasChallenge() ? "已指定" : "用申请值") + "}";
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("极验结果不能为空：请先在浏览器里完成极验，"
                    + "再把 getValidate() 返回的 geetest_validate / geetest_seccode 传进来");
        }
        return value.trim();
    }
}

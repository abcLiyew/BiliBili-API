package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 极验 v3 的初始化参数（{@code LoginCaptcha.geetest}）。
 *
 * <p>用法就是原样塞给极验官方 JS：
 * <pre>{@code
 * initGeetest({
 *     gt: captcha.getGeetest().getGt(),
 *     challenge: captcha.getGeetest().getChallenge(),
 *     offline: false,        // 必须 false：true 时极验走离线模式，返回的 validate 服务端不认
 *     new_captcha: true,     // 会影响回调里的字段名（geetest_challenge / geetest_validate / geetest_seccode）
 *     product: "float"
 * }, function (instance) { ... });
 * }</pre>
 *
 * <p>过验成功后从 {@code instance.getValidate()} 取三个值：
 * {@code geetest_challenge} / {@code geetest_validate} / {@code geetest_seccode}，
 * 打包成 {@link GeeTestValidation} 交回本库。
 *
 * @author 饿死的流浪猫
 */
@Data
public class GeetestInfo {

    /** 极验公钥标识（一个站点一个值，实测 B 站为固定串） */
    private String gt;

    /**
     * 本次验证的挑战串。
     *
     * <p><b>必须由 JS 回显</b>：过验后 {@code getValidate().geetest_challenge} 会给出新的 challenge，
     * 提交登录时要送<b>那一个</b>，而不是这里申请到的原始值 —— 两者常常不同。
     * 这也是 {@link GeeTestValidation} 保留 {@code challenge} 字段的原因。
     */
    private String challenge;
}

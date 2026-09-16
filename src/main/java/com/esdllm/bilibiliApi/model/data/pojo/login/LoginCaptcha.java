package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「申请验证码」的响应体（{@code x/passport-login/captcha} 的 {@code data}）。
 *
 * <p><b>它不是一张可渲染成图的验证码</b>（这是最容易想错的地方）：2026-09-16 实测
 * {@link #type} 恒为 {@code "geetest"}，真正要处理的是极验 v3 的交互式验证 ——
 * 背景图 + 滑动/点选，提交时还要带一个本地 JS 生成的 {@code w} 参数（轨迹 + 浏览器指纹）。
 * 因此本库<b>不生成图片、不做打码</b>，只把 {@link #token} 与 {@link #geetest}
 * 交给调用方，由调用方在浏览器里过验，再把 {@code validate} / {@code seccode} 回传
 * （见 {@link GeeTestValidation}）。
 *
 * <p>实测原始响应（2026-09-16）：
 * <pre>
 * {"code":0,"message":"OK","ttl":1,"data":{
 *   "type":"geetest",
 *   "token":"348eb3e916664c8bbd5a390c359bb3eb",
 *   "geetest":{"challenge":"228b357d958e94ca36507d4530e4fb6b","gt":"ac597a4506fee079629df5d8b66dd4fe"},
 *   "tencent":{"appid":""}}}
 * </pre>
 * 其中 {@code tencent} 恒为空串（另一个供应商的占位），本模型不映射它 ——
 * 万一哪天 {@code type} 变成腾讯系，{@code LoginService} 会连原始响应一起报出来，不会静默。
 *
 * @author 饿死的流浪猫
 */
@Data
public class LoginCaptcha {

    /**
     * 验证码类型。实测恒为 {@code "geetest"}。
     *
     * <p>本库目前只认这一种。出现其它值时 {@code LoginService} 会抛异常并带上原始响应，
     * 因为"换个供应商"意味着整条交互流程都要重写，绝不是能悄悄降级的事情。
     */
    private String type;

    /**
     * B 站侧的登录令牌，提交登录时原样回传。
     *
     * <p>它与 {@link GeetestInfo#getChallenge()} 都来自本次申请，<b>一次申请只能用于一次登录</b>。
     */
    private String token;

    /** 极验参数（{@code gt} + {@code challenge}），交给极验官方 JS 初始化用 */
    private GeetestInfo geetest;
}

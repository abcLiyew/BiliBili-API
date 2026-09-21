package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「提交登录」的响应体 —— 密码登录（{@code x/passport-login/web/login}）与
 * 短信登录（{@code x/passport-login/web/login/sms}）共用。
 *
 * <p>两个端点的 {@code data} 是同一套字段的子集关系：
 * <ul>
 *   <li>密码登录给 {@code status / message / url / refresh_token / timestamp}；</li>
 *   <li>短信登录给 {@code is_new / status / url}。</li>
 * </ul>
 * 因此合成一个模型，缺的字段自然是 {@code null} / 0。
 *
 * <p><b>真正的凭据不在这里</b>：四项 Cookie（{@code SESSDATA} 等）走响应头
 * {@code Set-Cookie} 下发。本类只提供两样东西 ——
 * <b>成功与否的旁证</b>（{@link #status}）与 <b>失败时服务端的原因</b>
 * （{@link #message}，尤其是"本次登录环境存在风险…"这类风控提示）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class LoginSubmitData {

    /**
     * 登录状态，远端文档标注成功时为 {@code 0}。
     *
     * <p><b>本库不靠它判成败</b> —— 判据是根对象的 {@code code}（错误都在那里）
     * 加上"是否真的拿到了 SESSDATA"。它非 0 时只会打一行告警，因为
     * 文档没给这张取值表，硬判会把未知但正常的值当成失败（本库在扫码那条链路上
     * 已经因为"误信单一字段"栽过一次，见 {@code QrCodePoll} 的说明）。
     *
     * <p>🆕 <b>已实测的一个取值</b>（2026-09-16，本机真机密码登录）：
     * {@code status=2} 与 {@link #message} 的"本次登录环境存在风险, 需使用手机号进行验证或绑定"
     * 同时出现，且 {@code url} 指向 {@code h5-app/passport/risk/verify?...&tmp_token=...}，
     * 响应里<b>没有任何 SESSDATA</b> ⇒ <b>{@code 2} = 服务端要求风控二次验证</b>。
     * 注意它<b>不是</b>密码错、也<b>不是</b>参数错：同一次请求里 RSA 加密密码与极验四件套
     * 都被服务端接受了（回的是业务级 {@code code=0}，不是参数/签名错误）。
     */
    private Integer status;

    /**
     * 提示信息：成功时通常为空串。
     *
     * <p>⚠️ 有一种取值<b>不是错误、但也不是成功</b>：
     * {@code 本次登录环境存在风险, 需使用手机号进行验证或绑定} ——
     * 意思是"账号密码过了，但风控要求再做一次手机验证"。
     * 本库会把它带进异常消息，交由调用方决定是否改走短信链路。
     */
    private String message;

    /**
     * 跳转地址。
     *
     * <p>密码登录时它是游戏分站跨域地址（{@code passport.biligame.com/crossDomain?...}），
     * <b>有时带凭据、有时只有 ticket</b>；短信登录时是 {@code https://space.bilibili.com} 这类普通地址。
     * 因此它只是取凭据的<b>兼容来源</b>而非主来源，见 {@code LoginService#credentialFrom}。
     */
    private String url;

    /** 刷新令牌（密码登录才有），本库只透出、尚未实现刷新流程 */
    private String refresh_token;

    /** 登录时间戳（毫秒，密码登录才有）；未登录为 0 */
    private Long timestamp;

    /** 是否为新注册用户（短信登录才有）：{@code true} 表示这次登录顺带注册了新账号 */
    private Boolean is_new;
}

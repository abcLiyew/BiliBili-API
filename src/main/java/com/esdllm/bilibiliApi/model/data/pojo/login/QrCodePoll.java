package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「轮询扫码状态」的响应体
 * （{@code x/passport-login/web/qrcode/poll} 的 {@code data}）。
 *
 * <p><b>这个类里有两个 {@code code}，别搞混</b>：
 * <ul>
 *   <li>响应最外层的 {@code code}（在 {@code ApiResponse} 里）—— 表示<b>接口调用</b>是否成功，
 *       轮询期间<b>恒为 0</b>；</li>
 *   <li>本类的 {@link #code} —— 表示<b>扫码进展</b>，{@code 0}/86038/86090/86101，
 *       才是调用方真正要判的东西。</li>
 * </ul>
 * 只判外层码 = 每次轮询都"成功"，包括用户压根还没扫码的时候。
 *
 * @author 饿死的流浪猫
 */
@Data
public class QrCodePoll {

    /**
     * 游戏分站跨域登录地址，<b>未登录时为空串</b>。
     *
     * <p>⚠️ <b>2026-09-16 真机实测：登录成功后它不含凭据</b>，只有一枚 ticket：
     * <pre>
     * <a href="https://passport.biligame.com/x/passport-login/web/crossDomain?ticket=">...</a>…&amp;gourl=…&amp;first_domain=.bilibili.com
     * </pre>
     * 四项 Cookie 是随 {@code Set-Cookie} <b>响应头</b>下发的。旧格式（以及游戏站跨域）才会把
     * {@code SESSDATA} 等 <b>URL 编码</b>值直接写进这个 query —— 两种形态本库都认，
     * 三级来源（{@code Set-Cookie} → 本字段 → 响应原文）见 {@code LoginService#credentialOf}。
     */
    private String url;

    /**
     * 刷新令牌，未登录时为空串；本库只做保留与透出，尚未实现「用它换新 Cookie」。
     *
     * <p>⚠️ 实测：web 端扫码登录下发的是<b>空串</b>（{@code "refresh_token":""}），
     * 所以"用 refresh_token 免掉 30 天一次人工扫码"这条路对 web 扫码<b>不成立</b>；
     * 到期时间看 {@link #timestamp} 与凭据的 {@code Expires}。
     */
    private String refresh_token;

    /** 登录时间（毫秒时间戳），未登录时为 0 */
    private long timestamp;

    /** <b>扫码状态码</b>：0=登录成功，86038=二维码已失效，86090=已扫码未确认，86101=未扫码 */
    private int code;

    /** 扫码状态文案，如「未扫码」 */
    private String message;
}

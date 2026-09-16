package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import com.esdllm.bilibiliApi.service.LoginService;

import java.io.IOException;

/**
 * 登录门面：<b>扫码 / 密码 / 短信三条链路 → 取得登录凭据</b>。
 *
 * <p>库内第 6 个门面（前 5 个：{@code Dynamic} / {@code Live} / {@code CardInfo} /
 * {@code BilibiliClient} / {@code ShortChain}）。新增类，<b>不触碰任何既有签名</b>，
 * 对 XatiiBot 是纯增量。
 *
 * <p><b>三条链路怎么选</b>：
 * <table border="1">
 *   <caption>人工门槛对比</caption>
 *   <tr><th>链路</th><th>入口</th><th>人工门槛</th><th>建议</th></tr>
 *   <tr><td>扫码</td><td>{@link #getLoginQrCode()} → {@link #waitForLogin}</td>
 *       <td>App 扫一次，<b>不过极验</b></td><td><b>首选</b>：唯一不需要浏览器的形态</td></tr>
 *   <tr><td>密码</td><td>{@link #getCaptcha()} → {@link #loginByPassword}</td>
 *       <td>在浏览器过极验</td><td>没有 App 可扫、但有账号密码时</td></tr>
 *   <tr><td>短信</td><td>{@link #getCaptcha()} → {@link #sendSmsCode} → {@link #loginBySms}</td>
 *       <td>过极验 + 手机收码</td><td>密码登录被风控要求二次验证时兜底</td></tr>
 * </table>
 *
 * <p><b>本门面不含任何浏览器 / 打码能力</b>：B 站登录用的极验 v3 需要在浏览器里完成交互
 * （提交时要带本地 JS 生成的轨迹指纹），本地伪造不出来。所以"过验"的责任在调用方，
 * 本库只提供参数入口（{@code LoginCaptcha}）与结果出口（{@code GeeTestValidation}）。
 *
 * <p><b>典型用法</b>（扫码，首次人工扫一次，之后长期复用）：
 * <pre>{@code
 * Login login = new Login();
 * QrCodeLogin qr = login.getLoginQrCode();       // ① 拿二维码内容
 * renderQrCode(qr.getUrl());                      // ② 自己渲染成图，用哔哩哔哩 App 扫
 * LoginCredential c = login.waitForLogin(qr.getQrcode_key(), 180_000L);  // ③ 等确认
 * HttpPolicy.setCookie(c.getCookieHeader());      // ④ 注入，之后所有请求自动带登录态
 * }</pre>
 *
 * <p><b>拿到的凭据能做什么</b>：治 {@code v1/feed/space} 的 {@code -412} 与
 * {@code code=0} 静默空（这是本库唯一真正需要登录的既有端点
 * {@code v1/feed/all} 之外，Cookie 的实际价值所在），以及解锁
 * 粉丝/关注列表、历史记录等 {~30 个} 需登录接口。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Live} / {@code BilibiliClient} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}，而不是换成一句固定的失败文案）：
 * 登录失败的原因几乎全是"响应形状 / 凭据 / 验证码"这类只有服务端原话才说得清的问题，
 * 抹掉内层消息等于把排障线索一起抹掉（2026-09-16 真机踩过）。
 *
 * @author 饿死的流浪猫
 */
public class Login {

    /**
     * 申请登录二维码。
     *
     * @return 二维码内容（渲染成二维码给用户扫）+ 32 字符的 {@code qrcode_key}，不可为 null
     * @throws IOException 网络/业务异常
     */
    public QrCodeLogin getLoginQrCode() throws IOException {
        try {
            return LoginService.INSTANCE.requestQrCode();
        } catch (BilibiliException e) {
            // 透出内层消息而不是换成一句固定的"申请登录二维码失败"：
            // 内层消息里带着 B 站业务码与原文片段，是排障唯一可用的线索（见 getLoginStatus 的同类注释）
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 查询一次扫码状态（单次，不轮询）。
     *
     * <p>想自己控制节奏（例如边渲染二维码边轮询）时用这个；要一步等到结果用
     * {@link #waitForLogin(String, long)}。
     *
     * <p><b>注意判的是返回值的状态而非有没有抛异常</b>："还没扫"是正常流程，不会抛。
     *
     * @param qrcodeKey {@link #getLoginQrCode()} 返回的密钥
     * @return 状态 + （成功时）凭据
     * @throws IOException 网络/业务异常
     */
    public QrLoginStatus getLoginStatus(String qrcodeKey) throws IOException {
        try {
            return LoginService.INSTANCE.poll(qrcodeKey);
        } catch (BilibiliException e) {
            // ★ 必须用 e.getMessage() 而不是一句固定的"查询扫码状态失败"。
            //   2026-09-16 真机踩坑：扫码确认后门面把内层消息整个丢掉，控制台只剩下
            //   "查询扫码状态失败"六个字 —— 服务端到底回了什么码、什么形状，一点都看不到，
            //   排障只能靠猜。内层消息里带着 HTTP 状态、业务码与响应原文片段，是唯一线索。
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 阻塞式等待用户完成扫码，直到拿到凭据。
     *
     * <p>轮询间隔固定 2 秒（{@link LoginService#DEFAULT_POLL_INTERVAL_MS}）。
     * 二维码失效或超时会抛 {@link IOException}，两者文案不同，调用方可据此决定
     * "重新申请二维码"还是"放弃"。
     *
     * @param qrcodeKey {@link #getLoginQrCode()} 返回的密钥
     * @param timeoutMs 总超时（毫秒），建议取 180000（与二维码寿命一致）
     * @return 登录凭据，不可为 null
     * @throws IOException 二维码失效、超时、或被中断
     */
    public LoginCredential waitForLogin(String qrcodeKey, long timeoutMs) throws IOException {
        try {
            return LoginService.INSTANCE.waitForLogin(
                    qrcodeKey, timeoutMs, LoginService.DEFAULT_POLL_INTERVAL_MS);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 密码 / 短信登录

    /**
     * 申请验证码前置信息（密码登录与短信登录<b>共用</b>的第一步）。
     *
     * <p>⚠️ <b>请先读这段</b>：返回的<b>不是一张能渲染出来让用户输的图</b>。
     * B 站登录用的是<b>极验 v3</b>（背景图 + 滑动/点选），且提交时必须带一个由本地 JS 生成的
     * {@code w} 参数（操作轨迹 + 浏览器指纹）—— 本地伪造不出来。
     * 所以"过验"这一步必须由调用方在浏览器里完成，本库只负责两件事：
     * <b>把参数交给你</b>（本方法）与<b>把你过验的结果送出去</b>
     * （{@link #loginByPassword} / {@link #sendSmsCode} 的 {@link GeeTestValidation} 参数）。
     *
     * <p>典型用法（四行）：
     * <pre>{@code
     * Login login = new Login();
     * LoginCaptcha captcha = login.getCaptcha();                 // ① 拿 gt/challenge/token
     * // ② 在你的页面里用极验官方 JS 初始化（gt + challenge），用户滑完/点完，
     * //    从 instance.getValidate() 取三个值，打包成 GeeTestValidation
     * GeeTestValidation gee = GeeTestValidation.of(validate);
     * LoginCredential c = login.loginByPassword(account, password, captcha, gee);   // ③
     * HttpPolicy.setCookie(c.getCookieHeader());                 // ④
     * }</pre>
     *
     * <p>极验 JS 的初始化口径（两个参数错了会拿到服务端不认的 validate）：
     * {@code offline: false}、{@code new_captcha: true}，
     * 脚本 {@code https://static.geetest.com/static/js/gt.0.4.9.js}。
     *
     * @return 验证码前置信息（{@code type} 恒为 {@code geetest}），不可为 null
     * @throws IOException 网络/业务异常
     */
    public LoginCaptcha getCaptcha() throws IOException {
        try {
            return LoginService.INSTANCE.requestCaptcha();
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>账号密码登录</b>。
     *
     * <p>内部顺序已被本库固定好（这个顺序很关键，{@code hash} 只有 <b>20 秒</b>寿命）：
     * <b>取 RSA 公钥 → 加密 → 立刻提交</b>。因此"等用户过极验"必须发生在调用本方法<b>之前</b>，
     * 不要在拿到 {@code captcha} 之后磨蹭。
     *
     * <p><b>明文密码不会进日志、也不会进异常消息</b>，只打长度。
     *
     * <p>失败时的常见码：{@code -629} 账号或密码错、{@code 2400} 公钥过期、
     * {@code 2406} 极验结果失效（{@code validate} 是一次性的）、
     * {@code -2100} 被风控要求手机号二次验证 —— 这时改走短信链路即可。
     *
     * @param username 手机号或邮箱
     * @param password 明文密码
     * @param captcha  {@link #getCaptcha()} 的结果
     * @param gee      调用方在浏览器里过验后的结果
     * @return 登录凭据，不可为 null
     * @throws IOException 参数为空、加密失败、网络失败、或服务端拒绝
     */
    public LoginCredential loginByPassword(String username, String password,
                                           LoginCaptcha captcha, GeeTestValidation gee) throws IOException {
        try {
            return LoginService.INSTANCE.passwordLogin(username, password, captcha, gee);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 取 RSA 公钥与盐（密码登录的中间产物）。
     *
     * <p>{@link #loginByPassword} 内部会自己调，通常<b>不需要单独调</b>。
     * 公开它是为了排障：把它单独调一次打日志，就能看出
     * "是不是取了 key 之后拖太久才提交"（{@code 2400} 的成因）。
     *
     * @return 公钥与 16 字符盐（盐有效期 20 秒），不可为 null
     * @throws IOException 网络/业务异常
     */
    public RsaKeyInfo getRsaKey() throws IOException {
        try {
            return LoginService.INSTANCE.rsaKey();
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>发送短信验证码</b>（短信登录第一步）。
     *
     * <p>⚠️ 极验 {@code validate} 是一次性的：本方法与 {@link #loginByPassword}
     * <b>必须各过各的极验</b>，共用一份会返回 {@code 2406}。
     *
     * <p>时效：同一手机号 60 秒内不能重发（{@code 1003}），验证码 5 分钟过期（{@code 1007}）。
     *
     * @param tel     手机号（中国大陆号码，不含 +86）
     * @param captcha {@link #getCaptcha()} 的结果
     * @param gee     调用方在浏览器里过验后的结果
     * @return {@code captcha_key} 等，不可为 null
     * @throws IOException 参数为空、网络失败、或服务端拒绝
     */
    public SmsSendResult sendSmsCode(String tel, LoginCaptcha captcha, GeeTestValidation gee)
            throws IOException {
        try {
            return LoginService.INSTANCE.sendSmsCode(tel, null, captcha, gee);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>发送短信验证码</b>（指定国际冠字码，境外号码用）。
     *
     * @param tel     手机号（不含冠字码）
     * @param cid     国际冠字码，如 {@code 86}（中国大陆）、{@code 852}（中国香港）
     * @param captcha {@link #getCaptcha()} 的结果
     * @param gee     调用方在浏览器里过验后的结果
     * @return {@code captcha_key} 等，不可为 null
     * @throws IOException 参数为空、网络失败、或服务端拒绝
     */
    public SmsSendResult sendSmsCode(String tel, String cid,
                                     LoginCaptcha captcha, GeeTestValidation gee) throws IOException {
        try {
            return LoginService.INSTANCE.sendSmsCode(tel, cid, captcha, gee);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>用短信验证码登录</b>（短信登录第二步，中国大陆号码）。
     *
     * <p>本步<b>不需要极验</b>：人工门槛只有"手机收码"这一个，这是它相对密码登录唯一的优势
     * （代价是每次登录都要收码）。它也是密码登录返回 {@code -2100}（风控要求二次验证）时的兜底。
     *
     * @param tel        手机号
     * @param code       用户收到的 6 位验证码
     * @param captchaKey {@link #sendSmsCode} 返回的 {@code captcha_key}
     * @return 登录凭据，不可为 null
     * @throws IOException 参数为空、网络失败、或服务端拒绝（{@code 1006} 码错、{@code 1007} 码过期）
     */
    public LoginCredential loginBySms(String tel, String code, String captchaKey) throws IOException {
        try {
            return LoginService.INSTANCE.smsLogin(tel, code, captchaKey);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>用短信验证码登录</b>（指定国际冠字码）。
     *
     * @param tel        手机号（不含冠字码）
     * @param cid        国际冠字码
     * @param code       短信验证码
     * @param captchaKey {@link #sendSmsCode} 返回的 {@code captcha_key}
     * @return 登录凭据，不可为 null
     * @throws IOException 参数为空、网络失败、或服务端拒绝
     */
    public LoginCredential loginBySms(String tel, String cid, String code, String captchaKey)
            throws IOException {
        try {
            return LoginService.INSTANCE.smsLogin(tel, cid, code, captchaKey);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

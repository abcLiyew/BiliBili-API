package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Login} 门面测试：只锁两件门面该负责的事 —— <b>委托是否正确</b>、
 * <b>异常边界是否按库内约定转成 {@link IOException}</b>。
 *
 * <p>业务分支（状态码、凭据解析）都在 {@code LoginServiceTest} 里覆盖，本类不重复。
 */
class LoginTest {

    private static final String GENERATE_PATH = "/x/passport-login/web/qrcode/generate";
    private static final String POLL_PATH = "/x/passport-login/web/qrcode/poll";
    private static final String QR_KEY = "c3bd5286a2b40a822f5f60e9bf3f602e";

    @Test
    @DisplayName("完整流程：申请二维码 → 等待登录 → 拿到凭据（门面与 Service 接线正确）")
    void qrLoginHappyPath() throws IOException {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(GENERATE_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                            + "\"url\":\"https://www.bilibili.com/h5/login?qrcode_key=" + QR_KEY + "\","
                            + "\"qrcode_key\":\"" + QR_KEY + "\"}}")
                    // 只放成功响应：门面固定 2 秒轮询一次，第一次就成功才不会有额外等待
                    .register(POLL_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                            + "\"url\":\"https://passport.biligame.com/crossDomain?DedeUserID=42"
                            + "&SESSDATA=sess%2Ctoken%2Cval*11&bili_jct=csrf1234\","
                            + "\"refresh_token\":\"rt\",\"timestamp\":1690000009601,"
                            + "\"code\":0,\"message\":\"\"}}");

            Login login = new Login();
            QrCodeLogin qr = login.getLoginQrCode();
            assertNotNull(qr);
            assertEquals(QR_KEY, qr.getQrcode_key());

            LoginCredential credential = login.waitForLogin(qr.getQrcode_key(), 5_000L);
            assertNotNull(credential);
            assertEquals("42", credential.getDedeUserId());
            assertEquals("sess,token,val*11", credential.getSessdata());
            assertTrue(credential.getCookieHeader().contains("SESSDATA=sess,token,val*11"));
        }
    }

    @Test
    @DisplayName("申请二维码失败 → 抛 IOException（库内 BilibiliException 不透出），且保留 cause")
    void errorBoundaries() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            // 什么都不注册：mock 会对未注册路径返回 HTTP 404
            IOException e = assertThrows(IOException.class, () -> new Login().getLoginQrCode());
            assertNotNull(e.getCause(), "库内异常应作为 cause 保留，便于下游按 code 细分处理");
        }
    }

    // ================================================================
    // 密码 / 短信登录（2026-09-16 新增）
    //
    // 本类只管"门面接线对不对 + 异常边界是不是 IOException"，
    // 业务分支（加密口径、表单参数、错误码语义）都在 LoginServiceTest 里覆盖。
    // ================================================================

    private static final String CAPTCHA_PATH = "/x/passport-login/captcha";
    private static final String KEY_PATH = "/x/passport-login/web/key";
    private static final String SMS_SEND_PATH = "/x/passport-login/web/sms/send";
    private static final String SMS_LOGIN_PATH = "/x/passport-login/web/login/sms";

    private static final String CAPTCHA_BODY = "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
            + "\"type\":\"geetest\",\"token\":\"348eb3e916664c8bbd5a390c359bb3eb\","
            + "\"geetest\":{\"challenge\":\"228b357d958e94ca36507d4530e4fb6b\","
            + "\"gt\":\"ac597a4506fee079629df5d8b66dd4fe\"}}}";

    @Test
    @DisplayName("短信两步走通：申请验证码 → 发送 → 用验证码登录（门面与 Service 接线正确）")
    void smsLoginHappyPath() throws IOException {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(CAPTCHA_PATH, CAPTCHA_BODY)
                    .register(SMS_SEND_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                            + "\"captcha_key\":\"7542f109c3318d74847626495c68c321\"}}")
                    .registerWithSetCookie(SMS_LOGIN_PATH,
                            "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                                    + "\"is_new\":false,\"status\":0,\"url\":\"https://space.bilibili.com\"}}",
                            // 凭据在 Set-Cookie 里（第三、四个参数是独立的 Set-Cookie 头）
                            "SESSDATA=aa11,1690000000,bb*22; Path=/; Domain=.bilibili.com",
                            "DedeUserID=42; Path=/; Domain=.bilibili.com");

            Login login = new Login();
            LoginCaptcha captcha = login.getCaptcha();
            assertEquals("geetest", captcha.getType());
            assertEquals("ac597a4506fee079629df5d8b66dd4fe", captcha.getGeetest().getGt());

            SmsSendResult sent = login.sendSmsCode("13888888888", captcha, GeeTestValidation.of("validate-x"));
            assertEquals("7542f109c3318d74847626495c68c321", sent.getCaptcha_key());

            LoginCredential credential = login.loginBySms("13888888888", "123456", sent.getCaptcha_key());
            assertEquals("42", credential.getDedeUserId());
            assertEquals("aa11,1690000000,bb*22", credential.getSessdata());
        }
    }

    @Test
    @DisplayName("取 RSA 公钥：hash + PEM 公钥映射正确")
    void fetchesRsaKey() throws IOException {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(KEY_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                    + "\"hash\":\"9333681c87fd8d6e\","
                    + "\"key\":\"-----BEGIN PUBLIC KEY-----\\nMIGfMA0GCSqGSIb3\\n-----END PUBLIC KEY-----\\n\"}}");

            RsaKeyInfo key = new Login().getRsaKey();

            assertEquals("9333681c87fd8d6e", key.getHash());
            assertTrue(key.getKey().contains("BEGIN PUBLIC KEY"));
        }
    }

    @Test
    @DisplayName("密码登录失败 → IOException，且内层消息被保留（不许换成一句固定的失败文案）")
    void passwordLoginErrorBoundaries() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            // 故意不给验证码前置信息：Service 会在发出任何请求之前就拒绝
            IOException e = assertThrows(IOException.class, () -> new Login().loginByPassword(
                    "13888888888", "pw", null, GeeTestValidation.of("validate-x")));

            assertTrue(e.getMessage().contains("验证码前置信息"),
                    "必须透出内层原因，否则排障无从下手。实际：" + e.getMessage());
            assertNotNull(e.getCause(), "库内异常应作为 cause 保留");
        }
    }

    @Test
    @DisplayName("验证码类型不是 geetest → 门面抛 IOException 并把原始响应带出来")
    void unsupportedCaptchaType() {
        try (MockBiliServer mock = MockBiliServer.start()) {
            mock.register(CAPTCHA_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                    + "\"type\":\"tencent\",\"token\":\"t\"}}");

            IOException e = assertThrows(IOException.class, () -> new Login().getCaptcha());

            assertTrue(e.getMessage().contains("tencent"), "实际：" + e.getMessage());
        }
    }
}

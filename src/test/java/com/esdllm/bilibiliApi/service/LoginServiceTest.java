package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import org.junit.jupiter.api.*;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LoginService} 单测。
 *
 * <p>本模块只有两处"错了还不报错"的地方，因此测试的重点不是"能不能跑通"，
 * 而是把这两个陷阱钉死：
 * <ol>
 *   <li><b>扫码状态在 {@code data.code}，不在外层 {@code code}</b>
 *       —— 判错会让"还没扫"被当成"登录成功"，拿着空凭据继续跑；</li>
 *   <li><b>{@code SESSDATA} 必须 URL 解码</b>
 *       —— 带 {@code %2C} 的原始串服务端不认，表现为"登录了但请求全是匿名态"。</li>
 * </ol>
 * 两处都做成回归用例（方法名带 ★）。
 */
class LoginServiceTest {

    private static final String GENERATE_PATH = "/x/passport-login/web/qrcode/generate";
    private static final String POLL_PATH = "/x/passport-login/web/qrcode/poll";

    /** 用官方文档里的样例密钥（恒 32 字符），便于与文档对照 */
    private static final String QR_KEY = "c3bd5286a2b40a822f5f60e9bf3f602e";

    private static final String GENERATE_BODY = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"url\":\"https://www.bilibili.com/h5/login?qrcode_key=" + QR_KEY + "\","
            + "\"qrcode_key\":\"" + QR_KEY + "\"}}";

    /** ★ 未扫码：外层 code=0，状态在 data.code=86101 */
    private static final String POLL_NOT_SCANNED = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"url\":\"\",\"refresh_token\":\"\",\"timestamp\":0,\"code\":86101,\"message\":\"未扫码\"}}";

    private static final String POLL_SCANNED = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"url\":\"\",\"refresh_token\":\"\",\"timestamp\":0,\"code\":86090,"
            + "\"message\":\"二维码已扫码未确认\"}}";

    private static final String POLL_EXPIRED = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"url\":\"\",\"refresh_token\":\"\",\"timestamp\":0,\"code\":86038,\"message\":\"二维码已失效\"}}";

    /** 未解码的 SESSDATA（真实形态：逗号被编码成 %2C，另有不被编码的 * 校验位） */
    private static final String RAW_SESSDATA = "a1b2c3d4%2C1690000000%2C7f8e*41";

    /** 解码后的期望值 —— 放进 Cookie 头的必须是这一份 */
    private static final String DECODED_SESSDATA = "a1b2c3d4,1690000000,7f8e*41";

    private static final String CROSS_DOMAIN_URL =
            "https://passport.biligame.com/crossDomain"
                    + "?DedeUserID=123456789"
                    + "&DedeUserID__ckMd5=abcdef1234567890"
                    + "&Expires=1692594809"
                    + "&SESSDATA=" + RAW_SESSDATA
                    + "&bili_jct=deadbeefcafe1234"
                    + "&gourl=https%3A%2F%2Fpassport.bilibili.com";

    /** ★ 登录成功：外层 0 + 内层 0，url 里带一把 URL 编码的凭据 */
    private static final String POLL_SUCCESS = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"url\":\"" + CROSS_DOMAIN_URL + "\","
            + "\"refresh_token\":\"refresh-token-sample\","
            + "\"timestamp\":1690000009601,"
            + "\"code\":0,\"message\":\"\"}}";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private void registerPoll(String body) {
        mock.register(POLL_PATH, body);
    }

    // ================================================================
    // 端点常量
    // ================================================================

    @Test
    @DisplayName("登录端点必须落在 passport 域（不是数据域 api.bilibili.com）")
    void endpointDomains() {
        assertTrue(BilibiliEndpoint.passportQrCodeUrl.startsWith("https://passport.bilibili.com/"),
                "实际：" + BilibiliEndpoint.passportQrCodeUrl);
        assertTrue(BilibiliEndpoint.passportQrCodePollUrl.startsWith("https://passport.bilibili.com/"),
                "实际：" + BilibiliEndpoint.passportQrCodePollUrl);
    }

    // ================================================================
    // ① 申请二维码
    // ================================================================

    @Nested
    @DisplayName("申请二维码")
    class RequestQrCode {

        @Test
        @DisplayName("正常路径：url + qrcode_key 映射正确，且只请求一次")
        void happyPath() {
            mock.register(GENERATE_PATH, GENERATE_BODY);

            QrCodeLogin qr = LoginService.INSTANCE.requestQrCode();
            assertNotNull(qr);
            assertTrue(qr.getUrl().startsWith("https://www.bilibili.com/h5/login"),
                    "url 是二维码内容（登录页地址），实际：" + qr.getUrl());
            assertEquals(QR_KEY, qr.getQrcode_key());
            assertEquals(1, mock.hitCount(GENERATE_PATH));
        }

        @Test
        @DisplayName("code=0 但没有 qrcode_key → 抛异常（不能返回一个扫不出来的二维码）")
        void missingKey() {
            mock.register(GENERATE_PATH, "{\"code\":0,\"message\":\"0\",\"data\":{\"url\":\"https://x\"}}");
            BilibiliException e = assertThrows(BilibiliException.class,
                    LoginService.INSTANCE::requestQrCode);
            assertTrue(e.getMessage().contains("qrcode_key"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("业务码非 0 → 抛异常")
        void nonZeroBusinessCode() {
            mock.register(GENERATE_PATH, "{\"code\":-400,\"message\":\"请求错误\",\"data\":null}");
            assertThrows(BilibiliException.class, LoginService.INSTANCE::requestQrCode);
        }

        @Test
        @DisplayName("响应不是 JSON（如风控页）→ 抛异常，消息里带截断后的原文")
        void invalidJson() {
            mock.register(GENERATE_PATH, "<html>风控页</html>");
            BilibiliException e = assertThrows(BilibiliException.class,
                    LoginService.INSTANCE::requestQrCode);
            assertTrue(e.getMessage().contains("无法解析"), "实际：" + e.getMessage());
        }
    }

    // ================================================================
    // ② 轮询扫码状态
    // ================================================================

    @Nested
    @DisplayName("轮询扫码状态")
    class Polling {

        @Test
        @DisplayName("★ 回归：未扫码时外层 code=0，状态取内层 data.code=86101，不得误判为登录成功")
        void notScannedIsNotSuccess() {
            registerPoll(POLL_NOT_SCANNED);

            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.NOT_SCANNED, status.getState(),
                    "只读外层 code 会得到 SUCCESS —— 这是本模块最危险的误判");
            assertEquals(86101, status.getRawCode());
            assertEquals("未扫码", status.getMessage());
            assertNull(status.getCredential(), "没登录成功就不该有凭据");
            assertFalse(status.hasCredential());
        }

        @Test
        @DisplayName("已扫码未确认 → SCANNED_NOT_CONFIRMED，同样不抛异常")
        void scannedNotConfirmed() {
            registerPoll(POLL_SCANNED);
            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.SCANNED_NOT_CONFIRMED, status.getState());
            assertNull(status.getCredential());
        }

        @Test
        @DisplayName("二维码失效 → EXPIRED（终态，调用方应重新申请）")
        void qrCodeExpired() {
            registerPoll(POLL_EXPIRED);
            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.EXPIRED, status.getState());
            assertTrue(status.getState().isTerminal());
        }

        @Test
        @DisplayName("未收录的状态码 → UNKNOWN（宽容处理，不把登录打死）")
        void unknownStatusCode() {
            registerPoll("{\"code\":0,\"message\":\"0\",\"data\":{\"url\":\"\",\"refresh_token\":\"\","
                    + "\"timestamp\":0,\"code\":99999,\"message\":\"?\"}}");
            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.UNKNOWN, status.getState());
            assertFalse(status.getState().isTerminal(), "未知状态不该终止流程，应继续轮询");
        }

        @Test
        @DisplayName("qrcode_key 为空 → 抛异常（不发出一个注定失败的请求）")
        void blankKey() {
            assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.poll("  "));
            assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.poll(null));
            assertEquals(0, mock.hitCount(POLL_PATH), "参数非法时不该产生任何出站请求");
        }

        // ------------------------------------------------------------------
        // 以下四条是 2026-09-16 真机"扫了码却登录失败"之后的回归。
        // 那次失败的表现是：HTTP 一切正常、日志一行没有、只得到一句"查询扫码状态失败"。
        // 成因有两个，都在这里钉死：① 把陌生的响应形状直接判成失败；
        // ② 门面把内层原因（唯一带服务端原话的线索）整个吞掉。
        // ------------------------------------------------------------------

        @Test
        @DisplayName("★ 回归：已确认登录但 data.url 里没有 SESSDATA → 回退读 Set-Cookie，而不是报失败")
        void fallsBackToSetCookieWhenUrlLacksSessdata() {
            mock.registerWithSetCookie(POLL_PATH,
                    "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                            + "\"url\":\"https://passport.biligame.com/crossDomain?DedeUserID=123456789\","
                            + "\"refresh_token\":\"rt\",\"timestamp\":1690000009601,\"code\":0,\"message\":\"\"}}",
                    "SESSDATA=" + RAW_SESSDATA + "; Path=/; Domain=.bilibili.com; HttpOnly",
                    "DedeUserID=123456789; Path=/; Domain=.bilibili.com",
                    "DedeUserID__ckMd5=abcdef1234567890; Path=/; Domain=.bilibili.com",
                    "bili_jct=deadbeefcafe1234; Path=/; Domain=.bilibili.com");

            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.SUCCESS, status.getState(),
                    "url 里没有凭据不等于登录失败 —— 凭据可能只在 Set-Cookie 里");
            LoginCredential c = status.getCredential();
            assertNotNull(c);
            assertEquals(DECODED_SESSDATA, c.getSessdata(), "Set-Cookie 里的 %2C 同样必须还原成逗号");
            assertEquals("123456789", c.getDedeUserId());
            assertEquals("deadbeefcafe1234", c.getBiliJct());
            assertTrue(c.getCookieHeader().contains("SESSDATA=" + DECODED_SESSDATA));
        }

        @Test
        @DisplayName("★ 回归：响应根本不是 JSON（风控页/空体），但 Set-Cookie 带了凭据 → 仍判登录成功")
        void unknownShapeButCookieHasCredential() {
            mock.registerWithSetCookie(POLL_PATH, "<html><body>unexpected</body></html>",
                    "SESSDATA=" + RAW_SESSDATA + "; Path=/");

            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.SUCCESS, status.getState());
            assertEquals(DECODED_SESSDATA, status.getCredential().getSessdata());
        }

        @Test
        @DisplayName("★ 回归：形状陌生且确实没有凭据 → 异常里必须带 HTTP 状态与响应原文片段")
        void unknownShapeMustNotBeSilent() {
            mock.register(POLL_PATH, "<html>风控页</html>");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.poll(QR_KEY));
            assertTrue(e.getMessage().contains("HTTP 200"), "要带 HTTP 状态，实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("风控页"), "要带响应原文片段，实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 回归：形状自检拦下被截断的 SESSDATA —— 宁可报失败，也不能塞进一枚残值")
        void truncatedCredentialRejected() {
            mock.register(POLL_PATH, "{\"code\":0,\"message\":\"0\",\"data\":{"
                    + "\"url\":\"\",\"note\":\"SESSDATA=abc123%2C1690\"}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.poll(QR_KEY));
            assertTrue(e.getMessage().contains("SESSDATA"), "实际：" + e.getMessage());
            assertFalse(e.getMessage().contains("abc123"),
                    "残值同样不能出现在异常消息里，实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 回归（真机形状）：data.url 只带 ticket + 凭据走 Set-Cookie —— 必须能登录成功")
        void realShape_ticketAndSetCookie() {
            // 这份 fixture 逐字来自 2026-09-16 真机扫码成功的响应：data.url 里没有任何凭据，
            // 只有 ticket；四项 Cookie 全在 Set-Cookie 头里。
            // 沿用老文档"解析 data.url"的写法会在这里失败，且失败得毫无痕迹
            // （HTTP 200、无异常、日志空白）—— 这正是真机第一次扫码的形态。
            mock.registerWithSetCookie(POLL_PATH,
                    "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                            + "\"url\":\"https://passport.biligame.com/x/passport-login/web/crossDomain"
                            + "?ticket=cc181e7c1f4e&gourl=https%3A%2F%2Fwww.bilibili.com&first_domain=.bilibili.com\","
                            + "\"refresh_token\":\"\",\"timestamp\":1789490439328,\"code\":0,\"message\":\"OK\"}}",
                    "SESSDATA=" + RAW_SESSDATA + "; Path=/; Domain=.bilibili.com; "
                            + "Expires=Thu, 15 Oct 2026 07:08:09 GMT; HttpOnly",
                    "bili_jct=deadbeefcafe1234; Path=/; Domain=.bilibili.com",
                    "DedeUserID=497078180; Path=/; Domain=.bilibili.com",
                    "DedeUserID__ckMd5=abcdef1234567890; Path=/; Domain=.bilibili.com");

            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.SUCCESS, status.getState());
            LoginCredential c = status.getCredential();
            assertNotNull(c);
            assertEquals("497078180", c.getDedeUserId());
            assertEquals(DECODED_SESSDATA, c.getSessdata(), "Set-Cookie 里的 %2C 同样要还原成逗号");
            assertEquals("deadbeefcafe1234", c.getBiliJct());
            assertEquals("abcdef1234567890", c.getDedeUserIdCkMd5());
            assertEquals(1789490439328L, c.getLoginTime());
            assertTrue(c.getExpiresAt() > 0L,
                    "Set-Cookie 的 Expires 是 HTTP 日期而非 Unix 秒，也必须解析出来，实际：" + c.getExpiresAt());
            assertFalse(c.isExpired(), "30 天后的到期时间不该被判成已过期");
        }
    }

    // ================================================================
    // ③ 凭据解析
    // ================================================================

    @Nested
    @DisplayName("凭据解析（登录成功）")
    class Credential {

        @Test
        @DisplayName("四项 Cookie 与 refresh_token / timestamp 逐一映射")
        void fieldMapping() {
            registerPoll(POLL_SUCCESS);

            QrLoginStatus status = LoginService.INSTANCE.poll(QR_KEY);
            assertEquals(QrLoginState.SUCCESS, status.getState());
            LoginCredential c = status.getCredential();
            assertNotNull(c);
            assertEquals("123456789", c.getDedeUserId());
            assertEquals("abcdef1234567890", c.getDedeUserIdCkMd5());
            assertEquals("deadbeefcafe1234", c.getBiliJct());
            assertEquals("refresh-token-sample", c.getRefreshToken());
            assertEquals(1690000009601L, c.getLoginTime(), "登录时间取 B 站返回的 timestamp");
            assertEquals(1692594809L, c.getExpiresAt(), "Expires 是秒级时间戳，原样解出");
        }

        @Test
        @DisplayName("过期判定：0 表示'未知'而不是'已过期'，避免误把没解析出来当成失效")
        void expiryDetection() {
            LoginCredential unknown = new LoginCredential();
            unknown.setExpiresAt(0L);
            assertFalse(unknown.isExpired(), "解析不出 Expires 时不能当已过期，否则凭据一到手就被弃用");

            LoginCredential past = new LoginCredential();
            past.setExpiresAt(System.currentTimeMillis() / 1000L - 60L);
            assertTrue(past.isExpired());

            LoginCredential future = new LoginCredential();
            future.setExpiresAt(System.currentTimeMillis() / 1000L + 86_400L);
            assertFalse(future.isExpired());
        }

        @Test
        @DisplayName("★ 回归：SESSDATA 必须 URL 解码（%2C 要还原成逗号）")
        void sessdataMustBeDecoded() {
            registerPoll(POLL_SUCCESS);

            LoginCredential c = LoginService.INSTANCE.poll(QR_KEY).getCredential();
            assertNotNull(c);
            assertEquals(DECODED_SESSDATA, c.getSessdata(),
                    "未解码的 %2C 是服务端不认的假值 —— 表现为'登录了但请求仍是匿名态'，且全程不报错");
            assertFalse(c.getSessdata().contains("%2C"), "Cookie 值里不该残留 percent-encoding");
        }

        @Test
        @DisplayName("cookieHeader 含四项凭据、排除 gourl，可直接交给 HttpPolicy")
        void cookieHeader() {
            registerPoll(POLL_SUCCESS);

            LoginCredential c = LoginService.INSTANCE.poll(QR_KEY).getCredential();
            assertNotNull(c);
            String header = c.getCookieHeader();
            assertTrue(header.contains("SESSDATA=" + DECODED_SESSDATA));
            assertTrue(header.contains("DedeUserID=123456789"));
            assertTrue(header.contains("DedeUserID__ckMd5=abcdef1234567890"));
            assertTrue(header.contains("bili_jct=deadbeefcafe1234"));
            assertFalse(header.contains("gourl"), "gourl 是跳转控制参数，不是 Cookie");
            assertFalse(header.contains("Expires"), "Expires 是属性而不是 Cookie 项");
            assertEquals("DedeUserID,DedeUserID__ckMd5,SESSDATA,bili_jct", c.cookieKeys());
        }

        @Test
        @DisplayName("★ 回归：toString 不得泄露凭据值（SESSDATA 等价于账号密码）")
        void toStringMasked() {
            registerPoll(POLL_SUCCESS);

            LoginCredential c = LoginService.INSTANCE.poll(QR_KEY).getCredential();
            assertNotNull(c);
            String text = c.toString();
            assertFalse(text.contains(DECODED_SESSDATA), "任一凭据值都不该出现在日志里，实际：" + text);
            assertFalse(text.contains("deadbeefcafe1234"), "bili_jct 也不能出，实际：" + text);
            assertTrue(text.contains("123456789"), "uid 不是凭据，保留它便于排障");
            assertTrue(text.contains("cookieKeys=DedeUserID,DedeUserID__ckMd5,SESSDATA,bili_jct"),
                    "键名可以出（它只回答'发了哪几项'），实际：" + text);
        }

        @Test
        @DisplayName("跨域地址里没有 SESSDATA → 抛异常（而不是返回一份空凭据）")
        void missingSessdata() {
            registerPoll("{\"code\":0,\"message\":\"0\",\"data\":{"
                    + "\"url\":\"https://passport.biligame.com/crossDomain?DedeUserID=1\","
                    + "\"refresh_token\":\"t\",\"timestamp\":1,\"code\":0,\"message\":\"\"}}");
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.poll(QR_KEY));
            assertTrue(e.getMessage().contains("SESSDATA"), "实际：" + e.getMessage());
        }
    }

    // ================================================================
    // ④ 阻塞等待（完整流程）
    // ================================================================

    @Nested
    @DisplayName("waitForLogin 阻塞等待")
    class Blocking {

        @Test
        @DisplayName("未扫码 → 已扫码 → 成功：轮询 3 次后返回凭据")
        void pollsUntilSuccess() {
            mock.registerSequence(POLL_PATH, POLL_NOT_SCANNED, POLL_SCANNED, POLL_SUCCESS);

            LoginCredential c = LoginService.INSTANCE.waitForLogin(QR_KEY, 5_000L, 10L);
            assertNotNull(c);
            assertEquals(DECODED_SESSDATA, c.getSessdata());
            assertEquals(3, mock.hitCount(POLL_PATH),
                    "必须真的轮询到第 3 次才成功；次数不对说明有额外重试或提前返回");
        }

        @Test
        @DisplayName("二维码失效 → 立即失败，不再轮询")
        void failsFastOnExpiry() {
            registerPoll(POLL_EXPIRED);

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.waitForLogin(QR_KEY, 5_000L, 10L));
            assertTrue(e.getMessage().contains("失效"), "实际：" + e.getMessage());
            assertEquals(1, mock.hitCount(POLL_PATH), "失效是终态，继续轮询纯属浪费");
        }

        @Test
        @DisplayName("一直是未扫码 → 超时抛异常，且消息里带最后一次状态")
        void timeout() {
            registerPoll(POLL_NOT_SCANNED);

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.waitForLogin(QR_KEY, 60L, 10L));
            assertTrue(e.getMessage().contains("超时"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("未扫码"), "应带出最后一次状态便于排障");
        }

        @Test
        @DisplayName("qrcode_key 为空 → 抛异常，且不发出任何请求")
        void blankKey() {
            assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.waitForLogin("", 1_000L, 10L));
            assertEquals(0, mock.hitCount(POLL_PATH));
        }
    }

    // ================================================================
    // 密码登录 / 短信登录（2026-09-16 新增）
    //
    // 覆盖重点只有一处：本模块最贵的错误全是"看起来成功了"——
    // 密码密文口径错了、极验参数没传全、凭据来源变了，服务端都只会回一句
    // "验证极验服务出错"或干脆什么都不回。所以这里的断言全部打在
    // 【实际发出去的字节】上（见 MockBiliServer#formParams），而不是打在返回值上。
    // ================================================================

    private static final String CAPTCHA_PATH = "/x/passport-login/captcha";
    private static final String KEY_PATH = "/x/passport-login/web/key";
    private static final String LOGIN_PATH = "/x/passport-login/web/login";
    private static final String SMS_SEND_PATH = "/x/passport-login/web/sms/send";
    private static final String SMS_LOGIN_PATH = "/x/passport-login/web/login/sms";

    /** 2026-09-16 实测抓到的真实响应，逐字复刻（含那个恒为空的 tencent 占位） */
    private static final String GT = "ac597a4506fee079629df5d8b66dd4fe";
    private static final String CHALLENGE = "228b357d958e94ca36507d4530e4fb6b";
    private static final String CAPTCHA_TOKEN = "348eb3e916664c8bbd5a390c359bb3eb";
    private static final String CAPTCHA_BODY = "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
            + "\"type\":\"geetest\",\"token\":\"" + CAPTCHA_TOKEN + "\","
            + "\"geetest\":{\"challenge\":\"" + CHALLENGE + "\",\"gt\":\"" + GT + "\"},"
            + "\"tencent\":{\"appid\":\"\"}}}";

    /** 官方文档的样例盐（恒 16 字符）与样例密码 —— 用文档原值便于对照 */
    private static final String SALT = "9333681c87fd8d6e";
    private static final String PLAIN_PASSWORD = "BiShi22332323";
    /** 调用方在浏览器里过完极验拿到的 validate */
    private static final String VALIDATE = "7f8e9d0c1b2a3948aabbccdd";
    private static final String ACCOUNT = "13888888888";

    /** 测试用密钥对：公钥进 fixture，私钥留在测试里解回密文（闭环校验加密口径） */
    private static final KeyPair KEY_PAIR = generateKeyPair();

    private static final String SMS_CAPTCHA_KEY = "7542f109c3318d74847626495c68c321";
    private static final String SMS_SEND_BODY = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"captcha_key\":\"" + SMS_CAPTCHA_KEY + "\"}}";
    private static final String SMS_LOGIN_BODY = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"is_new\":false,\"status\":0,\"url\":\"https://space.bilibili.com\"}}";

    /**
     * 登录成功：凭据在 {@code Set-Cookie} 里（与扫码那条链路一致），<b>正文明文里没有任何凭据</b>。
     *
     * <p>这正是真机验证过的形状 —— 照官方文档"从 {@code data.url} 解析"的写法会必然失败。
     */
    private static final String LOGIN_OK_BODY = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"status\":0,\"message\":\"\",\"url\":\"\","
            + "\"refresh_token\":\"rt-sample\",\"timestamp\":1690000009601}}";

    private static final String SESSDATA_VALUE = "a1b2c3d4,1690000000,7f8e*41";

    /**
     * 实测常量（由探测脚本用 JDK 17 跑出来，不是手算）：
     * {@code "Thu, 15 Oct 2026 07:08:09 GMT"} → {@code 1792048089}。
     */
    private static final long EXPIRES_2026_10_15 = 1792048089L;

    private static final String[] LOGIN_SET_COOKIES = cookiesWithExpires("Thu, 15 Oct 2026 07:08:09 GMT");

    /** 造一组登录成功的 {@code Set-Cookie}（四项凭据 + 可替换的 {@code Expires}） */
    private static String[] cookiesWithExpires(String expires) {
        return new String[]{
                "SESSDATA=" + SESSDATA_VALUE + "; Path=/; Domain=.bilibili.com; "
                        + "Expires=" + expires + "; HttpOnly",
                "DedeUserID=497078180; Path=/; Domain=.bilibili.com; Expires=" + expires,
                "DedeUserID__ckMd5=abcdef1234567890; Path=/; Domain=.bilibili.com; Expires=" + expires,
                "bili_jct=deadbeefcafe1234; Path=/; Domain=.bilibili.com; Expires=" + expires};
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            // 1024 位：与 B 站下发的公钥一致（公钥长度会决定"密码密文"的上限，用同尺寸才验得准）
            generator.initialize(1024);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("测试侧生成 RSA 密钥对失败", e);
        }
    }

    /** 把公钥编成 PEM（B 站下发的就是这种形状：头尾 + 64 字符一行的 base64 + {@code \n}） */
    private static String toPem(PublicKey key) {
        String base64 = Base64.getEncoder().encodeToString(key.getEncoded());
        StringBuilder pem = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return pem.append("-----END PUBLIC KEY-----\n").toString();
    }

    /** {@code /web/key} 的响应，公钥用测试密钥对 —— 这样密文就能在测试侧解回来验证 */
    private static String keyBody() {
        return "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{\"hash\":\"" + SALT
                + "\",\"key\":\"" + toPem(KEY_PAIR.getPublic()).replace("\n", "\\n") + "\"}}";
    }

    /**
     * 测试侧解密：拿私钥把提交的 {@code password} 解回明文。
     *
     * <p>这是本文件里最有价值的一条断言 —— 它一次性锁住三件事：
     * <b>填充方式</b>（PKCS#1 v1.5）、<b>盐拼在明文前面</b>、<b>输出 base64</b>。
     * 任何一项写错，服务端都只会回一句没有信息量的"RSA 解密失败"。
     */
    private static String decryptPassword(String base64Cipher) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, KEY_PAIR.getPrivate());
            return new String(cipher.doFinal(Base64.getDecoder().decode(base64Cipher)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("测试侧解密失败（密文口径与本测试的密钥不匹配）", e);
        }
    }

    private static GeeTestValidation gee() {
        return GeeTestValidation.of(VALIDATE);
    }

    @Nested
    @DisplayName("申请验证码（密码/短信共用第一步）")
    class CaptchaRequest {

        @Test
        @DisplayName("正常路径：type/token/gt/challenge 逐一映射（真实响应形状）")
        void happyPath() {
            mock.register(CAPTCHA_PATH, CAPTCHA_BODY);

            LoginCaptcha captcha = LoginService.INSTANCE.requestCaptcha();

            assertEquals("geetest", captcha.getType());
            assertEquals(CAPTCHA_TOKEN, captcha.getToken());
            assertEquals(GT, captcha.getGeetest().getGt());
            assertEquals(CHALLENGE, captcha.getGeetest().getChallenge());
        }

        @Test
        @DisplayName("★ 回归：type 不是 geetest → 明确报错并带出原始响应，绝不悄悄降级成'没有验证码'")
        void nonGeetestTypeRejected() {
            mock.register(CAPTCHA_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                    + "\"type\":\"tencent\",\"token\":\"t\",\"tencent\":{\"appid\":\"123\"}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    LoginService.INSTANCE::requestCaptcha);
            assertTrue(e.getMessage().contains("tencent"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("缺 gt/challenge → 报错（否则把空参数提交上去，只换来一句'验证极验服务出错'）")
        void missingGeetestParams() {
            mock.register(CAPTCHA_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                    + "\"type\":\"geetest\",\"token\":\"t\",\"geetest\":{\"gt\":\"\",\"challenge\":\"\"}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    LoginService.INSTANCE::requestCaptcha);
            assertTrue(e.getMessage().contains("gt"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("业务码非 0 → 抛异常，消息里带业务码")
        void nonZeroBusinessCode() {
            mock.register(CAPTCHA_PATH, "{\"code\":-400,\"message\":\"请求错误\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    LoginService.INSTANCE::requestCaptcha);
            assertTrue(e.getMessage().contains("code=-400"), "实际：" + e.getMessage());
        }
    }

    @Nested
    @DisplayName("账号密码登录")
    class PasswordLogin {

        @BeforeEach
        void setUp() {
            mock.register(CAPTCHA_PATH, CAPTCHA_BODY).register(KEY_PATH, keyBody());
        }

        @Test
        @DisplayName("★ 闭环：提交的 password 用私钥解回，必须逐字等于 hash + 明文密码")
        void passwordEncryptionRoundTrip() {
            mock.registerWithSetCookie(LOGIN_PATH, LOGIN_OK_BODY, LOGIN_SET_COOKIES);

            LoginCredential credential = LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee());

            String submitted = mock.formField(LOGIN_PATH, "password");
            assertNotNull(submitted, "必须提交 password");
            assertEquals(SALT + PLAIN_PASSWORD, decryptPassword(submitted),
                    "口径必须是 base64(RSA_PKCS1(hash + 明文))：盐拼在明文【前面】、一起加密");
            assertNotNull(credential);
        }

        @Test
        @DisplayName("表单参数齐全：keep/source/token/challenge/validate/seccode")
        void formParams() {
            mock.registerWithSetCookie(LOGIN_PATH, LOGIN_OK_BODY, LOGIN_SET_COOKIES);
            LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee());

            Map<String, String> form = mock.formParams(LOGIN_PATH);
            assertEquals(ACCOUNT, form.get("username"));
            assertEquals("0", form.get("keep"));
            assertEquals("main_web", form.get("source"));
            assertEquals(CAPTCHA_TOKEN, form.get("token"));
            assertEquals(CHALLENGE, form.get("challenge"), "未指定时用申请到的 challenge（文档口径）");
            assertEquals(VALIDATE, form.get("validate"));
            assertEquals(VALIDATE + "|jordan", form.get("seccode"), "seccode 必须带 |jordan 后缀");
        }

        @Test
        @DisplayName("调用方指定了 JS 回显的 challenge → 优先用它")
        void prefersEchoedChallenge() {
            mock.registerWithSetCookie(LOGIN_PATH, LOGIN_OK_BODY, LOGIN_SET_COOKIES);
            String echoed = "9f9e8d7c6b5a43210fedcba987654321";

            LoginService.INSTANCE.passwordLogin(ACCOUNT, PLAIN_PASSWORD,
                    LoginService.INSTANCE.requestCaptcha(), gee().withChallenge(echoed));

            assertEquals(echoed, mock.formField(LOGIN_PATH, "challenge"));
        }

        @Test
        @DisplayName("凭据走 Set-Cookie：四项映射正确，且 Expires 解析成时刻（不是'未知'）")
        void credentialFromSetCookie() {
            mock.registerWithSetCookie(LOGIN_PATH, LOGIN_OK_BODY, LOGIN_SET_COOKIES);

            LoginCredential c = LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee());

            assertEquals("497078180", c.getDedeUserId());
            assertEquals(SESSDATA_VALUE, c.getSessdata());
            assertEquals("deadbeefcafe1234", c.getBiliJct());
            assertEquals(EXPIRES_2026_10_15, c.getExpiresAt());
            assertFalse(c.isExpired(), "2026-10-15 在未来，不该判成已过期");
            assertTrue(c.getCookieHeader().contains("SESSDATA=" + SESSDATA_VALUE));
        }

        @Test
        @DisplayName("★ 回归：密码错误（-629）→ 异常带中文解释，且【不自动重试】")
        void wrongPasswordNotRetried() {
            mock.registerWithSetCookie(LOGIN_PATH,
                    "{\"code\":-629,\"message\":\"账号或密码错误\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("code=-629"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("手机号或邮箱"), "应说明 username 该填什么：" + e.getMessage());
            assertEquals(1, mock.hitCount(LOGIN_PATH),
                    "登录失败绝不能自动重试：拿同一份密码反复提交，形态就是撞库");
        }

        @Test
        @DisplayName("★ 回归：2400 登录秘钥错误 → 提示是 hash 过了 20 秒，而不是让用户怀疑密码")
        void expiredHashHint() {
            mock.registerWithSetCookie(LOGIN_PATH,
                    "{\"code\":2400,\"message\":\"0\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("20 秒"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 回归：-2100 被风控要求手机验证 → 提示可改走短信链路")
        void riskControlRequiresSecondFactor() {
            mock.registerWithSetCookie(LOGIN_PATH,
                    "{\"code\":-2100,\"message\":\"0\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("短信"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 服务端提示不许被吞：code=0 但没下发凭据时，风控提示要进异常")
        void serverMessageCarriedIntoException() {
            mock.registerWithSetCookie(LOGIN_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
                    + "\"status\":0,\"message\":\"本次登录环境存在风险, 需使用手机号进行验证或绑定\","
                    + "\"url\":\"\",\"refresh_token\":\"\",\"timestamp\":0}}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("需使用手机号"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 明文密码不许出现在异常消息里")
        void passwordNotInExceptionMessage() {
            mock.registerWithSetCookie(LOGIN_PATH,
                    "{\"code\":-629,\"message\":\"账号或密码错误\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertFalse(e.getMessage().contains(PLAIN_PASSWORD), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("参数为空 → 抛异常，且不去取公钥、不提交登录")
        void blankParams() {
            assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    "", PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));
            assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, "  ", LoginService.INSTANCE.requestCaptcha(), gee()));
            assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, null, gee()));

            assertEquals(0, mock.hitCount(KEY_PATH), "参数不合法时不该产生任何额外出站");
            assertEquals(0, mock.hitCount(LOGIN_PATH));
        }

        @Test
        @DisplayName("公钥响应缺 hash/key → 抛异常")
        void rsaKeyMissingField() {
            mock.register(KEY_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{\"hash\":\"\",\"key\":\"\"}}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("hash"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("密码超过 RSA 单块上限 → 明确报错，而不是送出一段无法解密的密文")
        void passwordTooLong() {
            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, "x".repeat(200), LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("最多"), "实际：" + e.getMessage());
        }
    }

    @Nested
    @DisplayName("短信验证码登录")
    class SmsLogin {

        @BeforeEach
        void setUp() {
            mock.register(CAPTCHA_PATH, CAPTCHA_BODY);
        }

        @Test
        @DisplayName("发送验证码：cid/tel/source + 极验四件套都对，返回 captcha_key")
        void sendsSmsCode() {
            mock.register(SMS_SEND_PATH, SMS_SEND_BODY);

            SmsSendResult result = LoginService.INSTANCE.sendSmsCode(
                    ACCOUNT, null, LoginService.INSTANCE.requestCaptcha(), gee());

            assertEquals(SMS_CAPTCHA_KEY, result.getCaptcha_key());
            Map<String, String> form = mock.formParams(SMS_SEND_PATH);
            assertEquals("86", form.get("cid"), "未指定冠字码时默认中国大陆");
            assertEquals(ACCOUNT, form.get("tel"));
            assertEquals("main_web", form.get("source"));
            assertEquals(CAPTCHA_TOKEN, form.get("token"));
            assertEquals(CHALLENGE, form.get("challenge"));
            assertEquals(VALIDATE, form.get("validate"));
            assertEquals(VALIDATE + "|jordan", form.get("seccode"));
        }

        @Test
        @DisplayName("境外号码：cid 可显式指定")
        void customCaptchaKey() {
            mock.register(SMS_SEND_PATH, SMS_SEND_BODY);

            LoginService.INSTANCE.sendSmsCode("51234567", "852",
                    LoginService.INSTANCE.requestCaptcha(), gee());

            assertEquals("852", mock.formField(SMS_SEND_PATH, "cid"));
        }

        @Test
        @DisplayName("★ 第二步不需要极验：表单里不该出现 validate/seccode")
        void loginStepNeedsNoGeetest() {
            mock.registerWithSetCookie(SMS_LOGIN_PATH, SMS_LOGIN_BODY, LOGIN_SET_COOKIES);

            LoginCredential credential = LoginService.INSTANCE.smsLogin(ACCOUNT, "123456", SMS_CAPTCHA_KEY);

            Map<String, String> form = mock.formParams(SMS_LOGIN_PATH);
            assertEquals(ACCOUNT, form.get("tel"));
            assertEquals("123456", form.get("code"));
            assertEquals(SMS_CAPTCHA_KEY, form.get("captcha_key"));
            assertEquals("86", form.get("cid"));
            assertEquals("main_web", form.get("source"));
            assertNull(form.get("validate"), "短信登录第二步不需要极验");
            assertNull(form.get("seccode"));
            assertEquals("497078180", credential.getDedeUserId(), "凭据同样走 Set-Cookie");
        }

        @Test
        @DisplayName("重复发送（1003）→ 提示 60 秒冷却")
        void duplicateSendHint() {
            mock.register(SMS_SEND_PATH,
                    "{\"code\":1003,\"message\":\"验证码已经发送\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class, () -> LoginService.INSTANCE.sendSmsCode(
                    ACCOUNT, null, LoginService.INSTANCE.requestCaptcha(), gee()));

            assertTrue(e.getMessage().contains("60 秒"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("验证码过期（1007）→ 提示 5 分钟有效期")
        void expiredCodeHint() {
            mock.register(SMS_LOGIN_PATH,
                    "{\"code\":1007,\"message\":\"短信验证码已过期\",\"ttl\":1,\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.smsLogin(ACCOUNT, "123456", SMS_CAPTCHA_KEY));

            assertTrue(e.getMessage().contains("5 分钟"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("缺 captcha_key → 抛异常且不发出请求")
        void missingCaptchaKey() {
            assertThrows(BilibiliException.class,
                    () -> LoginService.INSTANCE.smsLogin(ACCOUNT, "123456", ""));

            assertEquals(0, mock.hitCount(SMS_LOGIN_PATH));
        }
    }

    @Nested
    @DisplayName("★ Expires 解析（真机把到期时间显示成'未知'的根因）")
    class Expiry {

        /** 跑一次登录，返回凭据；@code Expires 由参数指定 */
        private LoginCredential loginWithExpires(String expires) {
            mock.register(CAPTCHA_PATH, CAPTCHA_BODY)
                    .register(KEY_PATH, keyBody())
                    .registerWithSetCookie(LOGIN_PATH, LOGIN_OK_BODY, cookiesWithExpires(expires));
            return LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee());
        }

        @Test
        @DisplayName("实测常量自检：1792048089 确实是 2026-10-15 07:08:09 UTC")
        void constantSelfCheck() {
            assertEquals(LocalDate.of(2026, 10, 15),
                    java.time.Instant.ofEpochSecond(EXPIRES_2026_10_15)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate());
        }

        @Test
        @DisplayName("★ 回归：星期几写错、d-MMM-yyyy 短横线格式，两种真实写法都必须解析")
        void acceptsBothRealFormats() {
            assertEquals(EXPIRES_2026_10_15,
                    loginWithExpires("Thu, 15 Oct 2026 07:08:09 GMT").getExpiresAt());
            assertEquals(EXPIRES_2026_10_15, loginWithExpires("Sat, 15 Oct 2026 07:08:09 GMT").getExpiresAt(),
                    "星期几写错不该让时间作废：RFC_1123_DATE_TIME 会抛 "
                            + "'Conflict found: Field DayOfWeek ...'，正是在这里退回 0 的");

            LoginCredential dashed = loginWithExpires("Sat, 18-Jul-2027 07:08:09 GMT");
            assertTrue(dashed.getExpiresAt() > 0,
                    "d-MMM-yyyy 是官方抓包里的真实写法，RFC 严格版在 index 7 就失败");
            assertFalse(dashed.isExpired(), "2027-07-18 在未来，不该判成已过期");
        }

        @Test
        @DisplayName("已过期的日期要判成【已过期】，不能与【没解析到】混为一谈")
        void expiredVsUnknownAreDistinct() {
            LoginCredential past = loginWithExpires("Sat, 18-Jul-2020 09:57:57 GMT");

            assertTrue(past.getExpiresAt() > 0, "必须解析出时刻，否则无法区分'已过期'与'没解析到'");
            assertTrue(past.isExpired());
        }

        @Test
        @DisplayName("缺 Expires → 0（未知），且不判成已过期")
        void missingExpiresIsUnknown() {
            mock.register(CAPTCHA_PATH, CAPTCHA_BODY)
                    .register(KEY_PATH, keyBody())
                    .registerWithSetCookie(LOGIN_PATH, LOGIN_OK_BODY,
                            "SESSDATA=" + SESSDATA_VALUE + "; Path=/; Domain=.bilibili.com",
                            "DedeUserID=497078180; Path=/; Domain=.bilibili.com");

            LoginCredential c = LoginService.INSTANCE.passwordLogin(
                    ACCOUNT, PLAIN_PASSWORD, LoginService.INSTANCE.requestCaptcha(), gee());

            assertEquals(0L, c.getExpiresAt());
            assertFalse(c.isExpired(), "0 的含义是'未知'，不是'已过期'");
        }
    }
}

package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.login.CredentialStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「查询凭据状态」单测（{@code LoginService#credentialStatus} + {@code Login#getCredentialStatus}）。
 *
 * <p>这个能力只有两种出错方式，各自都会产生<b>静默</b>后果，所以测试也围着这两条写：
 * <ol>
 *   <li><b>把"未登录"当成异常</b> —— 调用方就无法区分"该重新登录"（false）与"该重试"（异常），
 *       长驻进程要么反复重试一个必然失败的凭据，要么直接崩掉；</li>
 *   <li><b>把"非 2xx / 非 JSON"当成"未登录"</b> —— 出口被风控（412）会被误报成
 *       "凭据过期了，去重新登录吧"，而重新登录根本救不了风控。错在这条尤其隐蔽：
 *       两种情况的返回值都是 {@code isLoggedIn()==false}，只有"抛不抛异常"能区分。</li>
 * </ol>
 *
 * <p>另有一条防的是"半残结果"：{@code cookie/info} 查不到时，
 * {@code refreshChecked} 必须留在 {@code false} —— 让调用方看出"这一项没问到"，
 * 而不是拿到一个看起来正常的默认值就去下判断。
 *
 * <p>全部走 {@link MockBiliServer}，不联网、不受 smoke 门控。
 */
class CredentialStatusTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String COOKIE_INFO_PATH = "/x/passport-login/web/cookie/info";

    /**
     * 已登录的 nav 响应。
     *
     * <p>刻意带上了 {@code money} / {@code wbi_img} 等真实字段 —— 一是验证解析不会被
     * 多余字段干扰，二是钉住"只取 isLogin/mid/uname 三项"这件事
     * （{@code money} 是 B 币余额，属于账户隐私，不该被本库带进任何模型与日志）。
     */
    private static final String NAV_LOGGED_IN = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{"
            + "\"isLogin\":true,\"mid\":497078180,\"uname\":\"测试账号\",\"money\":437.2,"
            + "\"wbi_img\":{\"img_url\":\"https://i0.hdslb.com/bfs/wbi/abc.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/def.png\"}}}";

    /**
     * 未登录的 nav 响应（2026-09-16 真机实测形态）。
     *
     * <p>两个要点：HTTP 仍是 <b>200</b>（判据只能是业务码/isLogin），
     * 且 <b>{@code data.wbi_img} 照样有值</b> —— 这是 WBI 签名器将来取 key 的依据，与登录态无关。
     */
    private static final String NAV_NOT_LOGGED_IN = "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1,"
            + "\"data\":{\"isLogin\":false,\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/abc.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/def.png\"}}}";

    /** cookie/info：服务端认为无需刷新（真机当前就是这个状态） */
    private static final String COOKIE_INFO_QUIET = "{\"code\":0,\"message\":\"OK\",\"ttl\":1,"
            + "\"data\":{\"refresh\":false,\"timestamp\":1789537994849}}";

    /** cookie/info：服务端要求刷新 */
    private static final String COOKIE_INFO_NEEDS_REFRESH = "{\"code\":0,\"message\":\"OK\",\"ttl\":1,"
            + "\"data\":{\"refresh\":true,\"timestamp\":1789537994849}}";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    // ================================================================
    // 已登录
    // ================================================================

    @Test
    @DisplayName("已登录：isLogin/mid/uname 取自 nav，并顺带问到【无需刷新】")
    void loggedIn() {
        mock.register(NAV_PATH, NAV_LOGGED_IN).register(COOKIE_INFO_PATH, COOKIE_INFO_QUIET);

        CredentialStatus status = LoginService.INSTANCE.credentialStatus();

        assertTrue(status.isLoggedIn(), "isLogin=true 必须判成已登录");
        assertEquals(497078180L, status.getUid());
        assertEquals("测试账号", status.getUname());
        assertEquals(0, status.getCode());
        assertTrue(status.isRefreshChecked(), "已登录时必须去问 cookie/info");
        assertFalse(status.isRefreshNeeded());
        assertEquals(1789537994849L, status.getRefreshTimestamp());
    }

    @Test
    @DisplayName("服务端要求刷新时：refresh=true 必须如实透出，不能吞成 false")
    void refreshNeeded() {
        mock.register(NAV_PATH, NAV_LOGGED_IN).register(COOKIE_INFO_PATH, COOKIE_INFO_NEEDS_REFRESH);

        CredentialStatus status = LoginService.INSTANCE.credentialStatus();

        assertTrue(status.isLoggedIn());
        assertTrue(status.isRefreshChecked());
        assertTrue(status.isRefreshNeeded(), "refresh=true 是要透给运维的信号（当前含义 = 该重新登录）");
    }

    // ================================================================
    // 未登录
    // ================================================================

    @Test
    @DisplayName("★ 回归：未登录是【正常结果】而非异常 —— code=-101 必须不抛，由调用方看 isLoggedIn")
    void notLoggedInIsNotAnException() {
        mock.register(NAV_PATH, NAV_NOT_LOGGED_IN);

        CredentialStatus status = LoginService.INSTANCE.credentialStatus();

        assertFalse(status.isLoggedIn());
        assertEquals(-101, status.getCode());
        assertEquals("账号未登录", status.getMessage());
        assertFalse(status.isRefreshChecked(), "未登录时 refresh 一项无意义，不能标记成'已查过'");
    }

    @Test
    @DisplayName("★ 未登录时跳过 cookie/info：不浪费一次注定拿 -101 的请求")
    void notLoggedInSkipsRefreshQuery() {
        mock.register(NAV_PATH, NAV_NOT_LOGGED_IN);

        LoginService.INSTANCE.credentialStatus();

        assertEquals(0, mock.hitCount(COOKIE_INFO_PATH),
                "未登录还去打 cookie/info 是白费请求（它同样回 -101），且多一次出站就多一分风控风险");
    }

    // ================================================================
    // 半残结果与真故障的区分
    // ================================================================

    @Test
    @DisplayName("★ 刷新状态查不到：不阻塞主结果，但必须留下【没问到】的痕迹（refreshChecked=false）")
    void refreshQueryFailureDoesNotBlock() {
        mock.register(NAV_PATH, NAV_LOGGED_IN).registerStatus(COOKIE_INFO_PATH, 500, "server boom");

        CredentialStatus status = LoginService.INSTANCE.credentialStatus();

        assertTrue(status.isLoggedIn(), "登录态已由 nav 答了，不该被'顺带问的刷新'拖垮");
        assertFalse(status.isRefreshChecked(),
                "refreshChecked=false 是'这一项没问到'的显式标记 —— 缺了它，调用方就会把默认值当结论");
    }

    @Test
    @DisplayName("★ 回归：HTTP 非 2xx 必须抛异常且带状态码与原文，不能默默判成【未登录】")
    void httpErrorMustNotBeSilent() {
        // 用 500 而不是 412：412 会触发身份轮换重试（3 次出站），500 是 NO_RETRY、一次即返，
        // 测试更纯净；两者在"必须抛且必须带证据"这一点上完全等价
        mock.registerStatus(NAV_PATH, 500, "server boom");

        BilibiliException e = assertThrows(BilibiliException.class,
                LoginService.INSTANCE::credentialStatus);

        assertTrue(e.getMessage().contains("500"), "必须带 HTTP 状态码，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("server boom"), "必须带响应原文片段，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("★ 回归：响应不是 JSON（如风控页）→ 抛异常，不能默默判成【未登录】")
    void invalidJsonThrows() {
        mock.register(NAV_PATH, "<html>风控页</html>");

        BilibiliException e = assertThrows(BilibiliException.class,
                LoginService.INSTANCE::credentialStatus);

        assertTrue(e.getMessage().contains("不是合法 JSON"), "实际：" + e.getMessage());
    }

    // ================================================================
    // 门面边界
    // ================================================================

    @Test
    @DisplayName("门面：真故障包装成 IOException，且保留内层消息（与既有 5 个门面一致）")
    void facadeWrapsAsIoException() {
        mock.registerStatus(NAV_PATH, 500, "server boom");

        IOException e = assertThrows(IOException.class,
                () -> new Login().getCredentialStatus());

        assertTrue(e.getMessage().contains("500"),
                "门面必须保留内层消息 —— 换成一句固定的'查询失败'等于把排障线索一起抹掉，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("门面：未登录（-101）以返回值表达，不得转成 IOException")
    void facadeDoesNotThrowForNotLoggedIn() throws IOException {
        mock.register(NAV_PATH, NAV_NOT_LOGGED_IN);

        CredentialStatus status = new Login().getCredentialStatus();

        assertFalse(status.isLoggedIn());
    }

    // ================================================================
    // 端点与模型
    // ================================================================

    @Test
    @DisplayName("端点路径：nav 在数据域；cookie/info 在 passport 域且必须带 /web/ 段")
    void endpointPaths() {
        assertEquals("https://api.bilibili.com/x/web-interface/nav",
                com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint.navUrl);
        assertEquals("https://passport.bilibili.com/x/passport-login/web/cookie/info",
                com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint.passportCookieInfoUrl,
                "漏掉 /web/ 这一段会返回 HTTP 404 的 HTML 错误页（2026-09-16 实测踩过）");
    }

    @Test
    @DisplayName("summary() 不含任何凭据值，且未登录时把服务端原话带上")
    void summaryIsSafeForLogs() {
        mock.register(NAV_PATH, NAV_NOT_LOGGED_IN);
        CredentialStatus anon = LoginService.INSTANCE.credentialStatus();
        assertTrue(anon.summary().contains("账号未登录"), "实际：" + anon.summary());
        assertTrue(anon.summary().startsWith("未登录"));

        mock.register(NAV_PATH, NAV_LOGGED_IN).register(COOKIE_INFO_PATH, COOKIE_INFO_QUIET);
        CredentialStatus ok = LoginService.INSTANCE.credentialStatus();
        assertTrue(ok.summary().contains("uid=497078180"), "实际：" + ok.summary());
        assertTrue(ok.summary().contains("无需刷新"), "实际：" + ok.summary());
    }
}

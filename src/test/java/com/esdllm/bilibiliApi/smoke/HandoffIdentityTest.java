package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.http.HttpPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 人工交接文件里的「出站身份」如何落到 {@link HttpPolicy} —— 把两个<b>曾静默失效</b>的点锁住。
 *
 * <p><b>为什么值得单独测</b>：这件事全部发生在"读一个文本文件 → 设置全局策略"之间，
 * 没有任何一步会报错。出问题时的表现是<b>登录提醒写着「未知设备」、或风控要求二次验证</b> ——
 * 看起来像"服务端不认我"，实际上只是本地根本没把身份配上。这类缺陷只能靠断言发现，
 * 靠人看日志是看不出来的（日志里那句话本身也可能过期）。
 *
 * <p>两条被锁住的回归：
 * <ol>
 *   <li><b>UA 跨轮次保留</b>：{@code prepareAndWait} 每轮都会把交接文件覆盖成模板，
 *       而「申请验证码」恰好发生在那段空档里 —— 若读不到就退回库默认，同一个指纹生命周期里
 *       就会出现两副面孔。见 {@link LoginPasswordSmokeTest#applyHandoffIdentity(Path)}。</li>
 *   <li><b>设备指纹注入不覆盖登录 Cookie</b>：只补"用户没有的那个身份"，
 *       否则会出现"注入了 A 浏览器设备、却带着 A 的登录态"这种串味组合。</li>
 * </ol>
 *
 * <p>这些用例<b>不联网、也不受 {@code -Dbili.smoke} 门控</b>：它们只碰文件与内存里的静态配置。
 */
@DisplayName("人工交接的出站身份：UA 跨轮次保留 + 设备指纹注入")
class HandoffIdentityTest {

    /** 与真机交接文件里同形状的 Edge UA */
    private static final String EDGE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/153.0.0.0 Safari/537.36 Edg/153.0.0.0";

    @TempDir
    Path dir;

    @BeforeEach
    void clearBefore() {
        clearGlobalState();
    }

    @AfterEach
    void clearAfter() {
        clearGlobalState();
    }

    // ------------------------------------------------------------------ UA

    @Test
    @DisplayName("交接文件里的 ua= 生效：设上显式 UA 并配套 Client Hints")
    void userAgentFromFile() throws IOException {
        Path file = write("ua=" + EDGE_UA + "\nvalidate=abc|jordan\n");

        String note = LoginPasswordSmokeTest.applyHandoffIdentity(file);

        assertEquals(EDGE_UA, HttpPolicy.getUserAgent(), "UA 必须逐字取自交接文件");
        assertFalse(HttpPolicy.clientHints().isEmpty(), "显式指定 UA 必须一并配套 Client Hints");
        assertTrue(note.contains("来自交接文件"), "日志要能看出身份是从哪来的：" + note);
    }

    @Test
    @DisplayName("交接文件没有 ua= 行时不动库默认（改造前的行为必须不变）")
    void noUserAgentKeepsDefault() throws IOException {
        Path file = write("validate=abc|jordan\n");

        String note = LoginPasswordSmokeTest.applyHandoffIdentity(file);

        assertFalse(HttpPolicy.hasUserAgent(), "没读到 ua= 就不该设置显式 UA");
        assertTrue(HttpPolicy.clientHints().isEmpty(), "没有显式 UA 就不该发 Client Hints");
        assertTrue(note.contains("库默认"), note);
    }

    @Test
    @DisplayName("ua= 留空等价于没有：不会被当成「指定了一个空 UA」")
    void blankUserAgentTreatedAsAbsent() throws IOException {
        Path file = write("ua=   \nvalidate=abc|jordan\n");

        LoginPasswordSmokeTest.applyHandoffIdentity(file);

        assertFalse(HttpPolicy.hasUserAgent());
    }

    @Test
    @DisplayName("第二轮交接文件被覆盖后，沿用上一轮读到的浏览器 UA（不许打回库默认）")
    void secondRoundReusesRememberedUserAgent() throws IOException {
        LoginPasswordSmokeTest.applyHandoffIdentity(write("ua=" + EDGE_UA + "\nvalidate=a|jordan\n"));

        // prepareAndWait 每轮都把交接文件覆盖成注释模板，所以这一刻读不到 ua=
        String note = LoginPasswordSmokeTest.applyHandoffIdentity(
                write("# 点「复制 7 行」粘贴到这里（覆盖全文）\n"));

        assertEquals(EDGE_UA, HttpPolicy.getUserAgent(),
                "覆盖交接文件不该把出站身份打回库默认 —— 否则同一指纹生命周期里出现两副面孔");
        assertTrue(note.contains("沿用上一轮"), note);
    }

    // ------------------------------------------------------------------ 设备指纹

    @Test
    @DisplayName("buvid3 / buvid4 走设备身份注入，且能跳过随机领取")
    void deviceLinesBecomeDeviceCookie() throws IOException {
        Path file = write("buvid3=BROWSER3\nbuvid4=BROWSER4\n");

        String note = LoginPasswordSmokeTest.applyHandoffIdentity(file);

        assertTrue(HttpPolicy.cookieProvidesDeviceId(), "有 buvid3 就算自带设备指纹");
        assertTrue(HttpPolicy.getCookie().contains("buvid3=BROWSER3"), HttpPolicy.getCookie());
        assertTrue(HttpPolicy.getCookie().contains("buvid4=BROWSER4"), HttpPolicy.getCookie());
        assertTrue(note.contains("buvid3,buvid4"), note);
    }

    @Test
    @DisplayName("已注入登录 Cookie 时，交接文件里的 buvid 不覆盖它（避免身份串味）")
    void deviceLinesDoNotClobberInjectedCookie() throws IOException {
        HttpPolicy.setCookie("SESSDATA=keep-me; DedeUserID=1");
        Path file = write("buvid3=BROWSER3\n");

        String note = LoginPasswordSmokeTest.applyHandoffIdentity(file);

        assertTrue(HttpPolicy.getCookie().contains("SESSDATA=keep-me"), "登录 Cookie 不能被交接文件覆盖");
        assertFalse(HttpPolicy.getCookie().contains("buvid3=BROWSER3"), HttpPolicy.getCookie());
        assertTrue(note.contains("未应用"), note);
    }

    @Test
    @DisplayName("设备指纹同样跨轮次保留：第二轮只剩注释时仍用上一轮的 buvid")
    void secondRoundReusesRememberedDevice() throws IOException {
        LoginPasswordSmokeTest.applyHandoffIdentity(write("buvid3=BROWSER3\n"));
        LoginPasswordSmokeTest.applyHandoffIdentity(write("# 只剩注释\n"));

        assertTrue(HttpPolicy.getCookie().contains("buvid3=BROWSER3"),
                "设备身份被覆盖清掉，就等于又变回一台「新设备」");
    }

    @Test
    @DisplayName("没有 buvid 行时不动 Cookie：仍走匿名随机领取")
    void noDeviceLinesKeepsAnonymous() throws IOException {
        String note = LoginPasswordSmokeTest.applyHandoffIdentity(write("ua=" + EDGE_UA + "\n"));

        assertFalse(HttpPolicy.hasCookie(), "不该凭空注入设备身份");
        assertTrue(note.contains("匿名随机领取"), note);
    }

    // ------------------------------------------------------------------ 小工具

    /** 覆盖写一份交接文件（每次都是同一个路径，模拟真实的一轮覆盖一轮） */
    private Path write(String content) throws IOException {
        Path file = dir.resolve("geetest-result.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * 还原全局状态。
     *
     * <p>三条都要清：{@code HttpPolicy} 的 UA 与 Cookie 是<b>全局静态</b>，
     * 而记忆的出站身份是冒烟类里的静态字段 —— 漏掉任何一条，用例之间就会互相污染。
     */
    private static void clearGlobalState() {
        HttpPolicy.clearUserAgent();
        HttpPolicy.clearCookie();
        LoginPasswordSmokeTest.forgetRememberedIdentity();
    }
}

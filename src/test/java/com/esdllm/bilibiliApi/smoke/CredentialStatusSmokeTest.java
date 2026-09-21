package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.login.CredentialStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 联网冒烟：<b>凭据状态校验</b>（{@code nav} + {@code cookie/info}）真实链路。
 *
 * <p><b>为什么需要它</b>：单测里的响应是 fixture 造的，只能证明"解析逻辑对"；
 * 而这条能力真正要回答的是"<b>服务端现在还认不认这枚凭据</b>"—— 这只有真机问一次才知道。
 * 尤其 {@code cookie/info} 那个端点，路径错一段就是 404 的 HTML 错误页，
 * fixture 测试永远发现不了（你造什么它回什么）。
 *
 * <p>默认跳过（不联网）。跑法：
 * <pre>
 * mvn -o -B test "-Dtest=CredentialStatusSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true -Dbili.cookieFile=.workbuddy/bili-cookie.txt"
 * </pre>
 *
 * <p>第二个用例刻意用<b>伪造凭据</b>，且不需要任何账号 —— 它验证的是
 * "服务端说未登录时，本库把它作为<b>返回值</b>而不是异常给出来"，
 * 这是长驻进程"该重新登录"的判据，比第一条更常被用到。
 */
@DisplayName("联网冒烟：凭据状态校验（默认跳过）")
class CredentialStatusSmokeTest {

    /** 结构完整但 SESSDATA 是假的 —— 形状对、值不对，正是"凭据过期"的等价形态 */
    private static final String BROKEN_COOKIE =
            "SESSDATA=not-a-real-sessdata-value; bili_jct=deadbeefdeadbeef; "
                    + "DedeUserID=1; DedeUserID__ckMd5=0123456789abcdef";

    @Test
    @DisplayName("有效凭据：判【已登录】，并报出 uid 与昵称")
    void loggedInWithRealCredential() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(), "未提供 -Dbili.cookie / -Dbili.cookieFile，跳过");
        HttpPolicy.setCookie(cookie);

        CredentialStatus status = new Login().getCredentialStatus();

        System.out.printf("%n§ 凭据状态：%s%n", status.summary());
        System.out.printf("§ 该不该刷新：%s%n", status.isRefreshChecked()
                ? (status.isRefreshNeeded() ? "是（= 该重新登录，本库尚未实现自动刷新）" : "否")
                : "未查到（cookie/info 没给出结果）");
        System.out.printf("§ 说明：判据是 nav 的 data.isLogin，不是 HTTP 状态（未登录时 HTTP 也是 200）%n");

        assertTrue(status.isLoggedIn(), "提供的是有效凭据却被判成未登录，实际：" + status.summary());
        assertTrue(status.getUid() > 0, "已登录时 uid 必须 > 0");
        assertNotNull(status.getUname(), "已登录时应能取到昵称");
    }

    @Test
    @DisplayName("★ 伪造凭据：必须判【未登录】而不是抛异常 —— 这是'该重新登录'的判据")
    void brokenCredentialIsNotAnException() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        HttpPolicy.clearCookie();
        HttpPolicy.setCookie(BROKEN_COOKIE);

        CredentialStatus status = new Login().getCredentialStatus();

        System.out.printf("%n§ 伪造凭据的状态：%s%n", status.summary());

        assertFalse(status.isLoggedIn(), "假 SESSDATA 不该被认成登录态");
        assertNotEquals(0, status.getCode(), "未登录时业务码必须非 0（实测是 -101）");
        assertFalse(status.isRefreshChecked(),
                "未登录时不该去问 cookie/info，也不该把它标成'已查过'");
    }

    /** 取 Cookie：优先 {@code -Dbili.cookieFile=<文件路径>}，否则用 {@code -Dbili.cookie} */
    private static String cookie() throws Exception {
        String file = System.getProperty("bili.cookieFile");
        if (file != null && !file.isBlank()) {
            return java.nio.file.Files.readString(java.nio.file.Path.of(file)).trim();
        }
        return System.getProperty("bili.cookie");
    }
}

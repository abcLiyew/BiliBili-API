package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.login.LoginCredential;
import com.esdllm.bilibiliApi.model.data.pojo.login.QrCodeLogin;
import com.esdllm.bilibiliApi.model.data.pojo.login.QrLoginState;
import com.esdllm.bilibiliApi.model.data.pojo.login.QrLoginStatus;
import com.esdllm.bilibiliApi.service.LoginService;
import com.esdllm.bilibiliApi.support.QrImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 联网冒烟：<b>扫码登录</b>（把二维码渲染成 PNG，人工用哔哩哔哩 App 扫）。
 *
 * <p><b>为什么需要它</b>：扫码登录整条链路有一个"出错也不报错"的失败形态 —— 凭据确实拿到了、
 * 但 <b>URL 没解码</b>（{@code SESSDATA} 里带 {@code %2C}），服务端不认，后续请求<b>静默</b>退回匿名。
 * 单元测试里喂的是 mock 响应，只能证明"解析逻辑自洽"，证明不了"服务端真的认这枚凭据"。
 * 本冒烟用真实凭据打一次 {@code feed/all}（既有端点里真正需要登录的那个），把这个静默失败变成可见结果。
 *
 * <p><b>两种跑法</b>（选一种即可）：
 * <pre>
 * ① IDE 里直接跑 {@link #main(String[])} —— 不需要任何 VM 参数，最省事
 * ② 命令行（沿用库内 smoke 约定）：
 *    mvn -o -B test "-Dtest=LoginQrSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *      "-DargLine=-Dbili.smoke=true"
 * </pre>
 *
 * <p><b>产出</b>：
 * <ul>
 *   <li>{@code target/bili-login-qr.png} —— 二维码图片，会自动尝试用系统看图器打开；</li>
 *   <li>{@code .workbuddy/bili-cookie.txt} —— 完整 Cookie，可直接复用到
 *       {@code -Dbili.cookieFile=<该路径>}（如 {@code FollowFeedSmokeTest}）。</li>
 * </ul>
 * 两个路径都落在 {@code .gitignore} 覆盖范围内，不会误入库。
 *
 * <p><b>安全</b>：控制台默认<b>不打印</b>完整 Cookie（{@code SESSDATA} 等价于账号密码），
 * 只打印脱敏摘要与落盘路径；确实需要贴到别处时加 {@code -Dbili.showCookie=true}。
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网冒烟：扫码登录（需人工扫码，默认跳过）")
class LoginQrSmokeTest {

    /**
     * 轮询总超时（毫秒），与二维码 180 秒寿命对齐。
     *
     * <p>刻意取相等而不是更长：让"二维码已失效"这个<b>明确状态</b>先于"超时"出现，
     * 失败原因更具体（失效可以重跑换一张，超时只能猜）。
     */
    private static final long TIMEOUT_MS = LoginService.DEFAULT_TIMEOUT_MS;

    /** 二维码图片落盘位置（{@code target/} 已被 .gitignore 覆盖） */
    private static final Path QR_IMAGE_PATH = Path.of("target", "bili-login-qr.png");

    /** 凭据落盘位置（{@code .workbuddy/} 已被 .gitignore 覆盖，且不随 {@code mvn clean} 丢失） */
    private static final Path COOKIE_FILE_PATH = Path.of(".workbuddy", "bili-cookie.txt");

    @Test
    @DisplayName("扫码登录：渲染二维码 → 人工扫 → 凭据通过 feed/all 端到端验证")
    void qrLogin() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        // 预检模式下必须跳过本用例：否则它会真的等 180 秒人工扫码，把"几秒出结果"变成"卡三分钟"
        assumeTrue(!Boolean.getBoolean("bili.qr.dryRun"),
                "已开启 -Dbili.qr.dryRun=true（只跑预检），跳过需要人工扫码的用例");

        LoginCredential credential = scanAndLogin();

        // 凭据本身的自洽性（不联网也能判，失败多半是解析层的问题）
        assertNotNull(credential, "扫码成功必须返回凭据");
        assertFalse(credential.getCookieHeader().isBlank(), "Cookie 头不能为空");
        assertTrue(credential.getCookieHeader().contains("SESSDATA="),
                "凭据必须含 SESSDATA（缺它等于没登录）");
        assertFalse(credential.isExpired(), "刚拿到的凭据不该已过期");
        assertNotNull(credential.getDedeUserId(), "DedeUserID 应被解析出来");

        // ★ 真正的验收点：服务端认不认这枚凭据
        Verify verify = verifyCredential(credential);
        assertNotSame(Verify.AUTH_FAILED, verify, "凭据已取得但被服务端判为未登录 —— 优先怀疑 parseCredential 的 URL 解码失效"
                + "（SESSDATA 带 %2C 时服务端不认，且不会报错，只会静默按匿名处理）");
    }

    /**
     * <b>预检</b>（可选，几秒出结果）：只验证"申请二维码的端点可达 + 渲染出的图真能被解码器读回"，
     * <b>不等扫码</b>。
     *
     * <p>价值在于把"扫码登录跑不通"的两类原因提前分开：一类是出口 IP 被风控／端点不可达
     * （几秒就能知道，不用等 180 秒超时），另一类是渲染问题（本机可判）。跑法：
     * <pre>
     * mvn -o -B test "-Dtest=LoginQrSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
     *   "-DargLine=-Dbili.smoke=true -Dbili.qr.dryRun=true"
     * </pre>
     * 预检申请的二维码 180 秒后自然失效，不会被用于登录。
     */
    @Test
    @DisplayName("预检：二维码能申请到且渲染出的图可扫（不等扫码，几秒完成）")
    void dryRun() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");
        assumeTrue(Boolean.getBoolean("bili.qr.dryRun"), "未开启 -Dbili.qr.dryRun=true，跳过预检");

        // 能走到这里说明 passport 域可达且没被出口风控拦（不可达会直接抛，消息里带错误码）
        QrCodeLogin qr = preflight();
        assertNotNull(qr.getQrcode_key(), "必须拿到 qrcode_key");
        assertEquals(32, qr.getQrcode_key().length(), "qrcode_key 恒为 32 字符");
        assertNotNull(qr.getUrl(), "必须拿到二维码内容");
    }

    /**
     * 供 IDE 直接运行（无需 {@code -Dbili.smoke=true}）。
     *
     * <p>与 JUnit 用例走同一段逻辑，区别只是失败时打印一行结论而不是断言堆栈。
     *
     * @param args 传 {@code --dry-run} 则只做预检（申请二维码 + 渲染 + 回读解码，不等扫码）
     */
    public static void main(String[] args) {
        System.out.println("HttpPolicy：" + HttpPolicy.describe());
        try {
            if (args.length > 0 && "--dry-run".equals(args[0])) {
                preflight();
                return;
            }
            LoginCredential credential = scanAndLogin();

            // ★ 端到端验证必须跑：扫码只证明"换到了凭据"，不证明"服务端认这枚凭据"，
            //   而后者才是登录是否真的生效的分界线。JUnit 用例一直是这么做验收的，
            //   main 这条路此前漏了 —— 等于把最关键的一步留给运气。
            Verify verify = verifyCredential(credential);
            if (verify == Verify.AUTH_FAILED) {
                System.err.println("凭据已取得但被服务端判为未登录 —— 本次登录不能算成功（见上方 -101 提示）");
                System.exit(2);
            }

            System.out.printf("%n登录完成。凭据摘要：%s%n", credential);
            System.out.println("用法：HttpPolicy.setCookie(credential.getCookieHeader()) —— 之后所有出站请求自动带登录态。");
            System.out.printf("复用到其他冒烟：-Dbili.cookieFile=%s%n", COOKIE_FILE_PATH.toAbsolutePath());
            if (Boolean.getBoolean("bili.showCookie")) {
                System.out.printf("%n[完整 Cookie —— 因 -Dbili.showCookie=true 而打印，注意别外传]%n%s%n",
                        credential.getCookieHeader());
            } else {
                System.out.println("（完整 Cookie 未打印到控制台；文件已落在上面那个路径，需要就打开它）");
            }
        } catch (Throwable t) {
            // 必须打完整因果链：失败原因常常被包了两层（IOException → BilibiliException），
            // 只看最外层会退化成"登录失败：查询扫码状态失败"这种没有信息量的输出
            System.err.printf("%n登录失败：%s%n", t.getMessage());
            Throwable cause = t.getCause();
            while (cause != null) {
                System.err.printf("  起因：%s（%s）%n", cause.getMessage(), cause.getClass().getSimpleName());
                cause = cause.getCause();
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 预检的实现（{@code main --dry-run} 与 {@link #dryRun()} 共用一份，避免两处逻辑漂移）：
     * 申请二维码 → 渲染 PNG → <b>把图读回并解码</b>，只做本地能判定的部分，不等扫码。
     *
     * <p>回读不一致时抛异常而不是静默返回 —— "图生成了"与"图能扫"是两件事，
     * 后者才是用户扫码时会遇到的真实条件。
     *
     * @return 申请到的二维码（含 {@code url} 与 {@code qrcode_key}）
     * @throws IOException 端点不可达、业务失败、渲染失败，或图不可扫
     */
    static QrCodeLogin preflight() throws IOException {
        QrCodeLogin qr = new Login().getLoginQrCode();
        Path png = QrImages.writePng(qr.getUrl(), QR_IMAGE_PATH);
        String decoded = QrImages.decode(png);
        if (!qr.getUrl().equals(decoded)) {
            throw new IOException("渲染出的二维码回读不一致（图扫出来是别的内容）"
                    + "：期望 " + qr.getUrl() + "，实际 " + decoded);
        }

        // 顺带真打一次轮询：把"读扫码状态"的链路也纳入预检 ——
        // 它是登录流程里唯一会反复出站的动作，挂掉时的表现同样只有一句"查询扫码状态失败"
        QrLoginStatus status = new Login().getLoginStatus(qr.getQrcode_key());

        System.out.printf("%n---- 预检通过 ----%n");
        System.out.printf("端点可达：passport 域申请二维码成功（qrcode_key %d 字符）%n", qr.getQrcode_key().length());
        System.out.printf("图片可扫：%s（%d 字节）%n", png.toAbsolutePath(), Files.size(png));
        System.out.printf("轮询可用：当前状态=%s（data.code=%d，%s）%n",
                status.getState().getText(), status.getRawCode(), status.getMessage());
        System.out.printf("内容（%d 字符）：%s%n", decoded.length(), decoded);
        System.out.println("注意：本次申请的二维码 180 秒后自然失效，不会被用于登录。");
        return qr;
    }

    /**
     * 完整扫码登录流程：申请二维码 → 渲染 PNG → 轮询 → 落盘 → 端到端验证。
     *
     * @return 登录凭据
     * @throws IOException 申请失败、二维码失效、超时，或渲染/落盘失败
     */
    static LoginCredential scanAndLogin() throws IOException {
        Login login = new Login();

        // ① 申请二维码
        QrCodeLogin qr = login.getLoginQrCode();
        // 渲染失败会直接抛出来（图是这一步的全部意义）；可扫性由 QrImagesTest 的编码→解码闭环兜底
        Path image = QrImages.writePng(qr.getUrl(), QR_IMAGE_PATH);

        System.out.printf("%n==================== 请扫码登录 ====================%n");
        System.out.printf("二维码图片：%s%n", image.toAbsolutePath());
        System.out.printf("有效期：%d 秒（超时后重跑本方法换一张新码）%n", TIMEOUT_MS / 1000);
        System.out.println("扫码步骤：哔哩哔哩 App → 右上角「扫一扫」→ 扫描打开的图片 → 手机上点「确认登录」");
        System.out.printf("兜底（图片打不开时）：把这串在手机浏览器里打开试试%n  %s%n", qr.getUrl());
        System.out.println("====================================================");
        openQuietly(image);

        // ② 轮询到出结果
        LoginCredential credential = pollUntilDone(login, qr.getQrcode_key());

        // ③ 落盘 + 打印
        saveCookieQuietly(credential);
        System.out.printf("凭据：%s%n", credential);
        return credential;
    }

    /**
     * 轮询扫码状态，只在状态<b>发生变化</b>时打印一行。
     *
     * <p>为什么不用门面的 {@code waitForLogin}：那个方法全程静默阻塞，用户扫完码后看不到
     * "已扫码、请在手机上确认"这一步，很容易以为卡住了而反复重扫（而<b>重扫会让之前的状态作废</b>）。
     * 这里自己控节奏，把状态迁移暴露出来。轮询间隔仍取门面的默认值，两边保持一致。
     *
     * @param login     登录门面
     * @param qrcodeKey 二维码密钥
     * @return 登录凭据
     * @throws IOException 二维码失效或等待超时
     */
    private static LoginCredential pollUntilDone(Login login, String qrcodeKey) throws IOException {
        // 单次轮询失败不终止整场登录：二维码还在有效期内，2 秒后再问一次通常就有结果。
        // 但连续失败必须停下 —— 那说明不是偶发抖动（例如出口被风控），继续轮询只是白等 180 秒。
        // （2026-09-16 真机踩坑：第一次扫码时单次失败直接把整场登录判死了，用户得重扫一遍。）
        final int maxConsecutiveFailures = 5;
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        QrLoginState lastPrinted = null;
        int polls = 0;
        int failures = 0;

        while (true) {
            polls++;
            QrLoginStatus status;
            try {
                status = login.getLoginStatus(qrcodeKey);
                failures = 0;
            } catch (IOException e) {
                failures++;
                System.out.printf("[%tT] 第 %d 次轮询失败（连续失败 %d/%d）：%s%n",
                        System.currentTimeMillis(), polls, failures, maxConsecutiveFailures, e.getMessage());
                if (failures >= maxConsecutiveFailures) {
                    throw new IOException("连续 " + maxConsecutiveFailures + " 次轮询都失败，最后一次原因："
                            + e.getMessage(), e);
                }
                sleep(LoginService.DEFAULT_POLL_INTERVAL_MS);
                continue;
            }
            QrLoginState state = status.getState();

            if (state != lastPrinted) {
                System.out.printf("[%tT] 状态：%s（data.code=%d，B站文案「%s」）%n",
                        System.currentTimeMillis(), state.getText(), status.getRawCode(), status.getMessage());
                lastPrinted = state;
            }

            if (state.isSuccess()) {
                System.out.printf("[%tT] 手机已确认，共轮询 %d 次%n", System.currentTimeMillis(), polls);
                return status.getCredential();
            }
            if (state == QrLoginState.EXPIRED) {
                // 失效的 key 不会自己复活，继续轮询毫无意义
                throw new IOException("二维码已失效（有效期 " + TIMEOUT_MS / 1000
                        + " 秒，或被后申请的二维码顶掉）。重跑本方法即可换一张新码。");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("等待 " + TIMEOUT_MS / 1000 + " 秒仍未确认登录（最后状态："
                        + state.getText() + "，已轮询 " + polls + " 次）");
            }
            sleep(LoginService.DEFAULT_POLL_INTERVAL_MS);
        }
    }

    // ------------------------------------------------------------------ 端到端验证

    /**
     * 凭据验证结果。
     *
     * <p><b>包级可见是刻意的</b>：{@link LoginPasswordSmokeTest}（密码 / 短信登录）走的是同一条
     * "注入凭据 → 打 feed/all → 分流"的验收路径，两处必须用同一套判据，
     * 否则"扫码算成功、密码算失败"这种分歧迟早出现。
     */
    enum Verify {
        /** 凭据被服务端接受，登录态确实生效 */
        PASSED,
        /** 凭据可用，但该账号当前没有可推送的动态（关注流为空） */
        EMPTY_FEED,
        /** 服务端判为未登录 —— 凭据无效，必须当失败 */
        AUTH_FAILED,
        /** 出口 IP 被风控拦住（与凭据无关，本次没能验证出结论） */
        RISK_CONTROL
    }

    /**
     * 端到端验证：注入 Cookie 后请求 {@code feed/all}（既有端点里真正需要登录的那个）。
     *
     * <p>这一步是本次冒烟的核心价值 —— 它区分开三种"看起来都是登录了"的情形：
     * 凭据真的生效、凭据无效（漏了 URL 解码）、出口被风控（凭据没问题但拿不到数据）。
     * 三者只看"有没有抛异常"是分不出来的，必须看错误码。
     *
     * @param credential 凭据
     * @return 验证结论
     */
    static Verify verifyCredential(LoginCredential credential) {
        System.out.printf("%n---- 端到端验证：注入 Cookie 后请求 feed/all ----%n");
        HttpPolicy.setCookie(credential.getCookieHeader());
        try {
            List<Dynamic.DynamicInfo> feed = new Dynamic().getFollowFeed();
            System.out.printf("feed/all 返回 %d 条，前几条：%n", feed.size());
            feed.stream().limit(3).forEach(info -> System.out.printf("   uid=%s name=%s time=\"%s\"%n",
                    info.getUid(), info.getUserName(), info.getTime()));
            if (feed.isEmpty()) {
                System.out.println("⚠ 关注流为空 —— 凭据本身是有效的（否则这里会直接报错），"
                        + "只是该账号目前没有可推送的动态");
                return Verify.EMPTY_FEED;
            }
            System.out.println("✅ 凭据被服务端接受，登录态确实生效");
            return Verify.PASSED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("⚠ 验证被中断，未得出结论");
            return Verify.RISK_CONTROL;
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            // -101 = 账号未登录：这是凭据本身的问题；-352/412 = 出口风控，换网络就可能好
            boolean authFailure = message.contains("-101") || message.contains("账号未登录");
            System.out.printf("%s feed/all 失败：%s%n", authFailure ? "❌" : "⚠", message);
            if (authFailure) {
                return Verify.AUTH_FAILED;
            }
            System.out.println("   （该错误码属于出口风控而非登录态问题：凭据有效性本次未能验证，"
                    + "换网络出口后重跑即可）");
            return Verify.RISK_CONTROL;
        }
    }

    /** 尝试用系统默认看图器打开二维码；失败只提示，不影响扫码（路径已打印） */
    private static void openQuietly(Path file) {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return;
            }
            Desktop.getDesktop().open(file.toFile());
            System.out.println("（已尝试自动打开图片；没弹出来的话手动打开上面的绝对路径）");
        } catch (Exception e) {
            System.out.printf("（自动打开失败：%s；手动打开上面的绝对路径即可）%n",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 把 Cookie 落盘，便于后续冒烟（{@code -Dbili.cookieFile=}）与手工复用。
     *
     * <p>落在 {@code .workbuddy/} 而不是 {@code target/}：后者会被 {@code mvn clean} 清掉，
     * 而凭据本身有 30 天寿命，值得跨构建保留；该目录同样在 {@code .gitignore} 内。
     */
    static void saveCookieQuietly(LoginCredential credential) {
        try {
            Path parent = COOKIE_FILE_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(COOKIE_FILE_PATH, credential.getCookieHeader(), StandardCharsets.UTF_8);
            System.out.printf("凭据已落盘：%s%n", COOKIE_FILE_PATH.toAbsolutePath());
        } catch (IOException e) {
            // 落盘失败不阻塞验证：凭据已在内存里
            System.out.printf("（凭据落盘失败，不影响后续验证：%s）%n", e.getMessage());
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待扫码被中断", e);
        }
    }
}

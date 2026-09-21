package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.http.UserAgentPool;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import com.esdllm.bilibiliApi.support.HandoffFile;
import com.esdllm.bilibiliApi.support.StaticPageServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 联网冒烟：<b>密码登录 / 短信登录</b>（需人工在浏览器里过一次极验）。
 *
 * <p><b>为什么需要它</b>：这条链路有一段是本地<b>永远验证不了</b>的 ——
 * 极验 v3 要求提交一个由浏览器 JS 生成的轨迹/指纹参数（{@code w}），本地伪造不出来。
 * 单元测试里喂的是 mock，只能证明"参数拼装自洽"，证明不了"服务端真的接受这份过验结果"。
 * 本冒烟把剩下那段交给真人，然后用真实凭据打一次 {@code feed/all}（既有端点里真正需要登录的那个），
 * 把"登录到底成没成"变成可见结果。
 *
 * <p><b>怎么跑（选一种）</b>：
 * <pre>
 * ① IDE 里直接跑 {@link #main(String[])} —— 不需要任何 VM 参数，最省事（推荐）
 * ② 命令行：
 *    mvn -o -B test "-Dtest=LoginPasswordSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *      "-DargLine=-Dbili.smoke=true"
 * </pre>
 *
 * <p><b>交互流程（main）</b>：
 * <ol>
 *   <li>读凭据 + 定下<b>出站身份</b>（浏览器 UA / 可选设备指纹，见 {@link #applyHandoffIdentity(Path)}）→
 *       申请验证码 → 生成 {@code target/bili-geetest.html} → <b>起一个临时本地 http 服务</b>，
 *       用 {@code http://127.0.0.1:<随机端口>/bili-geetest.html} 打开（见下一条）；</li>
 *   <li>在页面里滑一下/点一下完成极验，点「复制 7 行」（第 7 行是浏览器 UA）；</li>
 *   <li>粘贴到 {@code .workbuddy/geetest-result.txt}（覆盖整个文件）；
 *       想让出站身份<b>就是浏览器那台设备</b>时，再追加两行 {@code buvid3=} / {@code buvid4=}
 *       （见 {@link #applyHandoffIdentity(Path)} 里"未知设备"那一段）；</li>
 *   <li>本类自动读取 → 登录 → 用 {@code feed/all} 验证 → 凭据落盘。</li>
 * </ol>
 * 短信模式（{@code main sms}）在第 4 步前多一步：把手机收到的 6 位验证码贴到
 * {@code .workbuddy/sms-code.txt}。已真机跑通（2026-09-16，凭据有效）。
 *
 * <p><b>🔴 辅助页必须用 http 打开，不能双击文件用 {@code file://} 打开</b>（2026-09-16 用户真机踩到）：
 * 极验的 {@code gt.js} 内部用<b>协议相对 URL</b>（{@code //static.geetest.com/.../fullpage.0.0.0.js}）
 * 加载后续脚本，而 {@code file://} 下它会继承 scheme 变成 {@code file://static.geetest.com/...}
 * → 浏览器去本地磁盘找 → {@code ERR_FILE_NOT_FOUND}；同时 {@code file://} 的 origin 是 {@code null}，
 * 跨源资源一律被 CORS 拦，最终只表现为极验框里一句"网络错误"。
 * <b>所以本类会临时起一个本地 http 服务</b>（{@link StaticPageServer}，只绑 127.0.0.1、端口系统分配），
 * 并把 {@code http://127.0.0.1:端口/...} 这个地址交给浏览器 —— scheme 正确 + origin 真实，三个报错一并消失。
 *
 * <p><b>人工交接为什么要走文件而不是控制台</b>：IDEA 的运行控制台对 {@code System.in} 的支持
 * 时灵时不灵（取决于是否启用终端模拟），而"粘贴到文件"在任何环境都成立，
 * 且每轮过验的产物天然留痕，排查时能直接看到送去服务端的到底是什么参数。
 *
 * <p><b>账号/密码怎么给</b>：<b>推荐填 {@code .workbuddy/login-account.txt}</b>（第一次跑会自动建好带注释的模板），
 * 也可用 {@code -Dbili.account=} / {@code -Dbili.password=}，或环境变量 {@code BILI_ACCOUNT} / {@code BILI_PASSWORD}。
 *
 * <p><b>缺凭据时不再"报错让你重跑"</b>（2026-09-16 改进）：交互式 {@code main} 会
 * <b>自动用系统默认编辑器打开 {@code login-account.txt}</b> 并轮询等待（与极验同一套交接机制），
 * 你填好保存后程序自己继续 —— 一次运行走完全程，不用"报错→去填→重跑"来回三趟。
 * 回放模式的 JUnit 用例（无人值守）不会等人，缺凭据直接跳过。
 *
 * <p><b>🔴 凭据会在"申请验证码"之前就校验</b>：过极验是「一次性 + 要人工花几十秒」的动作，
 * 把它排在凭据校验之前，用户会先白过一次验、才被告知"没填账号"（2026-09-16 真机就是这么翻的）。
 * 凡是不可逆或耗时的步骤，前置校验一律排在它前面。
 *
 * <p><b>单次性提醒</b>：极验的 {@code validate} <b>用过即废</b>。
 * 每跑一次登录（不管成功失败）都要重新生成页面、重新过验 ——
 * 复用上一轮的结果只会得到 {@code 2406 验证极验服务出错}，而它看上去像"网络问题"。
 *
 * <p><b>安全</b>：控制台默认<b>不打印</b>完整 Cookie（{@code SESSDATA} 等价于账号密码）。
 * 本冒烟也<b>不会</b>打印账号与明文密码。
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网冒烟：密码 / 短信登录（需人工过极验，默认跳过）")
class LoginPasswordSmokeTest {

    /** 等人工过极验的上限（毫秒）。5 分钟足够，同时避免在 CI 里无限挂住 */
    private static final long WAIT_FOR_HUMAN_MS = 5 * 60 * 1000L;

    /**
     * 是否交互模式（由 {@link #main(String[])} 置位）。
     *
     * <p>交互模式下缺凭据会打开文件并等人填；JUnit 回放时<b>绝不</b>等人 ——
     * 在无人值守环境里挂 5 分钟等一个人，比直接失败更糟。用实例无关的静态开关而不是系统属性，
     * 是为了让"谁在等人"这件事在代码里一眼可见，而不是藏在某个 VM 参数里。
     */
    private static boolean interactive;

    /**
     * 从交接文件里读到过的<b>出站身份</b>（浏览器 UA + 浏览器自己的设备指纹），<b>跨轮次保留</b>。
     *
     * <p><b>为什么要保留</b>：{@link #prepareAndWait(Login)} 每轮都会把交接文件覆盖成新模板，
     * 而 {@code ua=}/{@code buvid3=} 这些行是随「过验结果」一起被粘回来的 —— 于是
     * <b>本轮覆盖后到人工粘贴前的那段空档</b>里，身份是读不到的。偏偏「申请验证码」就发生在
     * 这段空档里（它是<b>第一次出站</b>，还会顺带领一套新的设备指纹）。
     * 而人用的浏览器并不会因此改变，所以这些值必须记住、下轮继续用 ——
     * 否则同一个指纹生命周期里会出现两副面孔（先库默认 UA，再浏览器 UA）。
     */
    private static volatile HandoffIdentity rememberedIdentity = HandoffIdentity.empty();

    /**
     * 忘掉记住的出站身份（<b>只给测试用</b>：测试之间必须互不污染）。
     *
     * <p>生产路径上不存在"需要忘记"的语义 —— 进程活着的这段时间里，人在用的浏览器不会变；
     * 换了浏览器就重新过验、重新粘贴，新值自然覆盖旧值。
     */
    static void forgetRememberedIdentity() {
        rememberedIdentity = HandoffIdentity.empty();
    }

    /** 极验辅助页落盘位置（{@code target/} 已 gitignore，且每次运行都会重新生成） */
    private static final Path PAGE_PATH = Path.of("target", "bili-geetest.html");

    /** 人工交接文件：极验 6 项参数（{@code .workbuddy/} 已 gitignore） */
    private static final Path GEETEST_HANDOFF = Path.of(".workbuddy", "geetest-result.txt");

    /** 人工交接文件：短信验证码 */
    private static final Path SMS_CODE_HANDOFF = Path.of(".workbuddy", "sms-code.txt");

    /**
     * 人工交接文件：登录凭据（账号 / 密码）。
     *
     * <p><b>为什么要有这个文件</b>：只靠 {@code -Dbili.account=} 这类 VM 参数的话，
     * 在 IDEA 里得专门去改 Run Configuration；而这条链路本来就有一半步骤靠"人改文件"交接
     * （{@code geetest-result.txt} / {@code sms-code.txt}），凭据放同一套机制里最一致。
     * 该目录在 {@code .gitignore} 内，不会入库。
     */
    private static final Path ACCOUNT_HANDOFF = Path.of(".workbuddy", "login-account.txt");

    /** 凭据文件初始模板（文件为空时自动落盘，照着填即可） */
    private static final String ACCOUNT_TEMPLATE =
            """
                    # 登录凭据（.workbuddy/ 已 gitignore，不会入库）；在本机是明文，用完记得清空。
                    # 密码登录：account 与 password 都填；短信登录：只填 account（手机号）。
                    # 直接把值写在下面两行的等号后面并保存 —— 程序会自动读到并继续，不用重新运行。
                    # 值会去掉首尾空格；密码首尾若真有空格，请改用 VM 参数 -Dbili.password= 传。
                    account=
                    password=
                    """;

    /** 模板里待替换的占位符 —— 残留即视为模板被改坏，直接报错而不是给用户一个打不开的页面 */
    private static final List<String> TEMPLATE_PLACEHOLDERS =
            List.of("__TOKEN__", "__GT__", "__CHALLENGE__", "__RESULT__");

    // ------------------------------------------------------------------ 入口

    /**
     * 供 IDE 直接运行（不需要 {@code -Dbili.smoke=true}）。
     *
     * @param args {@code password}（默认）｜{@code sms}｜{@code dryrun}（只预检，不等人）
     */
    public static void main(String[] args) {
        String mode = args.length > 0 && !args[0].isBlank() ? args[0].trim().toLowerCase() : "sms";
        interactive = true; // 有人在键盘后面 —— 缺凭据时可以直接打开文件等他填
        System.out.println("HttpPolicy：" + HttpPolicy.describe());
        System.out.printf("模式：%s（可选 password｜sms｜dryrun）%n", mode);
        try {
            if ("dryrun".equals(mode)) {
                dryRun();
                return;
            }
            LoginCredential credential = "sms".equals(mode) ? smsFlow() : passwordFlow();

            LoginQrSmokeTest.Verify verify = LoginQrSmokeTest.verifyCredential(credential);
            if (verify == LoginQrSmokeTest.Verify.AUTH_FAILED) {
                System.err.println("凭据已取得但被服务端判为未登录 —— 本次登录不能算成功");
                System.exit(2);
            }

            LoginQrSmokeTest.saveCookieQuietly(credential);
            System.out.printf("%n登录完成。凭据摘要：%s%n", credential);
            System.out.println("用法：HttpPolicy.setCookie(credential.getCookieHeader()) —— 之后所有出站请求自动带登录态。");
            if (Boolean.getBoolean("bili.showCookie")) {
                System.out.printf("%n[完整 Cookie —— 因 -Dbili.showCookie=true 而打印，注意别外传]%n%s%n",
                        credential.getCookieHeader());
            } else {
                System.out.println("（完整 Cookie 未打印；文件已落在 .workbuddy/bili-cookie.txt，需要就打开它）");
            }
        } catch (Throwable t) {
            // 必须打完整因果链：失败原因常被包两层（IOException → BilibiliException），
            // 只看最外层会退化成"密码登录失败"这种没有信息量的输出
            System.err.printf("%n登录失败：%s%n", t.getMessage());
            Throwable cause = t.getCause();
            while (cause != null) {
                System.err.printf("  起因：%s（%s）%n", cause.getMessage(), cause.getClass().getSimpleName());
                cause = cause.getCause();
            }
            if (isRiskChallenge(fullChain(t))) {
                printRiskChallengeAdvice();
            }
            System.err.println("排查提示：极验 validate 是一次性的 —— 只要已经提交过登录请求（哪怕失败），"
                    + "就得重新过验（2406 = 这份结果已用过或与本次申请不匹配）；");
            System.err.println("  若失败发生在提交之前（例如凭据没配好，那种情况现在会提前报，浏览器都还没打开），"
                    + "修好后重跑即可，那一份 validate 还没被用掉。");
            System.exit(1);
        }
    }

    /**
     * 服务端是不是在要求"风控二次验证"（而不是在拒绝这次请求）。
     *
     * <p>判据用<b>服务端自己的原话</b>，不去猜错误码 —— 实测 {@code data.status=2} 总是与
     * "本次登录环境存在风险, 需使用手机号进行验证或绑定"同时出现（2026-09-16，两次独立运行复现）。
     *
     * @param chain 完整异常因果链文本
     * @return 是否属于风控二次验证
     */
    private static boolean isRiskChallenge(String chain) {
        return chain.contains("环境存在风险") || chain.contains("status=2");
    }

    /**
     * 风控二次验证时的行动建议。
     *
     * <p>之所以要单独打一段：这种情况下默认报错文案（"服务端没有下发 SESSDATA"）
     * 会让人以为是自己参数写错了，而真相是<b>请求完全正确、服务端要求补一次手机验证</b> ——
     * 2026-09-16 用户连撞两次，两次都停在同一个地方、看到同一条信息。
     */
    private static void printRiskChallengeAdvice() {
        System.err.println();
        System.err.println("★ 这不是参数错 / 密码错 / 极验错 —— 服务端回的是业务级 code=0，");
        System.err.println("  RSA 加密密码与极验四件套都已被接受，是它要求再做一次手机号验证（风控）。");
        System.err.println("  本机两次独立运行（不同 buvid3 / challenge / request_id）结果一致 ⇒ 不是偶发。");
        System.err.println("  已确证的可行路径：");
        System.err.println("   ① main sms —— 短信登录**已真机跑通**（2026-09-16，凭据经 feed/all 验证有效）："
                + "它做的正是'手机号验证'，恰好满足这条风控要求；");
        System.err.println("   ② 扫码登录（LoginQrSmokeTest）—— 已真机跑通、凭据约 30 天。");
        System.err.println("  另外：B 站登录提醒对密码与短信两种登录**都**写「未知设备」（真人 Edge 登录写「Edge」），");
        System.err.println("  即便提交登录时用的就是交接文件里**你浏览器自己的 UA** + Client Hints，判定也不变");
        System.err.println("  ⇒ **与 UA 无关，别再往 UA 方向排查**（这条弯路已经走过两次）。");
        System.err.println("  现判据：服务端读不到一个可信的**浏览器环境** —— status=2 的服务端原话就是");
        System.err.println("  「本次登录环境存在风险」，两句同源；旁证：**扫码登录显示「Edge」**（登录态由手机端");
        System.err.println("  确认、不由我们的 POST 创建 ⇒ 不触发环境评估），密码/短信才写「未知设备」。");
        System.err.println("  这条 **Java 侧补不齐**（要真浏览器跑 B 站自己的 JS/WASM）⇒ 已收口为已知边界、不再追；");
        System.err.println("  要它变「Edge」的唯一正路：在真浏览器里登录一次，把 4 个 Cookie 交给库");
        System.err.println("  （HttpPolicy.setCookie / .workbuddy/bili-cookie.txt，约 30 天一次）。");
    }

    /**
     * 把异常因果链拼成一段文本（用于判断"是哪一类失败"）。
     *
     * @param t 异常
     * @return 自身与全部 cause 的 message，用 {@code |} 连接
     */
    private static String fullChain(Throwable t) {
        StringBuilder chain = new StringBuilder(String.valueOf(t.getMessage()));
        for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
            chain.append(" | ").append(cause.getMessage());
        }
        return chain.toString();
    }

    // ------------------------------------------------------------------ JUnit 用例

    /**
     * <b>预检</b>（不需要人工，几秒出结果）：验证码端点可达 + 返回的确实是极验三件套。
     *
     * <p>价值在于把"登录跑不通"的两类原因提前分开：一类是出口 IP 被风控 / 端点不可达
     * （几秒就知道），另一类是极验结果本身有问题（要等人工过验才发现）。
     */
    @Test
    @DisplayName("预检：验证码端点可达且返回 geetest 三件套（不等人）")
    void preflight() throws IOException {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        LoginCaptcha captcha = new Login().getCaptcha();

        assertEquals("geetest", captcha.getType());
        assertNotNull(captcha.getToken());
        assertNotNull(captcha.getGeetest());
        assertNotNull(captcha.getGeetest().getGt());
        assertNotNull(captcha.getGeetest().getChallenge());

        System.out.printf("预检通过：token %d 字符，gt=%s，challenge=%s%n",
                captcha.getToken().length(), captcha.getGeetest().getGt(), captcha.getGeetest().getChallenge());
    }

    /**
     * 密码登录（<b>回放</b>人工交接文件）。
     *
     * <p>需要 {@code .workbuddy/geetest-result.txt} 里已有完整的 6 项参数 ——
     * 先用 {@link #main(String[])} 跑一次会生成页面并落盘；人工过验后本用例可直接重放。
     * 文件不存在或只有注释行时<b>跳过</b>（绝不在无人值守环境里挂 5 分钟等一个人）。
     *
     * <p>账号密码同样从 {@code -Dbili.account=} / {@code -Dbili.password=}（或环境变量）取 ——
     * 与 {@code main} 同一个来源，不另设一套。
     */
    @Test
    @DisplayName("密码登录：回放 .workbuddy/geetest-result.txt（不重新申请、不等人）")
    void passwordLogin() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        assumeTrue(hasValidation(GEETEST_HANDOFF),
                "未找到可用的人工极验结果（" + GEETEST_HANDOFF + "）：先跑 main() 生成页面并过验");
        assumeTrue(hasCredential("account") && hasCredential("password"),
                "未配置账号/密码（" + ACCOUNT_HANDOFF + "）：回放模式不等人，缺凭据直接跳过");

        LoginCredential credential = replayPasswordLogin();

        assertValidCredential(credential);
        LoginQrSmokeTest.Verify verify = LoginQrSmokeTest.verifyCredential(credential);
        assertNotSame(verify, LoginQrSmokeTest.Verify.AUTH_FAILED, "凭据已取得但被服务端判为未登录");
    }

    /**
     * 短信登录（回放人工交接文件 + 短信验证码文件）。
     *
     * <p>需要两个文件都有内容，否则跳过。注意本用例会<b>消耗</b>掉 {@code geetest-result.txt} 里
     * 那份极验结果（极验 {@code validate} 用过即废），重跑必须重新过验。
     *
     * <p>与密码登录不同的是，短信这条路<b>必然要人参与</b>：验证码得有人从手机上读出来，
     * 且 5 分钟即过期。所以这里只省掉"多余的极验过验"，不假装它是全自动的。
     */
    @Test
    @DisplayName("短信登录：需先人工过极验 + 把收到的验证码写进 .workbuddy/sms-code.txt")
    void smsLogin() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        assumeTrue(hasValidation(GEETEST_HANDOFF),
                "未找到可用的人工极验结果（" + GEETEST_HANDOFF + "）：先跑 main() 生成页面并过验");
        assumeTrue(hasCredential("account"),
                "未配置手机号（" + ACCOUNT_HANDOFF + "）：回放模式不等人，缺凭据直接跳过");
        assumeTrue(!HandoffFile.lines(SMS_CODE_HANDOFF).isEmpty(),
                "未找到短信验证码（" + SMS_CODE_HANDOFF + "）：先跑 main sms 发送验证码");

        LoginCredential credential = replaySmsFlow();

        assertValidCredential(credential);
        LoginQrSmokeTest.Verify verify = LoginQrSmokeTest.verifyCredential(credential);
        assertNotSame(verify, LoginQrSmokeTest.Verify.AUTH_FAILED, "凭据已取得但被服务端判为未登录");
    }

    // ------------------------------------------------------------------ 流程

    /**
     * 预检：申请验证码 + 生成辅助页（不打开浏览器、不等人）。
     *
     * @return 申请到的验证码信息
     */
    static LoginCaptcha dryRun() throws IOException {
        LoginCaptcha captcha = new Login().getCaptcha();
        Path page = writeHelperPage(captcha);
        System.out.printf("%n---- 预检通过 ----%n");
        System.out.printf("端点可达：%s%n", BilibiliEndpointProbe.captchaUrl());
        System.out.printf("返回类型：%s（极验 v3；token %d 字符）%n",
                captcha.getType(), captcha.getToken().length());
        System.out.printf("辅助页已生成：%s（预检不会打开它，也不等人工）%n", page.toAbsolutePath());
        System.out.println("正式跑 main() 时，这个页面会由一个临时本地 http 服务提供（地址当场打印）——");
        System.out.println("  极验在 file:// 下加载不出来，所以别直接双击这个文件。");
        System.out.println("注意：本次申请的一次性参数会随进程结束作废，不会被用于登录。");
        return captcha;
    }

    /**
     * <b>回放</b>密码登录：只从交接文件还原「申请验证码 + 极验结果」，<b>不再申请、不再等人</b>。
     *
     * <p>与 {@link #passwordFlow()} 的唯一区别就是"不重新申请" —— 而这正是自动化用例该有的形态：
     * 人工过验<b>一次</b>，之后可反复重放（直到那份 {@code validate} 被消耗掉）。
     * 若这里改走 {@code passwordFlow()}，会申请一枚新验证码并覆盖交接文件，
     * 结果是"已经验过的人被要求再验一次"，在无人值守的测试里还会白等 5 分钟。
     *
     * <p>之所以能从文件还原 {@code token}/{@code gt}/{@code challenge}：辅助页把这三项与
     * {@code validate} 一并回吐了 —— 只有它们来自<b>同一次</b>申请，服务端才认。
     *
     * @return 凭据
     * @throws IOException 交接文件缺少关键字段
     */
    static LoginCredential replayPasswordLogin() throws IOException {
        LoginCaptcha captcha = readCaptcha(GEETEST_HANDOFF);
        GeeTestValidation validation = readValidation(GEETEST_HANDOFF);
        System.out.printf("出站身份：%s%n", applyHandoffIdentity(GEETEST_HANDOFF));
        System.out.printf("%n---- 回放密码登录（不重新申请验证码）----%n使用极验结果：%s%n", validation);

        LoginCredential credential =
                new Login().loginByPassword(readAccount(), readPassword(), captcha, validation);
        System.out.printf("密码登录成功：%s%n", credential);
        return credential;
    }

    /** 密码登录全流程：<b>先校验凭据</b> → 申请验证码 → 生成页面 → 等人工过验 → 登录 */
    static LoginCredential passwordFlow() throws IOException {
        Login login = new Login();

        // 🔴 凭据必须在这里就读到。过极验是「一次性 + 要人工花几十秒」的动作，
        //    若把它排在凭据校验之前，用户会先白过一次验、才被告知"没填账号"
        //    （2026-09-16 真机就发生了这一出）。凡是不可逆/耗时的步骤，前置校验一律排它前面。
        String account = readAccount();
        String password = readPassword();
        System.out.printf("账号：%s%n", mask(account));

        // 🔴 出站身份必须在这里就定下来：「申请验证码」是**第一次出站**，它会顺带领一套新的
        //    设备指纹（buvid3），而指纹与身份是绑在一起的。若等到过验后才配置，
        //    同一个指纹生命周期里就会出现两副面孔（先库默认 UA，再浏览器 UA）。
        //    本轮交接文件即将被 prepareAndWait 覆盖，但读到的值会记住、过验后继续沿用。
        System.out.printf("出站身份（申请验证码之前）：%s%n", applyHandoffIdentity(GEETEST_HANDOFF));

        LoginCaptcha captcha = prepareAndWait(login);
        GeeTestValidation validation = readValidation(GEETEST_HANDOFF);
        System.out.printf("出站身份：%s%n", applyHandoffIdentity(GEETEST_HANDOFF));
        System.out.printf("%n---- 提交密码登录 ----%n使用极验结果：%s%n", validation);

        LoginCredential credential = login.loginByPassword(account, password, captcha, validation);
        System.out.printf("密码登录成功：%s%n", credential);
        return credential;
    }

    /** 短信登录全流程（交互）：<b>先校验手机号</b> → 申请验证码 → 等人工过验 → 发短信 → 等人工填码 → 登录 */
    static LoginCredential smsFlow() throws IOException {
        Login login = new Login();
        String tel = readAccount(); // 同样先校验：不能让人过完验才发现手机号没填
        // 与 passwordFlow 同理：出站身份要在"申请验证码"（第一次出站、顺带领设备指纹）之前定下来
        System.out.printf("出站身份（申请验证码之前）：%s%n", applyHandoffIdentity(GEETEST_HANDOFF));
        LoginCaptcha captcha = prepareAndWait(login);
        return smsFlowAfterCaptcha(login, captcha, readValidation(GEETEST_HANDOFF), tel);
    }

    /**
     * <b>回放</b>短信登录：不重新申请验证码，直接从交接文件还原极验结果后发短信。
     *
     * <p>与密码登录不同的是，这里<b>无法完全省掉人工</b> —— 短信验证码必须由人从手机上读出来，
     * 且 5 分钟即过期，"回放一条旧验证码"没有意义。能省掉的是那一次多余的极验过验。
     */
    static LoginCredential replaySmsFlow() throws IOException {
        String tel = readAccount();
        return smsFlowAfterCaptcha(new Login(), readCaptcha(GEETEST_HANDOFF),
                readValidation(GEETEST_HANDOFF), tel);
    }

    /**
     * 短信登录的公共后半段：发码 → 等人工填码 → 登录。
     *
     * @param login      门面
     * @param captcha    与 {@code validation} 同一次申请得到的验证码信息
     * @param validation 极验过验结果
     * @param tel        手机号（由调用方<b>提前</b>校验，保证失败得比"过验"更早）
     * @return 凭据
     */
    private static LoginCredential smsFlowAfterCaptcha(Login login, LoginCaptcha captcha,
                                                       GeeTestValidation validation, String tel) throws IOException {
        System.out.printf("出站身份：%s%n", applyHandoffIdentity(GEETEST_HANDOFF));
        System.out.printf("%n---- 发送短信验证码（tel=%s）----%n", mask(tel));
        SmsSendResult sent = login.sendSmsCode(tel, captcha, validation);

        // 必须覆盖写：这里是"本轮的新验证码"，留着上一轮的会被误当成本轮的结果
        HandoffFile.overwrite(SMS_CODE_HANDOFF,
                """
                        # 把手机收到的 6 位验证码粘贴到下面（一行即可，覆盖本文件内容）
                        # 验证码 5 分钟内有效；同一手机号 60 秒内不能重发
                        """);
        System.out.printf("短信已发送。请把收到的验证码粘贴到：%s%n", SMS_CODE_HANDOFF.toAbsolutePath());
        String code = waitForSmsCode();

        System.out.printf("%n---- 提交短信登录 ----%n");
        LoginCredential credential = login.loginBySms(tel, code, sent.getCaptcha_key());
        System.out.printf("短信登录成功：%s%n", credential);
        return credential;
    }

    /**
     * 申请验证码 → 生成辅助页并打开 → 等人把过验结果写进交接文件。
     *
     * @return 申请到的验证码信息（提供 {@code token} 与申请时的 {@code challenge}）
     */
    private static LoginCaptcha prepareAndWait(Login login) throws IOException {
        LoginCaptcha captcha = login.getCaptcha();
        Path page = writeHelperPage(captcha);
        // 必须覆盖写：本轮申请了新的 challenge，上一轮的 validate 已经作废（复用会得 2406）
        HandoffFile.overwrite(GEETEST_HANDOFF,
                """
                        # 用浏览器打开 Java 侧打印的那个 http://127.0.0.1:端口/ 地址，过完极验后点「复制 7 行」，\
                        粘贴到这里（覆盖全文）
                        # 每一轮登录都必须重新过验：极验 validate 用过即废
                        # 想把「真浏览器那一整套环境」交给本次登录（用于判别 B 站登录提醒的「未知设备」）：
                        #   ★ 推荐：启动时加 -Dbili.cookieFile=.workbuddy/bili-anon-cookie.txt
                        #     该文件来自 tools/cdp-cookie-bridge 的 cookies 命令，含 buvid_fp / browser_resolution
                        #     等本库从不采集的键。它是启动参数，不怕本文件被覆盖（比下面两行稳）。
                        #   备选（只补设备指纹，不含 buvid_fp）：
                        #   buvid3=<F12 → Application → Cookies → passport.bilibili.com 下的 buvid3 值>
                        #   buvid4=<同上的 buvid4 值>
                        """);

        // 用临时 http 服务把页面送出去（不能用 file://，原因见类注释），服务随本次等待一起关闭
        try (StaticPageServer server = StaticPageServer.serving(page)) {
            System.out.printf("%n==================== 请完成极验 ====================%n");
            System.out.printf("① 浏览器打开：%s%n", server.url());
            System.out.println("   注意：请用这个 http 地址，不要直接双击 target/bili-geetest.html ——");
            System.out.println("   file:// 下极验必然加载失败（协议相对 URL 会变成 file://static.geetest.com/...，");
            System.out.println("   而且 file:// 的 origin 是 null，跨源请求一律被 CORS 拦），页面里只会显示「网络错误」。");
            System.out.println("② 在弹出的验证码里滑一下 / 点一下（这一步库做不了，必须在浏览器里过）");
            System.out.printf("③ 点页面上的「复制 7 行」，粘贴到：%s%n", GEETEST_HANDOFF.toAbsolutePath());
            System.out.println("   可选：若想「用浏览器那台设备」登录（用于判别登录提醒里的「未知设备」），");
            System.out.println("   就在粘贴内容后面追加两行 buvid3=<值> / buvid4=<值> —— 值取");
            System.out.println("   F12 → Application → Cookies → https://passport.bilibili.com 下的同名 Cookie。");
            System.out.println("   不追加则维持现状（库每次领一套全新随机指纹 = 每次都是新设备）。");
            System.out.printf("④ 我会自动读取（最长等 %d 秒），然后继续登录%n", WAIT_FOR_HUMAN_MS / 1000);
            System.out.println("兜底（页面里验证码还是加载不出来时）：打开 https://passport.bilibili.com/login 触发验证码，");
            System.out.println("  用 F12 → Network 找 validate 请求的载荷，把 validate/seccode 补进上面那个文件");
            System.out.println("====================================================");
            openQuietly(URI.create(server.url()));

            waitForGeetestResult();
        }
        return captcha;
    }

    // ------------------------------------------------------------------ 人工交接文件

    /**
     * 把模板渲染成辅助页。
     *
     * @param captcha 申请到的验证码信息（三件套注入页面，页面再过验后原样回吐，保证自洽）
     * @return 生成的页面路径
     * @throws IOException 模板缺失/占位符残留/写盘失败
     */
    private static Path writeHelperPage(LoginCaptcha captcha) throws IOException {
        String template = loadTemplate();
        String html = template
                .replace("__TOKEN__", captcha.getToken())
                .replace("__GT__", captcha.getGeetest().getGt())
                .replace("__CHALLENGE__", captcha.getGeetest().getChallenge())
                // 页面上显示的是正斜杠路径：它只用于"给人看/复制"，反斜杠在 HTML 里反而会乱
                .replace("__RESULT__", GEETEST_HANDOFF.toAbsolutePath().toString().replace('\\', '/'));

        Path parent = PAGE_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(PAGE_PATH, html, StandardCharsets.UTF_8);
        return PAGE_PATH;
    }

    /**
     * 读模板并校验占位符都在 —— <b>残留占位符必须报错</b>。
     *
     * <p>因为"页面里少了 token"的表现是：能过验、能复制、登录时却报 2406，
     * 排查方向会被带偏到极验本身。在这里拦住，失败信息才指向真正的原因。
     */
    private static String loadTemplate() throws IOException {
        try (InputStream in = LoginPasswordSmokeTest.class.getResourceAsStream("/geetest-helper.html")) {
            if (in == null) {
                throw new IOException("找不到辅助页模板 /geetest-helper.html（应在 src/test/resources 下）");
            }
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String placeholder : TEMPLATE_PLACEHOLDERS) {
                if (!template.contains(placeholder)) {
                    throw new IOException("辅助页模板缺少占位符 " + placeholder
                            + "（模板被改动了？改回来，或同步调整 writeHelperPage）");
                }
            }
            return template;
        }
    }

    /**
     * 轮询交接文件，直到出现一份可用的极验结果。
     *
     * @throws IOException 超时仍没读到
     */
    private static void waitForGeetestResult() throws IOException {
        long deadline = System.currentTimeMillis() + WAIT_FOR_HUMAN_MS;
        while (true) {
            if (hasValidation(GEETEST_HANDOFF)) {
                System.out.printf("[%tT] 已读到极验结果%n", System.currentTimeMillis());
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("等待 " + WAIT_FOR_HUMAN_MS / 1000 + " 秒仍未读到极验结果（"
                        + GEETEST_HANDOFF.toAbsolutePath() + "）。重跑即可换一张新验证码。");
            }
            sleep(1000);
        }
    }

    /** 轮询交接文件，直到出现一个像验证码的值（4~8 位数字） */
    private static String waitForSmsCode() throws IOException {
        long deadline = System.currentTimeMillis() + WAIT_FOR_HUMAN_MS;
        while (true) {
            List<String> lines = HandoffFile.lines(SMS_CODE_HANDOFF);
            if (!lines.isEmpty()) {
                String code = lines.get(0).replaceAll("\\D", "");
                if (code.length() >= 4) {
                    System.out.printf("[%tT] 已读到短信验证码（%d 位）%n", System.currentTimeMillis(), code.length());
                    return code;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("等待 " + WAIT_FOR_HUMAN_MS / 1000 + " 秒仍未读到短信验证码（"
                        + SMS_CODE_HANDOFF.toAbsolutePath() + "）");
            }
            sleep(1000);
        }
    }

    /**
     * 交接文件里是否已经有 validate（只有注释行视为"还没有"）。
     *
     * <p>读写交接文件的公共逻辑已下沉到 {@link HandoffFile}（三处交接共用一套解析，
     * 并由 {@code HandoffFileTest} 锁住 BOM / 覆盖 这两个曾静默失效的坑）。
     */
    private static boolean hasValidation(Path file) {
        try {
            return HandoffFile.value(file, "validate") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 交接文件里的<b>出站身份</b>三项 —— 都可以没有（{@code null} 表示"没读到"）。
     *
     * @param userAgent 浏览器 UA（辅助页第 7 行 {@code ua=}）
     * @param buvid3    浏览器自己的设备指纹 {@code buvid3}（可选，需人工从浏览器 Cookie 里抄）
     * @param buvid4    浏览器自己的设备指纹 {@code buvid4}（可选）
     */
    private record HandoffIdentity(String userAgent, String buvid3, String buvid4) {

        static HandoffIdentity empty() {
            return new HandoffIdentity(null, null, null);
        }

        /**
         * 用上一轮记住的值补缺。
         *
         * <p>交接文件每轮都被覆盖，但"人用的还是同一个浏览器"，所以缺的项要沿用旧值 ——
         * 见 {@link #rememberedIdentity}。
         */
        HandoffIdentity fillFrom(HandoffIdentity previous) {
            if (previous == null) {
                previous = empty();
            }
            return new HandoffIdentity(
                    userAgent != null ? userAgent : previous.userAgent(),
                    buvid3 != null ? buvid3 : previous.buvid3(),
                    buvid4 != null ? buvid4 : previous.buvid4());
        }

        boolean hasDevice() {
            return buvid3 != null || buvid4 != null;
        }

        /** 只含设备指纹的 Cookie 片段（不含登录态）——注入后即"用浏览器那台设备出站" */
        String deviceCookie() {
            StringBuilder cookie = new StringBuilder();
            if (buvid3 != null) {
                cookie.append("buvid3=").append(buvid3);
            }
            if (buvid4 != null) {
                if (!cookie.isEmpty()) {
                    cookie.append("; ");
                }
                cookie.append("buvid4=").append(buvid4);
            }
            return cookie.toString();
        }

        /** 键名（值不出），只用于日志 */
        String deviceKeys() {
            StringBuilder keys = new StringBuilder();
            if (buvid3 != null) {
                keys.append("buvid3");
            }
            if (buvid4 != null) {
                if (!keys.isEmpty()) {
                    keys.append(',');
                }
                keys.append("buvid4");
            }
            return keys.toString();
        }
    }

    /**
     * 用交接文件里的「出站身份」配置本次出站：<b>浏览器 UA</b>（必有）+ <b>浏览器自己的设备指纹</b>（可选）。
     *
     * <p><b>为什么需要 UA</b>：极验的 {@code w} 是<b>在浏览器里</b>按那个浏览器的环境算出来的，
     * 而提交登录的是 Java 侧。两边 UA 不一致时（典型：你用 2026 年的 Edge，而库默认发 {@code Edg/131}），
     * 服务端看到的是"同一个验证会话里出现两台设备"。辅助页把 {@code navigator.userAgent} 一起交回来，
     * 这里读走即天然一致。顺带还修掉一件事：显式设定后 UA <b>不再随身份轮换变动</b>
     * （默认"UA 跟身份走"，登录成功后恰好轮换一次就会变成"刚登录成功、转头 {@code -101}"）。
     *
     * <p><b>为什么还需要设备指纹</b>（2026-09-16 真机引出的首要假设）：补齐 UA + CH 之后，
     * B 站登录提醒<b>依旧</b>写「未知设备」⇒ 那条判定不由 UA / CH 决定。曾假设它判的是
     * "是不是一台<b>我认识的设备</b>"（{@code buvid3}/{@code buvid4}），但本库每次运行都从
     * {@code finger/spi} 领一套<b>全新随机</b>的，所以每次都是新设备 —— 把浏览器自己的两个值
     * 交进来即可做 A/B 判别。
     *
     * <p><b>🆕 2026-09-16 起多了一条更完整的入口：{@code cookie=}</b>。真浏览器<b>匿名态</b>就有
     * <b>15</b> 个 Cookie，而本库只采集 {@code buvid3}+{@code buvid4}；多出的
     * {@code buvid_fp}（前端 JS 算的浏览器指纹）与 {@code browser_resolution}（分辨率）是库从不采集的，
     * 而"环境"的判据很可能就在 Cookie 里 —— 此前只排查过<b>请求头</b>（UA / CH），那两类东西
     * 从未被区分（见 {@code API_FACTS.md} §2.8）。用 {@code tools/cdp-cookie-bridge} 的
     * {@code cookies} 命令导出，整行粘进来即可：
     * <pre>
     * cookie=SESSDATA=…; buvid3=…; buvid_fp=…; browser_resolution=…   （一整行，覆盖本文件其余内容）
     * ua=Mozilla/5.0 … Edg/153.0.0.0                                  （想要浏览器面孔就再加这行）
     * </pre>
     * 两者<b>各行其是</b>：{@code cookie=} 管"环境"，{@code ua=} 管"面孔"；同时写最接近真人。
     *
     * <p>⚠️ 本条走的是"交接文件"这一侧，而该文件会被「申请验证码」那一步<b>整份覆盖</b>
     * （见 {@code prepareAndWait}），所以 {@code cookie=} 只在"跑 {@code main} 之前就写好"这一种时序下有效。
     * <b>要稳就用启动参数 {@code -Dbili.cookieFile=}</b>（系统属性，不受本文件被覆盖影响）。
     * 两者同时存在时<b>本文件这一侧优先</b>（它是在运行时调 {@link HttpPolicy#setCookie}），所以别同时配。
     *
     * <p><b>🔴 调用时机</b>：必须在<b>第一次出站之前</b>调（见 {@code passwordFlow}/{@code smsFlow} 开头）——
     * 「申请验证码」那一步会顺带领一套设备指纹，而指纹与身份是绑在一起的。
     * 只在过验之后才配置，等于让同一个指纹生命周期里出现两副面孔。
     *
     * @param file 交接文件；缺项一律沿用 {@link #rememberedIdentity} 里记住的，再缺就是库默认
     * @return 本次实际生效的 UA 与设备指纹来源（值一律不出），供日志
     */
    static String applyHandoffIdentity(Path file) {
        Map<String, String> values;
        try {
            values = HandoffFile.read(file);
        } catch (IOException e) {
            return "库默认（读交接文件失败：" + e.getMessage() + "）";
        }
        // 🆕 整套 Cookie 直供：一行 cookie= 就把"真浏览器那一整套匿名环境"整体注入。
        //    它比 buvid3=/buvid4= 两行更完整 —— 真浏览器匿名态有 15 键，其中 buvid_fp（浏览器指纹）
        //    与 browser_resolution（分辨率）是库从不采集的，而它们很可能正是「未知设备」的判据
        //    （见 API_FACTS.md §2.8 / §2.10）。产物直接来自 tools/cdp-cookie-bridge 的 cookies 命令。
        //    与 ua= 各行其是：cookie 管"环境"，ua 管"面孔"，两者都要就都写上。
        String fullCookie = blankToNull(values.get("cookie"));

        HandoffIdentity fresh = new HandoffIdentity(
                blankToNull(values.get("ua")),
                blankToNull(values.get("buvid3")),
                blankToNull(values.get("buvid4")));
        boolean userAgentFromFile = fresh.userAgent() != null;
        boolean deviceFromFile = fresh.hasDevice();
        HandoffIdentity used = fresh.fillFrom(rememberedIdentity);
        rememberedIdentity = used;

        String userAgentNote;
        if (used.userAgent() == null) {
            userAgentNote = "UA=库默认 " + briefUa(UserAgentPool.defaultAgent())
                    + "（交接文件里没有 ua= 行，也没读到过）";
        } else {
            HttpPolicy.setUserAgent(used.userAgent());
            userAgentNote = "UA=" + briefUa(used.userAgent())
                    + (userAgentFromFile ? "(来自交接文件)" : "(沿用上一轮)");
        }

        String deviceNote;
        if (fullCookie != null) {
            // 优先级最高：整套环境是"真浏览器原样"，拆着补只会补成四不像
            HttpPolicy.setCookie(fullCookie);
            deviceNote = "整套 Cookie=来自交接文件 cookie= 行（键=" + HttpPolicy.cookieKeys()
                    + "）⇒ 跳过随机领取，UA 由上面那行单独决定";
        } else if (!used.hasDevice()) {
            deviceNote = "设备指纹=匿名随机领取（交接文件里没有 buvid3=/buvid4= 行）";
        } else if (HttpPolicy.hasCookie()) {
            // 已注入登录 Cookie 时不动它：那份 Cookie 自带的 buvid 优先级更高，覆盖只会造成串味
            deviceNote = "设备指纹=未应用（已注入登录 Cookie，交由其自带的 buvid 提供）";
        } else {
            HttpPolicy.setCookie(used.deviceCookie());
            deviceNote = "设备指纹=浏览器自己的 " + used.deviceKeys()
                    + (deviceFromFile ? "(来自交接文件)" : "(沿用上一轮)")
                    + " ⇒ 跳过随机领取";
        }
        return userAgentNote + "；" + deviceNote;
    }

    /** UA 太长，日志里只留到版本号那一段（形如 {@code ... Edg/140.0.0.0}） */
    private static String briefUa(String ua) {
        if (ua == null || ua.isBlank()) {
            return "（空）";
        }
        String tail = ua.trim();
        return tail.length() <= 60 ? tail : "…" + tail.substring(tail.length() - 60);
    }

    /**
     * 从交接文件还原极验结果。
     *
     * @param file 交接文件
     * @return 极验结果
     * @throws IOException 缺 {@code validate}
     */
    private static GeeTestValidation readValidation(Path file) throws IOException {
        Map<String, String> values = HandoffFile.read(file);
        String validate = values.get("validate");
        if (validate == null || validate.isBlank()) {
            throw new IOException("交接文件里没有 validate：" + file.toAbsolutePath());
        }
        String seccode = values.get("seccode");
        GeeTestValidation validation = (seccode == null || seccode.isBlank())
                ? GeeTestValidation.of(validate)
                : GeeTestValidation.of(validate, seccode);

        // 挑战串取哪个有分歧（官方文档说取"申请验证码接口处"的，真实前端会送 JS 回显的那个）。
        // 默认按文档口径；报 2406 时加 -Dbili.geetest.preferEcho=true 换另一个试 —— 这也是
        // 把这个分歧变成"可切换、可复现"，而不是留给运气。
        if (Boolean.getBoolean("bili.geetest.preferEcho") && values.get("echo_challenge") != null) {
            validation = validation.withChallenge(values.get("echo_challenge"));
            System.out.println("（按 -Dbili.geetest.preferEcho=true 使用 JS 回显的 challenge）");
        }
        return validation;
    }

    /**
     * 从交接文件还原"申请验证码"的信息（供 JUnit 回放用）。
     *
     * <p>之所以能从文件还原：页面把 {@code token}/{@code gt}/{@code challenge} 一并回吐了 ——
     * 只有它们与 {@code validate} 来自<b>同一次</b>申请，服务端才认。
     *
     * @param file 交接文件
     * @return 验证码信息
     * @throws IOException 缺关键字段
     */
    static LoginCaptcha readCaptcha(Path file) throws IOException {
        Map<String, String> values = HandoffFile.read(file);
        for (String key : List.of("token", "gt", "challenge")) {
            if (values.get(key) == null || values.get(key).isBlank()) {
                throw new IOException("交接文件缺少 " + key + "：" + file.toAbsolutePath()
                        + "（请用辅助页生成的 7 行内容覆盖整个文件）");
            }
        }
        LoginCaptcha captcha = new LoginCaptcha();
        captcha.setType("geetest");
        captcha.setToken(values.get("token"));
        GeetestInfo geetest = new GeetestInfo();
        geetest.setGt(values.get("gt"));
        geetest.setChallenge(values.get("challenge"));
        captcha.setGeetest(geetest);
        return captcha;
    }

    // ------------------------------------------------------------------ 小工具

    /** 凭据的最低自洽性检查（不联网也能判，失败多半是解析层的问题） */
    private static void assertValidCredential(LoginCredential credential) {
        assertNotNull(credential, "登录成功必须返回凭据");
        assertFalse(credential.getCookieHeader().isBlank(), "Cookie 头不能为空");
        assertTrue(credential.getCookieHeader().contains("SESSDATA="), "凭据必须含 SESSDATA（缺它等于没登录）");
        assertNotNull(credential.getDedeUserId(), "DedeUserID 应被解析出来");
        assertFalse(credential.isExpired(), "刚拿到的凭据不该已过期");
    }

    /**
     * 读登录账号。<b>三种来源，按优先级</b>：交接文件 → VM 参数 → 环境变量。
     *
     * <p>把文件排最前，是因为在 IDEA 里改 Run Configuration 的 VM 参数比"编辑一个文件"麻烦得多，
     * 而这条链路本来就已经有两个交接文件，多一个最自然。
     *
     * @return 账号（手机号或邮箱），已去首尾空白
     * @throws IOException 三处都没有（交互模式下会先打开文件等人填，超时才抛）
     */
    private static String readAccount() throws IOException {
        return readCredential("account", "BILI_ACCOUNT", "账号（手机号或邮箱）");
    }

    /**
     * 读登录密码（刻意不在控制台回显、也不写进日志；库不会留存）。
     *
     * <p>⚠️ 从<b>交接文件</b>读时首尾空白会被去掉（交接文件按 {@code key=value} 行解析）。
     * 密码首尾真有空格的话，请改用 {@code -Dbili.password=} 传。
     *
     * @return 密码原文
     * @throws IOException 三处都没有（交互模式下会先打开文件等人填，超时才抛）
     */
    private static String readPassword() throws IOException {
        return readCredential("password", "BILI_PASSWORD", "密码");
    }

    /**
     * 凭据的统一读取入口。
     *
     * <p><b>交互模式下缺凭据不报错，而是"把文件摆到人面前再等"</b>：先补出模板
     * （<b>已填过就不动</b>，见 {@link HandoffFile#seedIfAbsent}）、用系统默认编辑器打开、
     * 轮询等待用户填完保存，最多 {@link #WAIT_FOR_HUMAN_MS}。
     * 这样一次运行就能走完全程，而不是"报错 → 去填 → 重跑"来回三趟 ——
     * 2026-09-16 用户正是为此白跑了两轮，而这两轮本可以省掉。
     *
     * <p>回放模式（JUnit，无人值守）<b>不等人</b>：直接抛错，由调用方 {@code assumeTrue} 跳过。
     *
     * @param key       交接文件里的键（同时用于拼 VM 参数名 {@code bili.<key>}）
     * @param env       环境变量名
     * @param humanName 提示用语里的人类叫法（如"账号（手机号或邮箱）"）
     * @return 凭据值
     * @throws IOException 回放模式下拿不到，或交互模式下等到超时
     */
    private static String readCredential(String key, String env, String humanName) throws IOException {
        String value = credentialFromSources(key, env);
        if (value != null) {
            return value;
        }
        if (!interactive) {
            seedCredentialTemplate();
            throw new IOException(missingCredentialHint());
        }

        boolean seeded = seedCredentialTemplate();
        System.out.printf("%n==================== 请填写登录凭据 ====================%n");
        System.out.printf("没读到%s，%s：%s%n", humanName,
                seeded ? "已生成模板并打开" : "已打开", ACCOUNT_HANDOFF.toAbsolutePath());
        System.out.printf("把 %s 填到对应行上并保存（Ctrl+S），我会自动继续（最长等 %d 秒）%n",
                humanName, WAIT_FOR_HUMAN_MS / 1000);
        System.out.println("两行一起填好再保存最省事：account 与 password 各等一轮，一次保存就都满足了");
        System.out.println("提醒：本文件在本机是明文（该目录已 gitignore，不会入库），用完记得清空");
        System.out.println("========================================================");
        openFileQuietly(ACCOUNT_HANDOFF);

        return waitForCredential(key, env, humanName);
    }

    /** 凭据三来源取第一个非空值：交接文件 → VM 参数 {@code -Dbili.<key>=} → 环境变量 */
    private static String credentialFromSources(String key, String env) throws IOException {
        return firstNonBlank(
                HandoffFile.value(ACCOUNT_HANDOFF, key),
                System.getProperty("bili." + key),
                System.getenv(env));
    }

    /**
     * 补出凭据模板。
     *
     * <p>🔴 必须用 {@code seedIfAbsent}：这里<b>绝不能</b>覆盖写入 ——
     * 用户可能已经填了 account 只是漏了 password，覆盖会把他填的值一起抹掉。
     *
     * @return 是否真的写了模板
     */
    private static boolean seedCredentialTemplate() {
        try {
            return HandoffFile.seedIfAbsent(ACCOUNT_HANDOFF, ACCOUNT_TEMPLATE);
        } catch (IOException e) {
            // 写不进去不致命：用户可以自己建这个文件（提示里已经给了绝对路径）
            System.out.printf("（凭据模板初始化失败，请手动创建 %s：%s）%n",
                    ACCOUNT_HANDOFF.toAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * 轮询凭据文件，直到出现指定键的值。
     *
     * @param key       键
     * @param env       环境变量名
     * @param humanName 提示用语
     * @return 凭据值
     * @throws IOException 超时仍未填
     */
    private static String waitForCredential(String key, String env, String humanName) throws IOException {
        long deadline = System.currentTimeMillis() + WAIT_FOR_HUMAN_MS;
        while (true) {
            String value = credentialFromSources(key, env);
            if (value != null) {
                System.out.printf("[%tT] 已读到%s%n", System.currentTimeMillis(), humanName);
                return value;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("等待 " + WAIT_FOR_HUMAN_MS / 1000 + " 秒仍未读到" + humanName
                        + "（" + ACCOUNT_HANDOFF.toAbsolutePath() + "）。填好后重跑即可。");
            }
            sleep(1000);
        }
    }

    /**
     * 凭据是否已具备（供 JUnit 门控：缺凭据时<b>跳过</b>而不是失败）。
     *
     * <p>不触发模板落盘、不等人 —— 门控判断本身不该有副作用。
     *
     * @param key 交接文件里的键
     * @return 是否已有非空值
     */
    static boolean hasCredential(String key) {
        String env = "password".equals(key) ? "BILI_PASSWORD" : "BILI_ACCOUNT";
        try {
            return credentialFromSources(key, env) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** 凭据缺失时的统一提示（回放模式用；交互模式走"打开文件 + 等人"那条路） */
    private static String missingCredentialHint() {
        return "未提供登录凭据。三种给法任选一种（推荐第 ① 种，IDE 里直接跑，不用配任何 VM 参数）：\n"
                + "  ① 填进 " + ACCOUNT_HANDOFF.toAbsolutePath() + "（模板已写好）\n"
                + "       account=手机号或邮箱\n"
                + "       password=你的密码（短信登录用不到 password，只填 account）\n"
                + "  ② VM 参数：-Dbili.account=手机号或邮箱 -Dbili.password=你的密码\n"
                + "  ③ 环境变量：BILI_ACCOUNT / BILI_PASSWORD";
    }

    /** 取第一个非空白项；都没有则返回 {@code null} */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 空白等价于"没读到"。
     *
     * <p>交接文件里 {@code ua=} 留空是常见形态（辅助页抓不到时就是空值），
     * 不能把它当成"用户显式指定了一个空 UA"。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 打码后再打印：账号/手机号会进控制台与日志，没必要完整露出 */
    private static String mask(String secret) {
        if (secret == null || secret.length() < 6) {
            return "***";
        }
        return secret.substring(0, 3) + "****" + secret.substring(secret.length() - 2);
    }

    /** 尝试用系统默认浏览器打开；失败只提示，地址已打印 */
    private static void openQuietly(URI uri) {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return;
            }
            Desktop.getDesktop().browse(uri);
            System.out.println("（已尝试自动打开浏览器；没弹出来的话手动打开上面的地址）");
        } catch (Exception e) {
            System.out.printf("（自动打开失败：%s；手动打开上面的地址即可）%n",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 用系统默认程序打开一个文件（通常是编辑器），失败只提示 —— 路径已经打印过了。
     *
     * <p>用 {@link Desktop.Action#OPEN} 而不是 {@code BROWSE}：{@code browse} 会把它丢给浏览器，
     * 一个 {@code .txt} 在浏览器里既不好编辑、也不会触发我们等待的"保存"动作。
     *
     * @param file 要打开的文件（调用前应已存在，否则 Windows 会弹"找不到文件"）
     */
    private static void openFileQuietly(Path file) {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return;
            }
            Desktop.getDesktop().open(file.toFile());
            System.out.println("（已尝试用系统默认编辑器打开；没弹出来的话手动打开上面的路径）");
        } catch (Exception e) {
            System.out.printf("（自动打开失败：%s；手动打开上面的路径即可）%n",
                    e.getClass().getSimpleName());
        }
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待人工操作被中断", e);
        }
    }

    /** 只在提示信息里用一次端点地址，避免为它把 endpoint 常量再引一遍 */
    private static final class BilibiliEndpointProbe {
        private static String captchaUrl() {
            return com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint.passportCaptchaUrl;
        }
    }
}

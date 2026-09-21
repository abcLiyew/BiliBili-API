/*
 * 真浏览器凭据桥（CDP / Chrome DevTools Protocol）—— 零新增依赖版本。
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 为什么需要它
 * ─────────────────────────────────────────────────────────────────────────
 * 本库的登录三条链路（扫码 / 密码 / 短信）都由 Java 侧 POST 创建登录态，服务端因此在
 * 「新设备登录提醒」里写「未知设备」——排查结论是：服务端读不到「浏览器环境」，
 * 与 UA / Client Hints 无关（补齐后仍不变）。同源证据：扫码登录显示「Edge」，
 * 因为那条链路的登录态是手机端确认的，服务端不评估我们的 POST。
 *
 * Cookie 刷新（refresh_token -> correspond -> RSA/SHA-256）同样只能在浏览器里跑：
 * 那是 www.bilibili.com 上一个隐藏 iframe + 前端 WASM 的流程，纯 HTTP 到不了
 * （2026-09-16 实测 30 组组合全 404）。
 *
 * 两个问题指向同一个答案：**让登录与刷新都发生在真浏览器里，本工具只负责把凭据取出来。**
 * 库侧零改动 —— HttpPolicy.setCookie(...) 本来就接受任意来源的 Cookie。
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 为什么用 CDP 而不是 Playwright / Selenium
 * ─────────────────────────────────────────────────────────────────────────
 * 1. 零新增 Maven 依赖：JDK 17 自带 java.net.http.WebSocket，直接用即可。
 *    Playwright Java 会把浏览器二进制（~300MB）塞进依赖树，对一个「数据接口客户端」库
 *    是不可接受的负担 —— 而且这个工具根本不需要驱动浏览器做任何交互。
 * 2. 不驱动、只附着：人在自己惯用的浏览器里正常登录，我们只把 cookie 读走。
 *    这正是「未知设备」的解药（环境是真的），也避开了自动化特征被识别的风险。
 * 3. 独立于主库：本文件不进 src/main，不参与 mvn 构建，无需 pom 改动。
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 怎么用（两步；浏览器由本工具自己拉起，不必手拼长命令）
 * ─────────────────────────────────────────────────────────────────────────
 * ① 取凭据。端点没人监听时，本工具会用独立 profile **自动拉起 Edge** 并等它就绪：
 *
 *    java tools/cdp-cookie-bridge/CdpCookieBridge.java cookies .workbuddy/bili-cookie.txt
 *
 * ② 在弹出的那个窗口里正常登录一次（扫码 / 密码都行，就是普通人登录），再跑一次上面那条。
 *
 * 其它两个子命令：
 *    java tools/cdp-cookie-bridge/CdpCookieBridge.java check
 *    java tools/cdp-cookie-bridge/CdpCookieBridge.java refresh 8 .workbuddy/bili-cookie.txt
 *
 * 换浏览器：--browser=chrome|auto（缺省 edge）；也可 --browser-exe=<完整路径> 指定。
 * 只要附着、不要自动拉起：--no-launch。换 profile：--profile=<目录>。
 *
 * ③ 交给库（已有能力，无需改代码 —— 本库只消费凭据，不负责获取）：
 *    HttpPolicy.setCookie(Files.readString(Path.of(".workbuddy/bili-cookie.txt")));
 *    new Login().getCredentialStatus();
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 输出为什么是纯 ASCII
 * ─────────────────────────────────────────────────────────────────────────
 * 本工具会在 Windows 控制台 / Git Bash / IDE 终端里被反复调用，这些环境的 stdout
 * 编码不一致（GBK / UTF-8 都有）。中文输出在错误的那种编码下就是乱码，而排障时
 * 看到乱码等于没输出。所以：注释用中文（给人读），运行时输出一律 ASCII（给终端读）。
 */
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CdpCookieBridge {

    /** 默认调试端点。Edge/Chrome 用 --remote-debugging-port=9222 启动后监听在这里。 */
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:9222";

    /** 默认目标域（后缀匹配）。换站点用 {@code --domain=<后缀>} 覆盖，如 {@code --domain=example.com} */
    private static final String DEFAULT_DOMAIN = "bilibili.com";

    /** 默认目标站点（开/复用页签用）。换站点用 {@code --site=<url>} 覆盖 */
    private static final String DEFAULT_SITE = "https://www.bilibili.com";

    /** 本次运行的目标域 */
    private static volatile String targetDomain = DEFAULT_DOMAIN;

    /** 本次运行的目标站点 */
    private static volatile String targetSite = DEFAULT_SITE;

    /** 默认要拉起的浏览器：{@code edge} / {@code chrome} / {@code auto}；用 {@code --browser=} 覆盖 */
    private static final String DEFAULT_BROWSER = "edge";

    /** 独立 profile 目录名（Chromium 136+ 拒绝在日常 profile 上开 DevTools，所以必须独立） */
    private static final String PROFILE_DIR_NAME = "cdp-harvest-profile";

    /**
     * 本次运行要拉起的浏览器。
     *
     * <p>默认 {@code edge} —— 只影响"端点没人监听时本工具去起哪一个"；
     * 已经有一个带调试端口的浏览器在跑时，本选项不起作用（直接附着，不另起一个）。
     */
    private static volatile String browserChoice = DEFAULT_BROWSER;

    /** 本次运行的独立 profile 目录；{@code --profile=<dir>} 覆盖 */
    private static volatile String profileOverride = null;

    /** 本次运行的浏览器可执行文件；{@code --browser-exe=<path>} 覆盖（优先级最高） */
    private static volatile String browserExeOverride = null;

    /** 端点不可达时是否自动拉起浏览器；{@code --no-launch} 关闭（退回纯探测） */
    private static volatile boolean autoLaunch = true;

    private CdpCookieBridge() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.out.println();
            System.out.println("[FAIL] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println();
            printHelp();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            rest.add(args[i]);
        }
        String endpoint = System.getProperty("cdp.endpoint", DEFAULT_ENDPOINT);

        // --domain= / --site= 是"全局开关"：倒序摘出来，其余位置参数（秒数、输出文件）顺序不变
        for (int i = rest.size() - 1; i >= 0; i--) {
            String arg = rest.get(i);
            if (arg.startsWith("--domain=")) {
                targetDomain = arg.substring("--domain=".length()).trim();
                rest.remove(i);
            } else if (arg.startsWith("--site=")) {
                targetSite = arg.substring("--site=".length()).trim();
                rest.remove(i);
            } else if (arg.startsWith("--browser=")) {
                browserChoice = arg.substring("--browser=".length()).trim().toLowerCase();
                rest.remove(i);
            } else if (arg.startsWith("--browser-exe=")) {
                browserExeOverride = arg.substring("--browser-exe=".length()).trim();
                rest.remove(i);
            } else if (arg.startsWith("--profile=")) {
                profileOverride = arg.substring("--profile=".length()).trim();
                rest.remove(i);
            } else if ("--no-launch".equals(arg)) {
                autoLaunch = false;
                rest.remove(i);
            }
        }

        switch (command) {
            case "check" -> check(endpoint);
            case "cookies" -> cookies(endpoint, rest);
            case "refresh" -> refresh(endpoint, rest);
            case "help", "-h", "--help" -> printHelp();
            default -> {
                System.out.println("[FAIL] unknown command: " + command);
                printHelp();
                System.exit(1);
            }
        }
    }

    // ==================================================================== 命令

    /** 探测调试端点是否可用，并打印浏览器版本。不起浏览器、不改任何东西。 */
    private static void check(String endpoint) throws Exception {
        System.out.println("=== CDP Cookie Bridge / check ===");
        System.out.println("[endpoint] " + endpoint);
        Map<String, Object> version = ensureBrowser(endpoint);
        System.out.println("[browser ] " + str(version.get("Browser")));
        System.out.println("[ua      ] " + brief(str(version.get("User-Agent")), 110));
        System.out.println("[ws      ] " + str(version.get("webSocketDebuggerUrl")));
        System.out.println();
        System.out.println("[OK] debugging port is reachable.");
    }

    /**
     * 读 bilibili 域下的全部 cookie，拼成 {@code Cookie} 头。
     *
     * <p>用法：{@code cookies [--values] [outFile]}
     */
    private static void cookies(String endpoint, List<String> rest) throws Exception {
        boolean showValues = rest.remove("--values");
        String out = rest.isEmpty() ? null : rest.get(0);

        System.out.println("=== CDP Cookie Bridge / cookies ===");
        try (Cdp cdp = attachBrowser(endpoint)) {
            List<Map<String, Object>> all = readCookies(cdp);
            List<Map<String, Object>> kept = keepTargetDomain(all, System.out);
            if (kept.isEmpty()) {
                System.out.println();
                System.out.printf("[WARN] no %s cookie found.%n", targetDomain);
                System.out.println("       Log into the site inside the debugging browser first,");
                System.out.println("       then run this command again.");
                return;
            }
            String header = toCookieHeader(kept);
            dump(kept, showValues);
            if (out != null) {
                writeCookie(out, header);
            }
            System.out.println();
            System.out.printf("[summary] %d %s cookies, header %d chars%n",
                    kept.size(), targetDomain, header.length());
        }
    }

    /**
     * <b>核心验证命令</b>：打开一个真浏览器页签 → 等待 → 再读一次 cookie → 报告差异。
     *
     * <p>它回答的是那个一直没被验证过的问题：<b>浏览器里的 B 站前端到底会不会自己续期？</b>
     * 如果 {@code SESSDATA} / {@code bili_ticket} 之类在等待期间发生了变化，说明续期在浏览器侧
     * 是可观测、可取走的 —— 那「自动续期」就有了落点（定时跑一次本命令即可）；
     * 如果完全没变化，说明当前会话还没到需要刷新的时机（或刷新只在特定页面触发），
     * 那就得靠 {@code cookie/info} 的 {@code refresh} 标志来决定何时打开页面。
     *
     * <p>用法：{@code refresh [waitSeconds] [--values] [outFile]}
     */
    private static void refresh(String endpoint, List<String> rest) throws Exception {
        int waitSeconds = 8;
        if (!rest.isEmpty() && rest.get(0).matches("\\d+")) {
            waitSeconds = Integer.parseInt(rest.remove(0));
        }
        boolean showValues = rest.remove("--values");
        String out = rest.isEmpty() ? null : rest.get(0);

        System.out.println("=== CDP Cookie Bridge / refresh ===");
        try (Cdp cdp = attachBrowser(endpoint)) {
            List<Map<String, Object>> before = keepTargetDomain(readCookies(cdp), null);
            Map<String, String> beforeMap = indexByName(before);
            System.out.printf("[before] %d %s cookies%n", before.size(), targetDomain);

            String tab = ensureSiteTab(cdp);
            System.out.println("[tab   ] " + tab);
            System.out.printf("[wait  ] %ds ...%n", waitSeconds);
            Thread.sleep(waitSeconds * 1000L);

            List<Map<String, Object>> after = keepTargetDomain(readCookies(cdp), null);
            Map<String, String> afterMap = indexByName(after);
            System.out.printf("[after ] %d %s cookies%n%n", after.size(), targetDomain);

            // 差异分三类：值变了 / 新增 / 消失。值变了才是「续期真的发生了」的证据。
            Set<String> names = new LinkedHashSet<>();
            names.addAll(afterMap.keySet());
            names.addAll(beforeMap.keySet());
            List<Map<String, Object>> rows = new ArrayList<>();
            int changed = 0;
            int added = 0;
            int gone = 0;
            for (String name : names) {
                String b = beforeMap.get(name);
                String a = afterMap.get(name);
                String mark;
                if (b == null) {
                    mark = "NEW";
                    added++;
                } else if (a == null) {
                    mark = "GONE";
                    gone++;
                } else if (!b.equals(a)) {
                    mark = "CHANGED";
                    changed++;
                } else {
                    mark = "=";
                }
                if (!"=".equals(mark)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("domain", domainOf(after, before, name));
                    row.put("mark", mark);
                    row.put("before", b);
                    row.put("after", a);
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                System.out.println("(no cookie changed during the wait window)");
            } else {
                System.out.printf("%-28s %-18s %-9s %s%n", "NAME", "DOMAIN", "CHANGE", "HASH BEFORE -> AFTER");
                for (Map<String, Object> row : rows) {
                    System.out.printf("%-28s %-18s %-9s %s -> %s%n",
                            row.get("name"), row.get("domain"), row.get("mark"),
                            hash((String) row.get("before")), hash((String) row.get("after")));
                    if (showValues) {
                        System.out.printf("    before = %s%n    after  = %s%n", row.get("before"), row.get("after"));
                    }
                }
            }
            System.out.println();
            System.out.printf("[summary] changed=%d new=%d gone=%d%n", changed, added, gone);
            if (changed > 0) {
                System.out.println("[verdict] browser DID renew cookies during the window -> renewal is harvestable.");
            } else {
                System.out.println("[verdict] nothing changed. Either renewal is not due yet, or it is");
                System.out.println("          triggered only on specific pages / after a longer idle.");
                System.out.println("          Ask the server first: Login#getCredentialStatus().isRefreshNeeded()");
                System.out.println("          (nav + cookie/info), and only then open a tab.");
            }

            if (out != null) {
                String header = toCookieHeader(after);
                writeCookie(out, header);
            }
        }
    }

    // ==================================================================== 浏览器交互

    /** 连到 browser 级 WebSocket（可发 Storage / Target 域命令）；端点没人监听时自动拉起 */
    private static Cdp attachBrowser(String endpoint) throws Exception {
        Map<String, Object> version = ensureBrowser(endpoint);
        String wsUrl = str(version.get("webSocketDebuggerUrl"));
        if (wsUrl == null || wsUrl.isBlank()) {
            throw new IOException("no webSocketDebuggerUrl in /json/version");
        }
        try {
            return Cdp.attach(wsUrl);
        } catch (Exception wsFailed) {
            // 实测遇到过：端口 accepting 但永不回包（半死的浏览器实例）。原样往上抛是个
            // "TimeoutException: null"，看不懂；这里把它翻译成可执行的建议。
            throw new IOException("browser on " + endpoint + " answered /json/version but its"
                    + " DevTools socket is not responding (" + wsFailed.getClass().getSimpleName()
                    + "). Usually a stale/hung browser instance: close that window and re-run"
                    + " -- the tool will launch a fresh one.", wsFailed);
        }
    }

    // ---------------------------------------------------------------- 自动拉起

    /**
     * 取得 {@code /json/version}；端点不可达且允许自动拉起时，先起浏览器再重试。
     *
     * <p>这一步就是"简化操作"的全部：使用者不必再手工拼那条带 {@code --user-data-dir} 的
     * 长命令 —— 而那条命令是必须的（Chromium 136+ 会拒绝在日常 profile 上开 DevTools），
     * 正是最容易抄错的地方。
     *
     * <p>已经有浏览器挂在调试端口上时<b>不会</b>另起一个（那是"附着"，也是正常路径）。
     */
    private static Map<String, Object> ensureBrowser(String endpoint) throws Exception {
        try {
            return getJson(endpoint + "/json/version");
        } catch (Exception probeFailed) {
            String reason = probeReason(probeFailed);
            if (!autoLaunch) {
                throw new IOException("no browser on " + endpoint + " (" + reason
                        + "); start one yourself, or drop --no-launch to let this tool do it",
                        probeFailed);
            }
            System.out.println("[probe  ] no browser on " + endpoint + " (" + reason + ")");
            launchBrowser(endpoint);
            return waitForEndpoint(endpoint);
        }
    }

    /** 把探测失败翻译成一行可读原因（{@code ConnectException} 的 message 是 null，别直接印）。 */
    private static String probeReason(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + brief(message, 70);
    }

    /** 用独立 profile 拉起浏览器（默认 Edge），立刻返回、不等进程结束。 */
    private static void launchBrowser(String endpoint) throws Exception {
        Path exe = resolveBrowserExecutable();
        Path profile = profileDir();
        Files.createDirectories(profile);

        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("--remote-debugging-port=" + portOf(endpoint));
        cmd.add("--user-data-dir=" + profile);
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add(targetSite);

        System.out.println("[launch ] " + exe.getFileName() + "  (--browser=" + browserChoice + ")");
        System.out.println("[profile] " + profile + "   <- dedicated, your daily browser is untouched");
        System.out.println("[cmd    ] " + String.join(" ", cmd));

        new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** 轮询端点直到就绪（最多 20s）。Chromium 冷启动 + 首次建 profile 需要几秒。 */
    private static Map<String, Object> waitForEndpoint(String endpoint) throws Exception {
        int attempts = 40;
        for (int i = 1; i <= attempts; i++) {
            Thread.sleep(500L);
            try {
                Map<String, Object> version = getJson(endpoint + "/json/version");
                System.out.printf("[ready  ] %s (after %.1fs)%n",
                        str(version.get("Browser")), i * 0.5);
                return version;
            } catch (Exception notYet) {
                // 还没起来：继续等
            }
        }
        throw new IOException("browser did not expose " + endpoint
                + " within " + (attempts / 2) + "s");
    }

    /** 定位可执行文件：{@code --browser-exe=} 优先，其次平台候选路径，最后扫 {@code PATH}。 */
    private static Path resolveBrowserExecutable() throws IOException {
        if (browserExeOverride != null && !browserExeOverride.isBlank()) {
            Path explicit = Path.of(browserExeOverride.trim());
            if (!Files.isRegularFile(explicit)) {
                throw new IOException("--browser-exe not found: " + browserExeOverride);
            }
            return explicit;
        }
        for (String kind : browserOrder()) {
            for (String candidate : installCandidates(kind)) {
                Path path = Path.of(candidate);
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
            Path onPath = onPath(kind);
            if (onPath != null) {
                return onPath;
            }
        }
        throw new IOException("cannot find " + browserChoice + " executable; "
                + "use --browser-exe=<full path> or --browser=chrome|auto");
    }

    /** 默认 Edge；{@code auto} 依次试 Edge -> Chrome，找得到哪个用哪个。 */
    private static List<String> browserOrder() {
        if ("chrome".equals(browserChoice)) {
            return List.of("chrome");
        }
        if ("auto".equals(browserChoice)) {
            return List.of("edge", "chrome");
        }
        return List.of("edge");
    }

    /** 各平台常见安装位置。返回的路径<b>不保证存在</b>，由调用方逐个探测。 */
    private static List<String> installCandidates(String kind) {
        List<String> out = new ArrayList<>();
        String rel = kind.equals("edge")
                ? "Microsoft\\Edge\\Application\\msedge.exe"
                : "Google\\Chrome\\Application\\chrome.exe";
        if (isWindows()) {
            addIfPresent(out, env("ProgramFiles(x86)"), rel);
            addIfPresent(out, env("ProgramFiles"), rel);
            addIfPresent(out, env("LOCALAPPDATA"), rel);
        } else if (isMac()) {
            out.add(kind.equals("edge")
                    ? "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
                    : "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        } else {
            List<String> bins = kind.equals("edge")
                    ? List.of("microsoft-edge", "microsoft-edge-stable")
                    : List.of("google-chrome", "google-chrome-stable", "chromium");
            for (String bin : bins) {
                out.add("/usr/bin/" + bin);
                out.add("/usr/local/bin/" + bin);
            }
        }
        return out;
    }

    /** 扫 {@code PATH} 找可执行名（Linux/macOS 上比固定路径可靠）。 */
    private static Path onPath(String kind) {
        String pathVar = env("PATH");
        if (pathVar == null || pathVar.isBlank()) {
            return null;
        }
        List<String> names = kind.equals("edge")
                ? List.of("msedge.exe", "msedge", "microsoft-edge", "microsoft-edge-stable")
                : List.of("chrome.exe", "chrome", "google-chrome", "google-chrome-stable");
        for (String dir : pathVar.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 独立 profile 目录；{@code --profile=} 覆盖。默认落在用户目录下，绝不碰日常浏览器。 */
    private static Path profileDir() {
        if (profileOverride != null && !profileOverride.isBlank()) {
            return Path.of(profileOverride.trim());
        }
        String local = env("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, PROFILE_DIR_NAME);
        }
        return Path.of(System.getProperty("user.home", "."), "." + PROFILE_DIR_NAME);
    }

    private static String portOf(String endpoint) {
        try {
            int port = URI.create(endpoint).getPort();
            return port > 0 ? String.valueOf(port) : "9222";
        } catch (RuntimeException e) {
            return "9222";
        }
    }

    private static void addIfPresent(List<String> out, String base, String rel) {
        if (base != null && !base.isBlank()) {
            out.add(base + java.io.File.separator + rel);
        }
    }

    private static String env(String key) {
        try {
            return System.getenv(key);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** 读全部 cookie（browser 级 {@code Storage.getCookies}，跨所有域，与当前页无关） */
    private static List<Map<String, Object>> readCookies(Cdp cdp) throws Exception {
        Map<String, Object> result = cdp.call("Storage.getCookies", "{}");
        return asList(result.get("cookies"));
    }

    /**
     * 确保有一个目标站点页签处于活跃状态。
     *
     * <p>优先复用已有页签并 reload —— 直接 createTarget 会在长时间运行里堆出一串页签，
     * 而且已加载的页面本来就在跑站点自己的前端 JS。reload 是「让前端再跑一遍」最省的动作。
     */
    private static String ensureSiteTab(Cdp cdp) throws Exception {
        Map<String, Object> targets = cdp.call("Target.getTargets", "{}");
        String targetId = null;
        String url = null;
        for (Map<String, Object> t : asList(targets.get("targetInfos"))) {
            if (!"page".equals(str(t.get("type")))) {
                continue;
            }
            String u = str(t.get("url"));
            if (u != null && u.contains(targetDomain)) {
                targetId = str(t.get("targetId"));
                url = u;
                break;
            }
        }
        if (targetId == null) {
            Map<String, Object> created = cdp.call("Target.createTarget",
                    "{\"url\":\"" + targetSite + "\"}");
            return "created new tab " + str(created.get("targetId"));
        }
        cdp.call("Target.activateTarget", "{\"targetId\":\"" + targetId + "\"}");
        // attach(flatten) 拿到 sessionId 后才能在 page 上下文里发 Page.reload
        Map<String, Object> attached = cdp.call("Target.attachToTarget",
                "{\"targetId\":\"" + targetId + "\",\"flatten\":true}");
        String sessionId = str(attached.get("sessionId"));
        if (sessionId != null) {
            try {
                cdp.call("Page.reload", "{}", sessionId);
            } catch (Exception e) {
                // reload 失败不影响主流程：页面本身可能已在刷新，或 target 正在关闭
                System.out.println("[warn  ] Page.reload failed: " + e.getMessage());
            }
        }
        return "reused " + brief(url, 80);
    }

    // ==================================================================== Cookie 处理

    /** 只保留目标域下的 cookie，并按 name 去重（同名的优先取最宽泛的域） */
    private static List<Map<String, Object>> keepTargetDomain(List<Map<String, Object>> all,
                                                              java.io.PrintStream echo) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        int expired = 0;
        int offDomain = 0;
        for (Map<String, Object> c : all) {
            String cookieDomain = str(c.get("domain"));
            if (cookieDomain == null
                    || !(cookieDomain.equals(targetDomain) || cookieDomain.endsWith("." + targetDomain))) {
                offDomain++;
                continue;
            }
            double expires = num(c.get("expires"), -1);
            if (expires > 0 && expires * 1000 < now) {
                expired++;
                continue;
            }
            String name = str(c.get("name"));
            if (name == null) {
                continue;
            }
            Map<String, Object> old = byName.get(name);
            if (old == null || domainRank(cookieDomain) < domainRank(str(old.get("domain")))) {
                byName.put(name, c);
            }
        }
        if (echo != null) {
            System.out.printf("[filter ] total=%d kept=%d off-domain=%d expired=%d%n",
                    all.size(), byName.size(), offDomain, expired);
        }
        List<Map<String, Object>> kept = new ArrayList<>(byName.values());
        kept.sort(Comparator.comparing(c -> str(c.get("name"))));
        return kept;
    }

    /** 域名宽泛度：{@code .<域>}（0，最宽）< {@code <域>}（1）< 子域（2） */
    private static int domainRank(String cookieDomain) {
        if (("." + targetDomain).equals(cookieDomain)) {
            return 0;
        }
        if (targetDomain.equals(cookieDomain)) {
            return 1;
        }
        return 2;
    }

    private static Map<String, String> indexByName(List<Map<String, Object>> cookies) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map<String, Object> c : cookies) {
            map.put(str(c.get("name")), str(c.get("value")));
        }
        return map;
    }

    private static String domainOf(List<Map<String, Object>> after, List<Map<String, Object>> before, String name) {
        for (List<Map<String, Object>> list : List.of(after, before)) {
            for (Map<String, Object> c : list) {
                if (name.equals(str(c.get("name")))) {
                    return str(c.get("domain"));
                }
            }
        }
        return "?";
    }

    private static String toCookieHeader(List<Map<String, Object>> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : cookies) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(str(c.get("name"))).append('=').append(str(c.get("value")));
        }
        return sb.toString();
    }

    private static void dump(List<Map<String, Object>> cookies, boolean showValues) {
        System.out.println();
        System.out.printf("%-30s %-20s %-6s %-10s %s%n", "NAME", "DOMAIN", "LEN", "HASH", "FLAGS");
        for (Map<String, Object> c : cookies) {
            String value = str(c.get("value"));
            List<String> flags = new ArrayList<>();
            if (Boolean.TRUE.equals(c.get("httpOnly"))) {
                flags.add("httpOnly");
            }
            if (Boolean.TRUE.equals(c.get("secure"))) {
                flags.add("secure");
            }
            if (Boolean.TRUE.equals(c.get("session"))) {
                flags.add("session");
            }
            System.out.printf("%-30s %-20s %-6d %-10s %s%n",
                    str(c.get("name")), str(c.get("domain")), value == null ? 0 : value.length(),
                    hash(value), String.join(",", flags));
            if (showValues) {
                System.out.printf("    value = %s%n", value);
            }
        }
    }

    /** 落盘为 {@code Cookie} 头一行（与 .workbuddy/bili-cookie.txt 的既有格式一致） */
    private static void writeCookie(String out, String header) throws IOException {
        Path path = Path.of(out);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(path)) {
            // 留一份上一版，凭据失效时能对照「到底哪一项变了」
            Files.copy(path, Path.of(out + ".bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(path, header, StandardCharsets.UTF_8);
        System.out.printf("[write ] %s (%d chars, previous kept as %s.bak)%n", out, header.length(), out);
    }

    // ==================================================================== 小工具

    private static Map<String, Object> getJson(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
        }
        Object parsed = MiniJson.parse(response.body());
        if (!(parsed instanceof Map)) {
            throw new IOException("unexpected body from " + url);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(value instanceof List)) {
            return out;
        }
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double num(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static String brief(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 值不能进日志 —— 只打 8 位摘要，够用来判断「变了没有」，不足以还原凭据 */
    private static String hash(String value) {
        if (value == null) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "??";
        }
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("CDP Cookie Bridge - harvest site cookies from a REAL browser");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java CdpCookieBridge.java check                        probe the debugging port");
        System.out.println("  java CdpCookieBridge.java cookies [--values] [out]     dump + save current cookies");
        System.out.println("  java CdpCookieBridge.java refresh [sec] [--values] [out]");
        System.out.println("                                                         open a tab, wait, diff cookies");
        System.out.println();
        System.out.println("Global options:");
        System.out.println("  --domain=<suffix>   cookie domain to keep (default: " + DEFAULT_DOMAIN + ")");
        System.out.println("  --site=<url>        page to open / reload  (default: " + DEFAULT_SITE + ")");
        System.out.println("  --browser=<name>    edge|chrome|auto to launch (default: " + DEFAULT_BROWSER + ")");
        System.out.println("  --browser-exe=<p>   explicit browser executable (wins over --browser)");
        System.out.println("  --profile=<dir>     dedicated profile dir for the launched browser");
        System.out.println("  --no-launch         attach only; never start a browser");
        System.out.println();
        System.out.println("No browser on the port? This tool launches one with a dedicated profile");
        System.out.println("(REQUIRED: Chromium 136+ refuses DevTools on a daily-use profile).");
        System.out.println("Default profile dir: " + profileDir());
    }

    // ==================================================================== CDP 会话

    /** 一个 CDP WebSocket 会话：同步风格的 {@code call(method, paramsJson)} */
    private static final class Cdp implements AutoCloseable {

        private final WebSocket ws;
        private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        private final StringBuilder acc = new StringBuilder();

        private Cdp(WebSocket ws) {
            this.ws = ws;
        }

        static Cdp attach(String wsUrl) throws Exception {
            AtomicReference<Cdp> self = new AtomicReference<>();
            CompletableFuture<Cdp> ready = new CompletableFuture<>();

            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    Cdp session = new Cdp(webSocket);
                    self.set(session);
                    ready.complete(session);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    Cdp session = self.get();
                    if (session != null) {
                        session.onChunk(data.toString(), last);
                    }
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    ready.completeExceptionally(error);
                    Cdp session = self.get();
                    if (session != null) {
                        session.failAll(error);
                    }
                }
            };

            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(wsUrl), listener);

            return ready.get(10, TimeUnit.SECONDS);
        }

        /**
         * 累积分片并整条派发。
         *
         * <p>CDP 的响应可能是几十 KB（{@code Storage.getCookies} 全量返回时尤其如此），
         * 必然分片；只有 {@code last == true} 才是完整一条消息，提前解析会拿到半截 JSON。
         */
        private void onChunk(String text, boolean last) {
            acc.append(text);
            if (!last) {
                return;
            }
            String message = acc.toString();
            acc.setLength(0);
            handle(message);
        }

        private void handle(String message) {
            Object parsed;
            try {
                parsed = MiniJson.parse(message);
            } catch (Exception e) {
                return;   // 不是合法 JSON：忽略（可能是事件消息或异常分片）
            }
            if (!(parsed instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            Object idValue = map.get("id");
            if (!(idValue instanceof Number)) {
                return;   // 事件消息（只有 method），没有等待者
            }
            CompletableFuture<Map<String, Object>> waiter = pending.remove(((Number) idValue).intValue());
            if (waiter != null) {
                waiter.complete(map);
            }
        }

        private void failAll(Throwable error) {
            for (CompletableFuture<Map<String, Object>> waiter : pending.values()) {
                waiter.completeExceptionally(error);
            }
            pending.clear();
        }

        Map<String, Object> call(String method, String paramsJson) throws Exception {
            return call(method, paramsJson, null);
        }

        Map<String, Object> call(String method, String paramsJson, String sessionId) throws Exception {
            int id = seq.incrementAndGet();
            CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
            pending.put(id, waiter);

            StringBuilder sb = new StringBuilder("{\"id\":").append(id)
                    .append(",\"method\":\"").append(method).append('"');
            if (sessionId != null) {
                sb.append(",\"sessionId\":\"").append(sessionId).append('"');
            }
            if (paramsJson != null) {
                sb.append(",\"params\":").append(paramsJson);
            }
            sb.append('}');

            ws.sendText(sb.toString(), true).join();
            Map<String, Object> response = waiter.get(30, TimeUnit.SECONDS);

            Object error = response.get("error");
            if (error != null) {
                throw new IOException("CDP " + method + " failed: " + error);
            }
            Object result = response.get("result");
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) result;
                return typed;
            }
            return Map.of();
        }

        @Override
        public void close() {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
            } catch (Exception ignored) {
                // 关闭失败无所谓：进程退出时连接自然断开
            }
        }
    }

    // ==================================================================== 最小 JSON 解析

    /**
     * 只够本工具用的 JSON 解析（~120 行）。
     *
     * <p>刻意不引 fastjson：本工具必须在「没有 Maven 依赖树」的前提下直接
     * {@code java CdpCookieBridge.java} 跑起来 —— 一旦引第三方库，就得先配 classpath，
     * 那正是这个工具想避开的东西。CDP 的响应只用得上 object / array / string / number /
     * bool / null，实现全部六种即可。
     */
    private static final class MiniJson {

        private final String src;
        private int pos;

        private MiniJson(String src) {
            this.src = src;
        }

        static Object parse(String text) {
            MiniJson parser = new MiniJson(text);
            parser.skipWhitespace();
            Object value = parser.readValue();
            parser.skipWhitespace();
            if (parser.pos != text.length()) {
                throw new IllegalArgumentException("trailing content at " + parser.pos);
            }
            return value;
        }

        private Object readValue() {
            char c = peek();
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;                       // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(":");
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or } at " + (pos - 1));
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;                       // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + (pos - 1));
                }
            }
        }

        private String readString() {
            if (next() != '"') {
                throw new IllegalArgumentException("expected \" at " + (pos - 1));
            }
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + esc);
                }
            }
        }

        private Double readNumber() {
            int start = pos;
            while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("expected number at " + start);
            }
            return Double.valueOf(src.substring(start, pos));
        }

        private void expect(String literal) {
            if (!src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("expected " + literal + " at " + pos);
            }
            pos += literal.length();
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return src.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}

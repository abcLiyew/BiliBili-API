package com.esdllm.bilibiliApi.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * <b>仅供测试使用</b>：把 {@link BilibiliHttp} 的出站请求拦截到本机 HttpServer，让 fixture 驱动
 * 单元测试。生产代码不得引用本类。
 *
 * <p>使用方式（{@code try-with-resources}）：
 * <pre>{@code
 * try (MockBiliServer mock = MockBiliServer.start()
 *         .register("/x/web-interface/view?bvid=BV1xx", "{...json...}")
 *         .register("/x/web-interface/card?mid=123", "{...json...}")) {
 *     // ...调门面或 Service，所有出站请求都被劫到本机
 *     VideoInfo v = VideoService.INSTANCE.getVideoInfo("BV1xx");
 *     assertEquals("标题", v.getTitle());
 * }
 * }</pre>
 *
 * <p><b>设计要点</b>：
 * <ul>
 *   <li>启动时随机端口 + 后台线程池 0 个线程（即同步处理，fixture 简单可控）；</li>
 *   <li>构造时<b>自动</b>调 {@link BilibiliHttp#setTestBaseUrl(String)} 把生产 host 改写为
 *       本机地址，并调 {@link HttpPolicy#setMinRequestIntervalMs(long)} 关限流（避免
 *       fixture 测试被 400 ms 间隔拖慢）；</li>
 *   <li>{@link #close()} <b>反向还原</b>上面两个状态，避免污染其他测试；</li>
 *   <li>{@link #register(String, String)} 注册"路径+query → 响应体"，匹配是
 *       {@link String#startsWith(String)}，所以同 query 不同 aid 的请求可以用同一个
 *       路径前缀注册（典型场景：{@code /x/web-interface/view?bvid=}）。</li>
 *   <li>返回的响应体一律当作 {@code application/json;charset=UTF-8} 返回，
 *       HTTP 状态固定 200，body 即 fixture ——与"成功响应"形态完全一致。</li>
 *   <li>{@link #registerSequence(String, String...)} 支持<b>按次返回不同响应</b>，
 *       {@link #hitCount(String)} 用于断言"底层被调了几次"（验证重试行为用）。</li>
 *   <li>{@link #registerWithSetCookie(String, String, String...)} 额外带上
 *       {@code Set-Cookie} 响应头 —— 用于验证"凭据只在 Cookie 里下发"的登录场景。</li>
 *   <li>{@link #requestBody(String)} / {@link #formParams(String)} / {@link #formField(String, String)}
 *       读回<b>请求体</b> —— 登录类接口全是 POST form，"参数是否真的传对"必须能断言
 *       （例如密码密文能否用服务端私钥解回 {@code hash + 明文}）。</li>
 *   <li>{@link #requestHeader(String, String)} 读回<b>请求头</b> —— 锁"出站形状"用。
 *       UA / Accept / Referer 配错了不报错，只在服务端表现为可疑；而
 *       "显式指定的 UA 有没有真的发出去"尤其只能靠它断言（匿名身份是缓存的，
 *       若 UA 在生成身份时就固化，{@code setUserAgent} 会静默失效）。</li>
 * </ul>
 *
 * <p><b>注意</b>：未注册的路径会返回 HTTP 404 —— 这正好是 {@link BilibiliHttp} 的
 * {@code NO_RETRY} 分支，方便测试错误路径。
 *
 * @author esdllm
 */
public final class MockBiliServer implements AutoCloseable {

    private final HttpServer server;
    private final String baseUrl;
    private final Map<String, Route> routes = new HashMap<>();
    private final long savedMinIntervalMs;

    /** 路由：JSON fixture body（可为序列）、或 302 + Location、或带 Set-Cookie 的响应；并记录命中次数 */
    private static final class Route {
        final String body;
        final String redirectTo;
        /** 非 null 时按序返回：第 n 次命中给 {@code sequence[min(n, len-1)]}（最后一次重复） */
        final String[] sequence;
        /** 额外下发的 {@code Set-Cookie} 头（每个元素一个头），可为 null */
        final List<String> setCookies;
        /** 本次响应的 HTTP 状态码，默认 200（见 {@link #registerStatus}） */
        final int status;
        /** 命中次数（含重定向），供测试断言"底层确实被调了几次" */
        final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        /**
         * 最后一次命中的<b>请求体原文</b>（GET 为空串）。
         *
         * <p>登录类接口全是 POST form，而"参数是不是真的传对了"是它们最容易出错、
         * 又最难从响应反推的地方（服务端只回一句"参数错误"）。
         * 记下来才能在测试里断言语义 —— 例如 base64 密文能否用服务端私钥解回原文。
         */
        volatile String lastBody = "";

        /**
         * 最后一次命中的<b>请求头</b>（键统一小写，同名的多个头只留第一个）。
         *
         * <p>为什么要记它：出站 UA / Accept / Referer 这类"伪装形状"改错了<b>不会有任何报错</b>，
         * 只会在服务端那侧变成风控或误判。要断言"显式指定的 UA 真的发出去了"（而不是
         * 被缓存的匿名身份覆盖掉），就必须能读回请求头 —— 否则只能是"看着代码以为生效了"。
         */
        volatile Map<String, String> lastHeaders = Map.of();

        Route(String body) {
            this(body, null, null, null, 200);
        }

        Route(String body, String redirectTo) {
            this(body, redirectTo, null, null, 200);
        }

        Route(String body, String[] sequence) {
            this(body, null, sequence, null, 200);
        }

        Route(String body, String redirectTo, String[] sequence, List<String> setCookies) {
            this(body, redirectTo, sequence, setCookies, 200);
        }

        Route(String body, String redirectTo, String[] sequence, List<String> setCookies, int status) {
            this.body = body;
            this.redirectTo = redirectTo;
            this.sequence = sequence;
            this.setCookies = setCookies;
            this.status = status;
        }

        /** 取第 {@code n} 次（0 基）命中的响应体；序列越界则重复最后一个 */
        String bodyAt(int n) {
            if (sequence == null || sequence.length == 0) {
                return body;
            }
            return sequence[Math.min(n, sequence.length - 1)];
        }
    }

    private MockBiliServer(HttpServer server, String baseUrl) {
        this.server = server;
        this.baseUrl = baseUrl;
        this.savedMinIntervalMs = HttpPolicy.getMinRequestIntervalMs();
        server.createContext("/", this::handle);
        // 注意：setExecutor 已在 start() 之前调过了；start 之后再调会抛 "server already started"。
    }

    /**
     * 启动本机 server，并把 {@link BilibiliHttp} 的出站请求劫到这里。
     */
    public static MockBiliServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // setExecutor 必须在 start() 之前；JDK 的 HttpServer 文档明说：
            // "IllegalStateException if this server has been started"
            s.setExecutor(Executors.newSingleThreadExecutor());
            s.start();
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            BilibiliHttp.setTestBaseUrl(base);
            // 关限流：fixture 测试要快，不要被默认 400ms 间隔拖慢
            HttpPolicy.setMinRequestIntervalMs(0L);
            return new MockBiliServer(s, base);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动本机测试 server：" + e.getMessage(), e);
        }
    }

    /**
     * 注册路径匹配规则 + 响应体。
     *
     * @param pathPrefix 形如 {@code /x/web-interface/view?bvid=}，匹配是 startsWith
     * @param body       响应体（通常为 fixture JSON）
     * @return 本 server，支持链式注册
     */
    public MockBiliServer register(String pathPrefix, String body) {
        routes.put(Objects.requireNonNull(pathPrefix), new Route(Objects.requireNonNull(body)));
        return this;
    }

    /**
     * 注册路径 → <b>指定 HTTP 状态码</b> + 响应体。
     *
     * <p><b>为什么非要有它</b>：{@link #register(String, String)} 固定回 200，而"HTTP 非 2xx"
     * 恰恰是出站最需要能测的一类分支 —— {@code 412} 表示出口被风控（要换身份/换出口）、
     * {@code 5xx} 表示服务端故障（要重试），两者处置完全不同；而它们在<b>业务码层面都表现为
     * "body 里没有 code 字段"</b>，只靠 body 造不出来，必须能真的改状态码。
     *
     * @param pathPrefix 路径前缀（匹配规则同 {@link #register(String, String)}）
     * @param status     HTTP 状态码，如 {@code 412} / {@code 500}
     * @param body       响应体（可为空串 —— 顺带覆盖"非 2xx 且响应体为空"的情形）
     * @return 本 server
     */
    public MockBiliServer registerStatus(String pathPrefix, int status, String body) {
        routes.put(Objects.requireNonNull(pathPrefix),
                new Route(Objects.requireNonNull(body), null, null, null, status));
        return this;
    }

    /**
     * 注册路径 → 响应体 + 一组 {@code Set-Cookie} 响应头。
     *
     * <p>存在的意义：登录"凭据只在 Cookie 里下发"这个形态<b>用 {@link #register(String, String)}
     * 造不出来</b>（那个方法只会发 JSON，没有响应头），而它是扫码登录必须能处理的分支之一。
     *
     * @param pathPrefix 路径前缀
     * @param body       响应体（可为空串 —— 顺带覆盖"响应体为空但 Cookie 带凭据"的情形）
     * @param setCookies 每个元素是一个独立的 {@code Set-Cookie} 头
     * @return 本 server
     */
    public MockBiliServer registerWithSetCookie(String pathPrefix, String body, String... setCookies) {
        Objects.requireNonNull(setCookies);
        routes.put(Objects.requireNonNull(pathPrefix),
                new Route(Objects.requireNonNull(body), null, null, List.of(setCookies)));
        return this;
    }

    /**
     * 注册 302 重定向到 {@code location}（{@link com.esdllm.bilibiliApi.bilibiliApi.ShortChain}
     * 路径专用 —— 它读 {@code Location} header 拿真实地址）。
     *
     * @param pathPrefix  形如 {@code /shortLink/}
     * @param locationTo  重定向目标地址（{@code Location} header 值）
     * @return 本 server
     */
    public MockBiliServer registerRedirect(String pathPrefix, String locationTo) {
        routes.put(Objects.requireNonNull(pathPrefix), new Route(null, Objects.requireNonNull(locationTo)));
        return this;
    }

    /**
     * 注册<b>序列响应</b>：第 n 次命中返回 {@code bodies[min(n, len-1)]}（即最后一次会重复）。
     *
     * <p>用途是验证"重试/轮换后拿到不同结果"这类行为 —— 例如动态列表第一次返回空 items、
     * 换身份后第二次返回有内容。单用 {@link #register(String, String)} 只能返回恒定响应，
     * 无法证明"重试真的发生了且结果被采用"。
     *
     * @param pathPrefix 路径前缀（同 {@link #register(String, String)}）
     * @param bodies     依次返回的响应体，至少 1 个
     * @return 本 server
     */
    public MockBiliServer registerSequence(String pathPrefix, String... bodies) {
        Objects.requireNonNull(bodies);
        if (bodies.length == 0) {
            throw new IllegalArgumentException("registerSequence 至少需要 1 个 body");
        }
        routes.put(Objects.requireNonNull(pathPrefix), new Route(null, bodies.clone()));
        return this;
    }

    /**
     * 该路径前缀被命中的次数（未注册则 0）。
     *
     * <p>断言"底层到底调了几次"用 —— 例如验证空列表重试时，期望是 2 次。
     *
     * @param pathPrefix 注册时用的同一个 key
     * @return 命中次数
     */
    public int hitCount(String pathPrefix) {
        Route route = routes.get(pathPrefix);
        return route == null ? 0 : route.hits.get();
    }

    /** 测试基地址，{@code http://127.0.0.1:PORT}，无尾斜杠。 */
    public String baseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------ 请求体断言（POST form 专用）

    /**
     * 该路径<b>最后一次</b>命中的请求体原文（GET 请求为空串）。
     *
     * <p>用途是断言语义而不只是"发没发"：例如断言提交的 {@code password} 能被服务端私钥
     * 解回 {@code hash + 明文}（见 {@code LoginServiceTest} 的 RSA 闭环用例）。
     *
     * @param pathPrefix 注册时用的同一个 key
     * @return 请求体原文；未命中过或 GET 时返回空串
     */
    public String requestBody(String pathPrefix) {
        Route route = routes.get(pathPrefix);
        return route == null || route.lastBody == null ? "" : route.lastBody;
    }

    /**
     * 该路径最后一次命中的<b>表单字段</b>（{@code application/x-www-form-urlencoded}，已 URL 解码）。
     *
     * @param pathPrefix 注册时用的同一个 key
     * @return 字段表（保持报文里的顺序）；无请求体时为空表
     */
    public Map<String, String> formParams(String pathPrefix) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        String body = requestBody(pathPrefix);
        if (body.isEmpty()) {
            return params;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(urlDecode(key), urlDecode(value));
        }
        return params;
    }

    /**
     * 取一个表单字段的值（{@link #formParams(String)} 的快捷方式）。
     *
     * @param pathPrefix 注册时用的同一个 key
     * @param name       字段名
     * @return 字段值；不存在时返回 {@code null}
     */
    public String formField(String pathPrefix, String name) {
        return formParams(pathPrefix).get(name);
    }

    /** 读空请求体（GET）返回空串；读失败也返回空串 —— 它是测试辅助，不该把主流程搞挂 */
    private static String readBody(HttpExchange exchange) {
        try (java.io.InputStream in = exchange.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            return raw.length == 0 ? "" : new String(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 请求头断言

    /**
     * 该路径最后一次命中的某个<b>请求头</b>（键名不区分大小写）。
     *
     * <p>用途是"锁住出站形状"：UA / Accept / Referer 这类东西配错了不报错，
     * 只会让请求在服务端看起来不像浏览器。典型用例是
     * <b>"显式指定 UA 后，出站 UA 必须是它"</b> —— 匿名身份是<b>缓存</b>的，
     * 若 UA 在生成身份时就固化，{@code setUserAgent} 会静默失效，而这是最难查的一类缺陷。
     *
     * @param pathPrefix 注册时用的同一个 key
     * @param name       头名，如 {@code "User-Agent"}
     * @return 该头的第一个值；未命中过或没有该头时返回 {@code null}
     */
    public String requestHeader(String pathPrefix, String name) {
        Route route = routes.get(pathPrefix);
        if (route == null || name == null) {
            return null;
        }
        return route.lastHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 把请求头拍成"小写键 → 第一个值"的表（够断言用；同名多值只留第一个） */
    private static Map<String, String> snapshotHeaders(HttpExchange exchange) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                headers.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), values.get(0));
            }
        }
        return headers;
    }

    /** form 里的 {@code +} 是空格；{@code URLDecoder} 已被 JDK 标记为"表单解码"，正合适 */
    private static String urlDecode(String raw) {
        try {
            return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String fullPath = exchange.getRequestURI().toString();
        // 请求体要先读完：登录类接口全是 POST form，留痕后测试才能断言"参数真的传对了"
        String requestBody = readBody(exchange);
        // 找最长前缀匹配（防止 /x 抢 /x/... 的精确匹配）
        Route matched = null;
        int bestLen = -1;
        for (Map.Entry<String, Route> e : routes.entrySet()) {
            if (fullPath.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                matched = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        if (matched != null) {
            matched.lastBody = requestBody;
            matched.lastHeaders = snapshotHeaders(exchange);
        }
        if (matched != null && matched.redirectTo != null) {
            matched.hits.incrementAndGet();
            // 302 + Location
            exchange.getResponseHeaders().add("Location", matched.redirectTo);
            exchange.sendResponseHeaders(302, -1);
            exchange.getResponseBody().close();
            return;
        }
        byte[] body;
        int status;
        if (matched != null) {
            // 先取本次序号再自增：序列路由据此决定给第几个 body
            int n = matched.hits.getAndIncrement();
            if (matched.setCookies != null) {
                for (String cookie : matched.setCookies) {
                    exchange.getResponseHeaders().add("Set-Cookie", cookie);
                }
            }
            body = matched.bodyAt(n).getBytes(StandardCharsets.UTF_8);
            status = matched.status;
        } else {
            String errJson = "{\"code\":-404,\"message\":\"fixture not registered: " + fullPath + "\"}";
            body = errJson.getBytes(StandardCharsets.UTF_8);
            status = 404;
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * 停 server 并还原 BilibiliHttp / HttpPolicy 的状态。
     */
    @Override
    public void close() {
        try {
            server.stop(0);
        } catch (Exception ignored) {
            // server 已停或被并发打断：吞掉，不影响测试结果
        }
        BilibiliHttp.clearTestBaseUrl();
        HttpPolicy.setMinRequestIntervalMs(savedMinIntervalMs);
    }
}

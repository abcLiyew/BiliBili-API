package com.esdllm.bilibiliApi.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    /** 路由：JSON fixture body（可为序列）、或 302 + Location；并记录命中次数 */
    private static final class Route {
        final String body;
        final String redirectTo;
        /** 非 null 时按序返回：第 n 次命中给 {@code sequence[min(n, len-1)]}（最后一次重复） */
        final String[] sequence;
        /** 命中次数（含重定向），供测试断言"底层确实被调了几次" */
        final java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();

        Route(String body) {
            this.body = body;
            this.redirectTo = null;
            this.sequence = null;
        }

        Route(String body, String redirectTo) {
            this.body = body;
            this.redirectTo = redirectTo;
            this.sequence = null;
        }

        Route(String[] sequence) {
            this.body = null;
            this.redirectTo = null;
            this.sequence = sequence;
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
        routes.put(Objects.requireNonNull(pathPrefix), new Route(bodies.clone()));
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

    private void handle(HttpExchange exchange) throws IOException {
        String fullPath = exchange.getRequestURI().toString();
        // 找最长前缀匹配（防止 /x 抢 /x/... 的精确匹配）
        Route matched = null;
        int bestLen = -1;
        for (Map.Entry<String, Route> e : routes.entrySet()) {
            if (fullPath.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                matched = e.getValue();
                bestLen = e.getKey().length();
            }
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
            body = matched.bodyAt(n).getBytes(StandardCharsets.UTF_8);
            status = 200;
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
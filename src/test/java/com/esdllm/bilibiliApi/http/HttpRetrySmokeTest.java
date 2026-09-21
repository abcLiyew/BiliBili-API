package com.esdllm.bilibiliApi.http;

import com.sun.net.httpserver.HttpServer;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冒烟测试：证明<b>重试退避 / 限流 / 身份轮换</b>确实在跑，而不只是"代码写在那里"。
 *
 * <pre>
 * mvn -o -B test -Dtest=HttpRetrySmokeTest -Dbili.smoke=true
 * </pre>
 *
 * <p>做法：用 JDK 自带的 {@link HttpServer} 在 127.0.0.1 起一个"按剧本返回"的假服务，
 * 让 {@link BilibiliHttp} 真去打它 —— 这样验证的是真实的连接、真实的超时、真实的退避。
 * 放在 127.0.0.1 上，不碰任何外部服务。
 *
 * <p>默认跳过（{@code -Dbili.smoke=true} 才跑），因为它会做时序断言（等待退避），
 * 不适合塞进日常离线单测。
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("冒烟：重试退避 / 限流 / 身份轮换（本地假服务）")
class HttpRetrySmokeTest {

    private static final String OK_BODY = "{\"code\":0,\"data\":{}}";

    private final AtomicInteger hits = new AtomicInteger();
    private final List<Integer> servedStatuses = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        HttpPolicy.reset();
        RateLimiter.reset();
        hits.set(0);
        servedStatuses.clear();
    }

    /**
     * 起一个假服务：先按 {@code headStatuses} 依次返回，之后固定返回 {@code tailStatus}。
     *
     * @param tailStatus    剧本用完后固定返回的状态码
     * @param tailBody      剧本用完后固定返回的响应体
     * @param headStatuses  前几次返回的状态码（依次消耗）
     * @return 可访问的 URL
     */
    private String start(int tailStatus, String tailBody, int... headStatuses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            int index = hits.getAndIncrement();
            int status = index < headStatuses.length ? headStatuses[index] : tailStatus;
            servedStatuses.add(status);

            // 风控页返回 HTML，其它返回 JSON —— 与线上实际形态一致
            String body = status == 412 ? "<html>blocked</html>" : tailBody;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type",
                    status == 412 ? "text/html" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    /** 让退避幅度小、确定，便于断言 */
    private void deterministicBackoff() {
        HttpPolicy.setMinRequestIntervalMs(0L);
        HttpPolicy.setJitterRatio(0.0d);
        RateLimiter.reset();
    }

    @Test
    @DisplayName("503 会被退避重试直到成功：2 次 503 + 1 次 200 = 恰好 3 次请求")
    void retriesGatewayErrorWithBackoff() throws IOException {
        deterministicBackoff();
        HttpPolicy.setMaxAttempts(4);
        HttpPolicy.setBaseDelayMs(120L);
        HttpPolicy.setMaxDelayMs(1_000L);

        String url = start(200, OK_BODY, 503, 503);

        long start = System.currentTimeMillis();
        HttpResponse<String> response = BilibiliHttp.get(url);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(200, response.getStatus(), "最终应拿到成功响应");
        assertEquals(3, hits.get(), "应为 3 次请求，实际状态序列=" + servedStatuses);
        // 退避 120 + 240 = 360ms，留足余量只断言下界
        assertTrue(elapsed >= 300L, "应发生退避等待（约 360ms），实测 " + elapsed + "ms");
    }

    @Test
    @DisplayName("429 限流会被重试（4xx 里的瞬态例外）")
    void retriesRateLimitStatus() throws IOException {
        deterministicBackoff();
        HttpPolicy.setMaxAttempts(3);
        HttpPolicy.setBaseDelayMs(60L);

        String url = start(200, OK_BODY, 429);

        HttpResponse<String> response = BilibiliHttp.get(url);

        assertEquals(200, response.getStatus());
        assertEquals(2, hits.get(), "429 后应重试一次，实际状态序列=" + servedStatuses);
    }

    @Test
    @DisplayName("4101139 不重试：只打一次就原样返回")
    void nonRetryableRequestsOnce() throws IOException {
        deterministicBackoff();
        HttpPolicy.setMaxAttempts(4);
        HttpPolicy.setBaseDelayMs(10L);

        String url = start(200, "{\"code\":4101139,\"message\":\"请求数据发生错误\"}");

        HttpResponse<String> response = BilibiliHttp.get(url);

        assertEquals(200, response.getStatus());
        assertEquals(1, hits.get(), "不可重试的错误只能请求一次，实际 " + hits.get() + " 次");
        assertTrue(response.getBody().contains("4101139"), "响应应原样交给上层做语义化报错");
    }

    @Test
    @DisplayName("命中 412 风控：轮换一次身份后重试，随后放弃（恰好 2 次请求、代数 +1）")
    void rotatesIdentityOnceOnRiskControl() throws IOException {
        deterministicBackoff();
        HttpPolicy.setMaxAttempts(3);
        HttpPolicy.setMaxRotations(1);
        HttpPolicy.setBaseDelayMs(10L);

        int generationBefore = AnonymousSession.generation();
        String url = start(412, "<html>blocked</html>");

        HttpResponse<String> response = BilibiliHttp.get(url);

        assertEquals(412, response.getStatus(), "轮换用尽后应把风控响应原样返回");
        assertEquals(2, hits.get(), "应为 首请求 + 轮换后重试 = 2 次，实际 " + hits.get() + " 次");
        assertEquals(generationBefore + 1, AnonymousSession.generation(),
                "应恰好轮换一代匿名身份");
    }

    @Test
    @DisplayName("关掉身份轮换后，风控只请求一次（策略开关有效）")
    void noRotationMeansNoRetryOnRiskControl() throws IOException {
        deterministicBackoff();
        HttpPolicy.setMaxAttempts(3);
        HttpPolicy.setRotateOnRiskControl(false);

        String url = start(412, "<html>blocked</html>");

        BilibiliHttp.get(url);

        assertEquals(1, hits.get(), "关闭轮换后不应重试，实际 " + hits.get() + " 次");
    }

    @Test
    @DisplayName("全局最小间隔生效：3 次请求被摊开到约 2 个间隔")
    void rateLimitPacesRequests() throws IOException {
        HttpPolicy.setMaxAttempts(1);
        HttpPolicy.setMinRequestIntervalMs(150L);
        RateLimiter.reset();

        String url = start(200, OK_BODY);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            BilibiliHttp.get(url);
        }
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, hits.get());
        // 150ms × (3-1) = 300ms，留 20% 余量
        assertTrue(elapsed >= 240L, "限流应把 3 次请求摊开到约 300ms，实测 " + elapsed + "ms");
    }
}

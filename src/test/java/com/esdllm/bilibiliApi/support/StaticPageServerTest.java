package com.esdllm.bilibiliApi.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试期工具：{@link StaticPageServer} 的回归。
 *
 * <p><b>为什么值得单独测</b>：这个类存在的唯一理由就是"让页面走 http 而不是 {@code file://}"。
 * 如果哪天它被改成返回 {@code file:} 地址、或者绑到了 {@code 0.0.0.0}，
 * 症状会是"极验加载不出来"（浏览器控制台报 CORS / ERR_FILE_NOT_FOUND），
 * 而**不会**有任何 Java 侧异常 —— 正是那种只表现为"用户说不好使"的失败。
 * 所以这里把三件事锁死：内容逐字一致、地址必须是 127.0.0.1 的 http、关掉后端口真的不可用。
 *
 * <p>纯本地、不联网、不受 {@code -Dbili.smoke} 门控，默认就执行（同 {@code QrImagesTest}）。
 *
 * @author 饿死的流浪猫
 */
@DisplayName("测试工具：本地 http 静态页服务")
class StaticPageServerTest {

    @Test
    @DisplayName("① 页面能被 http 取回，且内容逐字一致（含中文）")
    void contentByteForByte(@TempDir Path dir) throws Exception {
        Path page = dir.resolve("demo.html");
        String html = "<!DOCTYPE html><html><meta charset=\"utf-8\"><body>中文 &amp; <b>标签</b></body></html>";
        Files.writeString(page, html, StandardCharsets.UTF_8);

        try (StaticPageServer server = StaticPageServer.serving(page)) {
            assertEquals(html, get(server.url()), "取回的内容必须与磁盘上的文件逐字一致");
        }
    }

    @Test
    @DisplayName("② 地址必须是 http://127.0.0.1:端口/文件名 —— 不能是 file://，也不能绑 0.0.0.0")
    void bindsLoopbackOnly(@TempDir Path dir) throws Exception {
        Path page = dir.resolve("bili-geetest.html");
        Files.writeString(page, "x", StandardCharsets.UTF_8);

        try (StaticPageServer server = StaticPageServer.serving(page)) {
            String url = server.url();
            // file:// 会让极验的协议相对 URL 解析成 file://static.geetest.com/... → 必然加载失败
            assertTrue(url.startsWith("http://127.0.0.1:"), "必须是 http 且绑回环地址：" + url);
            assertTrue(url.endsWith("/bili-geetest.html"), "URL 末尾应是文件名：" + url);
        }
    }

    @Test
    @DisplayName("③ 响应头带 no-store —— 缓存住旧页面会让一次性 challenge 对不上，报 2406")
    void noStoreHeader(@TempDir Path dir) throws Exception {
        Path page = dir.resolve("demo.html");
        Files.writeString(page, "x", StandardCharsets.UTF_8);

        try (StaticPageServer server = StaticPageServer.serving(page)) {
            HttpURLConnection conn = open(server.url());
            try {
                assertEquals(200, conn.getResponseCode());
                assertTrue(String.valueOf(conn.getHeaderField("Cache-Control")).contains("no-store"),
                        "必须禁止缓存：" + conn.getHeaderField("Cache-Control"));
            } finally {
                conn.disconnect();
            }
        }
    }

    @Test
    @DisplayName("④ 文件不存在要立刻报错，而不是起一个永远 404 的服务")
    void missingFileThrows(@TempDir Path dir) {
        Path missing = dir.resolve("nope.html");

        IOException e = assertThrows(IOException.class, () -> StaticPageServer.serving(missing));

        assertTrue(e.getMessage().contains("nope.html"), "错误信息要指出是哪个文件：" + e.getMessage());
    }

    @Test
    @DisplayName("⑤ 关掉之后端口真的不可用 —— 不能每跑一次就漏一个后台服务")
    void closedServerUnreachable(@TempDir Path dir) throws Exception {
        Path page = dir.resolve("demo.html");
        Files.writeString(page, "x", StandardCharsets.UTF_8);

        String url;
        try (StaticPageServer server = StaticPageServer.serving(page)) {
            url = server.url();
            assertEquals("x", get(url));
        }

        assertThrows(IOException.class, () -> get(url), "close() 之后应当连不上：" + url);
    }

    // ------------------------------------------------------------------ 小工具

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        return conn;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}

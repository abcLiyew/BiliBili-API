package com.esdllm.bilibiliApi.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试期工具：把一份本地 HTML（或任意静态文件）用
 * {@code http://127.0.0.1:<随机端口>/<文件名>} 提供出去（<b>仅测试代码使用</b>）。
 *
 * <p><b>为什么必须走 http 而不是 file://</b>（2026-09-16 用户真机反馈，三个报错同一根因）：
 * <ol>
 *   <li>极验的 {@code gt.js} 内部用<b>协议相对 URL</b>
 *       （{@code //static.geetest.com/static/js/fullpage.0.0.0.js}）去加载后续脚本。
 *       在 {@code file://} 下，协议相对 URL 会继承文档的 scheme，被解析成
 *       {@code file://static.geetest.com/...} —— 浏览器跑去本地磁盘找这个路径，
 *       于是 {@code net::ERR_FILE_NOT_FOUND}；</li>
 *   <li>更根本的是 {@code file://} 文档的 origin 是 <b>{@code null}</b>，
 *       任何跨源资源都会被直接拦掉：
 *       {@code Access to script at 'file://static.geetest.com/...' from origin 'null'
 *       has been blocked by CORS policy}；</li>
 *   <li>极验还会用 iframe，而 {@code file://} 下每个文件都被当成彼此独立的安全源，
 *       于是出现 {@code Unsafe attempt to load URL file:///... from frame with URL file:///...}。</li>
 * </ol>
 * 换成 http 之后三个问题一并消失：scheme 变成 {@code http}，协议相对 URL 解析成
 * {@code http://static.geetest.com/...}（已实测该地址返回 200，{@code fullpage.0.0.0.js} 338795 字节），
 * 且 origin 变成真实的 {@code http://127.0.0.1:<port>}。
 *
 * <p><b>只绑回环、端口由系统分配</b>：绑定 {@code 127.0.0.1}（不是 {@code 0.0.0.0}，不对外暴露），
 * 端口取 0 由系统分配，既不写死端口（避免与本机已有服务冲突），也不会泄漏到局域网。
 *
 * <p><b>为什么放进 {@code support} 而不是主源码</b>：这是"把页面送到浏览器"的联调脚手架，
 * 不是库的能力 —— {@code Login} 门面的职责止于"给出 {@code gt}/{@code challenge}"，
 * 浏览器那一侧归调用方（见 {@code LoginPasswordSmokeTest} 的边界说明）。
 *
 * @author 饿死的流浪猫
 */
public final class StaticPageServer implements AutoCloseable {

    /** 固定用 IPv4 回环：{@link InetAddress#getLoopbackAddress()} 在部分机器上会给 {@code ::1}，
     * 那时拼出来的 URL 不是浏览器好用的形式，显式取 127.0.0.1 更稳。 */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final HttpServer server;
    private final String url;

    private StaticPageServer(HttpServer server, String url) {
        this.server = server;
        this.url = url;
    }

    /**
     * 启动一个只服务单个文件的本地 http 服务。
     *
     * @param file 要提供的文件（文件名即 URL 路径）
     * @return 已启动的服务；用完必须 {@link #close()}，建议 try-with-resources
     * @throws IOException 文件不可读 / 校验失败 / 绑定端口失败
     */
    public static StaticPageServer serving(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isReadable(absolute)) {
            throw new IOException("要提供的文件不可读（还没生成？）：" + absolute);
        }
        String name = absolute.getFileName().toString();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 0);
        server.createContext("/" + name, exchange -> serve(exchange, absolute));
        server.setExecutor(null); // 单线程足够：整条链路只会有人打开这一个页面
        server.start();

        return new StaticPageServer(server,
                "http://" + LOOPBACK_HOST + ":" + server.getAddress().getPort() + "/" + name);
    }

    /**
     * 该文件在本地 http 服务上的地址，直接丢给浏览器即可。
     *
     * @return 形如 {@code http://127.0.0.1:52341/bili-geetest.html}
     */
    public String url() {
        return url;
    }

    /** 停掉服务（{@code 0} = 不等待在途请求）。重复调用无副作用。 */
    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * 读文件并原样返回。
     *
     * <p>{@code Cache-Control: no-store} 是刻意的：每次重跑都会重新申请验证码、重写这个页面，
     * 而 {@code challenge} 是一次性的 —— 一旦被浏览器缓存住，你过验的就是上一轮那份，
     * 提交时报 {@code 2406}，且看上去像网络故障。
     *
     * @param exchange 请求
     * @param file     要返回的文件
     * @throws IOException 读文件或写响应失败
     */
    private static void serve(HttpExchange exchange, Path file) throws IOException {
        try (exchange) {
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}

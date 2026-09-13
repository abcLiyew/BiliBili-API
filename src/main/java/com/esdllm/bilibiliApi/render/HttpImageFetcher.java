package com.esdllm.bilibiliApi.render;

import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.http.AnonymousSession;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 小图片抓取器（头像 / 正文配图 / 表情贴图）。
 *
 * <p>刻意不用 Unirest 或 Apache HttpClient：这里只要"拿到字节流解码成图"，
 * JDK 自带的 {@link HttpURLConnection} 足够，也不引入额外连接池语义。
 *
 * <p><b>两个关键优化</b>（9 图样本实测得出的）：
 * <ol>
 *   <li><b>向 CDN 索取缩放后的图</b>：B 站图片 CDN 支持 {@code @<w>w_<h>h_1c.<ext>} 后缀，
 *       同一张原图 250KB 变成 200×200 裁剪版 6.6KB（小 38 倍），解码后的内存占用同样大幅下降
 *       （原图 4284×5712 解码要 ~98MB）。</li>
 *   <li><b>并发预取</b>：串行下载 9 张大图会把单次渲染拖到分钟级，预取改成并发后耗时取决于最慢一张。</li>
 * </ol>
 *
 * <p>失败一律返回 {@code null}（渲染器画占位块），<b>绝不抛异常打断整张长图的渲染</b> ——
 * 一张配图挂掉不应该让整条动态推不出去。
 *
 * <p>缓存是"类级 + 有上限 + 同步"的：不动门面实例字段（README 鼓励多线程调用门面），
 * 上限防止长时间运行时图片无限堆积。
 */
@Slf4j
public final class HttpImageFetcher {

    /** 图片缓存上限（张） */
    private static final int CACHE_LIMIT = 256;

    /**
     * 预取并发度。
     *
     * <p>这就是图片侧的限流手段：同时最多 8 条连接，不会形成"一次开几十条"的突发。
     * 这里<b>刻意不接</b> {@code http.RateLimiter} 的全局最小间隔 —— 图片走的是 CDN，
     * 与 API 端点的风控面不是一回事；若把 9 张图也按 400ms 串行摊开，
     * 单次渲染要多等约 3.6 秒，收益却接近于零。超时统一取 {@code HttpPolicy}。
     */
    private static final int PREFETCH_THREADS = 8;

    private static final Map<String, BufferedImage> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    private static volatile boolean enabled = true;

    private HttpImageFetcher() {
    }

    /**
     * 全局开关。无网络环境（单元测试）可关掉，此时所有请求直接返回 null。
     *
     * @param value true 允许联网抓图
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * 一张待抓取的图：地址 + 期望尺寸。
     *
     * @param url   图片地址
     * @param hintW 期望宽度（像素），&lt;=0 表示不缩放
     * @param hintH 期望高度（像素），&lt;=0 表示只按宽度等比缩放
     */
    public record Request(String url, int hintW, int hintH) {

        /** 方形居中裁剪 */
        public static Request square(String url, int size) {
            return new Request(url, size, size);
        }

        /** 只限宽度、等比缩放 */
        public static Request width(String url, int width) {
            return new Request(url, width, -1);
        }

        /** 原图 */
        public static Request raw(String url) {
            return new Request(url, -1, -1);
        }
    }

    /**
     * 并发预取一批图片，填充缓存。之后 {@link #fetch(String)} 会直接命中。
     *
     * @param requests 待抓取清单
     */
    public static void prefetch(Collection<Request> requests) {
        if (!enabled || requests == null || requests.isEmpty()) {
            return;
        }
        List<Request> pending = requests.stream()
                .filter(r -> r != null && r.url() != null && !r.url().isEmpty())
                .filter(r -> !CACHE.containsKey(key(r)))
                .toList();
        if (pending.isEmpty()) {
            return;
        }

        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PREFETCH_THREADS, pending.size()));
        try {
            List<Future<Void>> futures = pending.stream()
                    .map(r -> pool.submit((Callable<Void>) () -> {
                        load(r);
                        return null;
                    }))
                    .toList();
            for (Future<Void> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 单张超时不影响其余图片
                }
            }
        } finally {
            pool.shutdownNow();
        }
        log.debug("图片预取完成：{} 张，耗时 {} ms", pending.size(), System.currentTimeMillis() - start);
    }

    /**
     * 抓一张图（走缓存）。
     *
     * @param url 图片地址（建议先经 {@code RenderModelLoader#normalizeUrl} 规范化）
     * @return 解码后的图片；失败返回 null
     */
    public static BufferedImage fetch(String url) {
        return fetch(Request.raw(url));
    }

    /**
     * 抓一张图（走缓存）。
     *
     * @param request 地址 + 期望尺寸
     * @return 解码后的图片；失败返回 null
     */
    public static BufferedImage fetch(Request request) {
        if (!enabled || request == null || request.url() == null || request.url().isEmpty()) {
            return null;
        }
        String key = key(request);
        BufferedImage cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        return load(request);
    }

    private static BufferedImage load(Request request) {
        String key = key(request);
        BufferedImage cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String url = withSizeHint(request);
        HttpURLConnection conn = null;
        long start = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) open(new URL(url));
            conn.setConnectTimeout(HttpPolicy.getConnectTimeoutMs());
            conn.setReadTimeout(HttpPolicy.getSocketTimeoutMs());
            // 用"当前身份"的 UA 与指纹，与 API 请求保持一致（同一身份的画像不能自相矛盾）
            conn.setRequestProperty("User-Agent", AnonymousSession.userAgent());
            conn.setRequestProperty("Accept", BilibiliConfig.accept);
            conn.setRequestProperty("Referer", BilibiliConfig.referer);
            String cookie = AnonymousSession.cookieHeader();
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode();
            if (status != 200) {
                log.debug("抓图失败 HTTP {}：{}", status, url);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                BufferedImage image = ImageIO.read(in);
                if (image != null) {
                    CACHE.put(key, image);
                    log.debug("抓图成功 {}x{} {}ms {}", image.getWidth(), image.getHeight(),
                            System.currentTimeMillis() - start, url);
                }
                return image;
            }
        } catch (Exception e) {
            log.debug("抓图异常（{}ms）{}：{}", System.currentTimeMillis() - start, url, e.toString());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 建立连接：配置了代理就走代理，否则直连。
     *
     * <p>{@link HttpURLConnection} 没有 setProxy，必须在 {@code openConnection(proxy)} 时就决定，
     * 所以这里包一层。
     *
     * @param url 目标地址
     * @return 未连接的 URLConnection
     * @throws java.io.IOException 打开失败
     */
    private static java.net.URLConnection open(URL url) throws java.io.IOException {
        if (HttpPolicy.hasProxy()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(HttpPolicy.getProxyHost(), HttpPolicy.getProxyPort()));
            return url.openConnection(proxy);
        }
        return url.openConnection();
    }

    private static String key(Request request) {
        return request.hintW() + "x" + request.hintH() + "|" + request.url();
    }

    /**
     * 给 B 站图片 CDN 地址加上尺寸后缀。
     *
     * <p>格式来自 B 站 CDN 约定：{@code @<w>w_<h>h_1c.<ext>}（{@code 1c} = 1:1 居中裁剪）。
     * 仅对 {@code hdslb.com} 的 {@code /bfs/} 资源生效，其它域名（如 emoji CDN）原样返回。
     *
     * @param request 请求
     * @return 带后缀的地址
     */
    static String withSizeHint(Request request) {
        String url = request.url();
        if (request.hintW() <= 0 || url == null || !url.contains("hdslb.com/bfs/")) {
            return url;
        }
        // 去掉可能已存在的 @ 后缀
        int at = url.indexOf('@');
        String base = at > 0 ? url.substring(0, at) : url;
        int dot = base.lastIndexOf('.');
        if (dot < 0) {
            return url;
        }
        String ext = base.substring(dot);
        String suffix = request.hintH() > 0
                ? "@" + request.hintW() + "w_" + request.hintH() + "h_1c" + ext
                : "@" + request.hintW() + "w" + ext;
        return base + suffix;
    }
}

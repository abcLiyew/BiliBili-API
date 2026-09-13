package com.esdllm.bilibiliApi.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HttpImageFetcher#withSizeHint} 单测。
 *
 * <p>这个方法决定抓图时向 B 站 CDN 索要的尺寸。实测收益很大：同一张原图
 * 250KB → 200×200 裁剪版 6.6KB（小 38 倍），解码后内存占用同理骤降
 * （原图 4284×5712 解码要约 98MB）。
 *
 * <p>它只对 {@code hdslb.com/bfs/} 资源生效 —— 别的域名（emoji CDN 等）
 * 加了后缀反而会 404，所以"不加"也是必须锁定的行为。
 */
class HttpImageFetcherSizeHintTest {

    private static final String BFS = "https://i0.hdslb.com/bfs/archive/abc.jpg";
    private static final String EMOJI = "https://i0.hdslb.com/bfs/emote/tv_abc.png";
    private static final String CDN = "https://cdn.jsdelivr.net/gh/x/y.png";

    @Test
    @DisplayName("raw（hintW<=0）→ 原样返回，不加任何后缀")
    void raw不加后缀() {
        assertEquals(BFS, HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.raw(BFS)));
    }

    @Test
    @DisplayName("非 hdslb/bfs 资源 → 原样返回（加后缀会让 CDN 404）")
    void 非bfs域名不加后缀() {
        assertEquals(CDN, HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.width(CDN, 300)),
                "jsdelivr 这类 CDN 不认识 B 站的 @ 后缀语法");
        assertEquals("https://i0.hdslb.com/some/other.png",
                HttpImageFetcher.withSizeHint(
                        HttpImageFetcher.Request.width("https://i0.hdslb.com/some/other.png", 300)),
                "同域名但不在 /bfs/ 下也不加");
    }

    @Test
    @DisplayName("square → @{w}w_{h}h_1c{ext}（1:1 居中裁剪）")
    void 方形裁剪() {
        assertEquals("https://i0.hdslb.com/bfs/archive/abc.jpg@200w_200h_1c.jpg",
                HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.square(BFS, 200)));
    }

    @Test
    @DisplayName("width（hintH<=0）→ @{w}w{ext}（只限宽度、等比缩放）")
    void 只限宽度() {
        assertEquals("https://i0.hdslb.com/bfs/archive/abc.jpg@300w.jpg",
                HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.width(BFS, 300)));
    }

    @Test
    @DisplayName("emoji/png 资源同样加后缀，且扩展名取自原地址")
    void png扩展名() {
        assertEquals("https://i0.hdslb.com/bfs/emote/tv_abc.png@20w_20h_1c.png",
                HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.square(EMOJI, 20)));
    }

    @Test
    @DisplayName("地址里已有 @ 后缀 → 先截掉旧后缀再加新的（避免 @a@b 叠加）")
    void 去掉旧后缀() {
        String withOld = "https://i0.hdslb.com/bfs/archive/abc.jpg@100w.jpg";
        assertEquals("https://i0.hdslb.com/bfs/archive/abc.jpg@500w.jpg",
                HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.width(withOld, 500)));
    }

    @Test
    @DisplayName("无扩展名（无 '.'）→ 原样返回，不瞎猜扩展名")
    void 无扩展名() {
        String noExt = "https://i0.hdslb.com/bfs/archive/noext";
        assertEquals(noExt, HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.width(noExt, 300)));
    }

    @Test
    @DisplayName("null url → 原样返回 null（不抛异常）")
    void null地址() {
        assertEquals(null, HttpImageFetcher.withSizeHint(new HttpImageFetcher.Request(null, 200, 200)));
    }

    @Test
    @DisplayName("带 query 的地址 → 原样返回（扩展名含 '?' 判为不可靠，不加后缀）")
    void 带query不加后缀() {
        String q = "https://i0.hdslb.com/bfs/archive/abc.jpg?v=2";
        assertEquals(q, HttpImageFetcher.withSizeHint(HttpImageFetcher.Request.width(q, 300)),
                "含 query 时 lastIndexOf('.') 算出的'扩展名'是 '.jpg?v=2'，不可靠 → 宁可不缩放也不能拼出畸形 URL");
    }

    @Test
    @DisplayName("以 '.' 结尾的地址 → 原样返回（不越界、不拼畸形后缀）")
    void 点结尾() {
        String trailing = "https://i0.hdslb.com/bfs/archive/x.";
        assertEquals(trailing, HttpImageFetcher.withSizeHint(
                HttpImageFetcher.Request.width(trailing, 300)));
    }
}
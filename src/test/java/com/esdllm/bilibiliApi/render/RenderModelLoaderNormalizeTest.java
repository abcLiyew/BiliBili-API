package com.esdllm.bilibiliApi.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RenderModelLoader#normalizeUrl(String)} 单测。
 *
 * <p>它是长图链路里"图片能不能取到"的第一道关：QQ 侧取图对协议敏感，
 * 而 B 站返回的地址混着 {@code http://}、协议相对（{@code //}）和已经是 https 的三种形态。
 * 归一化漏一种，对应那批图就会抓失败、渲染成占位块（而且不报错，很难发现）。
 */
class RenderModelLoaderNormalizeTest {

    @Test
    @DisplayName("null → null（不抛异常）")
    void null输入() {
        assertNull(RenderModelLoader.normalizeUrl(null));
    }

    @Test
    @DisplayName("空串 → 空串（原样返回，由调用方判空）")
    void 空串输入() {
        assertEquals("", RenderModelLoader.normalizeUrl(""));
    }

    @Test
    @DisplayName("协议相对地址 // → 补 https:")
    void 协议相对() {
        assertEquals("https://i0.hdslb.com/bfs/face/a.jpg",
                RenderModelLoader.normalizeUrl("//i0.hdslb.com/bfs/face/a.jpg"));
    }

    @Test
    @DisplayName("http:// → 升为 https://（B 站图片 CDN 实测同时支持两者，但 QQ 侧统一要 https）")
    void http升https() {
        assertEquals("https://i0.hdslb.com/bfs/archive/b.jpg",
                RenderModelLoader.normalizeUrl("http://i0.hdslb.com/bfs/archive/b.jpg"));
    }

    @Test
    @DisplayName("已经是 https:// → 原样返回")
    void https原样() {
        String url = "https://i1.hdslb.com/bfs/new_dyn/c.jpg";
        assertEquals(url, RenderModelLoader.normalizeUrl(url));
    }

    @Test
    @DisplayName("带 query / 端口 / 路径的 http 地址只改协议部分")
    void 只改协议() {
        assertEquals("https://cdn.example.com:8443/a/b.png?x=1&y=2",
                RenderModelLoader.normalizeUrl("http://cdn.example.com:8443/a/b.png?x=1&y=2"));
    }

    @Test
    @DisplayName("非 http(s) 协议原样返回（如 emoji 的 CDN 已是 https，data: 也不动）")
    void 其它协议() {
        assertEquals("data:image/png;base64,AAAA",
                RenderModelLoader.normalizeUrl("data:image/png;base64,AAAA"));
    }

    @Test
    @DisplayName("相对路径原样返回（不臆造 host）")
    void 相对路径() {
        assertEquals("/bfs/face/x.jpg", RenderModelLoader.normalizeUrl("/bfs/face/x.jpg"));
    }
}
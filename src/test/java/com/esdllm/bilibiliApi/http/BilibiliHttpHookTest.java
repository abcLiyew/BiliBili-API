package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link BilibiliHttp#applyTestBaseUrl(String)} 与测试钩子 {@code setTestBaseUrl} 的单测。
 *
 * <p>这是整个 fixture 测试体系的地基 —— {@code MockBiliServer} 之所以能把出站请求
 * 劫到本机，靠的就是这个改写。地基写错的表现会很隐蔽（请求打到真实 B 站、测试却"通过"），
 * 所以边界必须单独锁定。
 *
 * <p>包内可见（{@code static}）所以测试类放在同一包下。
 */
class BilibiliHttpHookTest {

    @AfterEach
    void tearDown() {
        BilibiliHttp.clearTestBaseUrl();
    }

    @Test
    @DisplayName("未设置 base 时原样返回（生产路径，零副作用）")
    void 未设置base() {
        String url = "https://api.bilibili.com/x/web-interface/view?bvid=BV1xx";
        assertEquals(url, BilibiliHttp.applyTestBaseUrl(url),
                "默认 null 必须原样返回 —— 生产行为不能被测试钩子改变");
    }

    @Test
    @DisplayName("base 为空串时也原样返回")
    void 空base() {
        BilibiliHttp.setTestBaseUrl("");
        String url = "https://api.bilibili.com/x/a?b=1";
        assertEquals(url, BilibiliHttp.applyTestBaseUrl(url));
    }

    @Test
    @DisplayName("正常改写：scheme+host+port 被替换，path 与 query 原样保留")
    void 正常改写() {
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18080");
        String url = "https://api.bilibili.com/x/web-interface/view?bvid=BV1xx&p=2";
        assertEquals("http://127.0.0.1:18080/x/web-interface/view?bvid=BV1xx&p=2",
                BilibiliHttp.applyTestBaseUrl(url));
    }

    @Test
    @DisplayName("带非默认端口的 base 也正确")
    void 带端口() {
        BilibiliHttp.setTestBaseUrl("http://localhost:9999");
        assertEquals("http://localhost:9999/room/v1/Room/get_info?room_id=732",
                BilibiliHttp.applyTestBaseUrl(
                        "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=732"));
    }

    @Test
    @DisplayName("URL 无 scheme 时原样返回（不误伤相对路径）")
    void 无scheme() {
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18080");
        assertEquals("/x/a?b=1", BilibiliHttp.applyTestBaseUrl("/x/a?b=1"));
        assertEquals("not-a-url", BilibiliHttp.applyTestBaseUrl("not-a-url"));
    }

    @Test
    @DisplayName("非 http scheme 也会被改写（实现按 '://' 定位，不挑协议）")
    void 非http协议也会改写() {
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18080");
        // 实现只找 "://"，不校验协议类型 —— 这里锁定该行为，避免以后误以为它挑协议
        assertEquals("http://127.0.0.1:18080/x",
                BilibiliHttp.applyTestBaseUrl("ftp://host/x"));
    }

    @Test
    @DisplayName("URL 只有 host 没有 path 时返回 base 本身")
    void 只有host() {
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18080");
        assertEquals("http://127.0.0.1:18080",
                BilibiliHttp.applyTestBaseUrl("https://api.bilibili.com"));
    }

    @Test
    @DisplayName("入参 null → 返回 null（不抛异常）")
    void 入参null() {
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18080");
        assertNull(BilibiliHttp.applyTestBaseUrl(null));
    }

    @Test
    @DisplayName("clearTestBaseUrl 后恢复原样返回")
    void clear后恢复() {
        String url = "https://api.bilibili.com/x/a";
        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18080");
        assertEquals("http://127.0.0.1:18080/x/a", BilibiliHttp.applyTestBaseUrl(url));

        BilibiliHttp.clearTestBaseUrl();
        assertEquals(url, BilibiliHttp.applyTestBaseUrl(url),
                "必须彻底还原，否则会污染同一 JVM 里的其它测试");
    }

    @Test
    @DisplayName("rewriteForTest 与 applyTestBaseUrl 行为一致（供其它包的测试验证改写）")
    void rewriteForTest一致() {
        String url = "https://api.bilibili.com/x/a?b=1";
        // 未设置时两者都原样
        assertEquals(BilibiliHttp.applyTestBaseUrl(url), BilibiliHttp.rewriteForTest(url));

        BilibiliHttp.setTestBaseUrl("http://127.0.0.1:18081");
        assertEquals(BilibiliHttp.applyTestBaseUrl(url), BilibiliHttp.rewriteForTest(url),
                "两个入口必须等价，否则跨包的测试断言会与实际改写行为不一致");
        assertEquals("http://127.0.0.1:18081/x/a?b=1", BilibiliHttp.rewriteForTest(url));
    }
}
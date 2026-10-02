package com.esdllm.bilibiliApi.http;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import org.apache.http.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BilibiliHttp#getNoRedirect} / {@link BilibiliHttp#getLocation} 单测。
 *
 * <p>这两个方法是 P3 从 {@code ApiBase.getHttpResponseNotRedirect} 迁过来的能力：
 * "关掉自动重定向、读中间那一跳的 {@code Location}"。短链解析（{@code b23.tv/xxx}）全靠它 ——
 * Unirest 会自动跟随 302，拿不到中间跳转的目标地址。
 *
 * <p>本类锁定三件事：
 * <ol>
 *   <li><b>确实关掉了重定向</b>：响应状态是 302 而不是最终页的 200；</li>
 *   <li><b>能读到 Location</b>；</li>
 *   <li><b>失败一律给 null 而不是抛异常</b>（{@code getLocation} 的契约）。</li>
 * </ol>
 */
class BilibiliHttpLocationTest {

    private static final String SHORT_PATH = "/shortLink/";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    @DisplayName("getLocation：302 + Location → 返回跳转目标")
    void readsLocationNormally() {
        mock.registerRedirect(SHORT_PATH, "https://www.bilibili.com/video/BV1tgPie2E3w");

        String location = BilibiliHttp.getLocation("http://localhost" + SHORT_PATH + "abc");
        assertEquals("https://www.bilibili.com/video/BV1tgPie2E3w", location);
    }

    @Test
    @DisplayName("getLocation：响应没有 Location（如 404）→ 返回 null，不抛异常")
    void nullWhenNoLocation() {
        // 未注册该路径 → mock 返回 404 + JSON body，没有 Location
        String location = BilibiliHttp.getLocation("http://localhost" + SHORT_PATH + "missing");
        assertNull(location, "读不到 Location 应给 null 让上层判空");
    }

    @Test
    @DisplayName("getLocation：地址不可达 / 非法 → 返回 null，不抛异常")
    void nullWhenUnreachable() {
        // 端口 1 基本不会有人监听；连接失败应被吞成 null
        assertNull(BilibiliHttp.getLocation("http://127.0.0.1:1/never"));
        // 非法 URL
        assertNull(BilibiliHttp.getLocation("not-a-url"));
    }

    @Test
    @DisplayName("getNoRedirect：确实关掉了自动重定向（状态是 302 而非最终页 200）")
    void redirectsDisabled() throws Exception {
        mock.registerRedirect(SHORT_PATH, "https://www.bilibili.com/video/BV1xx");

        org.apache.http.HttpResponse resp =
                BilibiliHttp.getNoRedirect("http://localhost" + SHORT_PATH + "abc");

        assertNotNull(resp);
        assertEquals(302, resp.getStatusLine().getStatusCode(),
                "关键：若自动跟随重定向，这里会是 200（最终页），Location 也就读不到了");
        // 同时确认 mock 的最终页并没有被请求到（即真的没跳）
        Header[] locations = resp.getHeaders("Location");
        assertTrue(locations.length > 0, "302 响应应带 Location 头");
        assertEquals("https://www.bilibili.com/video/BV1xx", locations[0].getValue());
    }

    @Test
    @DisplayName("getNoRedirect：入参 null 时抛 BilibiliException（调用方编程错误应显式暴露）")
    void nullArgument() {
        // 🔴 断言必须精确到 BilibiliException：以前这里只写 Exception，而它靠的是
        // `new HttpGet(null)` 在 JDK/HttpClient 内部抛 NPE —— 那是**第三方实现细节、不是本库契约**，
        // 换个 httpclient 版本就可能变成 IllegalArgumentException，测试会为"非回归"变红。
        // 现在库内显式判空（文案 "url 不能为空"，与 WBI 那条同口径），契约由本库自己钉。
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> BilibiliHttp.getNoRedirect(null));

        assertTrue(e.getMessage().contains("url 不能为空"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("测试钩子对关重定向路径同样生效（改写 URL 后打到本机 mock）")
    void testHookTakesEffect() throws Exception {
        mock.registerRedirect(SHORT_PATH, "https://www.bilibili.com/video/BV1hook");

        // 用生产域名 b23.tv：URL 改写只替换 scheme+host，path 原样保留 →
        // https://b23.tv/shortLink/abc 变成 http://127.0.0.1:PORT/shortLink/abc，命中 mock。
        // 若钩子没生效，请求会真的打到 b23.tv（外网）。
        String location = BilibiliHttp.getLocation("https://b23.tv/shortLink/abc");
        assertEquals("https://www.bilibili.com/video/BV1hook", location,
                "关重定向路径也必须吃 setTestBaseUrl 的改写，否则短链测试会打真实外网");
    }

    @Test
    @DisplayName("多次调用互不影响（无状态、无连接复用副作用）")
    void multipleCalls() {
        mock.registerRedirect(SHORT_PATH, "https://www.bilibili.com/video/BV1multi");
        for (int i = 0; i < 3; i++) {
            assertEquals("https://www.bilibili.com/video/BV1multi",
                    BilibiliHttp.getLocation("http://localhost" + SHORT_PATH + "a"));
        }
    }
}
package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ShortLinkService} 直接单测。
 *
 * <p>它有一条独特的路径：{@code resolveLocation} 走
 * {@link com.esdllm.bilibiliApi.http.BilibiliHttp#getLocation}（内部用 Apache HttpClient 关重定向）。
 * 该路径同样吃 {@code BilibiliHttp} 的测试钩子（URL 改写），本测试同时验证"改写确实生效"。
 */
class ShortLinkServiceTest {

    private static final String SHORT_LINK_PATH = "/shortLink/";
    private static final String VIEW_PATH = "/x/web-interface/view";
    private static final String LIVE_PATH = "/room/v1/Room/get_info";
    private static final String DETAIL_PATH = "/x/polymer/web-dynamic/v1/detail";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Nested
    @DisplayName("resolveLocation：读 Location header")
    class ResolveLocation {

        @Test
        @DisplayName("302 + Location → 返回跳转目标（验证 URL 改写钩子对关重定向路径也生效）")
        void happyPath() {
            mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/video/BV1tgPie2E3w");

            String location = ShortLinkService.INSTANCE.resolveLocation(
                    "http://localhost" + SHORT_LINK_PATH + "abc");
            assertEquals("https://www.bilibili.com/video/BV1tgPie2E3w", location);
        }

        @Test
        @DisplayName("响应没有 Location header → 返回 null（不抛异常）")
        void noLocation() {
            // mock 未注册该路径 → 返回 404 + JSON body，没有 Location 头
            String location = ShortLinkService.INSTANCE.resolveLocation(
                    "http://localhost" + SHORT_LINK_PATH + "missing");
            assertNull(location, "读不到 Location 应给 null 让上层判空，而不是抛异常");
        }

        @Test
        @DisplayName("不同短链可解析到不同类型的目标")
        void variousTargets() {
            mock.registerRedirect("/shortLink/vid/", "https://www.bilibili.com/video/BV1xx");
            mock.registerRedirect("/shortLink/live/", "https://live.bilibili.com/732");
            mock.registerRedirect("/shortLink/opus/", "https://www.bilibili.com/opus/123");

            assertEquals("https://www.bilibili.com/video/BV1xx",
                    ShortLinkService.INSTANCE.resolveLocation("http://localhost/shortLink/vid/a"));
            assertEquals("https://live.bilibili.com/732",
                    ShortLinkService.INSTANCE.resolveLocation("http://localhost/shortLink/live/a"));
            assertEquals("https://www.bilibili.com/opus/123",
                    ShortLinkService.INSTANCE.resolveLocation("http://localhost/shortLink/opus/a"));
        }
    }

    @Nested
    @DisplayName("getVideoInfoFromChain：BV / av 两种 chainId")
    class VideoFromChain {

        @Test
        @DisplayName("BV 开头 → 走 bvid 端点")
        void bvChain() throws IOException {
            mock.register(VIEW_PATH + "?bvid=",
                    Files.readString(Path.of("src/test/resources/fixtures/video-view.json")));

            VideoInfo v = ShortLinkService.INSTANCE.getVideoInfoFromChain("BV1tgPie2E3w");
            assertNotNull(v);
            assertEquals("BV1tgPie2E3w", v.getBvid());
        }

        @Test
        @DisplayName("非 BV 开头 → 截掉前 2 字符当 aid（'av123' → 123）")
        void avChain() throws IOException {
            mock.register(VIEW_PATH + "?aid=",
                    Files.readString(Path.of("src/test/resources/fixtures/video-view.json")));

            // 实现是 chainId.substring(2) 后 Long.parseLong
            VideoInfo v = ShortLinkService.INSTANCE.getVideoInfoFromChain("av114065439463311");
            assertNotNull(v);
            assertEquals(114065439463311L, v.getAid());
        }

        @Test
        @DisplayName("非 BV 且不是数字 → NumberFormatException（调用方编程错误应显式暴露）")
        void invalidChainId() {
            assertThrows(NumberFormatException.class,
                    () -> ShortLinkService.INSTANCE.getVideoInfoFromChain("xx-not-a-number"));
        }

        @Test
        @DisplayName("端点失败 → 抛 IOException")
        void endpointFailure() {
            assertThrows(IOException.class,
                    () -> ShortLinkService.INSTANCE.getVideoInfoFromChain("BV1unknown"));
        }
    }

    @Nested
    @DisplayName("getLiveRoomFromChain / getDynamicCardFromChain")
    class OtherChains {

        @Test
        @DisplayName("直播间：roomId 直接作为入参")
        void liveRoomTarget() throws IOException {
            mock.register(LIVE_PATH + "?room_id=",
                    Files.readString(Path.of("src/test/resources/fixtures/live-room.json")));

            LiveRoom room = ShortLinkService.INSTANCE.getLiveRoomFromChain(732L);
            assertNotNull(room);
            assertEquals(732L, room.getRoom_id());
        }

        @Test
        @DisplayName("动态卡片：chainId 直接透传给详情端点")
        void dynamicCardTarget() throws IOException {
            mock.register(DETAIL_PATH + "?id=",
                    Files.readString(Path.of("src/test/resources/fixtures/dynamic-detail-legacy.json")));

            BilibiliDynamicResp.Data.Card card =
                    ShortLinkService.INSTANCE.getDynamicCardFromChain("1247016318199136288");
            assertNotNull(card);
            assertEquals("1247016318199136288", card.getDesc().getDynamic_id_str());
            assertEquals("影视飓风", card.getDesc().getUser_profile().getInfo().getUname());
        }

        @Test
        @DisplayName("动态卡片端点失败 → 抛 IOException")
        void dynamicFailure() {
            assertThrows(IOException.class,
                    () -> ShortLinkService.INSTANCE.getDynamicCardFromChain("9999999"));
        }
    }

    @Test
    @DisplayName("单例入口非 null；未注册的短链返回 null（不抛异常）")
    void singletonAvailable() {
        assertNotNull(ShortLinkService.INSTANCE);
        // 未注册该路径 → server 返回 404（无 Location 头）→ resolveLocation 给 null
        assertNull(ShortLinkService.INSTANCE.resolveLocation(
                        "http://localhost" + SHORT_LINK_PATH + "x"),
                "读不到 Location 时应返回 null，让上层判空");
        // 注册一个可解析的，确认单例可复用
        mock.registerRedirect(SHORT_LINK_PATH + "ok/", "https://www.bilibili.com/video/BV1");
        assertEquals("https://www.bilibili.com/video/BV1",
                ShortLinkService.INSTANCE.resolveLocation("http://localhost" + SHORT_LINK_PATH + "ok/x"));
    }
}
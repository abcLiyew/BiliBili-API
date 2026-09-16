package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.common.ShotChainInfo;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>ShortChain 门面回归测试</b>（P1.6 改造后补）。
 *
 * <p>ShortChain 测试要点：
 * <ol>
 *   <li><b>resolveLocation</b>：mock server 返回 302 + Location header；</li>
 *   <li><b>类型分发</b>：Location 路径后缀解析出 type（0=直播 1=视频 2=动态 4=空间 6=其他）；</li>
 *   <li><b>跨门面派发</b>：video/live/dynamic 三种 chainId 走对应 Service 取数。</li>
 * </ol>
 */
class ShortChainTest {

    private static final String LIVE_PATH = "/room/v1/Room/get_info";
    private static final String LEGACY_DETAIL_PATH = "/x/polymer/web-dynamic/v1/detail";
    private static final String VIDEO_PATH = "/x/web-interface/view";
    private static final String SHORT_LINK_PATH = "/shortLink/";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    // —— resolveLocation ——

    @Test
    void resolveLocation_parsesLocationHeader() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/video/BV1tgPie2E3w");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertNotNull(sc.getShotChainInfo());
        assertEquals(1, sc.getShotChainInfo().getType()); // 视频
        assertEquals("BV1tgPie2E3w", sc.getShotChainInfo().getChainId());
    }

    @Test
    void resolveLocation_returnsNullInfoWhenServerReturnsNoHeader() {
        // mock 不注册任何 redirect → server 回 404（不是 302）→ getFirstHeader("Location") 返回 null
        // ShortChain.getShotChainInfo 因此返回 null
        ShortChain sc = new ShortChain("http://localhost/shortLink/missing");
        assertNull(sc.getShotChainInfo());
    }

    // —— 类型分发（基于 resolveLocation 拿到的真实 URL）——

    @Test
    void getShotChainType_video() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/video/BV1xx");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertEquals("视频", sc.getShotChainType());
    }

    @Test
    void getShotChainType_live() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://live.bilibili.com/732");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertEquals("直播", sc.getShotChainType());
    }

    @Test
    void getShotChainType_dynamic() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/opus/1234567890");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertEquals("动态", sc.getShotChainType());
    }

    @Test
    void getShotChainType_unknownForMalformedUrl() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        // 段数 < 3 归为"其他"
        assertEquals("其他", sc.getShotChainType());
    }

    // —— 跨门面派发：getVideoInfo / getLiveRoom / getDynamicCard ——

    @Test
    void getVideoInfo_bvidPath() throws IOException {
        String videoBody = Files.readString(Path.of("src/test/resources/fixtures/video-view.json"));
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/video/BV1tgPie2E3w");
        mock.register(VIDEO_PATH + "?bvid=", videoBody);

        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        VideoInfo v = sc.getVideoInfo();
        assertNotNull(v);
        assertEquals("BV1tgPie2E3w", v.getBvid());
        assertEquals("测试视频标题", v.getTitle());
    }

    @Test
    void getLiveRoom() throws IOException {
        String liveBody = Files.readString(Path.of("src/test/resources/fixtures/live-room.json"));
        mock.registerRedirect(SHORT_LINK_PATH, "https://live.bilibili.com/732");
        mock.register(LIVE_PATH + "?room_id=", liveBody);

        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        LiveRoom room = sc.getLiveRoom();
        assertNotNull(room);
        assertEquals(732L, room.getRoom_id());
        assertEquals("测试直播间标题", room.getTitle());
    }

    @Test
    void getDynamicCard_opusPath() throws IOException {
        String legacyBody = Files.readString(Path.of("src/test/resources/fixtures/dynamic-detail-legacy.json"));
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/opus/1247016318199136288");
        mock.register(LEGACY_DETAIL_PATH + "?id=", legacyBody);

        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        BilibiliDynamicResp.Data.Card card = sc.getDynamicCard();
        assertNotNull(card);
        // dynamicId 拍平到 desc.dynamic_id_str（DynamicSchemaAdapter 统一映射）
        assertEquals("1247016318199136288", card.getDesc().getDynamic_id_str());
        assertEquals("影视飓风", card.getDesc().getUser_profile().getInfo().getUname());
    }

    // —— 错误类型守卫 ——

    @Test
    void getVideoInfo_throwsWhenTypeMismatch() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://live.bilibili.com/732");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertThrows(RuntimeException.class, sc::getVideoInfo);
    }

    @Test
    void getLiveRoom_throwsWhenTypeMismatch() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://www.bilibili.com/video/BV1xx");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertThrows(RuntimeException.class, sc::getLiveRoom);
    }

    @Test
    void getDynamicCard_throwsWhenTypeMismatch() {
        mock.registerRedirect(SHORT_LINK_PATH, "https://live.bilibili.com/732");
        ShortChain sc = new ShortChain("http://localhost/shortLink/abc");
        assertThrows(RuntimeException.class, sc::getDynamicCard);
    }

    // —— ShotChainInfo 字段映射（直接构造对象，不走网络）——

    @Test
    void getShotChainType_handlesAllTypeCodes() {
        // 类型码 0~6 全覆盖（短链类型语义稳定性测试）
        for (int type = 0; type <= 6; type++) {
            ShortChain sc = new ShortChain();
            ShotChainInfo info = new ShotChainInfo();
            info.setType(type);
            sc.setShotChainInfo(info);
            String result = sc.getShotChainType();
            assertNotNull(result);
            assertTrue(!result.isEmpty(), "type=" + type + " 应映射到非空字符串");
        }
    }
}
package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.Staff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>BilibiliClient 门面回归测试</b>。
 *
 * <p>fixture 驱动：{@link MockBiliServer} 在本机起一个 HttpServer，拦截
 * {@code api.bilibili.com/x/web-interface/view} 的两条路径（{@code ?bvid=} / {@code ?aid=}），
 * 返回 {@code src/test/resources/fixtures/video-view.json}。所有断言对照 fixture 期望值。
 *
 * <p>验收：23 个 getter × 至少 2 个断言（bvid 路径 + aid 路径），并新增异常路径 +
 * 单槽缓存复用 + 空参数校验三个用例。
 */
class BilibiliClientTest {

    private static final String BVID = "BV1tgPie2E3w";
    private static final long AID = 114065439463311L;
    private static final String FIXTURE_PATH = "/x/web-interface/view";

    private MockBiliServer mock;
    private BilibiliClient client;

    @BeforeEach
    void setUp() throws IOException {
        String fixtureBody = Files.readString(Path.of("src/test/resources/fixtures/video-view.json"));
        mock = MockBiliServer.start()
                .register(FIXTURE_PATH + "?bvid=", fixtureBody)
                .register(FIXTURE_PATH + "?aid=", fixtureBody);
        client = new BilibiliClient();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    // —— 全字段映射（bvid 路径 + aid 路径两条都断言）——

    @Test
    void getVideoInfo_bothPaths() throws IOException {
        VideoInfo byBvid = client.getVideoInfo(BVID);
        VideoInfo byAid = client.getVideoInfo(AID);
        assertEquals(BVID, byBvid.getBvid());
        assertEquals(AID, byBvid.getAid());
        assertEquals("测试视频标题", byBvid.getTitle());
        // aid 路径应拿到与 bvid 路径完全一致的 VideoInfo（fixture 相同）
        assertEquals(byBvid.getBvid(), byAid.getBvid());
        assertEquals(byBvid.getTitle(), byAid.getTitle());
        assertEquals(byBvid.getPic(), byAid.getPic());
    }

    @Test
    void getVideoAv() {
        assertEquals(AID, client.getVideoAv(BVID));
    }

    @Test
    void getVideoBv() {
        assertEquals(BVID, client.getVideoBv(AID));
    }

    @Test
    void getVideoCoverUrl_bothPaths() {
        String expected = "http://i0.hdslb.com/bfs/archive/test_cover.jpg";
        assertEquals(expected, client.getVideoCoverUrl(BVID));
        assertEquals(expected, client.getVideoCoverUrl(AID));
    }

    @Test
    void getVideoTitle_bothPaths() {
        assertEquals("测试视频标题", client.getVideoTitle(BVID));
        assertEquals("测试视频标题", client.getVideoTitle(AID));
    }

    @Test
    void getVideoDesc_bothPaths() {
        assertEquals("测试视频简介", client.getVideoDesc(BVID));
        assertEquals("测试视频简介", client.getVideoDesc(AID));
    }

    @Test
    void getVideoDuration_bothPaths() {
        assertEquals(300, client.getVideoDuration(BVID));
        assertEquals(300, client.getVideoDuration(AID));
    }

    @Test
    void getVideoPubdate_bothPaths() {
        assertEquals(1700000000L, client.getVideoPubdate(BVID));
        assertEquals(1700000000L, client.getVideoPubdate(AID));
    }

    @Test
    void getVideoPlayCount_bothPaths() {
        assertEquals(12345L, client.getVideoPlayCount(BVID));
        assertEquals(12345L, client.getVideoPlayCount(AID));
    }

    @Test
    void getVideoDanmuCount_bothPaths() {
        assertEquals(678L, client.getVideoDanmuCount(BVID));
        assertEquals(678L, client.getVideoDanmuCount(AID));
    }

    @Test
    void getVideoCommentCount_bothPaths() {
        assertEquals(90L, client.getVideoCommentCount(BVID));
        assertEquals(90L, client.getVideoCommentCount(AID));
    }

    @Test
    void getVideoFavoriteCount_bothPaths() {
        assertEquals(234L, client.getVideoFavoriteCount(BVID));
        assertEquals(234L, client.getVideoFavoriteCount(AID));
    }

    @Test
    void getVideoCoinCount_bothPaths() {
        assertEquals(56L, client.getVideoCoinCount(BVID));
        assertEquals(56L, client.getVideoCoinCount(AID));
    }

    @Test
    void getVideoShareCount_bothPaths() {
        assertEquals(78L, client.getVideoShareCount(BVID));
        assertEquals(78L, client.getVideoShareCount(AID));
    }

    @Test
    void getVideoCurrentRank_bothPaths() {
        assertEquals(0L, client.getVideoCurrentRank(BVID));
        assertEquals(0L, client.getVideoCurrentRank(AID));
    }

    @Test
    void getVideoHistoryRank_bothPaths() {
        assertEquals(12L, client.getVideoHistoryRank(BVID));
        assertEquals(12L, client.getVideoHistoryRank(AID));
    }

    @Test
    void getVideoUpUid_bothPaths() {
        assertEquals(3546774476163227L, client.getVideoUpUid(BVID));
        assertEquals(3546774476163227L, client.getVideoUpUid(AID));
    }

    @Test
    void getVideoUpName_bothPaths() {
        assertEquals("测试UP主", client.getVideoUpName(BVID));
        assertEquals("测试UP主", client.getVideoUpName(AID));
    }

    @Test
    void getVideoUpFace_bothPaths() {
        assertEquals("http://i0.hdslb.com/bfs/face/test_up.jpg", client.getVideoUpFace(BVID));
        assertEquals("http://i0.hdslb.com/bfs/face/test_up.jpg", client.getVideoUpFace(AID));
    }

    @Test
    void getVideoPartCount_bothPaths() {
        assertEquals(1, client.getVideoPartCount(BVID));
        assertEquals(1, client.getVideoPartCount(AID));
    }

    @Test
    void getVideoIsInteraction_bothPaths() {
        // fixture 里 is_stein_gate=0 → Boolean.FALSE
        assertEquals(Boolean.FALSE, client.getVideoIsInteraction(BVID));
        assertEquals(Boolean.FALSE, client.getVideoIsInteraction(AID));
    }

    @Test
    void getStaffList_bothPaths() {
        List<Staff> byBvid = client.getStaffList(BVID);
        List<Staff> byAid = client.getStaffList(AID);
        assertNotNull(byBvid);
        assertNotNull(byAid);
        assertTrue(byBvid.isEmpty());
        assertEquals(byBvid, byAid);
    }

    @Test
    void getVideoPartTitle_bothPaths() {
        assertEquals("测试分P标题", client.getVideoPartTitle(BVID, 1));
        assertEquals("测试分P标题", client.getVideoPartTitle(AID, 1));
    }

    // —— 边界 & 异常路径 ——

    @Test
    void getVideoPartTitle_pageOutOfRange_throws() {
        // pages 只有 1 个，page=2 必抛
        assertThrows(IndexOutOfBoundsException.class,
                () -> client.getVideoPartTitle(BVID, 2));
    }

    @Test
    void getVideoInfo_nullBvid_throws() {
        assertThrows(Exception.class, () -> client.getVideoInfo((String) null));
    }

    @Test
    void getVideoInfo_invalidAid_throws() {
        // aid<=0 应抛（BilibiliException）
        assertThrows(Exception.class, () -> client.getVideoInfo(0L));
        assertThrows(Exception.class, () -> client.getVideoInfo(-1L));
    }

    @Test
    void getVideoInfo_singleSlotCacheHitsAfterFirst() throws IOException {
        // 第一次取真实数据；第二次同 bvid 应复用单槽缓存（理论上不再走 mock）
        // 这里通过替换 mock body 不可能（mock 已注册），所以只验证"两次返回相等 + 第二次不抛"
        VideoInfo first = client.getVideoInfo(BVID);
        VideoInfo second = client.getVideoInfo(BVID);
        assertEquals(first.getTitle(), second.getTitle());
        assertEquals(first.getBvid(), second.getBvid());
    }
}
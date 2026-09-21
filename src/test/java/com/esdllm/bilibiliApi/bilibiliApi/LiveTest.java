package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Live 门面回归测试</b>（P1.2 改造后补）。
 *
 * <p>fixture 驱动：拦截 {@code live.bilibili.com/room/v1/Room/get_info?room_id=732}，
 * 返回 {@code live-room.json}；13 个 getter 全部对照断言。
 *
 * <p>注意：{@link Live#getLiveStatus(Long)} / {@link Live#getLiveTitle(Long)} /
 * {@link Live#getImageUrl(Long)} 三个方法声明 {@code throws IOException}，对应
 * "Service 抛 BilibiliException → 门面包成 IOException 抛出" 的 §4.8.1 规则。
 *
 * @see com.esdllm.bilibiliApi.service.LiveService
 */
class LiveTest {

    private static final long ROOM_ID = 732L;
    private static final String LIVE_PATH = "/room/v1/Room/get_info";
    private static final String FIXTURE = "src/test/resources/fixtures/live-room.json";

    private MockBiliServer mock;
    private Live live;

    @BeforeEach
    void setUp() throws IOException {
        String body = Files.readString(Path.of(FIXTURE));
        mock = MockBiliServer.start().register(LIVE_PATH + "?room_id=", body);
        live = new Live();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void getLiveRoom_returnsFullObject() {
        LiveRoom room = live.getLiveRoom(ROOM_ID);
        assertNotNull(room);
        assertEquals(732L, room.getRoom_id());
        assertEquals(3546774476163227L, room.getUid());
        assertEquals("测试直播间标题", room.getTitle());
    }

    @Test
    void getLiveStatus() throws IOException {
        // live_status=1 → 直播中
        assertEquals(1, live.getLiveStatus(ROOM_ID));
    }

    @Test
    void getLiveTitle() throws IOException {
        assertEquals("测试直播间标题", live.getLiveTitle(ROOM_ID));
    }

    @Test
    void getLiveUrl_isPureStringComposition() {
        // 不走网络，纯字符串拼接
        assertEquals("https://live.bilibili.com/" + ROOM_ID, live.getLiveUrl(ROOM_ID));
    }

    @Test
    void getUid() {
        assertEquals(3546774476163227L, live.getUid(ROOM_ID));
    }

    @Test
    void getImageUrl() throws IOException {
        assertEquals("http://i0.hdslb.com/bfs/live/test_cover.jpg", live.getImageUrl(ROOM_ID));
    }

    @Test
    void getLiveArea() {
        // area_name="测试子分区"
        assertEquals("测试子分区", live.getLiveArea(ROOM_ID));
    }

    @Test
    void getOnline() {
        assertEquals(5000, live.getOnline(ROOM_ID));
    }

    @Test
    void getKeyFrame() {
        assertEquals("http://i0.hdslb.com/bfs/live/test_keyframe.jpg", live.getKeyFrame(ROOM_ID));
    }

    @Test
    void getTags() {
        assertEquals("测试,直播间,标签", live.getTags(ROOM_ID));
    }

    @Test
    void getDescription() {
        assertEquals("测试直播间描述", live.getDescription(ROOM_ID));
    }

    @Test
    void getLiveTime() {
        assertEquals("2026-09-13 12:00:00", live.getLiveTime(ROOM_ID));
    }

    @Test
    void getPkStatus() {
        assertEquals(0, live.getPkStatus(ROOM_ID));
    }

    @Test
    void getHotWords() {
        List<String> words = live.getHotWords(ROOM_ID);
        assertNotNull(words);
        assertEquals(2, words.size());
        assertEquals("测试", words.get(0));
        assertEquals("热词", words.get(1));
    }

    // —— 异常路径 ——

    @Test
    void getLiveStatus_nullRoomId_throws() {
        // §4.8.1：throws IOException 的方法把 BilibiliException 包成 IOException
        assertThrows(IOException.class, () -> live.getLiveStatus(null));
    }

    @Test
    void getLiveTitle_nullRoomId_throws() {
        // §4.8.1：throws IOException 的方法把 BilibiliException 包成 IOException
        assertThrows(IOException.class, () -> live.getLiveTitle(null));
    }

    @Test
    void getUid_nullRoomId_throws() {
        // 非 throws 的方法透传 BilibiliException（runtime）
        assertThrows(BilibiliException.class, () -> live.getUid(null));
    }
}
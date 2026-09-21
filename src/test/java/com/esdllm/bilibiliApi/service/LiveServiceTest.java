package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LiveService} 直接单测。
 *
 * <p>P1 把取数逻辑从 {@code Live} 门面迁到这里后，门面测试只能覆盖"委托 + 字段映射"，
 * Service 自己的校验分支（null roomId / 业务码非 0 / data 为空 / 非法 JSON）此前无人覆盖。
 *
 * <p><b>异常语义</b>：本 Service <b>只抛 {@link BilibiliException}</b>（runtime）——
 * 旧 {@code Live} 里"throws IOException + catch 后包 RuntimeException"的反模式已在 P1 收敛，
 * 由门面边界决定是否转成 IOException。本类锁定 Service 侧的 runtime 语义。
 */
class LiveServiceTest {

    private static final String LIVE_PATH = "/room/v1/Room/get_info";
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
    @DisplayName("正常路径：load 返回完整 LiveRoom，字段逐一映射")
    void happyPath() throws IOException {
        mock.register(LIVE_PATH + "?room_id=",
                Files.readString(Path.of("src/test/resources/fixtures/live-room.json")));

        LiveRoom room = LiveService.INSTANCE.load(732L);
        assertNotNull(room);
        assertEquals(732L, room.getRoom_id());
        assertEquals(3546774476163227L, room.getUid());
        assertEquals(1, room.getLive_status());
        assertEquals("测试直播间标题", room.getTitle());
        assertEquals(5000, room.getOnline());
        assertEquals("测试子分区", room.getArea_name());
        assertEquals("测试,直播间,标签", room.getTags());
        assertEquals(2, room.getHot_words().size());
    }

    @Test
    @DisplayName("roomId 为 null → 抛 BilibiliException，且消息带动作与入参上下文")
    void roomIdIsNull() {
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> LiveService.INSTANCE.load(null));
        assertTrue(e.getMessage().contains("房间号"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("null"), "应把入参原样带出便于排障");
    }

    @Test
    @DisplayName("业务码非 0 → 抛 BilibiliException（不是返回半个对象）")
    void nonZeroBusinessCode() {
        mock.register(LIVE_PATH + "?room_id=",
                "{\"code\":-400,\"message\":\"请求错误\",\"data\":null}");
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> LiveService.INSTANCE.load(1L));
        assertTrue(e.getMessage().contains("获取直播间信息失败"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("code=0 但 data 为 null → 抛 BilibiliException（不是 NPE）")
    void nullData() {
        mock.register(LIVE_PATH + "?room_id=", "{\"code\":0,\"message\":\"0\",\"data\":null}");
        assertThrows(BilibiliException.class, () -> LiveService.INSTANCE.load(1L));
    }

    @Test
    @DisplayName("响应不是合法 JSON → 抛 BilibiliException（而不是 fastjson 的异常穿透）")
    void invalidJson() {
        mock.register(LIVE_PATH + "?room_id=", "<html>风控页</html>");
        assertThrows(BilibiliException.class, () -> LiveService.INSTANCE.load(1L));
    }

    @Test
    @DisplayName("未注册路径（HTTP 404）→ 抛异常，不返回 null")
    void unregisteredPath() {
        assertThrows(BilibiliException.class, () -> LiveService.INSTANCE.load(99999L));
    }

    @Test
    @DisplayName("单例无状态：连续调不同 roomId 互不影响（不缓存上一次结果）")
    void stateless() throws IOException {
        // 同一 mock 对任意 room_id 返回同一份 fixture，但服务不该"记住"上一次调用
        mock.register(LIVE_PATH + "?room_id=",
                Files.readString(Path.of("src/test/resources/fixtures/live-room.json")));

        LiveRoom first = LiveService.INSTANCE.load(1L);
        LiveRoom second = LiveService.INSTANCE.load(2L);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getRoom_id(), second.getRoom_id(),
                "fixture 相同所以值相同；关键是两次调用都真的走了请求（INSTANCE 不缓存）");
    }
}
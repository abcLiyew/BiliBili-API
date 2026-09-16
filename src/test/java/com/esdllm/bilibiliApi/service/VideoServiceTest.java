package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VideoService} 直接单测。
 *
 * <p>覆盖两个入口（bvid / aid）各自的参数校验、正常映射与失败路径。
 * 注意 {@code getVideoInfo(Long)} 的校验条件与 {@code String} 版不同：
 * 数值版要求 {@code > 0}，字符串版只要求非 null。
 */
class VideoServiceTest {

    private static final String VIEW_PATH = "/x/web-interface/view";
    private static final String FIXTURE = "src/test/resources/fixtures/video-view.json";

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
    @DisplayName("按 bvid 取：正常映射")
    void byBvidHappyPath() throws IOException {
        mock.register(VIEW_PATH + "?bvid=", Files.readString(Path.of(FIXTURE)));

        VideoInfo v = VideoService.INSTANCE.getVideoInfo("BV1tgPie2E3w");
        assertNotNull(v);
        assertEquals("BV1tgPie2E3w", v.getBvid());
        assertEquals(114065439463311L, v.getAid());
        assertEquals("测试视频标题", v.getTitle());
        assertEquals(300, v.getDuration());
        assertEquals(12345L, v.getStat().getView());
        assertEquals("测试UP主", v.getOwner().getName());
        assertEquals(1, v.getPages().size());
    }

    @Test
    @DisplayName("按 aid 取：正常映射")
    void byAidHappyPath() throws IOException {
        mock.register(VIEW_PATH + "?aid=", Files.readString(Path.of(FIXTURE)));

        VideoInfo v = VideoService.INSTANCE.getVideoInfo(114065439463311L);
        assertNotNull(v);
        assertEquals(114065439463311L, v.getAid());
        assertEquals("BV1tgPie2E3w", v.getBvid());
    }

    @Test
    @DisplayName("bvid 为 null → 抛 BilibiliException('BV号不能为空')")
    void bvidIsNull() {
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo((String) null));
        assertTrue(e.getMessage().contains("BV号不能为空"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("aid 为 null / 0 / 负数 → 抛 BilibiliException('AV号不能为空')")
    void aidInvalid() {
        assertTrue(assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo((Long) null))
                .getMessage().contains("AV号不能为空"));
        assertTrue(assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo(0L))
                .getMessage().contains("AV号不能为空"));
        assertTrue(assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo(-5L))
                .getMessage().contains("AV号不能为空"));
    }

    @Test
    @DisplayName("参数校验在发请求之前")
    void validationRunsFirst() {
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo((Long) null));
        assertTrue(e.getMessage().contains("AV号不能为空"),
                "若先发请求会得到 404 文案，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("业务码非 0 → 抛 BilibiliException（含错误码信息）")
    void nonZeroBusinessCode() {
        mock.register(VIEW_PATH + "?bvid=",
                "{\"code\":-404,\"message\":\"啥都木有\",\"data\":null}");
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo("BV1xx"));
        assertTrue(e.getMessage().contains("获取视频信息失败"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("响应不是合法 JSON → 抛 BilibiliException（消息里带定位线索）")
    void invalidJson() {
        mock.register(VIEW_PATH + "?bvid=", "not-a-json");
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> VideoService.INSTANCE.getVideoInfo("BV1xx"));
        assertTrue(e.getMessage().contains("获取视频信息失败"));
        assertTrue(e.getMessage().contains("VideoService"), "消息应带类名便于定位，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("未注册路径（404）→ 抛异常")
    void unregisteredPath() {
        assertThrows(BilibiliException.class, () -> VideoService.INSTANCE.getVideoInfo("BV1unknown"));
    }

    @Test
    @DisplayName("两个入口共用同一份响应解析（同一 fixture 下字段一致）")
    void twoEntryPointsAgree() throws IOException {
        mock.register(VIEW_PATH + "?bvid=", Files.readString(Path.of(FIXTURE)))
                .register(VIEW_PATH + "?aid=", Files.readString(Path.of(FIXTURE)));

        VideoInfo byBvid = VideoService.INSTANCE.getVideoInfo("BV1tgPie2E3w");
        VideoInfo byAid = VideoService.INSTANCE.getVideoInfo(114065439463311L);
        assertEquals(byBvid.getTitle(), byAid.getTitle());
        assertEquals(byBvid.getStat().getView(), byAid.getStat().getView());
        assertEquals(byBvid.getOwner().getMid(), byAid.getOwner().getMid());
    }
}
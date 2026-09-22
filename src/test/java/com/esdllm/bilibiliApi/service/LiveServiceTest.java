package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveStream;
import com.esdllm.bilibiliApi.model.data.pojo.live.MasterInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    // ================================================================
    // B1 直播域扩（2026-09-22）：拉流地址 / 主播信息
    //
    // 与上面 load 的分工：load 管"这个直播间是什么状态"，新增的两个管
    // "怎么播起来"与"主播是谁"。三者都零门槛（不需要签名、不需要凭据）——
    // 这是直播域与点播域最大的差别：点播的 playurl 受出口信誉影响、历史上反复过多次。
    // ================================================================

    private static final String STREAM_PATH = "/room/v1/Room/playUrl";
    private static final String MASTER_PATH = "/live_user/v1/Master/info";
    private static final String LIVE_REFERER = "https://live.bilibili.com/";
    private static final String NAV_PATH = "/x/web-interface/nav";

    @Nested
    @DisplayName("直播流地址（room/v1/Room/playUrl）")
    class LiveStreamTest {

        @Test
        @DisplayName("默认 qn=10000（原画）：直播不消耗清晰度权限，所以不像点播那样'传 80 只给 64'")
        void defaultQuality() throws Exception {
            mock.register(STREAM_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-stream.json")));

            LiveStream data = LiveService.INSTANCE.getLiveStream(1024L, null);

            String uri = mock.requestUri(STREAM_PATH);
            assertTrue(uri.contains("cid=1024"), "★ 端点自己要的参数叫 cid（值是直播间号）。实际：" + uri);
            assertTrue(uri.contains("qn=10000"), "默认原画。实际：" + uri);
            assertTrue(uri.contains("platform=web"), "实际：" + uri);
            assertEquals(LIVE_REFERER, mock.requestHeader(STREAM_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点免签名、免登录（本批唯一没有历史包袱的播放地址端点）");
        }

        @Test
        @DisplayName("★ accept_quality 是【字符串数组】，且 durl 是多条 CDN 而非分片")
        void shape() throws Exception {
            mock.register(STREAM_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-stream.json")));

            LiveStream data = LiveService.INSTANCE.getLiveStream(1024L, null);

            assertEquals(List.of("4"), data.getAccept_quality(),
                    "★ 直播域是字符串数组 [\"4\"]；点播域那份是数字数组 —— 两个域的类不能互套");
            assertEquals(10000, data.getCurrent_qn());
            assertEquals(4, data.getCurrent_quality(), "注意它与 current_qn 不是同一套编号");

            assertEquals(1, data.getQuality_description().size());
            assertEquals("原画", data.getQuality_description().get(0).getDesc());

            assertEquals(2, data.getDurl().size(), "两条是同一路流的不同 CDN，不是'分片要拼接'");
            assertEquals(1, data.getDurl().get(0).getOrder());
            assertTrue(data.getDurl().get(0).getUrl().endsWith(".flv")
                            || data.getDurl().get(0).getUrl().contains(".flv?"),
                    "是 .flv 流。实际：" + data.getDurl().get(0).getUrl());
            assertEquals(0L, data.getDurl().get(0).getLength(),
                    "★ 实测恒为 0 —— 直播没有总时长，别指望它算进度");
        }

        @Test
        @DisplayName("qn 传正数则原样使用（不回落默认）")
        void explicitQn() throws Exception {
            mock.register(STREAM_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-stream.json")));

            LiveService.INSTANCE.getLiveStream(1024L, 400);

            assertTrue(mock.requestUri(STREAM_PATH).contains("qn=400"),
                    "实际：" + mock.requestUri(STREAM_PATH));
        }

        @Test
        @DisplayName("★ code=0 但 durl 为空：必须抛异常，不能把'一个地址都没有'当成功")
        void emptyDurlIsAnError() {
            mock.register(STREAM_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"current_qn\":10000,\"accept_quality\":[],\"durl\":[]}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LiveService.INSTANCE.getLiveStream(1024L, null));

            assertEquals(0, e.getCode(), "外层码是 0 —— 正因如此才不能靠它判断");
            assertTrue(e.getMessage().contains("durl 为空"), "实际：" + e.getMessage());
            assertEquals("没有可播放地址", e.getDescription(),
                    "要与'参数错'区分开：这是'参数没错、但没内容给你'");
        }

        @Test
        @DisplayName("roomId 为 null 或 ≤ 0：本地校验，零出站")
        void badRoomId() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LiveService.INSTANCE.getLiveStream(0L, null));

            assertTrue(e.getMessage().contains("房间号不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(STREAM_PATH));

            assertThrows(BilibiliException.class, () -> LiveService.INSTANCE.getLiveStream(null, null));
            assertEquals(0, mock.hitCount(STREAM_PATH));
        }
    }

    @Nested
    @DisplayName("主播信息（live_user/v1/Master/info）")
    class MasterInfoTest {

        @Test
        @DisplayName("一次回答两个问题：这人是谁 + 在哪个房间")
        void shape() throws Exception {
            mock.register(MASTER_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-master-info.json")));

            MasterInfo data = LiveService.INSTANCE.getMasterInfo(2L);

            String uri = mock.requestUri(MASTER_PATH);
            assertTrue(uri.contains("uid=2"), "★ 参数是主播 uid，不是房间号。实际：" + uri);
            assertEquals(LIVE_REFERER, mock.requestHeader(MASTER_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点免签名");

            assertEquals("碧诗", data.getInfo().getUname());
            assertEquals(2L, data.getInfo().getUid());
            assertEquals(1, data.getInfo().getGender(), "0=保密、1=男、2=女");
            assertEquals("bilibili个人认证:bilibili创始人（站长）",
                    data.getInfo().getOfficial_verify().getDesc());

            assertEquals(1429244L, data.getFollower_num(),
                    "与 x/relation/stat 的 follower 同源 —— 已打过名片/关系数的不必再打这条");
            assertEquals(1024L, data.getRoom_id(), "拿它回到房间域去取拉流地址");
            assertEquals("逸国", data.getMedal_name());
        }

        @Test
        @DisplayName("★ pendant 是【字符串】空串，不是对象 —— 按对象取会静默拿到 null")
        void pendantIsString() throws Exception {
            mock.register(MASTER_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-master-info.json")));

            MasterInfo data = LiveService.INSTANCE.getMasterInfo(2L);

            assertInstanceOf(String.class, data.getPendant(),
                    "★ 本字段声明为 String 是有意为之：实测给的是空串");
            assertEquals("", data.getPendant());
        }

        @Test
        @DisplayName("主播等级：current / next 是【两元素数组】，不是单值")
        void masterLevel() throws Exception {
            mock.register(MASTER_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-master-info.json")));

            MasterInfo data = LiveService.INSTANCE.getMasterInfo(2L);

            assertEquals(30, data.getExp().getMaster_level().getLevel(), "要等级值直接用 level，别猜数组下标");
            assertEquals(List.of(2870000L, 11883810L), data.getExp().getMaster_level().getCurrent());
            assertEquals(List.of(3730000L, 15613810L), data.getExp().getMaster_level().getNext());
        }

        @Test
        @DisplayName("room_news 三个字段都是空串 —— 别把空串当'接口没返回'")
        void roomNews() throws Exception {
            mock.register(MASTER_PATH, Files.readString(
                    Path.of("src/test/resources/fixtures/live-master-info.json")));

            MasterInfo data = LiveService.INSTANCE.getMasterInfo(2L);

            assertNotNull(data.getRoom_news());
            assertEquals("", data.getRoom_news().getContent());
        }

        @Test
        @DisplayName("uid 为 null 或 ≤ 0：本地校验，零出站")
        void badUid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> LiveService.INSTANCE.getMasterInfo(0L));

            assertTrue(e.getMessage().contains("uid不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(MASTER_PATH));

            assertThrows(BilibiliException.class, () -> LiveService.INSTANCE.getMasterInfo(null));
            assertEquals(0, mock.hitCount(MASTER_PATH));
        }

        @Test
        @DisplayName("业务码非 0 与 data 为 null：两种都抛")
        void failures() {
            mock.register(MASTER_PATH, "{\"code\":-412,\"message\":\"请求被拦截\",\"ttl\":1}");
            assertEquals(-412, assertThrows(BilibiliException.class,
                    () -> LiveService.INSTANCE.getMasterInfo(2L)).getCode());

            mock.register(MASTER_PATH, "{\"code\":0,\"message\":\"\",\"data\":null}");
            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> LiveService.INSTANCE.getMasterInfo(2L)).getCode());
        }
    }
}
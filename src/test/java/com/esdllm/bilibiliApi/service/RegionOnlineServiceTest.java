package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.OnlineTotal;
import com.esdllm.bilibiliApi.model.data.pojo.video.RegionOnline;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#getRegionOnlineCount} 的回归测试</b>（C2 批，2026-10-02）。
 *
 * <p>🔴 本文件存在的<b>首要理由</b>是把这条钉死：
 * <b>{@code x/web-interface/online} 与 {@code x/player/online/total} 不是一回事。</b>
 * 两者名字极像，实测在同一分钟内：
 * <pre>
 *   online/total      -> {"total":"690","count":"215",…}      单个视频，百量级
 *   web-interface/online -> {"region_count":{"160":159948,…}} 全站分区，26 个键、合计 349420
 * </pre>
 * <b>差约三个数量级（实测相差 500~7000 倍）</b>。混用会得出"这个视频有 34 万人在看"，
 * 而且不会抛异常。
 *
 * <p>所以这里不只断言"能解析"，还断言 <b>*形状*</b>：本端点的 {@code data} 里
 * <b>没有</b> {@code total} / {@code count} 这两个键，只有 {@code region_count}。
 *
 * <p>🔴 <b>另一条同样要钉死的：本端点没有参数。</b>2026-10-02 六格实测 ——
 * 无参 / {@code ?bvid=BV1BqhB6nEdN} / {@code ?bvid=BV1xx411c7mD} / {@code ?bvid=garbage} /
 * {@code ?bvid=} / {@code ?aid=1} <b>逐字节相同</b>（26 分区 / 合计 379006）。
 * ⇒ 出站 URI 必须<b>逐字等于路径</b>（连 {@code ?} 都没有）——
 * 这正是"有人想给它加个 {@code bvid} 参数"时该拦住的地方。
 *
 * <p>📌 夹具是真机原样（2026-10-02 匿名抓取，26 个分区）。
 * ⚠️ 夹具里的<b>数值</b>是抓取那一刻的快照，会过时 —— 断言只针对"形状与少量代表性取值"，
 * 不针对"当前在线人数真的是多少"。
 */
@DisplayName("服务：VideoService#getRegionOnlineCount（全站分区在线人数）")
class RegionOnlineServiceTest {

    /** 无参数：出站 URI 必须逐字等于路径（见类注释里的六格实测） */
    private static final String ONLINE_PATH = "/x/web-interface/online";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Nested
    @DisplayName("取数")
    class HappyPathTest {

        @Test
        @DisplayName("解析出 26 个分区的在线人数（键是字符串形式的数字）")
        void parsesRegionCountMap() throws Exception {
            mock.register(ONLINE_PATH, fixture("region-online.json"));

            RegionOnline data = VideoService.INSTANCE.getRegionOnlineCount();

            assertEquals(26, data.getRegion_count().size(), "夹具含 26 个分区");
            assertEquals(159948L, data.getRegion_count().get("160"), "键必须是字符串 \"160\"");
            assertEquals(59739L, data.getRegion_count().get("4"));
            assertEquals(15130L, data.getRegion_count().get("1"));
            assertEquals(0L, data.getRegion_count().get("165"),
                    "0 是合法值（该分区此刻没人），不是缺失");
        }

        @Test
        @DisplayName("★ 形状与 OnlineTotal 不同：本端点没有 total / count")
        void isNotOnlineTotal() {
            // 用"类字段"层面确认两者不是同一个东西（不依赖 fixture，纯结构断言）
            assertThrows(NoSuchFieldException.class,
                    () -> RegionOnline.class.getDeclaredField("total"),
                    "RegionOnline 不该有 total —— 那是 OnlineTotal 的字段");
            assertThrows(NoSuchFieldException.class,
                    () -> RegionOnline.class.getDeclaredField("count"));
            // 反向：OnlineTotal 有 total，说明两个类确实描述不同的端点
            assertDoesNotThrow(() -> OnlineTotal.class.getDeclaredField("total"));
        }
    }

    @Nested
    @DisplayName("出站形状")
    class RequestShapeTest {

        @Test
        @DisplayName("★ 无参数：出站 URI 逐字等于路径，连 ? 都没有")
        void sendsNoQueryAtAll() throws Exception {
            mock.register(ONLINE_PATH, fixture("region-online.json"));

            VideoService.INSTANCE.getRegionOnlineCount();

            String uri = mock.requestUri(ONLINE_PATH);
            assertEquals(ONLINE_PATH, uri,
                    "该端点不接受任何输入（六格实测：各种 bvid / aid 都逐字节相同）—— "
                            + "URI 里出现 query 就说明有人给它加了参数，实际：" + uri);
            assertFalse(uri.contains("?"), "不能有 query，实际：" + uri);
        }

        @Test
        @DisplayName("Referer 指向站根（不借某个视频页）")
        void sendsSiteRootReferer() throws Exception {
            mock.register(ONLINE_PATH, fixture("region-online.json"));

            VideoService.INSTANCE.getRegionOnlineCount();

            String referer = mock.requestHeader(ONLINE_PATH, "Referer");
            assertNotNull(referer);
            // 该端点是**全站**统计、与具体视频无关 ⇒ 用站根；实测站根 / 视频页 / 空间页等价，
            // 外域 Referer 会被 HTTP 403（见 VideoService 的 javadoc）。
            assertTrue(referer.startsWith("https://www.bilibili.com/"), "实际：" + referer);
            assertFalse(referer.contains("BV"), "不该借某个视频页当 Referer，实际：" + referer);
        }
    }

    @Nested
    @DisplayName("失败语义")
    class FailureTest {

        @Test
        @DisplayName("code=0 但 region_count 为空 -> 抛异常（不能返回一张空分布图）")
        void throwsOnEmptyRegionCount() {
            mock.register(ONLINE_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"data\":{\"region_count\":{}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getRegionOnlineCount());
            assertTrue(e.getMessage().contains("region_count"), "实际：" + e.getMessage());
        }
    }
}

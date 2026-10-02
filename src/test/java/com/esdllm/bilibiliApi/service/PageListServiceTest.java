package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.Pages;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#getParts} 的回归测试</b>（C1 批，2026-09-24）。
 *
 * <p>本端点本身很简单（一个参数、一个裸数组），所以重点不在"能不能解析"，而在两条
 * <b>看代码看不出来</b>的性质：
 *
 * <ol>
 *   <li>🔴 <b>它与 {@code view.pages[]} 共用同一个 {@code Pages} 类</b>，而后者是前者的
 *       <b>子集</b>（少 {@code first_frame} / {@code ctime} / {@code vid} / {@code weblink}）
 *       ⇒ <b>同一个类的字段"有没有值"取决于数据从哪条路来</b>。
 *       本文件把这一点摆成两个用例并排，免得后人从邻居外推。</li>
 *   <li><b>空数组要当失败抛</b>：任何稿件至少有一个分P，"零个分P"只可能是形状变了 ——
 *       与 {@code LiveService#getLiveAreas} 同一处理。</li>
 * </ol>
 */
@DisplayName("服务：VideoService#getParts（分P列表）")
class PageListServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String PARTS_PATH = "/x/player/pagelist";
    private static final String SAMPLE_BVID = "BV1BqhB6nEdN";

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
        @DisplayName("解析出 4 个分P：cid / page / part / duration 逐项对上")
        void parsesAllParts() throws Exception {
            mock.register(PARTS_PATH, fixture("pagelist.json"));

            List<Pages> parts = VideoService.INSTANCE.getParts(SAMPLE_BVID);

            assertEquals(4, parts.size(), "夹具里是 4 个分P（该样本 videos=4）");
            Pages first = parts.get(0);
            assertEquals(42082042159L, first.getCid());
            assertEquals(1, first.getPage());
            assertEquals(175L, first.getDuration());
            assertTrue(first.getPart().contains("沃雅妮莎"), "实际：" + first.getPart());
            assertEquals("vupload", first.getFrom());
            // 第 4 个分P 是"韩-…"，证明顺序没被打乱
            assertEquals(4, parts.get(3).getPage());
            assertTrue(parts.get(3).getPart().startsWith("韩-"), "实际：" + parts.get(3).getPart());

            assertTrue(mock.requestUri(PARTS_PATH).contains("bvid=" + SAMPLE_BVID),
                    "实际：" + mock.requestUri(PARTS_PATH));
        }

        @Test
        @DisplayName("匿名即通：一次 nav 都不该打")
        void anonymousNeedsNoCredential() throws Exception {
            mock.register(PARTS_PATH, fixture("pagelist.json"));

            VideoService.INSTANCE.getParts(SAMPLE_BVID);

            assertEquals(0, mock.hitCount(NAV_PATH), "★ 该端点匿名可用，不该为了它去探登录态");
        }

        @Test
        @DisplayName("★ 本端点专有的两个键：first_frame / ctime")
        void extraKeysOnlyFromPageList() throws Exception {
            mock.register(PARTS_PATH, fixture("pagelist.json"));

            Pages first = VideoService.INSTANCE.getParts(SAMPLE_BVID).get(0);

            assertNotNull(first.getFirst_frame(), "pagelist 会给分P首帧图");
            assertTrue(first.getFirst_frame().startsWith("http://"),
                    "★ 实测是 http:// 开头（和 VideoBrief#getPic 同一情况），展示前要归一化。实际："
                            + first.getFirst_frame());
            assertEquals(1789986535L, first.getCtime());
            assertNotNull(first.getDimension());
            assertEquals(2560, first.getDimension().getWidth());
        }
    }

    @Nested
    @DisplayName("🔴 同一个 Pages 类，字段有没有值取决于数据从哪条路来")
    class SharedModelTest {

        @Test
        @DisplayName("★ view.pages[] 那一代的元素（只有 6 键）→ first_frame / ctime 是 null，不是 0")
        void viewStyleElementLeavesExtraKeysNull() throws Exception {
            // view 的 pages[] 元素实测只有 6 个键：cid/page/from/part/duration/dimension
            mock.register(PARTS_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":["
                            + "{\"cid\":9,\"page\":1,\"from\":\"vupload\",\"part\":\"P1\","
                            + "\"duration\":10,\"dimension\":{\"width\":1,\"height\":1,\"rotate\":0}}]}");

            Pages onlyViewStyle = VideoService.INSTANCE.getParts(SAMPLE_BVID).get(0);

            assertEquals(9L, onlyViewStyle.getCid(), "共用字段照常解析");
            assertNull(onlyViewStyle.getFirst_frame(),
                    "★ 空缺字段是 null（不是 0 也不是空串）—— 同一个类服务两条路，别假设字段齐全");
            assertNull(onlyViewStyle.getCtime());
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailureTest {

        @Test
        @DisplayName("bvid 为空 → 抛异常且没发请求")
        void blankBvid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getParts(" "));
            assertTrue(e.getMessage().contains("BV号不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(PARTS_PATH));
        }

        @Test
        @DisplayName("空数组 → 抛异常（任何稿件都至少有一个分P）")
        void emptyArrayFails() throws Exception {
            mock.register(PARTS_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":[]}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getParts(SAMPLE_BVID));
            assertTrue(e.getMessage().contains("分P列表为空"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("业务码非 0 → BilibiliException，码值带出来")
        void businessCode() throws Exception {
            mock.register(PARTS_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getParts(SAMPLE_BVID));
            assertEquals(-404, e.getCode());
        }
    }
}

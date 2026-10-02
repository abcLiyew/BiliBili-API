package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.PreciousList;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoBrief;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#getPrecious} 的回归测试</b>（C1 批，2026-09-24）。
 *
 * <p>本文件重点防守的<b>不是解析，而是"这个端点没有分页"这件事本身</b>：
 * <pre>
 * 2026-09-24 实测（原样 98 条，五格入参全部相同）：
 *   不带参数 / ?page=1&amp;page_size=20 / ?page=1&amp;page_size=5 /
 *   ?page=2&amp;page_size=20 / ?page_size=1
 * </pre>
 * ⇒ 既然端点的分页旋钮<b>转不动</b>，库内就<b>一个都不发</b>。本文件用
 * <b>"出站 URI 里连 {@code ?} 都没有"</b>把这条钉住 —— 后人不小心把 {@code pn}/{@code ps}
 * 加进方法签名时，这里会红。
 *
 * <p>⚠️ <b>夹具被裁剪过</b>：真机响应是 98 条（164 KB），
 * {@code fixtures/popular-precious.json} 只留了前 <b>3</b> 条以便提交。
 * 所以断言里的"3 条"是<b>夹具的规模</b>，不是端点的规模。
 */
@DisplayName("服务：VideoService#getPrecious（入站必刷）")
class PopularPreciousServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String PRECIOUS_PATH = "/x/web-interface/popular/precious";

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
        @DisplayName("解析出专题信息与条目（title / media_id / explain / list）")
        void parsesPrecious() throws Exception {
            mock.register(PRECIOUS_PATH, fixture("popular-precious.json"));

            PreciousList data = VideoService.INSTANCE.getPrecious();

            assertEquals("入站必刷", data.getTitle());
            assertEquals(496307088L, data.getMedia_id());
            assertTrue(data.getExplain().contains("宝藏视频"), "实际：" + data.getExplain());
            assertEquals(3, data.getList().size(), "夹具裁剪后剩 3 条（真机是 98 条）");

            VideoBrief first = data.getList().get(0);
            assertEquals(898762590L, first.getAid());
            assertEquals("BV1MN4y177PB", first.getBvid(), "★ 元素形状与热门/排行榜同一代，共用 VideoBrief");
            assertNotNull(first.getOwner());
            assertNotNull(first.getOwner().getName(), "UP 主名要从 owner 里取");
            assertNull(first.getScore(), "★ 它不是排行榜，score 恒为 null —— 别拿 null 当数据坏了");
        }

        @Test
        @DisplayName("匿名即通：一次 nav 都不该打")
        void anonymousNeedsNoCredential() throws Exception {
            mock.register(PRECIOUS_PATH, fixture("popular-precious.json"));

            VideoService.INSTANCE.getPrecious();

            assertEquals(0, mock.hitCount(NAV_PATH), "★ 该端点匿名可用（且带凭据结果相同）");
        }
    }

    @Nested
    @DisplayName("🔴 这个端点没有分页：库内一个分页参数都不发")
    class NoPagingTest {

        @Test
        @DisplayName("★ 出站 URI 里连 ? 都没有 —— 后人不许把 page/page_size 加回来")
        void noQueryAtAll() throws Exception {
            mock.register(PRECIOUS_PATH, fixture("popular-precious.json"));

            VideoService.INSTANCE.getPrecious();

            String uri = mock.requestUri(PRECIOUS_PATH);
            assertEquals(PRECIOUS_PATH, uri,
                    "★ 本端点实测完全不吃分页参数（五格入参长度与首条全同，见 BilibiliEndpoint 的实测表），"
                            + "所以库内刻意不发任何参数。实际 URI：" + uri);
            assertFalse(uri.contains("?"), "不该有任何 query。实际：" + uri);
            // 带分隔符的反向断言（本库踩过子串误判：assertFalse(uri.contains("mid=2"))
            // 在 URI 为 …?vmid=2 时恒真）
            assertFalse(uri.contains("?page="), "实际：" + uri);
            assertFalse(uri.contains("&page="), "实际：" + uri);
            assertFalse(uri.contains("page_size"), "实际：" + uri);
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailureTest {

        @Test
        @DisplayName("空 list → 抛异常（必刷专题不会没有内容）")
        void emptyListFails() throws Exception {
            mock.register(PRECIOUS_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":"
                            + "{\"title\":\"入站必刷\",\"media_id\":1,\"explain\":\"x\",\"list\":[]}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getPrecious());
            assertTrue(e.getMessage().contains("list 为空"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("业务码非 0 → BilibiliException，码值带出来")
        void businessCode() throws Exception {
            mock.register(PRECIOUS_PATH, "{\"code\":-352,\"message\":\"风控校验失败\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getPrecious());
            assertEquals(-352, e.getCode());
        }

        @Test
        @DisplayName("code=0 但 data 为空 → 抛异常")
        void emptyData() throws Exception {
            mock.register(PRECIOUS_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":null}");

            assertThrows(BilibiliException.class, () -> VideoService.INSTANCE.getPrecious());
        }
    }
}

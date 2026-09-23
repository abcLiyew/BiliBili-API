package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#isNoteForbidden} 的回归测试</b>（B5 批，2026-09-23）。
 *
 * <p>这个端点本身极小（一个参数、一个布尔），所以本文件的重点<b>不在解析</b>，
 * 而在两条"只看代码看不出来、必须靠实测才知道"的性质：
 *
 * <ol>
 *   <li>🔴 <b>它不校验 {@code aid} 是否存在。</b>实测 {@code aid=1}（一个不存在的稿件）
 *       照样返回 {@code code=0} + 一个布尔 ⇒ <b>"调用成功"不是"稿件存在"的证明</b>。
 *       本文件用"不存在的 aid 也能拿到值"把这条直接钉住 —— 否则后人很容易在本库其它
 *       {@code -404} 端点的经验上，以为这里是"传错 id 会报错"。</li>
 *   <li>⚠️ 它是<b>匿名可用、且不需要签名</b>的（与凭据无关）⇒ 断言一次 {@code nav} 都不打。</li>
 * </ol>
 *
 * <p>📌 与 {@code x/note/info}（取笔记正文）的区别：那个端点需要真实 {@code cvid}、
 * 本库拿不到入口参数（见 {@code API_FACTS.md} §2.17），<b>不做</b>。
 * 本方法是"能不能进笔记"的只读查询，与"笔记内容"无关。
 */
@DisplayName("服务：VideoService#isNoteForbidden（笔记入口禁令）")
class NoteForbidServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String FORBID_PATH = "/x/note/is_forbid?aid=";

    /** 与 {@code note-isforbid.json} 对齐的样本 aid（来自 2026-09-23 实测） */
    private static final long AID = 80433022L;

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
        @DisplayName("解析出布尔：false 表示笔记入口可用")
        void notForbidden() throws Exception {
            mock.register(FORBID_PATH, fixture("note-isforbid.json"));

            assertFalse(VideoService.INSTANCE.isNoteForbidden(AID));

            assertTrue(mock.requestUri(FORBID_PATH).contains("aid=" + AID),
                    "实际：" + mock.requestUri(FORBID_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 匿名即通，一次 nav 都不该打（文档容易把它标成 Wbi，实测不是）");
        }

        @Test
        @DisplayName("true 也要读得出来（别把布尔写反）")
        void forbidden() throws Exception {
            mock.register(FORBID_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"data\":{\"forbid_note_entrance\":true}}");

            assertTrue(VideoService.INSTANCE.isNoteForbidden(AID));
        }

        @Test
        @DisplayName("字段缺失 → 按 false 处理，不抛也不 NPE")
        void missingFieldIsFalse() throws Exception {
            mock.register(FORBID_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{}}");

            assertFalse(assertDoesNotThrow(() -> VideoService.INSTANCE.isNoteForbidden(AID)));
        }

        @Test
        @DisplayName("aid ≤ 0 → 抛异常且没发请求")
        void invalidAid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.isNoteForbidden(0L));
            assertTrue(e.getMessage().contains("aid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(FORBID_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH));
        }
    }

    @Nested
    @DisplayName("🔴 它不校验 aid 是否存在（本端点头号坑）")
    class DoesNotValidateAidTest {

        @Test
        @DisplayName("★ 不存在的 aid 也照样 code=0 —— 成功≠稿件有效")
        void nonexistentAidStillSucceeds() throws Exception {
            // 实测：aid=1（不存在的稿件）→ code=0 + forbid_note_entrance=false
            mock.register(FORBID_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"data\":{\"forbid_note_entrance\":false}}");

            assertFalse(VideoService.INSTANCE.isNoteForbidden(1L),
                    "★ 本端点不核对 aid。请勿把它当'稿件存在性检查'用 —— "
                            + "要确认稿件建议先打一次 x/web-interface/view");
        }

        @Test
        @DisplayName("★ 对照：同一个不存在的 bvid，在 view 上是 62012 —— 两个端点的'严格程度'不同")
        void viewIsStricter() throws Exception {
            // 端点各自的严格程度不同：view 侧会对不存在的稿件报 62012，而 is_forbid 一律 code=0。
            // 用 mock 把两种形态并排摆出来，免得后人"从邻居外推"。
            mock.register("/x/web-interface/view/detail?bvid=",
                    "{\"code\":62012,\"message\":\"62012\",\"ttl\":1}");
            mock.register(FORBID_PATH, fixture("note-isforbid.json"));

            BilibiliException fromView = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getViewDetail("BV1N741127Tj"));
            assertEquals(62012, fromView.getCode(),
                    "走 requireData 的路径会把业务码带出来（而旧 doGet 路径只给 0）");

            assertFalse(VideoService.INSTANCE.isNoteForbidden(AID),
                    "is_forbid 这边却是正常返回 —— 两个端点对'id 无效'的态度完全不同");
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailureTest {

        @Test
        @DisplayName("业务码非 0 → BilibiliException，码值带出来")
        void businessCode() throws Exception {
            mock.register(FORBID_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.isNoteForbidden(AID));
            assertEquals(-404, e.getCode());
            assertTrue(e.getMessage().contains("获取笔记入口状态失败"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("code=0 但 data 为空 → 抛异常，不静默当 false")
        void emptyData() throws Exception {
            mock.register(FORBID_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":null}");

            assertThrows(BilibiliException.class, () -> VideoService.INSTANCE.isNoteForbidden(AID),
                    "★ 单布尔端点的'没有 data'只可能是解析异常，不该被读成'入口可用'");
        }
    }
}

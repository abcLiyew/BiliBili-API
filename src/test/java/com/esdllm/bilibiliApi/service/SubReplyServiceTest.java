package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.comment.SubReplyPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code CommentService#getSubReplies}</b>（楼中楼）的回归测试（2026-09-22 B2 批 #4）。
 *
 * <p>本文件守三件<b>光看代码看不出来</b>的事 —— 它们都是"一级评论"与"二级评论"
 * <b>看起来像、其实不一样</b>的地方：
 * <ol>
 *   <li>🔴 <b>楼中楼只有一层</b>：实测每条 {@code replies[].replies} 都是 {@code null}。
 *       写递归去"再取一层"永远拿到空。</li>
 *   <li>🔴 <b>本端点没有 {@code page.acount}、{@code upper} 也只有 {@code mid}</b>。
 *       本库刻意复用了评论列表的 {@code Page}/{@code Upper}（字段是超集），
 *       代价就是这两个 {@code null} —— 断言它们为 null，是为了把"这是端点的形状"钉住，
 *       免得后人以为是映射漏了。</li>
 *   <li>🔴 <b>{@code root} 是一级评论的 {@code rpid}</b>，不是 aid / bvid：
 *       {@code replies[1].parent} 指向的是 {@code replies[0].rpid}（楼中楼里回复另一个人），
 *       这与 {@code root} 字段是两回事，别混。</li>
 * </ol>
 */
@DisplayName("服务：CommentService#getSubReplies（楼中楼）")
class SubReplyServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String SUB_PATH = "/x/v2/reply/reply";

    /** 与 {@code sub-reply-page.json} 的 {@code replies[0].oid} 一致 */
    private static final long AID = 117284131638286L;
    /** 一级评论的 rpid（`root` 参数要的就是它） */
    private static final long ROOT_RPID = 314487292657L;
    /** 楼中楼第一条 */
    private static final long FIRST_SUB_RPID = 314487658177L;

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

    // ================================================================
    // 形状
    // ================================================================

    @Nested
    @DisplayName("响应形状（★ 与一级评论的三处差异）")
    class ShapeTest {

        @Test
        @DisplayName("replies 与 root 都拿得到；root 就是被回复的那条一级评论")
        void rootAndReplies() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            assertEquals(2, data.getReplies().size());
            assertEquals(ROOT_RPID, data.getRoot().getRpid(),
                    "★ root 表示「我在回复谁」—— 它不是容器，是那一条评论本身");
            assertEquals(AID, data.getRoot().getOid());
            assertEquals("企鹅要做好人", data.getReplies().get(0).getMember().getUname());
        }

        @Test
        @DisplayName("★ 楼中楼只有一层：每条 replies[].replies 都是 null")
        void onlyOneLevel() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            for (var sub : data.getReplies()) {
                assertNull(sub.getReplies(),
                        "★ 实测本端点不给更深一层（是 null，不是空数组）。"
                                + "写递归去'再取一层'永远拿到空 —— 楼中楼就是一层。rpid=" + sub.getRpid());
            }
        }

        @Test
        @DisplayName("★ page 少 acount、upper 少 top（复用一级的 POJO ⇒ 这两个字段恒为 null）")
        void differencesFromMainList() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            assertEquals(16, data.getPage().getCount(), "count 是楼中楼总数（服务端原值）");
            assertEquals(1, data.getPage().getNum());
            assertEquals(20, data.getPage().getSize());
            assertNull(data.getPage().getAcount(),
                    "★ 本端点不给 acount（一级评论列表才给）—— null 是形状，不是漏映射");

            assertEquals(346749212L, data.getUpper().getMid());
            assertNull(data.getUpper().getTop(),
                    "★ 本端点不给 upper.top（一级评论列表才给）—— 同样是形状，不是漏映射");
        }

        @Test
        @DisplayName("★ root 与 parent 是两个字段：parent 指向楼中楼里被回复的那条")
        void rootVersusParent() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            var first = data.getReplies().get(0);
            var second = data.getReplies().get(1);

            assertEquals(ROOT_RPID, first.getRoot(), "两条的 root 都是那条一级评论");
            assertEquals(ROOT_RPID, second.getRoot());
            assertEquals(ROOT_RPID, first.getParent(), "第一条直接在回复一级评论");
            assertEquals(FIRST_SUB_RPID, second.getParent(),
                    "★ 第二条在回复【第一条楼中楼】—— parent 与 root 不同值。"
                            + "想做'回复树'必须用 parent，用 root 会把所有节点拍平到一层");
        }

        @Test
        @DisplayName("config / control 是评论区 UI 状态，本库原样收下不做映射")
        void uiBlocks() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            assertNotNull(data.getConfig());
            assertFalse(data.getConfig().getBooleanValue("read_only"));
            assertNotNull(data.getControl());
            assertEquals(19, data.getControl().size(),
                    "输入框状态有 19 个键 —— 保留原始 JSONObject 只为'需要时能看到'，不逐个映射");
        }
    }

    // ================================================================
    // 请求形状
    // ================================================================

    @Nested
    @DisplayName("请求形状")
    class RequestTest {

        @Test
        @DisplayName("★ oid=aid、root=rpid、type=1；参数名是 root 不是 rpid")
        void queryShape() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            String uri = mock.requestUri(SUB_PATH);
            assertTrue(uri.contains("oid=" + AID), "实际：" + uri);
            assertTrue(uri.contains("root=" + ROOT_RPID),
                    "★ 参数名是 root（一级评论的 rpid）。实际：" + uri);
            assertFalse(uri.contains("rpid=" + ROOT_RPID),
                    "★ 别写成 rpid= —— 参数名错了只会得到 4101139「参数名错误」这类误导性文案。实际：" + uri);
            assertTrue(uri.contains("type=1"), "实际：" + uri);
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不需要签名（实测匿名 code=0）");
        }

        @Test
        @DisplayName("分页被夹到合法范围：pn ≤ 0 → 1、ps ≤ 0 → 20")
        void pagingClamped() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 0, 0);

            String uri = mock.requestUri(SUB_PATH);
            assertTrue(uri.contains("pn=1"), "实际：" + uri);
            assertTrue(uri.contains("ps=20"), "实际：" + uri);
        }

        @Test
        @DisplayName("Referer 用站根")
        void referer() throws Exception {
            mock.register(SUB_PATH, fixture("sub-reply-page.json"));

            CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            assertEquals("https://www.bilibili.com/", mock.requestHeader(SUB_PATH, "Referer"));
        }
    }

    // ================================================================
    // 失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("aid ≤ 0 / root ≤ 0：本地校验，零出站")
        void badIds() {
            BilibiliException e1 = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getSubReplies(0L, ROOT_RPID, 1, 20));
            assertTrue(e1.getMessage().contains("aid不能小于0"), "实际：" + e1.getMessage());

            BilibiliException e2 = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getSubReplies(AID, 0L, 1, 20));
            assertTrue(e2.getMessage().contains("root不能小于0"), "实际：" + e2.getMessage());
            assertTrue(e2.getMessage().contains("rpid"),
                    "★ 文案里必须点出 root 是什么，否则调用方只会看到'root 不能小于 0'而不知该填什么");

            assertEquals(0, mock.hitCount(SUB_PATH));
        }

        @Test
        @DisplayName("★ 空楼中楼是合法结果：不抛异常（这条评论就是没人回）")
        void emptyRepliesIsLegal() throws Exception {
            mock.register(SUB_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{"
                    + "\"page\":{\"num\":1,\"size\":20,\"count\":0},"
                    + "\"upper\":{\"mid\":1},\"replies\":[],\"root\":null,"
                    + "\"config\":{},\"control\":{}}}");

            SubReplyPage data = CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20);

            assertNotNull(data);
            assertTrue(data.getReplies().isEmpty(),
                    "★ 与收藏夹那条刻意不同：没人回复是合法的，不能抛");
            assertEquals(0, data.getPage().getCount());
        }

        @Test
        @DisplayName("data 为 null：抛异常（那才是真的没拿到东西）")
        void nullData() {
            mock.register(SUB_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20)).getCode());
        }

        @Test
        @DisplayName("-101 未登录：业务码原样透传（本端点理论上不需要，但别把码吞掉）")
        void notLoggedIn() {
            mock.register(SUB_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20));

            assertEquals(-101, e.getCode());
            assertTrue(e.getMessage().contains("账号未登录"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 412 风控：码值保住 412")
        void http412() {
            mock.registerStatus(SUB_PATH, 412, "");

            assertEquals(412, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getSubReplies(AID, ROOT_RPID, 1, 20)).getCode());
        }
    }
}

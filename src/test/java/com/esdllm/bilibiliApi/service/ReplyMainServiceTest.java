package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.comment.Comment;
import com.esdllm.bilibiliApi.model.data.pojo.comment.MainReplyPage;
import com.esdllm.bilibiliApi.model.data.pojo.comment.ReplyCount;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>新版评论链路（{@code x/v2/reply/main} + {@code x/v2/reply/count}）的回归测试</b>
 * （C1 批，2026-09-24）。
 *
 * <p>🔴 <b>本文件存在的全部理由是钉住一个"会骗人的响应"</b>。
 * 2026-09-24 实测（<b>三轮独立复现</b>）、夹具 {@code reply-main.json} 就是其中一趟的原始响应：
 * <table border="1">
 *   <caption>同一个 aid、同一 UA，只换 Cookie</caption>
 *   <tr><th></th><th>{@code replies}</th><th>{@code cursor.is_end}</th>
 *       <th>{@code cursor.all_count}</th><th>翻第二页</th></tr>
 *   <tr><td>带匿名指纹</td><td><b>3</b></td><td><b>{@code true}</b>（谎报）</td><td>11062</td>
 *       <td>{@code replies=null}（all_count 一并消失）</td></tr>
 *   <tr><td><b>零 Cookie</b></td><td><b>20</b></td><td>{@code false}</td><td>11062</td><td>正常</td></tr>
 *   <tr><td>凭据</td><td><b>20</b></td><td>{@code false}</td><td>11062</td><td>正常</td></tr>
 * </table>
 * ⇒ 🔴 <b>截断的触发器是"匿名指纹"，不是"缺凭据"</b>（2026-09-24 下午变量分离，三趟复验）；
 * 本库的匿名出站默认自动领指纹，所以"库的匿名"恰好是被截的那一档。
 * 带指纹档的症状是<b>"3 条 + 到底了"</b>：不报错、不少字段、看上去完全正常 ——
 * 而真实评论数是 <b>11062</b>。本文件用三组用例把这条钉死：
 * <ol>
 *   <li>{@code AnonymousTruncationTest} —— <b>收到 3 条却拿到 all_count=11062、is_end=true</b>，
 *       并证明 {@code reply/count} 在同一条稿件上给的是同一个 11062（<b>两把尺子对齐</b>）；</li>
 *   <li>{@code PagingTest} —— 匿名回传 {@code cursor.next} 会拿到 {@code replies=null}，
 *       库内判为"服务端自相矛盾"<b>显式抛异常</b>（否则 null 会被读成"没有评论"）；
 *       同时证明 {@code all_count=0} 时同一种形状<b>不抛</b>（那才是"确实没人评论"）；</li>
 *   <li>{@code CredentialShapeTest} —— 凭据形状（{@code is_end=false}、20 条）能被正常解析，
 *       即"本库没有把 3 条写死"。</li>
 * </ol>
 */
@DisplayName("服务：新版评论链路（reply/main + reply/count）")
class ReplyMainServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String MAIN_PATH = "/x/v2/reply/main";
    private static final String COUNT_PATH = "/x/v2/reply/count";

    /** 与两个夹具同源的真实 aid（2026-09-24 预检所用样本） */
    private static final long AID = 117308542555694L;

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
    @DisplayName("取数（游标 / 评论 / UP 主）")
    class HappyPathTest {

        @Test
        @DisplayName("游标块逐项对上：all_count / is_end / mode / next / support_mode")
        void parsesCursor() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            MainReplyPage page = CommentService.INSTANCE.getMainReplies(AID);

            assertNotNull(page.getCursor(), "★ 游标必须映射 —— 少了它调用方无法翻页");
            assertEquals(11062, page.getCursor().getAll_count());
            assertEquals(3, page.getCursor().getMode(), "夹具是 mode=3（仅按热度）");
            assertEquals(2, page.getCursor().getNext(), "下一页游标");
            assertEquals(0, page.getCursor().getPrev(), "首页的 prev 是 0");
            assertEquals("热门评论", page.getCursor().getName());
            assertEquals(2, page.getCursor().getSupport_mode().size());
            assertNotNull(page.getCursor().getPagination_reply(), "实测是空对象 {} 而不是 null");
        }

        @Test
        @DisplayName("评论元素复用 Comment 类：rpid / like / dialog_str / dynamic_id 都能解析")
        void parsesReplies() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            MainReplyPage page = CommentService.INSTANCE.getMainReplies(AID);

            assertEquals(3, page.getReplies().size());
            Comment first = page.getReplies().get(0);
            assertEquals(314694777825L, first.getRpid());
            assertEquals(3741, first.getLike());
            assertEquals(0L, first.getRoot(), "主评论的 root 是 0（楼中楼才非 0）");
            // 下面两个键是 reply/main 独有的，2026-09-24 为它们给 Comment 补了字段
            assertEquals("0", first.getDialog_str(),
                    "★ 少了这个字段它就是静默 null —— fastjson2 只认同名");
            assertEquals(1250751908920950786L, first.getDynamic_id());
            assertEquals(0, page.getTop_replies().size(), "夹具里置顶评论为空列表");
            assertEquals(401742377L, page.getUpper().getMid());
            assertNull(page.getUpper().getTop(), "★ 本端点只给 mid，top 恒为 null（端点不给，不是丢了）");
        }

        @Test
        @DisplayName("匿名即通：一次 nav 都不该打（虽然只拿到 3 条）")
        void anonymousNeedsNoCredential() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            CommentService.INSTANCE.getMainReplies(AID);

            assertEquals(0, mock.hitCount(NAV_PATH), "该端点匿名即可调通（拿到的量另说）");
        }
    }

    @Nested
    @DisplayName("🔴 匿名档的静默截断 + 假终止信号（本批最危险的形态）")
    class AnonymousTruncationTest {

        @Test
        @DisplayName("★ 只给 3 条，却把 is_end 报成 true，而 all_count 是 11062")
        void threeRepliesButClaimsEndOfList() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            MainReplyPage page = CommentService.INSTANCE.getMainReplies(AID);

            assertEquals(3, page.getReplies().size(), "匿名只给 3 条");
            assertEquals(11062, page.getCursor().getAll_count(), "总数没被篡改");
            assertEquals(Boolean.TRUE, page.getCursor().getIs_end(),
                    "★ 这就是那条假信号：说总共 11062 条，却同时说『到底了』。"
                            + "按 is_end 决定要不要翻页的调用方会得出『这条稿件只有 3 条评论』"
                            + "—— 不报错、不缺字段、看上去完全正常。判终止必须结合 all_count");
        }

        @Test
        @DisplayName("★ 两把尺子对齐：reply/count 给的 11062 与 reply/main 的 all_count 是同一个数")
        void crossCheckWithReplyCount() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));
            mock.register(COUNT_PATH, fixture("reply-count.json"));

            MainReplyPage page = CommentService.INSTANCE.getMainReplies(AID);
            ReplyCount count = CommentService.INSTANCE.getReplyCount(AID);

            assertEquals(11062L, count.getCount());
            assertEquals(count.getCount().intValue(), page.getCursor().getAll_count(),
                    "★ 两个端点同源同 aid，总数必须一致。也正因为 count 不受匿名影响，"
                            + "它才是『评论到底有没有被截断』的唯一可靠依据："
                            + "匿名下 reply/main 只回 3 条，而这里始终是 11062");
            assertTrue(page.getReplies().size() < count.getCount(),
                    "3 < 11062 —— 差距就是被静默丢掉的那部分");
        }
    }

    @Nested
    @DisplayName("游标翻页")
    class PagingTest {

        @Test
        @DisplayName("★ 匿名回传 next → replies=null 且 all_count>0 → 抛异常（不许静默读成『没有评论』）")
        void anonymousSecondPageThrows() throws Exception {
            mock.register(MAIN_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                            + "\"cursor\":{\"is_begin\":false,\"prev\":0,\"next\":0,\"is_end\":true,"
                            + "\"all_count\":11062,\"mode\":3,\"support_mode\":[2,3]},"
                            + "\"replies\":null,\"top_replies\":[],\"upper\":{\"mid\":1}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_HOT, 2, 20));

            assertTrue(e.getMessage().contains("cursor.all_count=11062"),
                    "消息里要带上那个自相矛盾的数字。实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("匿名"),
                    "★ 可操作的建议必须写在 message 里（门面边界只保留 getMessage()，"
                            + "description 到不了调用方）。实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ replies=null 且 all_count 一并消失 → 同样抛（2026-09-24 下午实测：带指纹翻页连计数都被抹掉）")
        void repliesNullAndAllCountMissingThrows() throws Exception {
            // 守卫若写成 allCount != null && allCount > 0，这一格会漏过去、null 被静默上抛 ——
            // 这正是 2026-09-24 review 时用真机补出来的格子（with-buvid p2: replies=null, all_count=null）
            mock.register(MAIN_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                            + "\"cursor\":{\"is_begin\":false,\"prev\":0,\"next\":0,\"is_end\":true,"
                            + "\"mode\":3,\"support_mode\":[2,3]},"
                            + "\"replies\":null,\"top_replies\":[],\"upper\":{\"mid\":1}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_HOT, 2, 20));

            assertTrue(e.getMessage().contains("all_count 也一并消失"),
                    "★ all_count 缺失不能成为守卫的盲区。实际：" + e.getMessage());
        }

        @Test
        @DisplayName("对照：all_count=0 且 replies=null → 不抛（确实没人评论是合法结果）")
        void trulyEmptySectionDoesNotThrow() throws Exception {
            mock.register(MAIN_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                            + "\"cursor\":{\"all_count\":0,\"is_end\":true,\"next\":0,\"mode\":3},"
                            + "\"replies\":null,\"top_replies\":[],\"upper\":null}}");

            MainReplyPage page = assertDoesNotThrow(
                    () -> CommentService.INSTANCE.getMainReplies(AID),
                    "★ 判据是 all_count，不是 replies 是否为 null —— "
                            + "一律报错会把『零评论的稿件』也一起打成失败");

            assertNull(page.getReplies());
            assertEquals(0, page.getCursor().getAll_count());
        }

        @Test
        @DisplayName("游标与 ps 真的拼进了 query")
        void cursorAndPageSizeSent() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_HOT, 7, 30);

            String uri = mock.requestUri(MAIN_PATH);
            assertTrue(uri.contains("next=7"), "实际：" + uri);
            assertTrue(uri.contains("ps=30"), "实际：" + uri);
            assertTrue(uri.contains("oid=" + AID), "★ oid 是 aid。实际：" + uri);
            assertTrue(uri.contains("type=1"), "实际：" + uri);
        }
    }

    @Nested
    @DisplayName("mode 与参数边界")
    class ModeTest {

        @Test
        @DisplayName("mode=1 / 2 / 3 原样发出")
        void validModesSent() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_HOT_AND_TIME, 0, 20);
            assertTrue(mock.requestUri(MAIN_PATH).contains("mode=1"), "实际：" + mock.requestUri(MAIN_PATH));

            CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_TIME, 0, 20);
            assertTrue(mock.requestUri(MAIN_PATH).contains("mode=2"), "实际：" + mock.requestUri(MAIN_PATH));

            CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_HOT, 0, 20);
            assertTrue(mock.requestUri(MAIN_PATH).contains("mode=3"), "实际：" + mock.requestUri(MAIN_PATH));
        }

        @Test
        @DisplayName("★ mode=0 与 mode=4 都回落到 3 —— 实测 0 会被服务端归一、4 直接 -400")
        void invalidModesFallBackToHot() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            // 0：服务端会把它归一成 3（回显的 cursor.mode 是 3），本库干脆不接受 0
            CommentService.INSTANCE.getMainReplies(AID, 0, 0, 20);
            String zeroUri = mock.requestUri(MAIN_PATH);
            assertTrue(zeroUri.contains("mode=3"), "★ 不该把 0 原样发出去。实际：" + zeroUri);
            assertFalse(zeroUri.contains("mode=0"), "实际：" + zeroUri);

            // 4：上游直接 -400 invalid mode，更不该发出去
            CommentService.INSTANCE.getMainReplies(AID, 4, 0, 20);
            String fourUri = mock.requestUri(MAIN_PATH);
            assertTrue(fourUri.contains("mode=3"), "实际：" + fourUri);
            assertFalse(fourUri.contains("mode=4"), "实际：" + fourUri);
        }

        @Test
        @DisplayName("ps≤0 兜成 20；next<0 兜成 0")
        void psAndNextClamped() throws Exception {
            mock.register(MAIN_PATH, fixture("reply-main.json"));

            CommentService.INSTANCE.getMainReplies(AID, CommentService.MAIN_MODE_HOT, -5, 0);

            String uri = mock.requestUri(MAIN_PATH);
            assertTrue(uri.contains("ps=20"), "实际：" + uri);
            assertTrue(uri.contains("next=0"), "实际：" + uri);
        }

        @Test
        @DisplayName("aid ≤ 0 → 抛异常且没发请求")
        void invalidAid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getMainReplies(0L));
            assertTrue(e.getMessage().contains("aid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(MAIN_PATH));
        }
    }

    @Nested
    @DisplayName("🔑 凭据形状（本库没有把『3 条』写死）")
    class CredentialShapeTest {

        @Test
        @DisplayName("★ is_end=false + 20 条能正常解析 —— 证明截断来自服务端，不是库内限制")
        void credentialShapeIsParsed() throws Exception {
            mock.register(MAIN_PATH, credShapedBody(20));

            MainReplyPage page = CommentService.INSTANCE.getMainReplies(AID);

            assertEquals(20, page.getReplies().size(),
                    "★ 同一条代码路径，匿名给 3 条、凭据给 20 条 —— 差异全在凭据，"
                            + "库里没有任何截断逻辑");
            assertEquals(Boolean.FALSE, page.getCursor().getIs_end(),
                    "凭据档 is_end 才回到 false");
            assertEquals(3, page.getCursor().getNext(), "凭据档的下一页游标");
        }

        /** 造一份"凭据档形状"的响应：20 条评论、is_end=false、next=3 */
        private static String credShapedBody(int replyCount) {
            StringBuilder replies = new StringBuilder();
            for (int i = 0; i < replyCount; i++) {
                if (i > 0) {
                    replies.append(',');
                }
                replies.append("{\"rpid\":").append(1000 + i)
                        .append(",\"oid\":").append(AID)
                        .append(",\"type\":1,\"mid\":1,\"root\":0,\"parent\":0,")
                        .append("\"like\":0,\"invisible\":false}");
            }
            return "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                    + "\"cursor\":{\"is_begin\":true,\"prev\":0,\"next\":3,\"is_end\":false,"
                    + "\"all_count\":11062,\"mode\":3,\"support_mode\":[2,3]},"
                    + "\"replies\":[" + replies + "],\"top_replies\":[],\"upper\":{\"mid\":1}}}";
        }
    }

    @Nested
    @DisplayName("评论总数 reply/count")
    class ReplyCountTest {

        @Test
        @DisplayName("解析出 count；匿名即通")
        void parsesCount() throws Exception {
            mock.register(COUNT_PATH, fixture("reply-count.json"));

            ReplyCount count = CommentService.INSTANCE.getReplyCount(AID);

            assertEquals(11062L, count.getCount());
            assertTrue(mock.requestUri(COUNT_PATH).contains("oid=" + AID), "★ oid 是 aid");
            assertTrue(mock.requestUri(COUNT_PATH).contains("type=1"));
            assertEquals(0, mock.hitCount(NAV_PATH), "★ 匿名与带凭据取值相同，不需要登录态");
        }

        @Test
        @DisplayName("aid ≤ 0 → 抛异常且没发请求")
        void invalidAid() {
            assertThrows(BilibiliException.class, () -> CommentService.INSTANCE.getReplyCount(-1L));
            assertEquals(0, mock.hitCount(COUNT_PATH));
        }

        @Test
        @DisplayName("code=0 但 data 为空 → 抛异常")
        void emptyData() throws Exception {
            mock.register(COUNT_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":null}");

            assertThrows(BilibiliException.class, () -> CommentService.INSTANCE.getReplyCount(AID));
        }
    }
}

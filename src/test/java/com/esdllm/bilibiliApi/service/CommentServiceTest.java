package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.comment.Comment;
import com.esdllm.bilibiliApi.model.data.pojo.comment.CommentPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code CommentService}</b> 的回归测试（2026-09-22 B1 批 #6 评论列表）。
 *
 * <p>本文件守三件<b>光看代码看不出来</b>的事：
 *
 * <ol>
 *   <li>🔴 <b>{@code oid} 要的是 {@code aid}，不是 {@code bvid}。</b>
 *       传 {@code BV…} 不会报错，只会得到一个空的 {@code replies} ——
 *       与"这个视频真的没有评论"<b>完全同形</b>。所以服务层只收 {@code aid}
 *       （易错点被移到了门面，见 {@code CommentTest}），本文件断言 query 里的
 *       {@code oid} 就是数字 aid。</li>
 *   <li>🔴 <b>三块内容会重叠：{@code replies} / {@code top_replies} / {@code upper.top}。</b>
 *       夹具里 {@code top_replies[0].rpid} 与 {@code upper.top.rpid} 是<b>同一个值</b> ——
 *       这就是"要做列表就只读 {@code replies}"的实证。若本库"贴心地"把它们合并成一个大列表，
 *       调用方会看到重复项，而且没人会想到是库干的。</li>
 *   <li>🔴 <b>{@code replies} 里的楼中楼只是预览</b>（实测 3 条）。
 *       判断"这是主评论还是楼中楼"要看 {@code root} 是不是 {@code 0}，
 *       <b>不能看它出现在哪个字段里</b> —— {@code replies} 里嵌的也有 {@code root} 指向主评论的。</li>
 * </ol>
 *
 * <p>⚠️ {@code count} 与 {@code rcount} 是两个不同的数（夹具 {@code 22} vs {@code 16}）：
 * 要显示"共 N 条回复"用 {@code count}。
 *
 * <p>📌 文档把本端点标成 {@code Wbi}（需签名），<b>实测匿名 {@code code=0}</b> ——
 * 因此每个用例都断言不走签名出口。
 */
@DisplayName("服务：CommentService（评论列表）")
class CommentServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String REPLY_PATH = "/x/v2/reply";

    /** 与 {@code comment-replies.json} 的 {@code oid} 一致 */
    private static final long AID = 117284131638286L;
    /** 主评论 rpid（夹具定值） */
    private static final long MAIN_RPID = 314487292657L;
    /** 置顶评论 rpid（同时出现在 top_replies 与 upper.top） */
    private static final long TOP_RPID = 317702172304L;

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
    @DisplayName("响应形状")
    class ShapeTest {

        @Test
        @DisplayName("分页：count 是评论总数（990），num/size 是当前页参数")
        void page() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentPage data = CommentService.INSTANCE.getReplies(AID, 1, 5);

            assertNotNull(data.getPage());
            assertEquals(990, data.getPage().getCount(), "评论总数 —— 不是本页条数");
            assertEquals(1, data.getPage().getNum());
            assertEquals(5, data.getPage().getSize());
        }

        @Test
        @DisplayName("一条评论：昵称在 uname、正文在 content.message、相对时间在 reply_control.time_desc")
        void commentFields() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentPage data = CommentService.INSTANCE.getReplies(AID, 1, 5);
            Comment first = data.getReplies().get(0);

            assertEquals(MAIN_RPID, first.getRpid());
            assertEquals(AID, first.getOid(), "oid 是 aid —— 传 bvid 进来的话这里就对不上");
            assertEquals(0L, first.getRoot(), "主评论的 root 是 0，这是判断层级的唯一判据");
            assertEquals("悠悠白云苍狗", first.getMember().getUname(),
                    "昵称字段是 uname 不是 name —— 照 name 取会永远拿到 null");
            assertEquals("若不争那一纸文凭，世人怎知我寒窗苦读数载",
                    first.getContent().getMessage());
            assertEquals("2天前发布", first.getReply_control().getTime_desc(),
                    "相对时间文案只存在于列表类端点，展示给人看时比自己格式化更贴近 B 站页面");
            assertEquals(2001, first.getLike());
        }

        @Test
        @DisplayName("count 与 rcount 是两个数：共 N 条回复要用 count")
        void countVersusRcount() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            Comment first = CommentService.INSTANCE.getReplies(AID, 1, 5).getReplies().get(0);

            assertEquals(22, first.getCount(), "本条的回复总数口径");
            assertEquals(16, first.getRcount(), "当前可见的楼中楼条数 —— 与 count 不同值，别混用");
            assertNotEquals(first.getCount(), first.getRcount(),
                    "夹具刻意让两者不相等：相等的话就测不出'取错了字段'");
        }

        @Test
        @DisplayName("★ 大会员信息是 camelCase（vipType/vipStatus），与名片域的 snake_case 不能互套")
        void vipIsCamelCase() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            Comment first = CommentService.INSTANCE.getReplies(AID, 1, 5).getReplies().get(0);

            assertNotNull(first.getMember().getVip());
            assertEquals(2, first.getMember().getVip().getVipType(),
                    "评论域的 vip 用 camelCase。套用名片域的 Vip 类会静默拿到 null（且不报错）");
            assertEquals(1, first.getMember().getVip().getVipStatus());
            assertEquals(1811433600000L, first.getMember().getVip().getVipDueDate(),
                    "毫秒时间戳，不是秒 —— 当秒用会得到一个 1970 年的日期");
        }
    }

    // ================================================================
    // 三块内容的关系（本文件的核心）
    // ================================================================

    @Nested
    @DisplayName("★ replies / top_replies / upper.top 的重叠关系")
    class OverlapTest {

        @Test
        @DisplayName("★ top_replies 与 upper.top 是同一条评论（rpid 相同）—— 所以别把它们拼起来当列表")
        void topRepliesOverlapsUpperTop() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentPage data = CommentService.INSTANCE.getReplies(AID, 1, 5);

            assertNotNull(data.getTop_replies());
            assertNotNull(data.getUpper());
            assertEquals(1, data.getTop_replies().size());
            assertNotNull(data.getUpper().getTop(), "upper.top 实测不是 null");

            assertEquals(TOP_RPID, data.getTop_replies().get(0).getRpid());
            assertEquals(data.getTop_replies().get(0).getRpid(), data.getUpper().getTop().getRpid(),
                    "★ 同一 rpid 出现在两处。库若替调用方做去重合并，就说不清'谁是谁'了 —— "
                            + "所以本库不合并，只把原始形状交出来");
            assertTrue(data.getUpper().getMid() != null && data.getUpper().getMid() > 0,
                    "upper.mid 是 UP 主本人（与置顶评论的 mid 一致）");
        }

        @Test
        @DisplayName("★ 置顶评论不在 replies 里 —— replies 才是\"评论列表\"的那一块")
        void repliesIsTheList() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentPage data = CommentService.INSTANCE.getReplies(AID, 1, 5);

            Set<Long> replyIds = data.getReplies().stream()
                    .map(Comment::getRpid).collect(Collectors.toSet());
            Set<Long> topIds = data.getTop_replies().stream()
                    .map(Comment::getRpid).collect(Collectors.toSet());

            assertEquals(2, replyIds.size(), "本页正文两条，且 rpid 不重复");
            assertTrue(replyIds.contains(MAIN_RPID));
            assertFalse(replyIds.contains(TOP_RPID),
                    "夹具里置顶那条不在 replies 中。要'评论列表'就只读 replies，否则会看到重复项");
            assertFalse(topIds.isEmpty());
        }

        @Test
        @DisplayName("★ 楼中楼预览：靠 root != 0 判断，不靠它嵌在哪个字段里")
        void subRepliesArePreviews() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            Comment main = CommentService.INSTANCE.getReplies(AID, 1, 5).getReplies().get(0);

            assertNotNull(main.getReplies(), "主评论里嵌着楼中楼预览");
            assertEquals(1, main.getReplies().size(), "实测只给预览（本例 1 条），不是全部 22 条");
            Comment sub = main.getReplies().get(0);
            assertEquals(MAIN_RPID, sub.getRoot(),
                    "★ 楼中楼的 root 指向主评论的 rpid —— 这是判断层级的唯一判据");
            assertEquals(MAIN_RPID, sub.getParent());
            assertEquals("企鹅要做好人", sub.getMember().getUname());
            assertNull(sub.getReplies(),
                    "楼中楼自己没有更深一层：这里实测是 **null**（而置顶那条是空数组 []）—— "
                            + "两种'没有下级'的形状都会出现，判空要用 == null || isEmpty，别只判一种");
        }

        @Test
        @DisplayName("第二条评论也是主评论：root 为 0、无楼中楼（replies 是 null）")
        void plainComment() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            Comment second = CommentService.INSTANCE.getReplies(AID, 1, 5).getReplies().get(1);

            assertEquals(0L, second.getRoot());
            assertEquals(0, second.getCount(), "没人回复");
            assertNull(second.getReplies(), "没有楼中楼时是 null（与 empty 数组不是一回事，都别当异常）");
            assertEquals("路过的观众甲", second.getMember().getUname());
        }
    }

    // ================================================================
    // 请求参数
    // ================================================================

    @Nested
    @DisplayName("请求参数")
    class RequestTest {

        @Test
        @DisplayName("★ oid 必须是数字 aid；type=1；默认按热度（sort=2）")
        void queryShape() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentService.INSTANCE.getReplies(AID, 1, 5);

            String uri = mock.requestUri(REPLY_PATH);
            assertTrue(uri.contains("oid=" + AID), "★ 传 bvid 不会报错、只会静默空列表。实际：" + uri);
            assertFalse(uri.contains("BV"), "query 里不该出现 BV 号。实际：" + uri);
            assertTrue(uri.contains("type=1"), "type=1 表示视频评论区。实际：" + uri);
            assertTrue(uri.contains("sort=" + CommentService.SORT_HOT), "默认热度。实际：" + uri);
            assertEquals(0, mock.hitCount(NAV_PATH), "文档标 Wbi，实测免签名");
        }

        @Test
        @DisplayName("排序：0/1/2 原样透传，其它值回落到热度（不把脏值交给服务端）")
        void sortIsValidated() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentService.INSTANCE.getReplies(AID, 1, 5, CommentService.SORT_TIME);
            assertTrue(mock.requestUri(REPLY_PATH).contains("sort=0"), "实际：" + mock.requestUri(REPLY_PATH));

            CommentService.INSTANCE.getReplies(AID, 1, 5, CommentService.SORT_LIKE);
            assertTrue(mock.requestUri(REPLY_PATH).contains("sort=1"), "实际：" + mock.requestUri(REPLY_PATH));

            CommentService.INSTANCE.getReplies(AID, 1, 5, 99);
            assertTrue(mock.requestUri(REPLY_PATH).contains("sort=" + CommentService.SORT_HOT),
                    "脏值回落到热度：原样发出去只会换来一个 -400，而 -400 在这里很难与'参数名写错'区分。"
                            + "实际：" + mock.requestUri(REPLY_PATH));
        }

        @Test
        @DisplayName("分页被夹到合法范围：pn ≤ 0 → 1、ps ≤ 0 → 20")
        void pagingClamped() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentService.INSTANCE.getReplies(AID, 0, 0);

            String uri = mock.requestUri(REPLY_PATH);
            assertTrue(uri.contains("pn=1"), "实际：" + uri);
            assertTrue(uri.contains("ps=20"), "实际：" + uri);
        }

        @Test
        @DisplayName("Referer 用站根（本端点不挑 Referer，与 ranking 正相反）")
        void referer() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentService.INSTANCE.getReplies(AID, 1, 5);

            assertEquals("https://www.bilibili.com/", mock.requestHeader(REPLY_PATH, "Referer"));
        }
    }

    // ================================================================
    // 失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("aid ≤ 0：本地校验，零出站")
        void badAid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getReplies(0L, 1, 5));

            assertTrue(e.getMessage().contains("aid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(REPLY_PATH));
        }

        @Test
        @DisplayName("-101 未登录 / -404 不存在：业务码原样透传")
        void businessCodes() {
            mock.register(REPLY_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getReplies(AID, 1, 5));
            assertEquals(-101, e.getCode());
            assertTrue(e.getMessage().contains("账号未登录"), "实际：" + e.getMessage());

            mock.register(REPLY_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");
            assertEquals(-404, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getReplies(AID, 1, 5)).getCode());
        }

        @Test
        @DisplayName("data 为 null 抛异常；而 replies 为空数组是【正常结果】，不抛（评论区可以真的没人说话）")
        void emptyRepliesIsFine() throws Exception {
            mock.register(REPLY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");
            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getReplies(AID, 1, 5)).getCode());

            mock.register(REPLY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"page\":{\"num\":1,\"size\":20,\"count\":0},\"replies\":[],\"top_replies\":[]}}");
            CommentPage data = CommentService.INSTANCE.getReplies(AID, 1, 20);
            assertNotNull(data);
            assertTrue(data.getReplies().isEmpty(),
                    "与收藏夹那条刻意不同：没人评论是合法的，不能抛");
            assertEquals(0, data.getPage().getCount());
        }
    }
}

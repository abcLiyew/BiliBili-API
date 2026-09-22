package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.OnlineTotal;
import com.esdllm.bilibiliApi.model.data.pojo.video.ViewDetail;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#getViewDetail} 与 {@code #getOnlineTotal}</b> 的回归测试
 * （2026-09-22 B1 批 #1 一站式详情 / #4 在线观看数 / #5 视频状态数）。
 *
 * <p>三个需求合在一个文件、两个方法，是因为它们共享同一句结论：<b>一个响应顶三个需求</b>。
 * 本文件要守的核心不是"能不能解析 JSON"，而是下面这条<b>很容易被想当然</b>的边界：
 *
 * <p>🔴 <b>「一站式详情」并不包含评论列表。</b>
 * 实测 {@code Reply.page} 是 {@code null}、{@code replies} <b>只有 1 条</b>。
 * 若调用方按名字理解成"评论也回来了"，会得到一个永远只有 1 条的列表 ——
 * 且不报错、不告警。所以 {@code replyIsOnlyOneHotComment} 是本文件的头号用例。
 *
 * <p>另有两处形状坑由本文件钉住：
 * <ol>
 *   <li>{@code Related[].rcmd_reason} 在这里是<b>字符串</b>（{@code ""}），
 *       而在 {@code popular} 里是<b>对象</b> —— 同名字段两种形状，见 {@code VideoBrief} 的说明；
 *       本文件只覆盖"字符串"这一半，另一半在 {@code RankingServiceTest}。</li>
 *   <li>{@code OnlineTotal.total}/{@code count} 在 JSON 里是<b>字符串数字</b>（{@code "690"}），
 *       模型用 {@code Long} 接 —— 断言它真被转成了数字，而不是靠"看起来像数字"。</li>
 * </ol>
 *
 * <p><b>门槛</b>：两者都是匿名可用（实测 {@code code=0}），因此本文件的每个用例都顺带断言
 * <b>一次 {@code nav} 都不打</b> —— {@code hitCount(NAV)} 是"有没有偷偷套签名"的唯一判据。
 */
@DisplayName("服务：VideoService#getViewDetail / #getOnlineTotal（一站式详情 · 在线观看数）")
class VideoDetailServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String DETAIL_PATH = "/x/web-interface/view/detail";
    private static final String ONLINE_PATH = "/x/player/online/total";

    private static final String BVID = "BV1o2eM6kEDT";
    /** 与 {@code view-detail.json} 的 {@code View.cid} 一致 —— 两个夹具必须对得上，否则是假绿 */
    private static final long CID = 41961327629L;

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
    // 一站式详情
    // ================================================================

    @Nested
    @DisplayName("一站式详情（x/web-interface/view/detail）")
    class ViewDetailTest {

        @Test
        @DisplayName("★ 一次出站顶四项：详情 + 状态数 + 标签 + 相关推荐，全在这个响应里")
        void fourCapabilitiesInOneResponse() throws Exception {
            mock.register(DETAIL_PATH, fixture("view-detail.json"));

            ViewDetail data = VideoService.INSTANCE.getViewDetail(BVID);

            // 详情
            assertNotNull(data.getView());
            assertEquals("至此，已成神品！！！", data.getView().getTitle());
            assertEquals(117284131638286L, data.getView().getAid());
            // 状态数（B1 #5）—— 不用另打一个请求
            assertNotNull(data.getView().getStat(), "stat 内嵌在 View 里，这就是 #5 省下的那次出站");
            assertEquals(1200335L, data.getView().getStat().getView());
            assertEquals(990L, data.getView().getStat().getReply());
            // 标签（B1 #2）
            assertEquals(2, data.getTags().size());
            assertEquals("归零", extractTagName(data.getTags().get(0).getTag_name()),
                    "BGM 类标签的名字形如《归零》");
            // 相关推荐（B1 #3）
            assertEquals(2, data.getRelated().size());
            assertEquals("爱睡觉的_Koala", data.getRelated().get(0).getOwner().getName());
            // UP 主概览（复用名片域结构）
            assertNotNull(data.getCard());
            assertEquals(1343577, data.getCard().getFollower());
            assertEquals(495, data.getCard().getArchive_count());
        }

        @Test
        @DisplayName("★ Reply 只有 1 条热评，且没有分页 —— 它不是评论列表，别当评论列表用")
        void replyIsOnlyOneHotComment() throws Exception {
            mock.register(DETAIL_PATH, fixture("view-detail.json"));

            ViewDetail data = VideoService.INSTANCE.getViewDetail(BVID);

            assertNotNull(data.getReply(), "Reply 段是有的 —— 正因如此才容易被误读");
            assertNull(data.getReply().getPage(),
                    "实测 page 是 null，端点在详情里不给评论分页信息");
            assertEquals(1, data.getReply().getReplies().size(),
                    "★ 只有 1 条热评。要完整评论必须另调 x/v2/reply —— 这条边界是本文件的头号用例");
            assertEquals(314487292657L, data.getReply().getReplies().get(0).getRpid());
        }

        @Test
        @DisplayName("★ Related 里的 rcmd_reason 是【字符串】，且这类条目没有 score")
        void relatedRcmdReasonIsString() throws Exception {
            mock.register(DETAIL_PATH, fixture("view-detail.json"));

            ViewDetail data = VideoService.INSTANCE.getViewDetail(BVID);

            Object reason = data.getRelated().get(0).getRcmd_reason();
            assertInstanceOf(String.class, reason,
                    "★ 相关推荐这条路径给的是空串。若把它当对象接，反序列化会直接炸 —— "
                            + "同一个字段在 popular 里却是对象（见 RankingServiceTest）");
            assertEquals("", reason);

            assertNull(data.getRelated().get(0).getScore(),
                    "score 只有排行榜会给，相关推荐恒为 null —— 别把 null 当'这条数据坏了'");
        }

        @Test
        @DisplayName("Referer 要指向视频页，不是站根；且全程不签名")
        void requestShape() throws Exception {
            mock.register(DETAIL_PATH, fixture("view-detail.json"));

            VideoService.INSTANCE.getViewDetail(BVID);

            String uri = mock.requestUri(DETAIL_PATH);
            assertTrue(uri.startsWith(DETAIL_PATH + "?bvid=" + BVID), "实际：" + uri);
            assertEquals("https://www.bilibili.com/video/" + BVID,
                    mock.requestHeader(DETAIL_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH),
                    "本端点免签名：套上 getSigned 只会平白多一个 'nav 不可达' 的失败面");
        }

        @Test
        @DisplayName("bvid 为空 / null：本地校验抛异常，零出站")
        void blankBvid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getViewDetail("  "));

            assertTrue(e.getMessage().contains("BV号不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(DETAIL_PATH));

            assertThrows(BilibiliException.class, () -> VideoService.INSTANCE.getViewDetail(null));
            assertEquals(0, mock.hitCount(DETAIL_PATH));
        }

        @Test
        @DisplayName("业务码非 0：透传码值（含 -404 稿件不存在）")
        void businessCode() {
            mock.register(DETAIL_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getViewDetail(BVID));

            assertEquals(-404, e.getCode());
        }

        @Test
        @DisplayName("code=0 但 data 为 null：抛异常（data 为空是硬失败）")
        void nullData() {
            mock.register(DETAIL_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getViewDetail(BVID));

            assertEquals(0, e.getCode(), "外层码就是 0 —— 正因如此才不能靠它判成败");
            assertTrue(e.getMessage().contains("data 为空"), "实际：" + e.getMessage());
            // 注意：本端点**没有**"空对象 = 缺凭据"那种守卫（那是 upstat / 收藏夹那类凭据域的事）。
            // 它本就匿名可用，所以失败判据只有两个：外层 code 非 0、或 data 为 null。
        }

        @Test
        @DisplayName("HTTP 412：走 ErrorMapper 的风控分支（不是参数错）")
        void http412() {
            mock.registerStatus(DETAIL_PATH, 412, "");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getViewDetail(BVID));

            assertEquals(412, e.getCode());
        }
    }

    // ================================================================
    // 在线观看数
    // ================================================================

    @Nested
    @DisplayName("在线观看数（x/player/online/total）")
    class OnlineTotalTest {

        @Test
        @DisplayName("★ JSON 里是字符串数字（\"690\"），模型必须给我 Long")
        void stringNumbersBecomeLong() throws Exception {
            mock.register(ONLINE_PATH, fixture("online-total.json"));

            OnlineTotal data = VideoService.INSTANCE.getOnlineTotal(BVID, CID);

            assertEquals(690L, data.getTotal(), "原始形状是字符串 \"690\"，靠 fastjson 宽松转换接住");
            assertEquals(215L, data.getCount());
            assertTrue(data.getShow_switch().getTotal(), "服务端允许展示才展示");
            assertEquals("b", data.getAbtest().getGroup(), "AB 分组：B 站内部用，只做留档");
        }

        @Test
        @DisplayName("cid 是必需的：bvid 与 cid 都要出现在 query 里")
        void bothParamsOnTheWire() throws Exception {
            mock.register(ONLINE_PATH, fixture("online-total.json"));

            VideoService.INSTANCE.getOnlineTotal(BVID, CID);

            String uri = mock.requestUri(ONLINE_PATH);
            assertTrue(uri.contains("bvid=" + BVID), "实际：" + uri);
            assertTrue(uri.contains("cid=" + CID), "实际：" + uri);
            assertEquals(0, mock.hitCount(NAV_PATH), "文档标它'APP 端、需签名'，实测两者都不需要");
        }

        @Test
        @DisplayName("cid 缺失或 ≤ 0：本地校验，零出站（只给 bvid 是拿不到结果的）")
        void cidIsRequired() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getOnlineTotal(BVID, null));

            assertTrue(e.getMessage().contains("cid不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(ONLINE_PATH));

            assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getOnlineTotal(BVID, 0L));
            assertEquals(0, mock.hitCount(ONLINE_PATH));
        }

        @Test
        @DisplayName("bvid 为空也拦在前面：两个参数先后校验，别只测后一个")
        void blankBvid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getOnlineTotal("", CID));

            assertTrue(e.getMessage().contains("BV号不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(ONLINE_PATH));
        }

        @Test
        @DisplayName("业务码非 0 与 data 为 null：两种都抛，不返回 0")
        void failures() {
            mock.register(ONLINE_PATH, "{\"code\":-400,\"message\":\"请求错误\",\"ttl\":1}");
            assertEquals(-400, assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getOnlineTotal(BVID, CID)).getCode());

            // ⚠️ 判据是 data **为 null**，不是"data 是空对象"：
            // 本端点没有 upstat / 收藏夹那类凭据域才需要的"空对象守卫"，
            // 因为 {code:0, data:{}} 在这里会被解析成一个字段全 null 的 OnlineTotal 而正常返回。
            mock.register(ONLINE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");
            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getOnlineTotal(BVID, CID)).getCode());
        }
    }

    /** BGM 类标签名形如 {@code 发现《归零》}，断言时只取书名号里的部分，避免把文案写死在测试里 */
    private static String extractTagName(String tagName) {
        int open = tagName.indexOf('《');
        int close = tagName.indexOf('》');
        return (open >= 0 && close > open) ? tagName.substring(open + 1, close) : tagName;
    }
}

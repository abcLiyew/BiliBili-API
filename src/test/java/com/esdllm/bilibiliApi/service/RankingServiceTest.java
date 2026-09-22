package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.PopularList;
import com.esdllm.bilibiliApi.model.data.pojo.video.RankingList;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#getRanking} 与 {@code #getPopular}</b> 的回归测试（2026-09-22 B1 批 #11 / #12）。
 *
 * <p>两个端点合在一个文件，是因为它们<b>元素形状完全一样</b>（都复用 {@code VideoBrief}），
 * 而差别全在<b>外层</b>与<b>请求头</b>上 —— 本文件要守的正是这两处：
 *
 * <p>🔴 <b>头号用例是 {@code rankingRefererIsNotTheSiteRoot}。</b>
 * {@code ranking/v2} 对<b>站根</b> Referer（本库其它端点的默认值）会返回 {@code -352 风控校验失败}，
 * 换排行榜页就 {@code code=0}。这个现象在 2026-09-22 的预检里差点被读成"出口被封 / 需要签名"，
 * 是<b>阳性对照</b>把它救了回来（详见 {@code BilibiliEndpoint#rankingUrl} 的 4 次复现对照表）。
 * ⇒ 所以这条断言不是"顺手锁个请求头"，它锁的是<b>一次排期事故的根因</b>：
 * 改了这一行，报出来的是风控码，看起来完全不像"Referer 写错了"。
 *
 * <p>🔴<b>第二个坑是 {@code rcmd_reason} 的两种形状</b>：{@code popular} 给的是<b>对象</b>
 * （{@code {"content":"百万播放",…}}），而 {@code view/detail} 的 {@code Related} 给的是<b>字符串</b>
 * （{@code ""}，在 {@code VideoDetailServiceTest} 里覆盖）。
 * 同一个字段、同一个 POJO，两条路径两种形状 —— 所以字段声明成了 {@code Object}。
 * 本文件里 {@code rcmdReasonIsObjectHere} 与 {@code rcmdReasonMayBeNull} 一起把这一半钉住。
 *
 * <p>两者都匿名可用（实测 {@code code=0}），因此每个用例都断言<b>不签名</b>。
 */
@DisplayName("服务：VideoService#getRanking / #getPopular（排行榜 · 热门）")
class RankingServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String RANK_PATH = "/x/web-interface/ranking/v2";
    private static final String POPULAR_PATH = "/x/web-interface/popular";

    /** 与 {@code BilibiliEndpoint.rankingReferer} 同值 —— 这里是**故意**写死字面量做交叉校验 */
    private static final String RANK_REFERER = "https://www.bilibili.com/v/popular/rank/all";
    /** 与 {@code BilibiliEndpoint.referer}（站根）同值 */
    private static final String SITE_ROOT = "https://www.bilibili.com/";

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
    // 排行榜
    // ================================================================

    @Nested
    @DisplayName("排行榜（x/web-interface/ranking/v2）")
    class RankingTest {

        @Test
        @DisplayName("★ Referer 必须是排行榜页，绝不能是站根 —— 站根会换来 -352 风控")
        void rankingRefererIsNotTheSiteRoot() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));

            VideoService.INSTANCE.getRanking(0, "all");

            String referer = mock.requestHeader(RANK_PATH, "Referer");
            assertEquals(RANK_REFERER, referer,
                    "★ 这一行是本端点唯一的坑：站根 Referer 会 -352，看起来像出口被封而不是头写错");
            assertNotEquals(SITE_ROOT, referer,
                    "站根是库内其它端点的默认值，但**不能**用在这里（实测 4 次复现）");
        }

        @Test
        @DisplayName("榜单条目：score（只有排行榜给）+ owner + stat 都要能取到")
        void entryFields() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));

            RankingList data = VideoService.INSTANCE.getRanking(0, "all");

            assertEquals("数据由视频各维度真实数据加权计算得出", data.getNote(),
                    "note 是榜单口径说明，不是错误信息");
            assertEquals(2, data.getList().size());

            assertEquals(100000L, data.getList().get(0).getScore(),
                    "score 是排行榜独有的字段，名次口径");
            assertEquals("wrizedm", data.getList().get(0).getOwner().getName());
            assertEquals(1200335L, data.getList().get(0).getStat().getView());
            assertEquals("BV1o2eM6kEDT", data.getList().get(0).getBvid());
            // 排行榜的条目形状与相关推荐/热门一致，所以共用同一个 VideoBrief
            assertNull(data.getList().get(0).getRcmd_reason(),
                    "排行榜不给 rcmd_reason —— 字段共用不代表每个端点都会填");
        }

        @Test
        @DisplayName("参数：rid 与 type 都上 query；rid < 0 夹到 0、type 空白回落 all")
        void parameters() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));

            VideoService.INSTANCE.getRanking(1, "all");
            String uri = mock.requestUri(RANK_PATH);
            assertTrue(uri.contains("rid=1") && uri.contains("type=all"), "实际：" + uri);

            VideoService.INSTANCE.getRanking(-5, "  ");
            uri = mock.requestUri(RANK_PATH);
            assertTrue(uri.contains("rid=0"), "负 rid 不该原样发出去。实际：" + uri);
            assertTrue(uri.contains("type=all"), "空白 type 应回落 all。实际：" + uri);

            assertEquals(0, mock.hitCount(NAV_PATH), "本端点免签名");
        }

        @Test
        @DisplayName("业务码非 0（含 -352 风控）：透传码值")
        void riskControl() {
            mock.register(RANK_PATH, "{\"code\":-352,\"message\":\"风控校验失败\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getRanking(0, "all"));

            assertEquals(-352, e.getCode(),
                    "码值必须活下来：调用方要靠它分辨'风控'与'参数错'");
        }

        @Test
        @DisplayName("data 为 null 与 HTTP 412：两种失败都要抛")
        void otherFailures() {
            // ⚠️ 判据是 data **为 null**，不是"空对象"：{code:0,data:{}} 会被解析成一个
            // list 为 null 的 RankingList 而正常返回 —— 本端点没有凭据域那种空对象守卫。
            mock.register(RANK_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");
            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getRanking(0, "all")).getCode());

            mock.registerStatus(RANK_PATH, 412, "");
            assertEquals(412, assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getRanking(0, "all")).getCode());
        }
    }

    // ================================================================
    // 热门视频
    // ================================================================

    @Nested
    @DisplayName("热门视频（x/web-interface/popular）")
    class PopularTest {

        @Test
        @DisplayName("★ 同一个 rcmd_reason，在这里是【对象】—— 与相关推荐的字符串形态正相反")
        void rcmdReasonIsObjectHere() throws Exception {
            mock.register(POPULAR_PATH, fixture("popular.json"));

            PopularList data = VideoService.INSTANCE.getPopular(20, 1);

            Object reason = data.getList().get(0).getRcmd_reason();
            assertInstanceOf(JSONObject.class, reason,
                    "★ popular 这条路径给的是对象。字段声明为 Object 正是为了让两种形状都能反序列化");
            assertEquals("百万播放", ((JSONObject) reason).getString("content"));
        }

        @Test
        @DisplayName("rcmd_reason 也可以是 null（不是每条都有推荐理由）—— 不报错")
        void rcmdReasonMayBeNull() throws Exception {
            mock.register(POPULAR_PATH, fixture("popular.json"));

            PopularList data = VideoService.INSTANCE.getPopular(20, 1);

            assertNull(data.getList().get(1).getRcmd_reason(),
                    "第二条是 null。若把它当必填，这段数据就会被当成坏的");
            assertFalse(data.getNo_more(), "no_more=false 表示还能往下翻");
        }

        @Test
        @DisplayName("它没有 page：翻页只能靠调用方自己记住 pn；且用站根 Referer 即可")
        void noPageButSiteRootIsFine() throws Exception {
            mock.register(POPULAR_PATH, fixture("popular.json"));

            VideoService.INSTANCE.getPopular(20, 1);

            String uri = mock.requestUri(POPULAR_PATH);
            assertTrue(uri.contains("ps=20") && uri.contains("pn=1"), "实际：" + uri);
            assertEquals(SITE_ROOT, mock.requestHeader(POPULAR_PATH, "Referer"),
                    "与排行榜不同：热门对站根不敏感（同一分钟对照过），所以这里用默认 Referer");
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点免签名");
        }

        @Test
        @DisplayName("ps/pn 被夹到 ≥1：传 0 不该原样发出去")
        void pagingClamped() throws Exception {
            mock.register(POPULAR_PATH, fixture("popular.json"));

            VideoService.INSTANCE.getPopular(0, 0);

            String uri = mock.requestUri(POPULAR_PATH);
            assertTrue(uri.contains("ps=20"), "ps=0 应回落 20。实际：" + uri);
            assertTrue(uri.contains("pn=1"), "pn=0 应夹到 1。实际：" + uri);
        }

        @Test
        @DisplayName("data 为 null 也要抛：任何形态都别把'没拿到'翻译成'列表是空的'")
        void nullDataIsAnError() {
            mock.register(POPULAR_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getPopular(20, 1));

            assertEquals(0, e.getCode());
            assertTrue(e.getMessage().contains("data 为空"), "实际：" + e.getMessage());
        }
    }
}

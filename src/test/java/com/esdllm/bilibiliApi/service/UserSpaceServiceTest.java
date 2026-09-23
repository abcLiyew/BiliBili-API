package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.user.AccInfo;
import com.esdllm.bilibiliApi.model.data.pojo.user.ArchiveSearchResult;
import com.esdllm.bilibiliApi.model.data.pojo.user.SeasonsArchives;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoBrief;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UserService} 的 WBI 批新增方法测试（fixture 驱动）。
 *
 * <p>三个端点的门槛<b>各不相同</b>，这是本批最容易记错的地方，所以每个方法都单独一组：
 * <table border="1">
 *   <caption>本测试要守住的差异</caption>
 *   <tr><th>方法</th><th>走哪个出口</th><th>为什么</th></tr>
 *   <tr><td>{@link UserService#getAccInfo}</td><td>签名 GET</td>
 *       <td>签名 + 凭据都要（匿名是 {@code -352}，曾误记为匿名可用）</td></tr>
 *   <tr><td>{@link UserService#getArchives}</td><td>签名 GET</td><td>签名 + 凭据都要</td></tr>
 *   <tr><td>{@link UserService#getSeasonArchives}</td><td><b>普通 GET</b></td>
 *       <td>两者都不要 —— 若也套上签名，就白白多打一次 {@code nav} 并让失败面变大</td></tr>
 * </table>
 * "走了哪个出口"是可以用 {@code hitCount(nav)} 断言的，所以这里不是靠读代码相信，而是靠断言钉住。
 */
@DisplayName("UserService：空间信息 / 投稿 / 合集（WBI 批）")
class UserSpaceServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String ACC_PATH = "/x/space/wbi/acc/info";
    private static final String ARC_PATH = "/x/space/wbi/arc/search";
    private static final String SEAS_PATH = "/x/polymer/web-space/seasons_archives_list";
    /** B5 批：置顶视频。实测<b>参数名是 vmid</b>，传 mid 直接 -400 */
    private static final String TOP_ARC_PATH = "/x/space/top/arc?vmid=";

    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private static final long MID = 946974L;

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
    }

    @AfterEach
    void tearDown() {
        mock.close();
        WbiKeyStore.invalidate();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    // ================================================================
    // getAccInfo
    // ================================================================

    @Nested
    @DisplayName("getAccInfo")
    class AccInfoTest {

        @Test
        @DisplayName("解析出空间信息（42 个顶层键的真实夹具）")
        void happyPath() throws Exception {
            mock.register(ACC_PATH, fixture("acc-info.json"));

            AccInfo data = UserService.INSTANCE.getAccInfo(MID);

            assertEquals(946974L, data.getMid());
            assertEquals("影视飓风", data.getName());
            assertEquals(6, data.getLevel());
            assertNotNull(data.getSex());
            assertNotNull(data.getFace());
            assertNotNull(data.getSign());
            assertNotNull(data.getOfficial(), "认证信息（official.type）是 acc/info 独有的价值之一");
            assertNotNull(data.getVip(), "大会员信息");
            assertNotNull(data.getLive_room(), "live_room 是 acc/info 与 card 的差异点（此人是否在播）");
        }

        @Test
        @DisplayName("🔴 走的是签名出口（会先打一次 nav）")
        void goesThroughSignedExit() throws Exception {
            mock.register(ACC_PATH, fixture("acc-info.json"));

            UserService.INSTANCE.getAccInfo(MID);

            assertEquals(1, mock.hitCount(NAV_PATH), "必须走 getSigned —— 不签名只会拿到 -352/-403");
            String uri = mock.requestUri(ACC_PATH);
            assertTrue(uri.contains("mid=" + MID), "实际：" + uri);
            assertTrue(uri.contains("wts=") && uri.contains("w_rid="), "实际：" + uri);
            assertEquals("https://space.bilibili.com/" + MID, mock.requestHeader(ACC_PATH, "Referer"));
        }

        @Test
        @DisplayName("mid ≤ 0 → 抛异常且没发请求")
        void invalidMid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getAccInfo(0L));
            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(ACC_PATH));
        }

        @Test
        @DisplayName("未注入凭据时服务端的 -352 会变成 BilibiliException（不是静默 null）")
        void notLoggedInSurfaces() throws Exception {
            // 真实行为：匿名访问本端点返回 -352 风控校验失败（见 BilibiliEndpoint 实测表）
            mock.register(ACC_PATH, "{\"code\":-352,\"message\":\"风控校验失败\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getAccInfo(MID));
            assertEquals(-352, e.getCode());
            assertTrue(e.getMessage().contains("获取用户空间信息失败"), "实际：" + e.getMessage());
        }
    }

    // ================================================================
    // getArchives
    // ================================================================

    @Nested
    @DisplayName("getArchives")
    class ArchivesTest {

        @Test
        @DisplayName("解析投稿列表，且 page 取自 data.page（不是 data.list.page）")
        void happyPath() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            ArchiveSearchResult data = UserService.INSTANCE.getArchives(MID, 1, 5);

            assertNotNull(data.getList());
            assertEquals(3, data.getList().getVlist().size());
            assertNotNull(data.getPage(), "分页信息在 data.page 上");
            assertEquals(931, data.getPage().getCount(), "写成 list.page 会永远拿到 0/分页失效");
            assertNotNull(data.getList().getTlist(), "tlist 是分区聚合");
        }

        @Test
        @DisplayName("默认排序 pubdate；可显式换 click")
        void orderParam() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            UserService.INSTANCE.getArchives(MID, 1, 5);
            assertTrue(mock.requestUri(ARC_PATH).contains("order=pubdate"),
                    "实际：" + mock.requestUri(ARC_PATH));

            UserService.INSTANCE.getArchives(MID, 1, 5, "click");
            assertTrue(mock.requestUri(ARC_PATH).contains("order=click"),
                    "实际：" + mock.requestUri(ARC_PATH));

            UserService.INSTANCE.getArchives(MID, 1, 5, "  ");
            assertTrue(mock.requestUri(ARC_PATH).contains("order=pubdate"),
                    "空白 order 应回落到 pubdate：" + mock.requestUri(ARC_PATH));
        }

        @Test
        @DisplayName("页码 / 每页条数小于 1 被夹到 1")
        void clampsPage() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            UserService.INSTANCE.getArchives(MID, 0, -5);

            String uri = mock.requestUri(ARC_PATH);
            assertTrue(uri.contains("pn=1"), "实际：" + uri);
            assertTrue(uri.contains("ps=1"), "实际：" + uri);
        }

        @Test
        @DisplayName("mid ≤ 0 → 抛异常且没发请求")
        void invalidMid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getArchives(-1L, 1, 5));
            assertTrue(e.getMessage().contains("mid不能小于0"));
            assertEquals(0, mock.hitCount(ARC_PATH));
        }
    }

    // ================================================================
    // findSeasonId（合集链路的前置）
    // ================================================================

    @Nested
    @DisplayName("findSeasonId")
    class FindSeasonIdTest {

        @Test
        @DisplayName("从 vlist[].season_id 取出第一个非 0 值（真实夹具里是 5485575）")
        void picksFirstNonZero() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            Long seasonId = UserService.INSTANCE.findSeasonId(MID);

            assertEquals(5485575L, seasonId);
            assertEquals(1, mock.hitCount(ARC_PATH), "只翻一页投稿");
        }

        @Test
        @DisplayName("首页投稿都不属合集 → 返回 null（不是异常：\"没有\"是一种正常结果）")
        void returnsNullWhenNone() throws Exception {
            mock.register(ARC_PATH, "{\"code\":0,\"data\":{\"list\":{\"vlist\":["
                    + "{\"bvid\":\"BV1\",\"season_id\":0},{\"bvid\":\"BV2\"}]},"
                    + "\"page\":{\"pn\":1,\"ps\":50,\"count\":2}}}");

            assertNull(UserService.INSTANCE.findSeasonId(MID));
        }

        @Test
        @DisplayName("vlist 缺失 → 返回 null（不 NPE）")
        void noVlist() throws Exception {
            mock.register(ARC_PATH, "{\"code\":0,\"data\":{\"list\":{},\"page\":{\"count\":0}}}");
            assertNull(UserService.INSTANCE.findSeasonId(MID));
        }
    }

    // ================================================================
    // getSeasonArchives
    // ================================================================

    @Nested
    @DisplayName("getSeasonArchives")
    class SeasonArchivesTest {

        @Test
        @DisplayName("解析合集内容（aids / archives / meta / page）")
        void happyPath() throws Exception {
            mock.register(SEAS_PATH, fixture("seasons-archives.json"));

            SeasonsArchives data = UserService.INSTANCE.getSeasonArchives(MID, 5485575L, 1, 5);

            assertEquals(3, data.getArchives().size());
            assertEquals(3, data.getAids().size());
            assertNotNull(data.getMeta());
            assertEquals(5485575L, data.getMeta().getSeason_id());
            assertEquals(17, data.getPage().getTotal());
            assertNotNull(data.getArchives().get(0).getBvid());
        }

        @Test
        @DisplayName("🔴 走的是普通 GET：nav 一次都没有被调用")
        void doesNotUseSignedExit() throws Exception {
            mock.register(SEAS_PATH, fixture("seasons-archives.json"));

            UserService.INSTANCE.getSeasonArchives(MID, 5485575L, 1, 5);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "本端点免签名（实测匿名 code=0）—— 套上签名只会白打一次 nav 并放大失败面");
            assertFalse(mock.requestUri(SEAS_PATH).contains("w_rid"),
                    "不该有签名字段：" + mock.requestUri(SEAS_PATH));
        }

        @Test
        @DisplayName("四个参数都真的发出去了")
        void paramsOnTheWire() throws Exception {
            mock.register(SEAS_PATH, fixture("seasons-archives.json"));

            UserService.INSTANCE.getSeasonArchives(MID, 5485575L, 2, 10);

            String uri = mock.requestUri(SEAS_PATH);
            assertTrue(uri.contains("mid=" + MID), uri);
            assertTrue(uri.contains("season_id=5485575"), uri);
            assertTrue(uri.contains("page_num=2"), uri);
            assertTrue(uri.contains("page_size=10"), uri);
        }

        @Test
        @DisplayName("🔴 season_id ≤ 0 → 抛带人话的异常（传 1 会得到 -404 啥都木有）")
        void invalidSeasonId() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getSeasonArchives(MID, 0L, 1, 5));
            assertTrue(e.getMessage().contains("season_id 必须是真实 id"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(SEAS_PATH), "参数校验在发请求之前");
        }

        @Test
        @DisplayName("服务端 -404 → BilibiliException（带业务码，便于调用方分辨）")
        void notFound() throws Exception {
            mock.register(SEAS_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getSeasonArchives(MID, 5485575L, 1, 5));
            assertEquals(-404, e.getCode());
        }
    }

    // ================================================================
    // getTopArchive（B5 批，2026-09-23）
    //
    // 🔴 本组守的不是"能不能解析"，而是**「没有置顶」不是一个错误**：
    // 服务端在 UP 主没设置置顶时回 code=53016，而 ResponseParserSupport.requireData
    // 对 code != 0 一律抛异常 ⇒ 服务层必须**特地把 53016 catch 回来**翻成 null。
    // 漏了这一步的症状很隐蔽：调用方会收到一个异常，于是把"没有置顶"当成"接口坏了"。
    // ================================================================

    @Nested
    @DisplayName("getTopArchive（置顶视频 · 🔴 没有置顶是正常结果）")
    class TopArchiveTest {

        @Test
        @DisplayName("解析出置顶视频；键名是列表那一代（tidv2 / vt_display），不是 view 那一代")
        void happyPath() throws Exception {
            mock.register(TOP_ARC_PATH, fixture("top-arc.json"));

            VideoBrief data = UserService.INSTANCE.getTopArchive(2L);

            assertEquals(349L, data.getAid());
            assertEquals("BV1xx411c7DS", data.getBvid());
            assertEquals("[人生的导师松冈修造]東方修夢造", data.getTitle());
            assertEquals(2L, data.getOwner().getMid());
            assertEquals("碧诗", data.getOwner().getName());
            assertEquals(643227L, data.getStat().getView());

            // 这一组是"归到 VideoBrief 而不是 VideoInfo"的实证：三个字段只有列表那一代才有
            assertEquals(2010, data.getTidv2(), "列表那一代是 tidv2（view 那代是 tid_v2）");
            assertEquals("明星剪辑", data.getTnamev2());
            assertEquals("57.3万", data.getVt_display(), "★ 只有 space/top/arc 给这个键");
            assertEquals(Integer.valueOf(32768), data.getAttribute(),
                    "★ 本端点给 attribute；ranking/popular 给的是 attribute_v3 —— 两个字段都要留");
        }

        @Test
        @DisplayName("🔴 走的是普通 GET：一次 nav 都不打（匿名可用）")
        void doesNotUseSignedExit() throws Exception {
            mock.register(TOP_ARC_PATH, fixture("top-arc.json"));

            UserService.INSTANCE.getTopArchive(2L);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "本端点实测匿名 code=0 —— 套上签名只会白打一次 nav 并放大失败面");
            assertFalse(mock.requestUri(TOP_ARC_PATH).contains("w_rid"),
                    "不该有签名字段：" + mock.requestUri(TOP_ARC_PATH));
        }

        @Test
        @DisplayName("★ 参数名是 vmid：真的发出去的是 vmid=2，不是 mid=2")
        void parameterNameIsVmid() throws Exception {
            mock.register(TOP_ARC_PATH, fixture("top-arc.json"));

            UserService.INSTANCE.getTopArchive(2L);

            String uri = mock.requestUri(TOP_ARC_PATH);
            assertTrue(uri.contains("vmid=2"), "实际：" + uri);
            // 🔴 这里不能用 uri.contains("mid=2") —— "vmid=2" 本身就<b>包含</b>子串 "mid=2"，
            // 那样写会永远为 true（本用例第一版就栽在这，跑全量才暴露）。
            // 判"有没有一个独立参数叫 mid"，必须带上它的分隔符。
            assertFalse(uri.contains("?mid=") || uri.contains("&mid="),
                    "★ 09-22 那次盘点正是把参数写成 mid、拿到 -400 才差点把端点判死。实际：" + uri);
        }

        @Test
        @DisplayName("🔴 code=53016（没有置顶视频）→ 返回 null，【不】抛异常")
        void noTopArchiveIsNotAnError() throws Exception {
            mock.register(TOP_ARC_PATH, "{\"code\":53016,\"message\":\"没有置顶视频\",\"ttl\":1}");

            VideoBrief data = assertDoesNotThrow(() -> UserService.INSTANCE.getTopArchive(1L));

            assertNull(data,
                    "★ '没有置顶'是正常状态。requireData 会对它抛异常，服务层必须特判 catch 回来");
        }

        @Test
        @DisplayName("🔴 「没有置顶」(53016→null) 与「UP 不存在」(-404→抛) 是两件事")
        void nullVersusNotFound() throws Exception {
            mock.register(TOP_ARC_PATH, fixture("top-arc.json"));
            assertNotNull(UserService.INSTANCE.getTopArchive(2L));

            // 换成 -404：此时必须抛，且码值要能读到 —— 否则调用方会把"查无此人"当成"没有置顶"
            mock.register(TOP_ARC_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getTopArchive(999999999999L));
            assertEquals(-404, e.getCode(),
                    "★ 只有 53016 才翻译成 null；其它非 0 码照常抛，否则'UP 不存在'会被静默吞掉");
        }

        @Test
        @DisplayName("vmid ≤ 0 → 抛异常且没发请求")
        void invalidVmid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getTopArchive(0L));
            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(TOP_ARC_PATH));
        }

        @Test
        @DisplayName("code=0 但 data 为空 → 抛异常（不当成'没有置顶'放过）")
        void emptyDataStillFails() throws Exception {
            mock.register(TOP_ARC_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            assertThrows(BilibiliException.class, () -> UserService.INSTANCE.getTopArchive(2L),
                    "★ 只有 53016 是'正常但没有'；code=0 却不给数据是异常，两者别混");
        }
    }
}

package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.user.AccInfo;
import com.esdllm.bilibiliApi.model.data.pojo.user.ArchiveSearchResult;
import com.esdllm.bilibiliApi.model.data.pojo.user.RelationStat;
import com.esdllm.bilibiliApi.model.data.pojo.user.SeasonsArchives;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoBrief;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>UserSpace 门面回归测试</b>（B3 批新增的第 8 个门面）。
 *
 * <p>与 {@code SearchTest} 同一套思路：门面没有业务逻辑，只钉<b>委托正确</b>与
 * <b>异常边界</b>两件事；解析细节留给 {@code UserSpaceServiceTest}。
 *
 * <p>但这里多守一条<b>本批最贵的实测结论</b>：三个端点的门槛并不一样 ——
 * {@code acc/info} 与 {@code arc/search} 要"签名 + 凭据"，而
 * {@code seasons_archives_list} 两者都不要。差别不只是"多打一次 nav"，
 * 而是<b>失败面</b>：把免签名的端点也套上签名，就等于让"能用的功能"额外依赖
 * "nav 可达"这个前提 —— 所以用 {@code hitCount(nav)} 把这条直接断言下来，
 * 而不是靠读代码相信。
 *
 * <p>⚠️ 门面方法都声明 {@code throws IOException}，因此"抛没抛对异常类型"必须实测：
 * {@link BilibiliException} 是 runtime，若门面漏了 catch，用例会看到它原样逃出来。
 */
@DisplayName("门面：UserSpace（空间信息 / 投稿 / 合集）")
class UserSpaceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String ACC_PATH = "/x/space/wbi/acc/info";
    private static final String ARC_PATH = "/x/space/wbi/arc/search";
    private static final String SEAS_PATH = "/x/polymer/web-space/seasons_archives_list";
    /** B5 批：置顶视频（参数名是 {@code vmid}） */
    private static final String TOP_ARC_PATH = "/x/space/top/arc?vmid=";

    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private static final long MID = 946974L;
    private static final long SEASON_ID = 5485575L;

    private MockBiliServer mock;
    private UserSpace space;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
        space = new UserSpace();
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
    // 委托
    // ================================================================

    @Nested
    @DisplayName("委托（返回值能不能被调用方直接当强类型用）")
    class DelegationTest {

        @Test
        @DisplayName("getAccInfo：拿到空间信息，且确实走了签名出口")
        void accInfo() throws Exception {
            mock.register(ACC_PATH, fixture("acc-info.json"));

            AccInfo data = space.getAccInfo(MID);

            assertEquals(MID, data.getMid());
            assertEquals("影视飓风", data.getName());
            assertNotNull(data.getVip(), "acc/info 的价值之一是更完整的 vip");
            assertNotNull(data.getLive_room(), "live_room 是它与 CardInfo#getCard 的差异点");
            assertEquals(1, mock.hitCount(NAV_PATH), "必须走 getSigned —— 不签名只会拿到 -403/-352");
        }

        @Test
        @DisplayName("getArchives：分页信息在 data.page 上（写成 list.page 会永远拿到 0）")
        void archives() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            ArchiveSearchResult data = space.getArchives(MID, 1, 5);

            assertEquals(3, data.getList().getVlist().size());
            assertEquals(931, data.getPage().getCount(), "count 必须在 data 顶层");
        }

        @Test
        @DisplayName("getArchives(…, order)：排序参数照传，空白回落 pubdate")
        void archivesWithOrder() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            space.getArchives(MID, 1, 5, "click");
            assertTrue(mock.requestUri(ARC_PATH).contains("order=click"),
                    "实际：" + mock.requestUri(ARC_PATH));

            space.getArchives(MID, 1, 5, "  ");
            assertTrue(mock.requestUri(ARC_PATH).contains("order=pubdate"),
                    "空白 order 应回落：" + mock.requestUri(ARC_PATH));
        }

        @Test
        @DisplayName("findSeasonId：从 vlist[].season_id 拿到真实 id（合集链路的前置）")
        void findSeasonId() throws Exception {
            mock.register(ARC_PATH, fixture("arc-search.json"));

            assertEquals(SEASON_ID, space.findSeasonId(MID));
        }

        @Test
        @DisplayName("getSeasonArchives：拿到合集内容，且【一次 nav 都不打】—— 本端点是零门槛的")
        void seasonArchives() throws Exception {
            mock.register(SEAS_PATH, fixture("seasons-archives.json"));

            SeasonsArchives data = space.getSeasonArchives(MID, SEASON_ID, 1, 5);

            assertEquals(3, data.getArchives().size());
            assertEquals(SEASON_ID, data.getMeta().getSeason_id());
            assertEquals(17, data.getPage().getTotal());
            assertEquals(0, mock.hitCount(NAV_PATH),
                    "该端点免签名、免登录 —— 套上签名只会白白多一次 nav 并放大失败面");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("mid ≤ 0：包装成 IOException、内层文案保留，且没有任何出站")
        void invalidMid() {
            IOException e = assertThrows(IOException.class, () -> space.getAccInfo(0L));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(ACC_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH), "本地校验失败时连密钥都不该去取");
        }

        @Test
        @DisplayName("seasonId ≤ 0：同样包成 IOException（合集那条是本地校验，一个出站都不发）")
        void invalidSeasonId() {
            IOException e = assertThrows(IOException.class,
                    () -> space.getSeasonArchives(MID, 0L, 1, 5));

            assertTrue(e.getMessage().contains("season_id 必须是真实 id"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(SEAS_PATH));
        }

        @Test
        @DisplayName("服务端 -352（未注入凭据时的真实形态）：业务码要能被调用方读到")
        void riskControl() throws Exception {
            mock.register(ACC_PATH, "{\"code\":-352,\"message\":\"风控校验失败\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> space.getAccInfo(MID));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-352, cause.getCode(),
                    "调用方要靠这个码区分'该换凭据'与'该重试'，不能只剩一句文案");
            assertTrue(e.getMessage().contains("风控校验失败"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("合集 -404（真实存在的失败形态：season_id 传 1 就是这个）：也是 IOException")
        void seasonNotFound() throws Exception {
            mock.register(SEAS_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class,
                    () -> space.getSeasonArchives(MID, 1L, 1, 5));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-404, cause.getCode());
        }
    }

    // ================================================================
    // B1 新增：用户关系数（2026-09-22）
    //
    // 上面那张"门槛表"到这里要再补一行，而且这一行是**反向**的：
    // acc/info 与 arc/search 要"签名 + 凭据"，seasons 两者都不要，
    // 而 relation/stat **不需要凭据、且查任意用户都行** —— 它是本门面唯一的匿名方法。
    // 与它就是"数量 vs 名单"的关系：同域、同参数名（vmid）、名字也像，
    // 但名单端点（getFollowers/getFollowings）是 -101 且只限本人。
    // 这一对最容易被后人"顺手合并"，所以两处都写了。
    // ================================================================

    private static final String RELSTAT_PATH = "/x/relation/stat";

    @Nested
    @DisplayName("B1 新增：用户关系数（本门面唯一的匿名方法）")
    class RelationStatTest {

        @Test
        @DisplayName("getRelationStat：三个数取到，且【一次 nav 都不打】")
        void relationStat() throws Exception {
            mock.register(RELSTAT_PATH, fixture("relation-stat.json"));

            RelationStat data = space.getRelationStat(2L);

            assertEquals(2L, data.getMid());
            assertEquals(429L, data.getFollowing());
            assertEquals(1429244L, data.getFollower());

            assertTrue(mock.requestUri(RELSTAT_PATH).contains("vmid=2"),
                    "实际：" + mock.requestUri(RELSTAT_PATH));
            assertEquals("https://space.bilibili.com/2",
                    mock.requestHeader(RELSTAT_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 本门面其余方法全要签名或凭据，只有它两样都不要 —— 别一起改");
        }

        @Test
        @DisplayName("★ 与名单端点门槛正相反：同一份设置下数量通、名单 -101")
        void countsVersusLists() throws Exception {
            mock.register(RELSTAT_PATH, fixture("relation-stat.json"));
            mock.register("/x/relation/followers", "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            assertEquals(1429244L, space.getRelationStat(2L).getFollower(), "数量：匿名通");

            IOException e = assertThrows(IOException.class, () -> space.getFollowers(2L, 1, 5));
            assertEquals(-101, assertInstanceOf(BilibiliException.class, e.getCause()).getCode(),
                    "名单：必须登录，且只拿得到本人的");
        }

        @Test
        @DisplayName("vmid ≤ 0：包成 IOException，零出站")
        void badVmid() {
            IOException e = assertThrows(IOException.class, () -> space.getRelationStat(0L));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(RELSTAT_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH), "本地校验失败时连密钥都不该去取");
        }
    }

    // ================================================================
    // B5 新增：置顶视频（2026-09-23）
    //
    // 本组要守的是**一个"看起来像失败、其实是正常"的返回值**：
    // 该 UP 没设置置顶时，服务端回 code=53016，而本库把它翻成 `null`。
    // 也就是说 `getTopArchive` 是**可能返回 null 的**（与 findSeasonId 同一档），
    // 调用方必须显式判空 —— 不能因为它"是个查询"就假定非 null。
    // 顺带把"参数名是 vmid 不是 mid"钉住（09-22 那次盘点就是栽在这里）。
    // ================================================================

    @Nested
    @DisplayName("B5 新增：置顶视频（匿名可用，但可能返回 null）")
    class TopArchiveTest {

        @Test
        @DisplayName("getTopArchive：拿到置顶视频，且【一次 nav 都不打】")
        void topArchive() throws Exception {
            mock.register(TOP_ARC_PATH, fixture("top-arc.json"));

            VideoBrief data = space.getTopArchive(2L);

            assertEquals("BV1xx411c7DS", data.getBvid());
            assertEquals(349L, data.getAid());
            assertEquals(2L, data.getOwner().getMid());

            assertTrue(mock.requestUri(TOP_ARC_PATH).contains("vmid=2"),
                    "实际：" + mock.requestUri(TOP_ARC_PATH));
            assertEquals("https://space.bilibili.com/2",
                    mock.requestHeader(TOP_ARC_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 该端点免签名、免凭据 —— 与要签名的 acc/info、arc/search 不是一档");
        }

        @Test
        @DisplayName("★ 没有置顶时返回 null（不抛异常）—— 与'UP 不存在'是两件事")
        void noTopArchiveReturnsNull() throws Exception {
            mock.register(TOP_ARC_PATH, "{\"code\":53016,\"message\":\"没有置顶视频\",\"ttl\":1}");

            assertNull(space.getTopArchive(1L),
                    "53016 是'成功但没有内容'。调用方要判空，而不是 catch 异常");
        }

        @Test
        @DisplayName("★ UP 不存在（-404）反而抛 —— 所以 null 与异常各有所指")
        void notFoundThrows() throws Exception {
            mock.register(TOP_ARC_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> space.getTopArchive(999999999999L));

            assertEquals(-404, assertInstanceOf(BilibiliException.class, e.getCause()).getCode(),
                    "★ 只有 53016 被翻成 null，别的非 0 码照常抛");
        }

        @Test
        @DisplayName("vmid ≤ 0：包成 IOException，零出站")
        void badVmid() {
            IOException e = assertThrows(IOException.class, () -> space.getTopArchive(0L));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(TOP_ARC_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH), "本地校验失败时连密钥都不该去取");
        }
    }
}

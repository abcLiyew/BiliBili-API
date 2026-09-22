package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveArea;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveStream;
import com.esdllm.bilibiliApi.model.data.pojo.live.MasterInfo;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>LiveExtra 门面回归测试</b>（第 13 个门面，2026-09-22 B1 批新增）。
 *
 * <p>为什么另立一个类而不是往 {@code Live} 里加：{@code Live} 的类名与 public 方法签名是
 * <b>逐字冻结</b>的下游契约 —— 新能力一律走新类，这是本库自 v1 起写死的规矩。
 *
 * <p>本文件守两条容易搞混的东西：
 * <ol>
 *   <li>🔴 <b>两个方法收的参数不一样</b>：{@code getLiveStream} 收<b>房间号</b>（端点参数名叫 {@code cid}，
 *       容易与视频的 cid 撞车），{@code getMasterInfo} 收<b>主播 uid</b>。
 *       传错不会报错，只会查到别人或查不到 —— 所以两个方法各有一条"参数真的上对 query 了吗"的断言。</li>
 *   <li>🔴 <b>{@code Live#getLiveRoom} 里那个"地址"是房间页地址</b>（给人点的网页链接），
 *       不是能喂给播放器的拉流地址。两个东西都叫"地址"，本文件断言返回的真是 {@code .flv}。</li>
 * </ol>
 *
 * <p><b>门槛</b>：三项都零门槛（实测匿名 {@code code=0}），因此每个用例都断言一次 {@code nav} 都不打。
 *
 * <p>🆕 <b>B2 批（2026-09-22）本门面又多了第三项</b>：{@code getAreaList} 直播分区树。
 * 它<b>没有入参</b>是刻意的 —— 文档里的 {@code parent_area_id} 实测不起作用
 * （四格全同），本库不暴露它。详见 {@code AreaListTest}。
 */
@DisplayName("门面：LiveExtra（直播流地址 / 主播信息）")
class LiveExtraTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String STREAM_PATH = "/room/v1/Room/playUrl";
    private static final String MASTER_PATH = "/live_user/v1/Master/info";
    /** B2 批 #8：直播分区树。注意它在 {@code api.live} 下，与上面两个同域但不同 host 前缀 */
    private static final String AREA_PATH = "/room/v1/Area/getList";

    private static final long ROOM_ID = 1024L;
    private static final long UID = 2L;

    private MockBiliServer mock;
    private LiveExtra liveExtra;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
        liveExtra = new LiveExtra();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    // ================================================================
    // 委托
    // ================================================================

    @Nested
    @DisplayName("委托")
    class DelegationTest {

        @Test
        @DisplayName("★ getLiveStream 收的是房间号，端点参数名叫 cid；默认原画")
        void liveStream() throws Exception {
            mock.register(STREAM_PATH, fixture("live-stream.json"));

            LiveStream data = liveExtra.getLiveStream(ROOM_ID);

            assertEquals(2, data.getDurl().size());
            assertEquals(10000, data.getCurrent_qn(), "默认原画（直播不消耗清晰度权限）");
            assertEquals(List.of("4"), data.getAccept_quality(), "字符串数组，与点播域形状不同");

            String uri = mock.requestUri(STREAM_PATH);
            assertTrue(uri.contains("cid=" + ROOM_ID),
                    "★ 房间号被放进名叫 cid 的参数里 —— 它不是视频的 cid。实际：" + uri);
            assertEquals("https://live.bilibili.com/", mock.requestHeader(STREAM_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "零门槛：既不要签名也不要凭据");
        }

        @Test
        @DisplayName("getLiveStream(…, qn)：指定清晰度照传")
        void liveStreamWithQn() throws Exception {
            mock.register(STREAM_PATH, fixture("live-stream.json"));

            liveExtra.getLiveStream(ROOM_ID, 400);

            assertTrue(mock.requestUri(STREAM_PATH).contains("qn=400"),
                    "实际：" + mock.requestUri(STREAM_PATH));
        }

        @Test
        @DisplayName("★ getMasterInfo 收的是主播 uid（不是房间号）—— 两个方法的参数不能互换")
        void masterInfo() throws Exception {
            mock.register(MASTER_PATH, fixture("live-master-info.json"));

            MasterInfo data = liveExtra.getMasterInfo(UID);

            assertEquals("碧诗", data.getInfo().getUname());
            assertEquals(1429244L, data.getFollower_num());
            assertEquals(ROOM_ID, data.getRoom_id(), "从 uid 还能回到房间号 —— 反向链路是通的");

            assertTrue(mock.requestUri(MASTER_PATH).contains("uid=" + UID),
                    "实际：" + mock.requestUri(MASTER_PATH));
            assertEquals("https://live.bilibili.com/", mock.requestHeader(MASTER_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH));
        }

        @Test
        @DisplayName("两个方法打的是两个端点，别写反")
        void twoDistinctEndpoints() throws Exception {
            mock.register(STREAM_PATH, fixture("live-stream.json"));
            mock.register(MASTER_PATH, fixture("live-master-info.json"));

            liveExtra.getLiveStream(ROOM_ID);
            liveExtra.getMasterInfo(UID);

            assertEquals(1, mock.hitCount(STREAM_PATH));
            assertEquals(1, mock.hitCount(MASTER_PATH));
            // 房间域与主播域的参数名不同：cid vs uid。搞混了不会报错，只会悄悄查错人
            assertTrue(mock.requestUri(STREAM_PATH).contains("cid="));
            assertTrue(mock.requestUri(MASTER_PATH).contains("uid="));
        }
    }

    // ================================================================
    // 直播分区树（B2 批 #8）
    // ================================================================

    /**
     * 🔴 本组守两件"看不出来但会咬人"的东西：
     * <ol>
     *   <li><b>两层分区不能共用 POJO</b>：一级 {@code id} 是数值、二级 {@code id} 是<b>字符串</b>
     *       —— 用错类型不会报错，只会让整层的 id 变成 null 或直接抛解析错；</li>
     *   <li><b>请求里不该出现 {@code parent_area_id}</b>：实测四格对照（不传 / =1 / =2 / =999）
     *       返回<b>完全一致</b>，连不存在的 id 也返回全树 ⇒ 本库刻意不暴露这个参数。
     *       这条断言把"以后有人手贱把它加回去"变成一个需要显式删用例的动作。</li>
     * </ol>
     */
    @Nested
    @DisplayName("直播分区树（零门槛，且 parent_area_id 是装饰品）")
    class AreaListTest {

        @Test
        @DisplayName("getAreaList：两层结构完整，二级分区的 parent_id 能指回一级的 id")
        void areaList() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            List<LiveArea> areas = liveExtra.getAreaList();

            assertEquals(2, areas.size(), "夹具裁到 2 个一级分区（真实响应 12 个 / 450 个二级）");

            LiveArea first = areas.get(0);
            assertEquals(2, first.getId());
            assertEquals("网游", first.getName());
            assertEquals(3, first.getList().size(), "每个一级分区各裁到 3 个二级");

            // 二级用 String id —— 见下面那条专门的断言
            var sub = first.getList().get(0);
            assertEquals("86", sub.getId());
            assertEquals("英雄联盟", sub.getName());
            assertEquals("2", sub.getParent_id(),
                    "★ 二级的 parent_id 是字符串 '2'，而上面一级的 id 是数值 2 —— 同一个分区、两种类型");
            assertEquals("网游", sub.getParent_name(), "冗余字段，但省掉一次反查");
            assertEquals(0, sub.getHot_status());

            assertEquals(3, areas.get(1).getId());
            assertEquals("手游", areas.get(1).getName());
        }

        @Test
        @DisplayName("★ 两层 id 类型必须分开：一级 Integer、二级 String")
        void twoLevelsHaveDifferentIdTypes() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            LiveArea parent = liveExtra.getAreaList().get(0);

            assertEquals(2, parent.getId(), "一级 id 是数值（实测 2）");
            assertEquals("86", parent.getList().get(0).getId(),
                    "★ 二级 id 是【字符串】（实测 \"86\"）—— 用 Integer 接会静默变 null 或抛错，"
                            + "所以这两层是两个类（LiveArea / LiveSubArea），不能为了'少写一个类'合并");
        }

        @Test
        @DisplayName("★ 请求里【绝不】出现 parent_area_id —— 那个参数实测不起作用，本库刻意不暴露")
        void noParentAreaIdParam() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            liveExtra.getAreaList();

            String uri = mock.requestUri(AREA_PATH);
            assertFalse(uri.contains("parent_area_id"),
                    "★ 实测 A/B：不传 / =1 / =2 / =999 的响应完全相同（连不存在的 id 也返回全树）。"
                            + "暴露一个'传了没用'的入参只会让人以为是自己传错了。实际：" + uri);
            assertEquals("https://live.bilibili.com/", mock.requestHeader(AREA_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "零门槛：既不要签名也不要凭据");
            assertEquals(0, mock.hitCount(STREAM_PATH), "与拉流是两个端点，别互相写错");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("★ code=0 但一条地址都没有：必须抛，不能交给调用方一个空 durl")
        void emptyDurlIsNotSilent() {
            mock.register(STREAM_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"current_qn\":10000,\"accept_quality\":[],\"durl\":[]}}");

            IOException e = assertThrows(IOException.class, () -> liveExtra.getLiveStream(ROOM_ID));

            assertTrue(e.getMessage().contains("durl 为空"),
                    "这条没有非 0 的码可用，文案就是唯一判据。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码确实是 0 —— 正因如此才不能靠它判成败");
            assertEquals("没有可播放地址", cause.getDescription(), "要给出下一步动作");
        }

        @Test
        @DisplayName("roomId 为 null / ≤ 0：包成 IOException，且没有任何出站")
        void badRoomId() {
            IOException e = assertThrows(IOException.class, () -> liveExtra.getLiveStream(null));

            assertTrue(e.getMessage().contains("房间号不能为空"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(STREAM_PATH));

            assertThrows(IOException.class, () -> liveExtra.getLiveStream(0L));
            assertEquals(0, mock.hitCount(STREAM_PATH));
        }

        @Test
        @DisplayName("uid 为 null / ≤ 0：同样包成 IOException，零出站")
        void badUid() {
            IOException e = assertThrows(IOException.class, () -> liveExtra.getMasterInfo(null));

            assertTrue(e.getMessage().contains("uid不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(MASTER_PATH));

            assertThrows(IOException.class, () -> liveExtra.getMasterInfo(0L));
            assertEquals(0, mock.hitCount(MASTER_PATH));
        }

        @Test
        @DisplayName("-412 请求被拦截：业务码要活到 cause 里")
        void intercepted() {
            mock.register(MASTER_PATH, "{\"code\":-412,\"message\":\"请求被拦截\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> liveExtra.getMasterInfo(UID));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-412, cause.getCode());
        }

        @Test
        @DisplayName("★ 分区树 code=0 但列表为空：必须抛（该端点匿名可用，空列表只能是形状变了）")
        void emptyAreaListIsNotSilent() {
            mock.register(AREA_PATH, "{\"code\":0,\"msg\":\"success\",\"data\":[]}");

            IOException e = assertThrows(IOException.class, () -> liveExtra.getAreaList());

            assertTrue(e.getMessage().contains("分区列表为空"),
                    "这条没有非 0 的码可用，文案就是唯一判据。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码确实是 0 —— 正因如此才不能靠它判成败");
        }

        @Test
        @DisplayName("★ data 为 null：走通用分支，同样是 IOException 而不是 NPE")
        void nullAreaData() {
            mock.register(AREA_PATH, "{\"code\":0,\"msg\":\"success\",\"data\":null}");

            IOException e = assertThrows(IOException.class, () -> liveExtra.getAreaList());

            assertInstanceOf(BilibiliException.class, e.getCause());
            assertTrue(e.getMessage().contains("data 为空"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 412（出口风控）：也是 IOException，码值保住 412")
        void areaHttp412() {
            mock.registerStatus(AREA_PATH, 412, "");

            IOException e = assertThrows(IOException.class, () -> liveExtra.getAreaList());

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(412, cause.getCode());
        }
    }
}

package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderInfo;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavResourceList;
import com.esdllm.bilibiliApi.model.data.pojo.content.HistoryCursor;
import com.esdllm.bilibiliApi.model.data.pojo.content.ToViewList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Content 门面回归测试</b>（第 11 个门面，2026-09-22 B3.5 批新增）。
 *
 * <p>与 {@code UserSpaceTest} / {@code SearchTest} 同一套思路：门面没有业务逻辑，
 * 只钉<b>委托正确</b>与<b>异常边界</b>两件事；解析细节留给 {@code ContentServiceTest}。
 *
 * <p>这里额外守一条本门面特有的东西：<b>三项的"失败长相"不一致</b>。
 * 门面把它们统一成 {@code IOException}，但<b>业务码必须原样活下来</b> ——
 * 因为调用方要靠 {@code -101} 决定"去重新登录"，而收藏夹那条连码都是 0，
 * 只能靠文案里的"list 为空"来分辨。写成一个统一的错误处理就等于把这个区别抹掉了。
 *
 * <p>🆕 <b>B2 批（2026-09-22）本门面又扩了两项</b>：{@code getFolderInfo} / {@code getResources}。
 * 它们的门槛与上面三项<b>不同</b> —— 不是"一律需登录"，而是<b>取决于夹本身</b>
 * （实测同一分钟匿名：{@code attr=2} 的夹 {@code code=0}、{@code attr=1} 的夹 {@code -403}）。
 * 所以本文件另开一组，把<b>两个方向</b>都钉住，并断言 {@code -403} 的<b>两义成因</b>被写进了文案。
 * ⚠️ 顺带订正了 B3.5 的一处错注：{@code attr} 的公开/私密方向原先写反了。
 */
@DisplayName("门面：Content（观看历史 / 稍后再看 / 收藏夹目录）")
class ContentTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String HISTORY_PATH = "/x/web-interface/history/cursor";
    private static final String TOVIEW_PATH = "/x/v2/history/toview";
    private static final String FAV_PATH = "/x/v3/fav/folder/created/list-all";
    /** B2 批 #5：收藏夹详情 */
    private static final String FOLDER_INFO_PATH = "/x/v3/fav/folder/info";
    /** B2 批 #6：收藏夹内容 */
    private static final String RESOURCE_LIST_PATH = "/x/v3/fav/resource/list";

    /**
     * 与 {@code fav-folder-info.json} / {@code fav-resource-list.json} 一致 ——
     * 这是那个<b>匿名可读</b>的夹（实测 {@code attr=2}、{@code code=0}）。
     */
    private static final long MEDIA_ID_PUBLIC = 3526698880L;

    /**
     * 上一批（B3.5）在 {@code fav-folders.json} 里出镜的「默认收藏夹」，实测 <b>{@code attr=1}</b>，
     * 匿名对它两个端点都回 <b>{@code -403 访问权限不足}</b>。
     *
     * <p>★ 注意方向：{@code attr=2} 能读、{@code attr=1} 读不到 —— 与"1=公开、2=私密"的直觉相反，
     * 所以<b>不能</b>用 {@code attr} 提前判公开性（B3.5 的旧注写反了，B2 批已订正）。
     */
    private static final long MEDIA_ID_PRIVATE = 1095405480L;

    private static final long MID = 497078180L;

    private MockBiliServer mock;
    private Content content;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
        content = new Content();
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
    @DisplayName("委托（返回值要能被调用方直接当强类型用）")
    class DelegationTest {

        @Test
        @DisplayName("getWatchHistory：拿到历史，翻页游标一并带出")
        void watchHistory() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            HistoryCursor data = content.getWatchHistory(5);

            assertEquals(2, data.getList().size());
            assertNotNull(data.getCursor(), "门面必须把游标透出来，否则调用方翻不了页");
            assertEquals(3, data.getTab().size());
        }

        @Test
        @DisplayName("getWatchHistory(游标)：四个参数照传")
        void watchHistoryPaged() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            content.getWatchHistory(20, 116904916159077L, 1789988333L, "archive");

            String uri = mock.requestUri(HISTORY_PATH);
            assertTrue(uri.contains("ps=20") && uri.contains("max=116904916159077")
                    && uri.contains("view_at=1789988333") && uri.contains("business=archive"),
                    "实际：" + uri);
        }

        @Test
        @DisplayName("getToView：不分页，一次拿全")
        void toView() throws Exception {
            mock.register(TOVIEW_PATH, fixture("toview.json"));

            ToViewList data = content.getToView();

            assertEquals(37, data.getCount());
            assertEquals(2, data.getList().size());
            assertEquals("BV1jnKE6PEDn", data.getList().get(0).getBvid());
        }

        @Test
        @DisplayName("getFavoriteFolders：目录里 id 与 fid 都在，且相差一段")
        void favoriteFolders() throws Exception {
            mock.register(FAV_PATH, fixture("fav-folders.json"));

            FavFolderList data = content.getFavoriteFolders(MID);

            assertEquals(12, data.getCount());
            assertEquals(1095405480L, data.getList().get(0).getId());
            assertNotEquals(data.getList().get(0).getId(), data.getList().get(0).getFid());
        }

        @Test
        @DisplayName("三项都不走签名出口（一次 nav 都不该打）")
        void noSigning() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));
            mock.register(TOVIEW_PATH, fixture("toview.json"));
            mock.register(FAV_PATH, fixture("fav-folders.json"));

            content.getWatchHistory(5);
            content.getToView();
            content.getFavoriteFolders(MID);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "本门面三项都是普通 GET，套上签名只会平白多一个 'nav 不可达' 的失败面");
        }
    }

    // ================================================================
    // 收藏夹详情与内容（B2 批 #5 / #6）
    // ================================================================

    /**
     * 🔴 本门面前三项<b>一律需登录</b>，而这两项<b>取决于夹本身</b> —— 门槛不是常量，
     * 所以本组把两个方向都钉住（公开夹能读、{@code attr=1} 的夹 {@code -403}）。
     *
     * <p>另一件必须钉住的是 <b>{@code -403} 的两义性</b>：它既可能是"缺 WBI 签名"，
     * 也可能是"资源权限不足"。本域<b>根本不需要签名</b>，所以这里的 {@code -403} 几乎一定是后者
     * —— 服务层为此专门改写了文案，本组断言那个改写真的发生了，
     * <b>且没有对别的错误码乱改写</b>（否则会把通用错误掩盖成收藏夹专属文案）。
     */
    @Nested
    @DisplayName("收藏夹详情 / 内容（门槛取决于夹，不是常量）")
    class FavDetailTest {

        @Test
        @DisplayName("getFolderInfo：夹元信息 + 作者 + 统计都在")
        void folderInfo() throws Exception {
            mock.register(FOLDER_INFO_PATH, fixture("fav-folder-info.json"));

            FavFolderInfo info = content.getFolderInfo(MEDIA_ID_PUBLIC);

            assertEquals("小雨绒Candy", info.getTitle());
            assertEquals(2, info.getAttr());
            assertEquals(101, info.getMedia_count(), "夹里 101 条内容 —— 下面 getResources 会拿它做交叉校验");
            assertEquals(3526698880L, info.getId());
            assertNotEquals(info.getId(), info.getFid(), "id 与 fid 相差一段，别混用");

            assertEquals("可可小绒猫", info.getUpper().getName());
            assertEquals(497078180L, info.getUpper().getMid());
            assertEquals(2, info.getUpper().getVip_type(), "FavFolderInfo.Upper 有 vip 字段 —— 与内容条目的 Upper 形状不同");
            assertEquals(1L, info.getCnt_info().getPlay());
            assertEquals(Boolean.FALSE, info.getIs_top());

            assertTrue(mock.requestUri(FOLDER_INFO_PATH).contains("media_id=" + MEDIA_ID_PUBLIC),
                    "★ 参数是 media_id（不是 fid）。实际：" + mock.requestUri(FOLDER_INFO_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH), "本域不需要签名");
        }

        @Test
        @DisplayName("★ -403 改写：文案要讲清两种成因，并点出'attr 不能用来提前判公开性'")
        void forbiddenIsRewritten() {
            mock.register(FOLDER_INFO_PATH, "{\"code\":-403,\"message\":\"访问权限不足\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> content.getFolderInfo(MEDIA_ID_PRIVATE));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-403, cause.getCode(), "码值必须原样保留");
            assertTrue(e.getMessage().contains("code=-403"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("访问权限不足"), "服务端原话要带上：" + e.getMessage());
            assertTrue(e.getMessage().contains("media_id=" + MEDIA_ID_PRIVATE),
                    "★ 要带上出问题的那个夹 —— 否则一批夹里报一个 -403，不知道是哪个。实际：" + e.getMessage());
            assertTrue(cause.getDescription().contains("两种成因"),
                    "★ -403 在本库是两义码，文案必须把两种成因都写出来。实际：" + cause.getDescription());
            assertTrue(cause.getDescription().contains("attr"),
                    "★ 并提醒 attr 不能用来提前判公开性（实测含 attr=1 的夹匿名为 -403）。"
                            + "实际：" + cause.getDescription());
        }

        @Test
        @DisplayName("★ 非 -403 不许改写：-101 要原样抛出，不能伪装成收藏夹专属文案")
        void otherCodesAreNotRewritten() {
            mock.register(RESOURCE_LIST_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class,
                    () -> content.getResources(MEDIA_ID_PUBLIC, 1, 20));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-101, cause.getCode());
            assertTrue(e.getMessage().contains("账号未登录"), "实际：" + e.getMessage());
            assertFalse(cause.getDescription().contains("两种成因"),
                    "★ 只有 -403 才改写；把 -101 也套上收藏夹文案会把真正的成因掩盖掉。"
                            + "实际：" + cause.getDescription());
        }

        @Test
        @DisplayName("getResources：一页内容 + 夹信息，条目字段对齐到 ugc.first_cid")
        void resources() throws Exception {
            mock.register(RESOURCE_LIST_PATH, fixture("fav-resource-list.json"));

            FavResourceList data = content.getResources(MEDIA_ID_PUBLIC, 1, 20);

            assertEquals("小雨绒Candy", data.getInfo().getTitle(), "info 与 getFolderInfo 是同一个形状");
            assertEquals(101, data.getInfo().getMedia_count());
            assertEquals(2, data.getMedias().size(), "夹具裁到 2 条");
            assertEquals(Boolean.TRUE, data.getHas_more(), "★ 夹里 101 条但只给 2 条 ⇒ has_more 才是翻页的依据");

            FavResourceList.Media first = data.getMedias().get(0);
            assertEquals("甜嗓翻唱『执迷不悟』所以会忙忙碌碌～", first.getTitle());
            assertEquals(2, first.getType(), "2 = 视频");
            assertEquals(117283494035779L, first.getId(), "id 就是 aid");
            assertEquals("BV1mpeK6nEDK", first.getBvid());
            assertEquals(first.getBvid(), first.getBv_id(), "bv_id 与 bvid 同值，两个键都留着是为了兼容不同产物");
            assertEquals(41958378053L, first.getUgc().getFirst_cid(),
                    "★ 这是拿 cid 的一条路 —— Danmaku 门面就吃它");
            assertNull(first.getOgv(), "实测视频条目的 ogv 为 null（形状未验证）");
            assertNull(first.getSeason());

            // ★ cnt_info 的两种形状：这里比 FavFolderInfo.CntInfo 多 danmaku/reply，且 view_text_1 是字符串
            assertEquals(306L, first.getCnt_info().getPlay());
            assertEquals("306", first.getCnt_info().getView_text_1(),
                    "★ view_text_1 是【字符串】数字（带单位后缀的展示值），别当数值用");
            assertNotNull(first.getCnt_info().getDanmaku(), "内容条目的 cnt_info 比夹信息的多几个键");

            // ★ Upper 的两种形状：内容条目这里是另一套字段（没有 vip_*，多了 jump_link）
            assertEquals("小雨绒Candy", first.getUpper().getName());
            assertEquals("", first.getUpper().getJump_link());

            String uri = mock.requestUri(RESOURCE_LIST_PATH);
            assertTrue(uri.contains("media_id=" + MEDIA_ID_PUBLIC)
                            && uri.contains("pn=1") && uri.contains("ps=20"),
                    "实际：" + uri);
            assertEquals(0, mock.hitCount(NAV_PATH));
        }

        @Test
        @DisplayName("★ 空 medias 但 media_count=101：必须抛 —— 那是「本页没给」，不是「夹是空的」")
        void emptyPageWithDeclaredCountIsNotSilent() {
            mock.register(RESOURCE_LIST_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"info\":{\"media_count\":101},\"medias\":[],\"has_more\":false}}");

            IOException e = assertThrows(IOException.class,
                    () -> content.getResources(MEDIA_ID_PUBLIC, 9, 20));

            assertTrue(e.getMessage().contains("一条都没给"),
                    "这条没有非 0 的码可用，文案就是唯一判据。实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("pn=9"),
                    "★ 要带上出问题的页码（最常见成因就是 pn 越界）。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码是 0 —— 正因如此才不能靠它判成败");
        }

        @Test
        @DisplayName("★ 真的空夹（media_count=0）：返回空列表，不抛 —— 与上面那条刚好相反")
        void genuinelyEmptyFolderIsLegal() throws Exception {
            mock.register(RESOURCE_LIST_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"info\":{\"media_count\":0,\"title\":\"新建的夹\"},\"medias\":[],\"has_more\":false}}");

            FavResourceList data = content.getResources(MEDIA_ID_PUBLIC, 1, 20);

            assertTrue(data.getMedias().isEmpty());
            assertEquals(0, data.getInfo().getMedia_count(),
                    "★ 空能由 media_count 佐证 ⇒ 合法；佐证不了才报错。"
                            + "这与 fav/folder/created/list-all「空列表一律报错」刚好相反，区别就在这里");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("★ -101 未登录：包装成 IOException，业务码必须能读出来")
        void notLoggedIn() {
            mock.register(TOVIEW_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> content.getToView());

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-101, cause.getCode(),
                    "调用方靠它决定'去重新登录'还是'重试'，只剩一句文案就判不出来");
            assertTrue(e.getMessage().contains("账号未登录"), "服务端原话要带上：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 收藏夹的空列表：码是 0，只能靠文案分辨 —— 门面不许把它变成一句含糊的'失败了'")
        void favFolderEmptyIsNotSilent() {
            mock.register(FAV_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{\"count\":0,\"list\":[]}}");

            IOException e = assertThrows(IOException.class, () -> content.getFavoriteFolders(MID));

            assertTrue(e.getMessage().contains("list 为空"),
                    "这条没有非 0 的码可用，文案就是唯一判据。实际：" + e.getMessage());
        }

        @Test
        @DisplayName("upstat 式的静默空在【本门面】不存在：历史为空是正常结果，不抛")
        void emptyHistoryStillReturns() throws Exception {
            mock.register(HISTORY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"cursor\":{\"max\":0,\"view_at\":0,\"business\":\"\",\"ps\":5},"
                    + "\"tab\":[],\"list\":[]}}");

            HistoryCursor data = content.getWatchHistory(5);

            assertNotNull(data);
            assertTrue(data.getList().isEmpty());
        }

        @Test
        @DisplayName("本地校验：mid ≤ 0 时包成 IOException，且一个出站都不发")
        void badMid() {
            IOException e = assertThrows(IOException.class, () -> content.getFavoriteFolders(0L));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(FAV_PATH));
        }

        @Test
        @DisplayName("HTTP 412：同样是 IOException（出口风控，不是参数错）")
        void http412() {
            mock.registerStatus(HISTORY_PATH, 412, "");

            IOException e = assertThrows(IOException.class, () -> content.getWatchHistory(5));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(412, cause.getCode());
        }

        @Test
        @DisplayName("★ media_id ≤ 0：两个新方法都包成 IOException，且一个出站都不发")
        void badMediaId() {
            IOException e = assertThrows(IOException.class, () -> content.getFolderInfo(0L));

            assertTrue(e.getMessage().contains("media_id不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(FOLDER_INFO_PATH));

            assertThrows(IOException.class, () -> content.getResources(-1L, 1, 20));
            assertEquals(0, mock.hitCount(RESOURCE_LIST_PATH),
                    "★ 校验在发请求之前 —— 两个方法都要拦住，不能只拦一个");
        }

        @Test
        @DisplayName("两个新方法都不走签名出口")
        void newMethodsDoNotSign() throws Exception {
            mock.register(FOLDER_INFO_PATH, fixture("fav-folder-info.json"));
            mock.register(RESOURCE_LIST_PATH, fixture("fav-resource-list.json"));

            content.getFolderInfo(MEDIA_ID_PUBLIC);
            content.getResources(MEDIA_ID_PUBLIC, 1, 20);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 正因为本域不签名，这里的 -403 才几乎一定是'资源权限不足'而不是'签名错'");
        }
    }
}

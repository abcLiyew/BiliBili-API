package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
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
 * <b>{@code HistoryService} 与 {@code FavoriteService} 的回归测试</b>（2026-09-22 B3.5 批 #3 / #5 / #6）。
 *
 * <p>两个服务放在一个文件里，是因为它们服务同一个门面（{@code Content}），
 * 而且一起回答"<b>这三项到底哪一项会骗人</b>"：
 *
 * <table border="1">
 *   <caption>同批三项，缺凭据时的表现各不相同</caption>
 *   <tr><th>方法</th><th>缺凭据</th><th>危险程度</th></tr>
 *   <tr><td>{@code getWatchHistory}</td><td>{@code -101}</td><td>低（敞亮）</td></tr>
 *   <tr><td>{@code getToView}</td><td>{@code -101}</td><td>低（敞亮）</td></tr>
 *   <tr><td>{@code getCreatedFolders}</td><td><b>{@code code=0} + 空 list</b></td>
 *       <td><b>高</b> —— 会被读成"此人没有收藏夹"</td></tr>
 * </table>
 * ⇒ 所以 {@code favFolderEmptyListIsAnError} 与 {@code historyCursorIsCarriedOver} 是本文件的核心：
 * 前者守"别把没凭据读成没收藏夹"，后者守"别让翻页静默失效"（只映射 list 不映射 cursor 也能跑通第一条）。
 */
@DisplayName("服务：HistoryService / FavoriteService（历史 · 稍后再看 · 收藏夹目录）")
class ContentServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String HISTORY_PATH = "/x/web-interface/history/cursor";
    private static final String TOVIEW_PATH = "/x/v2/history/toview";
    private static final String FAV_PATH = "/x/v3/fav/folder/created/list-all";

    private static final long MID = 497078180L;

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
    // 观看历史
    // ================================================================

    @Nested
    @DisplayName("观看历史（x/web-interface/history/cursor）")
    class HistoryTest {

        @Test
        @DisplayName("★ cursor 必须被带出来 —— 少了它调用方永远只能看第一页，而且不报错")
        void historyCursorIsCarriedOver() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            HistoryCursor data = HistoryService.INSTANCE.getWatchHistory(5);

            assertNotNull(data.getCursor(),
                    "翻页全靠它。只映射 list 是这块最隐蔽的错：第一次调用看起来完全正常");
            assertEquals(116904916159077L, data.getCursor().getMax());
            assertEquals(1789988333L, data.getCursor().getView_at());
            assertEquals("archive", data.getCursor().getBusiness());
            assertEquals(5, data.getCursor().getPs());
        }

        @Test
        @DisplayName("列表与页签：标题/作者在条目本层，oid/cid/bvid 在 history 子对象里")
        void itemLayers() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            HistoryCursor data = HistoryService.INSTANCE.getWatchHistory(5);

            assertEquals(2, data.getList().size());
            assertEquals(3, data.getTab().size(), "视频 / 直播 / 专栏");
            assertEquals("archive", data.getTab().get(0).getType());

            HistoryCursor.HistoryItem first = data.getList().get(0);
            assertEquals("阿喵峡谷分享", first.getAuthor_name(), "作者名在条目本层");
            assertEquals("日常", first.getTag_name());
            assertNotNull(first.getHistory(), "播放器身份在 history 子对象里，不在本层");
            assertEquals("BV1Lfhq6jEGp", first.getHistory().getBvid());
            assertEquals(42086696070L, first.getHistory().getCid(), "要拿播放地址就用这个 cid");
            assertEquals(27L, first.getProgress());
        }

        @Test
        @DisplayName("翻页：游标三个值要原样回传（只带 max 在跨业务时会跳错位置）")
        void pagingPassesCursorBack() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            HistoryService.INSTANCE.getWatchHistory(5, 116904916159077L, 1789988333L, "archive");

            String uri = mock.requestUri(HISTORY_PATH);
            assertTrue(uri.contains("ps=5"), "实际：" + uri);
            assertTrue(uri.contains("max=116904916159077"), "实际：" + uri);
            assertTrue(uri.contains("view_at=1789988333"), "实际：" + uri);
            assertTrue(uri.contains("business=archive"), "实际：" + uri);
        }

        @Test
        @DisplayName("首页只带 ps：未指定的游标参数不该被拼成空值发出去")
        void firstPageOmitsCursor() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            HistoryService.INSTANCE.getWatchHistory(5);

            String uri = mock.requestUri(HISTORY_PATH);
            assertTrue(uri.contains("ps=5"), "实际：" + uri);
            assertFalse(uri.contains("max="), "空的游标不该发出去。实际：" + uri);
            assertFalse(uri.contains("view_at="), "实际：" + uri);
        }

        @Test
        @DisplayName("Referer 要是历史页，不是站根")
        void historyReferer() throws Exception {
            mock.register(HISTORY_PATH, fixture("history-cursor.json"));

            HistoryService.INSTANCE.getWatchHistory(5);

            assertEquals("https://www.bilibili.com/account/history",
                    mock.requestHeader(HISTORY_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不签名");
        }

        @Test
        @DisplayName("-101 未登录：透传业务码")
        void notLoggedIn() {
            mock.register(HISTORY_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> HistoryService.INSTANCE.getWatchHistory(5));

            assertEquals(-101, e.getCode());
        }

        @Test
        @DisplayName("历史为空是【正常结果】，不是错误（与收藏夹空列表的处置刻意不同）")
        void emptyHistoryIsFine() {
            mock.register(HISTORY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"cursor\":{\"max\":0,\"view_at\":0,\"business\":\"\",\"ps\":5},"
                    + "\"tab\":[],\"list\":[]}}");

            HistoryCursor data = HistoryService.INSTANCE.getWatchHistory(5);

            assertNotNull(data);
            assertTrue(data.getList().isEmpty(), "没看过视频是合法的：不能像收藏夹那样抛异常");
        }
    }

    // ================================================================
    // 稍后再看
    // ================================================================

    @Nested
    @DisplayName("稍后再看（x/v2/history/toview）")
    class ToViewTest {

        @Test
        @DisplayName("一次给完：count 与 list，不涉及分页")
        void shape() throws Exception {
            mock.register(TOVIEW_PATH, fixture("toview.json"));

            ToViewList data = HistoryService.INSTANCE.getToView();

            assertEquals(37, data.getCount(), "它是全量列表，count 应当等于 list 的实际条数口径");
            assertEquals(2, data.getList().size());
            assertEquals("BV1jnKE6PEDn", data.getList().get(0).getBvid());
            assertEquals("莴苣某人", data.getList().get(0).getOwner().getName(),
                    "owner/stat/dimension 复用了 video 域，要能解析出来而不是 null");
            assertEquals(9124818L, data.getList().get(0).getStat().getView());
            assertEquals(1080, data.getList().get(0).getDimension().getHeight());
            assertEquals(1785045767L, data.getList().get(0).getAdd_at(),
                    "add_at 是'我什么时候想看的'，比 pubdate 更贴近这个列表的语义");
            assertEquals(84L, data.getList().get(0).getProgress());
        }

        @Test
        @DisplayName("Referer 要是稍后再看页")
        void referer() throws Exception {
            mock.register(TOVIEW_PATH, fixture("toview.json"));

            HistoryService.INSTANCE.getToView();

            assertEquals("https://www.bilibili.com/watchlater/",
                    mock.requestHeader(TOVIEW_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不签名");
        }

        @Test
        @DisplayName("-101 未登录：透传业务码（本端点是'真需登录'那一档）")
        void notLoggedIn() {
            mock.register(TOVIEW_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> HistoryService.INSTANCE.getToView());

            assertEquals(-101, e.getCode());
            assertTrue(e.getMessage().contains("账号未登录"), "实际：" + e.getMessage());
        }
    }

    // ================================================================
    // 收藏夹目录
    // ================================================================

    @Nested
    @DisplayName("收藏夹目录（x/v3/fav/folder/created/list-all）")
    class FavFolderTest {

        @Test
        @DisplayName("id 才是 media_id，fid 是另一套短 id —— 两者都要能取到且不相等")
        void idVersusFid() throws Exception {
            mock.register(FAV_PATH, fixture("fav-folders.json"));

            FavFolderList data = FavoriteService.INSTANCE.getCreatedFolders(MID);

            assertEquals(12, data.getCount());
            assertEquals(3, data.getList().size());

            FavFolderList.FavFolder first = data.getList().get(0);
            assertEquals(1095405480L, first.getId(), "★ 查夹内内容用的就是这个 id");
            assertEquals(10954054L, first.getFid(), "fid 是短 id，拿它去查内容会查不到");
            assertNotEquals(first.getId(), first.getFid(),
                    "两者实测不同值 —— 若哪天相等，说明服务端改了，需要重新确认该信哪个");
            assertEquals("默认收藏夹", first.getTitle());
            assertEquals(103, first.getMedia_count());
            assertEquals(1, first.getAttr(), "attr=1 公开、2 私密：它决定后续查内容会不会 -403");
            assertNull(data.getSeason(), "season 实测恒为 null，映射出来只为证明不是我们漏了");
        }

        @Test
        @DisplayName("up_mid 与 Referer（收藏夹页）都要对")
        void requestShape() throws Exception {
            mock.register(FAV_PATH, fixture("fav-folders.json"));

            FavoriteService.INSTANCE.getCreatedFolders(MID);

            assertTrue(mock.requestUri(FAV_PATH).contains("up_mid=" + MID),
                    "实际：" + mock.requestUri(FAV_PATH));
            String referer = mock.requestHeader(FAV_PATH, "Referer");
            assertTrue(referer != null && referer.endsWith("/favlist"), "实际：" + referer);
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不签名");
        }

        @Test
        @DisplayName("★ code=0 但 list 为空：必须抛异常，不能读成'此人没有收藏夹'")
        void favFolderEmptyListIsAnError() {
            // 这就是匿名调用的真实形状：外层 code=0，但拿不到列表。
            mock.register(FAV_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":"
                    + "{\"count\":0,\"list\":[],\"season\":null}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getCreatedFolders(MID));

            assertEquals(0, e.getCode(), "外层码是 0 —— 正因如此才不能靠它判断");
            assertTrue(e.getMessage().contains("list 为空"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("凭据"),
                    "要给出下一步动作。实际：" + e.getDescription());
        }

        @Test
        @DisplayName("list 为 null 与空数组一样判失败")
        void nullListIsAlsoAnError() {
            mock.register(FAV_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{\"count\":0}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getCreatedFolders(MID));

            assertTrue(e.getMessage().contains("list 为空"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("upMid ≤ 0：本地校验，零出站")
        void badMid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getCreatedFolders(0L));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(FAV_PATH));
        }
    }

    /** 服务层抛的是 {@link BilibiliException}（运行时），门面才转成 IOException —— 这里把边界钉住 */
    @Test
    @DisplayName("服务层边界：BilibiliException 是运行时异常，不会被服务自己吞掉")
    void serviceThrowsRuntimeException() {
        mock.register(TOVIEW_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

        BilibiliException e = assertThrows(BilibiliException.class,
                () -> HistoryService.INSTANCE.getToView());

        assertInstanceOf(RuntimeException.class, e);
        // 注意：BilibiliException 与 IOException 无继承关系，javac 不允许对二者直接 instanceof
        // （报"不兼容的类型"），所以用 isAssignableFrom 表达同一件事。
        assertFalse(IOException.class.isAssignableFrom(e.getClass()), "IOException 转换只发生在门面边界");
    }
}

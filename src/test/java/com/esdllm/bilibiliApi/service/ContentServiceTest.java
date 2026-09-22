package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.content.ArticleInfo;
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
@DisplayName("服务：HistoryService / FavoriteService / ArticleService（历史 · 稍后再看 · 收藏夹 · 专栏）")
class ContentServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String HISTORY_PATH = "/x/web-interface/history/cursor";
    private static final String TOVIEW_PATH = "/x/v2/history/toview";
    private static final String FAV_PATH = "/x/v3/fav/folder/created/list-all";
    /** B4 批 #1：专栏信息。本文件里<b>唯一不需要凭据</b>的一条。 */
    private static final String ARTICLE_PATH = "/x/article/viewinfo";

    private static final long MID = 497078180L;

    /** 夹具用的专栏号（{@code cv4538122} → 传数字部分） */
    private static final long CV = 4538122L;

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

    /**
     * 专栏是本文件里<b>唯一不需要凭据</b>的一项（B4 批 #1），也是唯一"<b>同名两个字段含义相反</b>"的一项。
     * 所以这里守两件事：① 全局统计只在 {@code stats} 里；② 那两个布尔<b>不能按字面理解</b>。
     *
     * <p>🔴 <b>2026-09-22 真机订正（本类原来写错了）</b>：原文称 {@code is_author} / {@code in_list}
     * "都是登录态字段，匿名恒 false"。真机跑冒烟时同一个 cv、同样没带凭据，{@code in_list} 拿到了
     * {@code true}。于是补做了一次 <b>2×2</b>（{@code 零 Cookie / 匿名指纹} × {@code 无凭据 / 有凭据}），
     * 结果两者<b>归因完全不同</b>：
     *
     * <table border="1">
     *   <caption>is_author / in_list 的 2×2 实测（cv4538122，作者 mid=5842315，凭据 mid=497078180）</caption>
     *   <tr><th>请求</th><th>{@code nav.isLogin}</th><th>{@code is_author}</th><th>{@code in_list}</th></tr>
     *   <tr><td>零 Cookie</td><td>false</td><td>false</td><td><b>false</b></td></tr>
     *   <tr><td>仅匿名指纹</td><td>false</td><td>false</td><td><b>true</b></td></tr>
     *   <tr><td>仅凭据（无指纹）</td><td>true</td><td><b>true</b></td><td><b>true</b></td></tr>
     *   <tr><td>凭据 + 指纹</td><td>true</td><td><b>true</b></td><td><b>true</b></td></tr>
     * </table>
     *
     * <ul>
     *   <li>{@code is_author} 与凭据<b>完全同向</b>（四格一致）⇒ 可以当"<b>已登录</b>"的指示器；
     *       但它与"是不是作者"无关 —— 四格都在读<b>别人的</b>文章，有凭据时照样 {@code true}。</li>
     *   <li>{@code in_list} <b>不跟凭据走，只跟"有没有会话标识"走</b>：零 Cookie 是 {@code false}，
     *       一旦带上匿名指纹（或凭据）就是 {@code true} ⇒ <b>它连"已登录"都指示不了</b>。
     *       ⚠️ 本库运行时<b>必然</b>携带匿名指纹 ⇒ 真实调用拿到的 {@code in_list} 通常是 {@code true}，
     *       与夹具（零 Cookie 快照，{@code false}）<b>不一致</b>。测试断言的是夹具快照，
     *       调用方<b>不得</b>用它判断"未收藏"。</li>
     * </ul>
     *
     * <p>📌 <b>方法论教训</b>：B4 首轮做过一次 A/B，得到"匿名 false → 凭据 true"，就把两个字段都归因成
     * "登录态"。但那次 A/B 的"匿名"格用的是<b>零 Cookie</b>，而"凭据"格必然带指纹 ——
     * <b>两个变量同时在变</b>，于是把 {@code in_list} 的差异错误地归给了凭据。
     * ⇒ <b>"匿名"不是一个状态</b>（同 WBI 那次的"匿名·无签名 vs 匿名·签名"），
     * 只变一个变量的对照才叫对照。
     */
    @Nested
    @DisplayName("专栏信息（x/article/viewinfo）")
    class ArticleTest {

        @Test
        @DisplayName("★ stats 才是全局统计：同一篇里 stats.like=35 而顶层 like=0，认错字段会读成「没人点赞」")
        void statsIsTheGlobalCounter() throws Exception {
            mock.register(ARTICLE_PATH, fixture("article-viewinfo.json"));

            ArticleInfo data = ArticleService.INSTANCE.getArticleInfo(CV);

            assertNotNull(data.getStats(), "全局统计在 stats 里，不在顶层");
            assertEquals(3231L, data.getStats().getView());
            assertEquals(35L, data.getStats().getLike());
            assertEquals(120L, data.getStats().getFavorite());
            assertEquals(9L, data.getStats().getReply());
            assertEquals(8L, data.getStats().getShare());
            assertEquals(2L, data.getStats().getCoin());
            assertEquals(4L, data.getStats().getDynamic());
            assertEquals(0L, data.getStats().getDislike());

            // 🔴 这一行是本文件最值钱的断言：两个同名字段含义完全不同
            assertEquals(0, data.getLike(), "顶层的 like 是「我点过赞没」，不是点赞总数");
            assertNotEquals((long) data.getLike(), (long) data.getStats().getLike(),
                    "若两者相等，说明夹具或映射错了 —— 这个坑就白记了");
        }

        @Test
        @DisplayName("「我视角」四个字段：匿名一律 0/false（表达的是「当前凭据做过什么」）")
        void myViewFieldsAreZeroWhenAnonymous() throws Exception {
            mock.register(ARTICLE_PATH, fixture("article-viewinfo.json"));

            ArticleInfo data = ArticleService.INSTANCE.getArticleInfo(CV);

            assertEquals(0, data.getLike());
            assertEquals(0, data.getCoin());
            assertFalse(data.getFavorite());
            assertFalse(data.getAttention());
        }

        @Test
        @DisplayName("🔴 两个布尔在夹具里的值：is_author=false（随凭据）／in_list=false（随会话，见类注释的 2×2）"
                + " —— 都不可拿来判断作者身份、收藏状态或是否已登录")
        void loginFlagsInFixtureAreTheAnonymousSnapshot() throws Exception {
            mock.register(ARTICLE_PATH, fixture("article-viewinfo.json"));

            ArticleInfo data = ArticleService.INSTANCE.getArticleInfo(CV);

            assertFalse(data.getIs_author(),
                    "夹具的值；带凭据时即使读【别人的】文章也是 true ⇒ 它只能当'已登录'的指示器，"
                            + "不能当'是不是我的'");
            assertFalse(data.getIn_list(),
                    "🔴 这个 false 只是'夹具是零 Cookie 快照'的产物。in_list 跟【会话标识】走而不是跟凭据走："
                            + "库运行时必然携带匿名指纹，实测那时它是 true。"
                            + "⇒ 千万不要拿 in_list 判断'未收藏'或'未登录'");
        }

        @Test
        @DisplayName("内容字段：标题/作者/作者 mid/图片/分享渠道")
        void contentFields() throws Exception {
            mock.register(ARTICLE_PATH, fixture("article-viewinfo.json"));

            ArticleInfo data = ArticleService.INSTANCE.getArticleInfo(CV);

            assertEquals("辉煌禄来——从2.8（3.5）a~2.8（3.5）e3", data.getTitle());
            assertEquals("凯申物流公司CEO", data.getAuthor_name());
            assertEquals(5842315L, data.getMid(), "这是【作者】的 mid，不是当前凭据的");
            assertEquals(1, data.getImage_urls().size());
            assertEquals(data.getImage_urls(), data.getOrigin_image_urls(), "实测两者同值");
            assertEquals(5, data.getShare_channels().size(), "QQ / QQ空间 / 微信 / 朋友圈 / 微博");
            assertEquals("QZONE", data.getShare_channels().get(1).getShare_channel());
            assertTrue(data.getShareable());
            assertFalse(data.getDisable_share());
            assertEquals("", data.getBanner_url(), "实测空串（不是 null）");
        }

        @Test
        @DisplayName("pre/next 无相邻文章时是 0 而不是 null —— 别用 != null 判断有没有下一篇")
        void prevNextAreZeroNotNull() throws Exception {
            mock.register(ARTICLE_PATH, fixture("article-viewinfo.json"));

            ArticleInfo data = ArticleService.INSTANCE.getArticleInfo(CV);

            assertEquals(0L, data.getPre());
            assertEquals(0L, data.getNext());
        }

        @Test
        @DisplayName("请求形状：id 走 query、Referer 是站根（本端点实测免疫）、不签名")
        void requestShape() throws Exception {
            mock.register(ARTICLE_PATH, fixture("article-viewinfo.json"));

            ArticleService.INSTANCE.getArticleInfo(CV);

            String uri = mock.requestUri(ARTICLE_PATH);
            assertTrue(uri.contains("id=" + CV), "实际：" + uri);
            assertFalse(uri.contains("cv"), "别把 cv 前缀发出去。实际：" + uri);
            assertEquals("https://www.bilibili.com/", mock.requestHeader(ARTICLE_PATH, "Referer"),
                    "四格实测 Referer 无影响，走全库默认站根");
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不签名，不该有 nav");
        }

        @Test
        @DisplayName("专栏号 ≤ 0：本地就挡掉，不发请求")
        void badId() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> ArticleService.INSTANCE.getArticleInfo(0L));

            assertTrue(e.getMessage().contains("专栏号"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(ARTICLE_PATH), "参数不合法时不该出站");
        }

        @Test
        @DisplayName("业务码非 0：透传")
        void businessCode() {
            mock.register(ARTICLE_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> ArticleService.INSTANCE.getArticleInfo(CV));

            assertEquals(-404, e.getCode());
        }

        @Test
        @DisplayName("data 为空/null：要响亮地报错，不能安静返回 null")
        void emptyData() {
            mock.register(ARTICLE_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":null}");

            assertThrows(BilibiliException.class,
                    () -> ArticleService.INSTANCE.getArticleInfo(CV));
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

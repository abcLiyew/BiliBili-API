package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderInfo;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavResourceList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code FavoriteService#getFolderInfo / #getResources}</b> 的回归测试（2026-09-22 B2 批 #5 #6）。
 *
 * <p>本文件守四件光看代码看不出来事：
 * <ol>
 *   <li>🔴 <b>{@code -403} 是两义码</b>（缺签名 <b>或</b> 资源权限不足），本域几乎总是后者。
 *       这里不只断言码值没丢，还断言<b>文案把两种成因讲清楚了</b> ——
 *       否则调用方会照着"缺 WBI 签名"去查半天，而本端点根本不需要签名。</li>
 *   <li>🔴 <b>{@code attr} 不能用来提前判断公开性</b>：实测含 {@code attr=1} 的夹（默认收藏夹）
 *       匿名是 {@code -403}，{@code attr=2} 的却能读。夹具用的是后者（可读的那个）。</li>
 *   <li>🔴 <b>"本页 0 条" 与 "夹是空的"不是一回事</b>：本库拿 {@code info.media_count}
 *       做交叉校验 —— 夹里有内容却一条不给要抛，真的空夹不抛。这两个方向都钉。</li>
 *   <li>⚠️ <b>{@code cnt_info} 在两个端点是两种形状</b>（夹的 4 个键 / 内容的 7 个键），
 *       套错会静默拿到 null。</li>
 * </ol>
 */
@DisplayName("服务：FavoriteService（收藏夹详情 / 夹内内容）")
class FavoriteFolderServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String INFO_PATH = "/x/v3/fav/folder/info";
    private static final String LIST_PATH = "/x/v3/fav/resource/list";

    /** 与两个夹具一致（可匿名读的那个夹） */
    private static final long MEDIA_ID = 3526698880L;
    /** 夹具里夹内内容第一条的 aid */
    private static final long FIRST_AID = 117283494035779L;

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
    // 夹详情
    // ================================================================

    @Nested
    @DisplayName("夹详情（folder/info）")
    class FolderInfoTest {

        @Test
        @DisplayName("字段逐条对上：id / fid / attr / title / media_count / upper / cnt_info")
        void fields() throws Exception {
            mock.register(INFO_PATH, fixture("fav-folder-info.json"));

            FavFolderInfo info = FavoriteService.INSTANCE.getFolderInfo(MEDIA_ID);

            assertEquals(MEDIA_ID, info.getId(), "★ id 才是夹内查询要用的 media_id");
            assertEquals(35266988L, info.getFid(), "fid 是另一套短 id —— 拿它去查内容会查不到");
            assertEquals(2, info.getAttr());
            assertEquals("小雨绒Candy", info.getTitle());
            assertEquals(101, info.getMedia_count());
            assertEquals("可可小绒猫", info.getUpper().getName());
            assertEquals(497078180L, info.getUpper().getMid());
            assertEquals(2, info.getUpper().getVip_type());
            assertEquals(Boolean.FALSE, info.getUpper().getFollowed(), "匿名态 followed 是 false");
            assertEquals(0L, info.getCnt_info().getCollect());
            assertEquals(1L, info.getCnt_info().getPlay());
            assertEquals(11, info.getType(), "含义未验证，原样映射");
            assertEquals(1742619602L, info.getCtime());
        }

        @Test
        @DisplayName("请求形状：media_id 原样带上；Referer 用站根")
        void request() throws Exception {
            mock.register(INFO_PATH, fixture("fav-folder-info.json"));

            FavoriteService.INSTANCE.getFolderInfo(MEDIA_ID);

            String uri = mock.requestUri(INFO_PATH);
            assertTrue(uri.contains("media_id=" + MEDIA_ID), "实际：" + uri);
            assertFalse(uri.contains("fid="), "★ 查询参数是 media_id，不是 fid。实际：" + uri);
            assertEquals("https://www.bilibili.com/", mock.requestHeader(INFO_PATH, "Referer"));
            assertEquals(0, mock.hitCount(NAV_PATH), "本域不需要签名");
        }

        @Test
        @DisplayName("media_id ≤ 0：本地校验，零出站")
        void badMediaId() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getFolderInfo(0L));

            assertTrue(e.getMessage().contains("media_id不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(INFO_PATH));
        }
    }

    // ================================================================
    // 夹内内容
    // ================================================================

    @Nested
    @DisplayName("夹内内容（resource/list）")
    class ResourcesTest {

        @Test
        @DisplayName("info / medias / has_more / ttl 都在；条目字段逐条对上")
        void fields() throws Exception {
            mock.register(LIST_PATH, fixture("fav-resource-list.json"));

            FavResourceList data = FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20);

            assertNotNull(data.getInfo(), "★ info 与 folder/info 的 data 同形状，本库共用 FavFolderInfo");
            assertEquals(MEDIA_ID, data.getInfo().getId());
            assertEquals(Boolean.TRUE, data.getHas_more());
            assertEquals(1790057068, data.getTtl(), "★ 实测是时间戳形态的整数，不是'剩余秒数'");
            assertEquals(2, data.getMedias().size(), "夹具裁自真实响应（原 20 条）");

            FavResourceList.Media m = data.getMedias().get(0);
            assertEquals(FIRST_AID, m.getId(), "★ id 是 aid");
            assertEquals(2, m.getType(), "2 = 视频");
            assertEquals("甜嗓翻唱『执迷不悟』所以会忙忙碌碌～", m.getTitle());
            assertEquals(1, m.getPage());
            assertEquals(33, m.getDuration());
            assertEquals(3546774476163227L, m.getUpper().getMid());
            assertEquals("小雨绒Candy", m.getUpper().getName());
        }

        @Test
        @DisplayName("★ bv_id 与 bvid 是同一个值（同值两存，别以为它们是两个 id）")
        void bvIdEqualsBvid() throws Exception {
            mock.register(LIST_PATH, fixture("fav-resource-list.json"));

            FavResourceList.Media m = FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20)
                    .getMedias().get(0);

            assertEquals("BV1mpeK6nEDK", m.getBvid());
            assertEquals(m.getBvid(), m.getBv_id());
            assertEquals("bilibili://video/" + FIRST_AID, m.getLink());
        }

        @Test
        @DisplayName("★ season / ogv 对视频内容是 null —— 它们是番剧/课程的字段，形状未验证")
        void seasonAndOgvAreNull() throws Exception {
            mock.register(LIST_PATH, fixture("fav-resource-list.json"));

            FavResourceList.Media m = FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20)
                    .getMedias().get(0);

            assertNull(m.getSeason(), "★ 夹具 20 条全是 type=2（视频），season 全为 null");
            assertNull(m.getOgv());
            assertNotNull(m.getUgc(), "ugc.first_cid 就是能拿去拉弹幕的 cid");
            assertEquals(41958378053L, m.getUgc().getFirst_cid());
        }

        @Test
        @DisplayName("★ cnt_info 与夹的那个不是同一形状：这里多 danmaku/reply，少 thumb_up/share")
        void cntInfoDiffersFromFolder() throws Exception {
            mock.register(LIST_PATH, fixture("fav-resource-list.json"));

            FavResourceList.CntInfo cnt = FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20)
                    .getMedias().get(0).getCnt_info();

            assertEquals(11L, cnt.getCollect());
            assertEquals(306L, cnt.getPlay());
            assertEquals(0L, cnt.getDanmaku());
            assertEquals(0L, cnt.getReply());
            assertEquals("306", cnt.getView_text_1(),
                    "★ 播放数的展示文本是【字符串】—— 别按数字读");
        }

        @Test
        @DisplayName("请求形状：media_id / pn / ps 都在；分页夹到合法范围")
        void request() throws Exception {
            mock.register(LIST_PATH, fixture("fav-resource-list.json"));

            FavoriteService.INSTANCE.getResources(MEDIA_ID, 0, 0);

            String uri = mock.requestUri(LIST_PATH);
            assertTrue(uri.contains("media_id=" + MEDIA_ID), "实际：" + uri);
            assertTrue(uri.contains("pn=1"), "实际：" + uri);
            assertTrue(uri.contains("ps=20"), "实际：" + uri);
        }

        @Test
        @DisplayName("media_id ≤ 0：本地校验，零出站")
        void badMediaId() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getResources(-1L, 1, 20));

            assertTrue(e.getMessage().contains("media_id不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(LIST_PATH));
        }
    }

    // ================================================================
    // ★ 本文件的核心一：-403 的两义
    // ================================================================

    @Nested
    @DisplayName("★ -403 的两义（本文件的核心）")
    class ForbiddenTest {

        private static final String FORBIDDEN =
                "{\"code\":-403,\"message\":\"访问权限不足\",\"ttl\":1,\"data\":null}";

        @Test
        @DisplayName("★ 夹详情 -403：码值保住 -403，文案把'两种成因'讲清楚，并点出 attr 不能反推")
        void folderInfoForbidden() {
            mock.register(INFO_PATH, FORBIDDEN);

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getFolderInfo(1095405480L));

            assertEquals(-403, e.getCode(), "★ -403 必须活下来（调用方可能按它分流）");
            assertTrue(e.getMessage().contains("访问权限不足"), "服务端原话要带上：" + e.getMessage());
            assertTrue(e.getMessage().contains("media_id=1095405480"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("资源权限不足"),
                    "★ 要点出真成因。实际：" + e.getDescription());
            assertTrue(e.getDescription().contains("不需要签名"),
                    "★ 还要说清'本域不需要签名'，否则调用方会去查签名那条死路。实际：" + e.getDescription());
            assertTrue(e.getDescription().contains("attr"),
                    "★ 顺带警告 attr 不能反推公开性。实际：" + e.getDescription());
        }

        @Test
        @DisplayName("★ 夹内内容 -403：同样改写（两个端点的裁决一致）")
        void resourcesForbidden() {
            mock.register(LIST_PATH, FORBIDDEN);

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getResources(1095405480L, 1, 20));

            assertEquals(-403, e.getCode());
            assertTrue(e.getMessage().contains("获取收藏夹内容失败"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("私密"), "实际：" + e.getDescription());
        }

        @Test
        @DisplayName("★ 其它错误码【不】被改写成收藏夹专属文案（别把通用错误掩盖掉）")
        void otherCodesPassThroughUnchanged() {
            mock.register(INFO_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getFolderInfo(MEDIA_ID));

            assertEquals(-101, e.getCode());
            assertFalse(e.getDescription().contains("资源权限不足"),
                    "★ -101 与 -403 处置完全不同，不能被套上同一段 hint。实际：" + e.getDescription());
            assertTrue(e.getDescription().contains("账号未登录"), "实际：" + e.getDescription());
        }
    }

    // ================================================================
    // ★ 本文件的核心二：空数据的两种含义
    // ================================================================

    @Nested
    @DisplayName("★ 空数据的两种含义（本文件的核心）")
    class EmptyDataTest {

        @Test
        @DisplayName("★ 夹里说有内容却一条都没给：抛异常（多半是 pn 越界或形状变了）")
        void declaredButEmptyThrows() {
            mock.register(LIST_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{"
                    + "\"info\":{\"id\":3526698880,\"media_count\":101},"
                    + "\"medias\":[],\"has_more\":false,\"ttl\":1}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getResources(MEDIA_ID, 5, 20));

            assertEquals(0, e.getCode());
            assertTrue(e.getMessage().contains("101"), "★ 文案要把服务端声明的条数带出来。实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("pn=5"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("pn"), "hint 要给可执行的下一步。实际：" + e.getDescription());
        }

        @Test
        @DisplayName("★ 真的空夹（media_count=0）：返回空列表，【不】抛 —— 与上一条刻意相反")
        void trulyEmptyIsLegal() {
            mock.register(LIST_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{"
                    + "\"info\":{\"id\":3526698880,\"media_count\":0},"
                    + "\"medias\":[],\"has_more\":false,\"ttl\":1}}");

            FavResourceList data = FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20);

            assertNotNull(data);
            assertTrue(data.getMedias().isEmpty(),
                    "★ 空夹是合法结果。判据是 info.media_count —— 有它佐证才敢安静返回空");
        }

        @Test
        @DisplayName("info 缺失时不做交叉校验（拿不到依据就不猜）")
        void noInfoNoGuard() {
            mock.register(LIST_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{"
                    + "\"info\":null,\"medias\":[],\"has_more\":false,\"ttl\":1}}");

            FavResourceList data = FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20);

            assertTrue(data.getMedias().isEmpty(),
                    "★ 没有 media_count 就没有判据 —— 这时不该把'空'当成错误（那会误伤真空的夹）");
        }

        @Test
        @DisplayName("data 为 null：抛异常")
        void nullData() {
            mock.register(LIST_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20)).getCode());
        }

        @Test
        @DisplayName("HTTP 412 风控：码值保住 412")
        void http412() {
            mock.registerStatus(LIST_PATH, 412, "");

            assertEquals(412, assertThrows(BilibiliException.class,
                    () -> FavoriteService.INSTANCE.getResources(MEDIA_ID, 1, 20)).getCode());
        }
    }
}

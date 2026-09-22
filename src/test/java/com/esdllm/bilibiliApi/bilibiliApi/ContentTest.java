package com.esdllm.bilibiliApi.bilibiliApi;

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
 * <b>Content 门面回归测试</b>（第 11 个门面，2026-09-22 B3.5 批新增）。
 *
 * <p>与 {@code UserSpaceTest} / {@code SearchTest} 同一套思路：门面没有业务逻辑，
 * 只钉<b>委托正确</b>与<b>异常边界</b>两件事；解析细节留给 {@code ContentServiceTest}。
 *
 * <p>这里额外守一条本门面特有的东西：<b>三项的"失败长相"不一致</b>。
 * 门面把它们统一成 {@code IOException}，但<b>业务码必须原样活下来</b> ——
 * 因为调用方要靠 {@code -101} 决定"去重新登录"，而收藏夹那条连码都是 0，
 * 只能靠文案里的"list 为空"来分辨。写成一个统一的错误处理就等于把这个区别抹掉了。
 */
@DisplayName("门面：Content（观看历史 / 稍后再看 / 收藏夹目录）")
class ContentTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String HISTORY_PATH = "/x/web-interface/history/cursor";
    private static final String TOVIEW_PATH = "/x/v2/history/toview";
    private static final String FAV_PATH = "/x/v3/fav/folder/created/list-all";

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
    }
}

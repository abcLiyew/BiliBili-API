package com.esdllm.bilibiliApi.bilibiliApi;

import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.PopularList;
import com.esdllm.bilibiliApi.model.data.pojo.video.RankingList;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Ranking 门面回归测试</b>（第 14 个门面，2026-09-22 B1 批新增）。
 *
 * <p>与 {@code UserSpaceTest} / {@code ContentTest} 同一套思路：门面没有业务逻辑，
 * 只钉<b>委托正确</b>与<b>异常边界</b>两件事；解析细节留给 {@code RankingServiceTest}。
 *
 * <p>但本文件要额外把<b>本批最贵的一条实测结论</b>从门面这一层再钉一遍：
 * {@code ranking/v2} <b>不能带站根 Referer</b>（会 {@code -352 风控校验失败}），
 * 必须用排行榜页。这不是"顺手锁个请求头"—— 2026-09-22 的预检里它差点被读成
 * "出口被封 / 需要签名"，是<b>阳性对照</b>把误判救了回来。
 * 断言写在门面层，是因为调用方最可能从这里"顺手统一一下 Referer"。
 *
 * <p>两者都匿名可用、都不需要签名，因此每个用例都断言一次 {@code nav} 都不打。
 */
@DisplayName("门面：Ranking（排行榜 / 热门视频）")
class RankingTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String RANK_PATH = "/x/web-interface/ranking/v2";
    private static final String POPULAR_PATH = "/x/web-interface/popular";

    /** 与 {@code BilibiliEndpoint.rankingReferer} 同值 —— 故意写死字面量做交叉校验 */
    private static final String RANK_REFERER = "https://www.bilibili.com/v/popular/rank/all";
    /** 站根，本库多数端点的默认 Referer —— 但对 ranking 是**错的** */
    private static final String SITE_ROOT = "https://www.bilibili.com/";

    private MockBiliServer mock;
    private Ranking ranking;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
        ranking = new Ranking();
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
        @DisplayName("★ 排行榜必须用排行榜页 Referer：站根会换来 -352，且看起来像出口被封")
        void rankingReferer() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));

            RankingList data = ranking.getRanking(0);

            assertEquals(2, data.getList().size());
            assertEquals(100000L, data.getList().get(0).getScore(), "score 只有排行榜给");
            assertNotNull(data.getNote(), "note 是榜单口径说明，不是错误信息");

            String referer = mock.requestHeader(RANK_PATH, "Referer");
            assertEquals(RANK_REFERER, referer,
                    "★ 门面这一层也要钉住：站根 Referer 会 -352（实测 4 次复现），"
                            + "报出来的是风控码，完全不像'Referer 写错了'");
            assertNotEquals(SITE_ROOT, referer, "站根是库内其它端点的默认值，但这里不能用");
            assertEquals(0, mock.hitCount(NAV_PATH), "免签名");
        }

        @Test
        @DisplayName("getRanking(rid)：默认 type=all")
        void defaultType() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));

            ranking.getRanking(1);

            String uri = mock.requestUri(RANK_PATH);
            assertTrue(uri.contains("rid=1") && uri.contains("type=all"), "实际：" + uri);
        }

        @Test
        @DisplayName("getRanking(rid, type)：type 照传；空白回落 all")
        void explicitType() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));

            ranking.getRanking(1, "origin");
            assertTrue(mock.requestUri(RANK_PATH).contains("type=origin"),
                    "实际：" + mock.requestUri(RANK_PATH));

            ranking.getRanking(1, "   ");
            assertTrue(mock.requestUri(RANK_PATH).contains("type=all"),
                    "实际：" + mock.requestUri(RANK_PATH));
        }

        @Test
        @DisplayName("getPopular：一次一页；对站根 Referer 不敏感（与排行榜正相反）")
        void popular() throws Exception {
            mock.register(POPULAR_PATH, fixture("popular.json"));

            PopularList data = ranking.getPopular(20, 1);

            assertEquals(2, data.getList().size());
            assertFalse(data.getNo_more());
            Object reason = data.getList().get(0).getRcmd_reason();
            assertInstanceOf(JSONObject.class, reason,
                    "★ popular 的 rcmd_reason 是对象（相关推荐那边是字符串）——同一个 POJO 两种形状");
            assertEquals("百万播放", ((JSONObject) reason).getString("content"));

            String uri = mock.requestUri(POPULAR_PATH);
            assertTrue(uri.contains("ps=20") && uri.contains("pn=1"), "实际：" + uri);
            assertEquals(SITE_ROOT, mock.requestHeader(POPULAR_PATH, "Referer"),
                    "与排行榜不同：同一个门面里两个端点的 Referer 策略不一致，别一起改");
            assertEquals(0, mock.hitCount(NAV_PATH), "免签名");
        }

        @Test
        @DisplayName("两种返回类型不同：榜单是 RankingList、热门是 PopularList，别互相接收")
        void returnTypesDiffer() throws Exception {
            mock.register(RANK_PATH, fixture("ranking.json"));
            mock.register(POPULAR_PATH, fixture("popular.json"));

            RankingList list = ranking.getRanking(0);
            PopularList popular = ranking.getPopular(20, 1);

            assertNotNull(list.getNote(), "排行榜多一个 note");
            assertNotNull(popular.getNo_more(), "热门多一个 no_more");
            assertEquals(list.getList().get(0).getClass(), popular.getList().get(0).getClass(),
                    "元素形状是同一个 VideoBrief，但外层两个容器不同");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("★ -352 风控：包成 IOException，码值活下来（调用方靠它分辨风控与参数错）")
        void riskControl() {
            mock.register(RANK_PATH, "{\"code\":-352,\"message\":\"风控校验失败\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> ranking.getRanking(0));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-352, cause.getCode());
            assertTrue(e.getMessage().contains("风控校验失败"), "服务端原话要带上：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 412：同样是 IOException（出口风控，不是参数错）")
        void http412() {
            mock.registerStatus(RANK_PATH, 412, "");

            IOException e = assertThrows(IOException.class, () -> ranking.getRanking(0));

            assertEquals(412, assertInstanceOf(BilibiliException.class, e.getCause()).getCode());
        }

        @Test
        @DisplayName("data 为 null：也走 IOException 边界")
        void nullData() {
            mock.register(POPULAR_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":null}");

            IOException e = assertThrows(IOException.class, () -> ranking.getPopular(20, 1));

            assertTrue(e.getMessage().contains("data 为空"), "实际：" + e.getMessage());
        }
    }
}

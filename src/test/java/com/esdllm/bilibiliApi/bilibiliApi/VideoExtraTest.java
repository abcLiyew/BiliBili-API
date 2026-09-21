package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.AiSummary;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>VideoExtra 门面回归测试</b>（B3 批新增的第 9 个门面）。
 *
 * <p>本文件同时补上了 AI 摘要这条链路<b>此前缺失的断言</b>：{@code VideoServiceTest} 里
 * 只有视频信息/旧接口的用例，{@code conclusion.json} 与 {@code video-view.json} 的
 * {@code data.cid} 是为本批新加的夹具 —— 夹具不配套断言就等于没测。
 *
 * <p>这里钉住三件容易被写错的事：
 * <ol>
 *   <li><b>两个重载各自该打几次请求</b>：{@code getAiSummary(bvid)} 会先多打一次
 *       {@code x/web-interface/view} 拿 {@code cid}，{@code getAiSummary(bvid, cid)}
 *       不该再打 —— 用 {@code hitCount} 断言，而不是靠读代码相信"它省了一次"；</li>
 *   <li><b>这个端点要"签名 + 凭据"两样</b>，所以走的是 {@code getSigned}：断言
 *       {@code nav} 被打了 1 次、且 query 里有 {@code wts}/{@code w_rid}。</li>
 *   <li><b>{@code -101}（未登录）也要走 IOException 边界并保住业务码</b> —— 它是本端点
 *       最常遇到的失败（实测：匿名签名后暴露的就是它），调用方要靠这个码判断
 *       "该换凭据"还是"该重试"。</li>
 * </ol>
 *
 * <p>⚠️ 不要用 {@code data.code} / {@code data.status} 判断"有没有摘要"——实测两者都是 0
 * 而摘要正常返回，唯一判据是 {@link AiSummary#hasSummary()}。
 */
@DisplayName("门面：VideoExtra（AI 摘要）")
class VideoExtraTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String VIEW_PATH = "/x/web-interface/view?bvid=";
    private static final String CONC_PATH = "/x/web-interface/view/conclusion/get";

    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private static final String BVID = "BV1tgPie2E3w";
    /** 与 {@code video-view.json} 里 {@code data.cid} 一致 —— 两个夹具必须对得上，否则是假绿 */
    private static final long CID = 9990001L;

    private MockBiliServer mock;
    private VideoExtra videoExtra;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
        videoExtra = new VideoExtra();
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
    @DisplayName("委托")
    class DelegationTest {

        @Test
        @DisplayName("已知 cid：解析出摘要，只打 nav + conclusion 两次，且【不】再打 view")
        void withKnownCid() throws Exception {
            mock.register(CONC_PATH, fixture("conclusion.json"));

            AiSummary summary = videoExtra.getAiSummary(BVID, CID);

            assertTrue(summary.hasSummary(), "唯一判据是 hasSummary()，不是 code/status");
            assertNotNull(summary.getModel_result());
            assertEquals(2, summary.getModel_result().getOutline().size(), "大纲 2 段（夹具定值）");
            assertNotNull(summary.getModel_result().getSummary());

            assertEquals(1, mock.hitCount(NAV_PATH), "走 getSigned 要先取一次 WBI 密钥");
            assertEquals(0, mock.hitCount(VIEW_PATH), "已知 cid 就不该再打 view —— 这是本重载的意义");
            String uri = mock.requestUri(CONC_PATH);
            assertTrue(uri.contains("bvid=" + BVID) && uri.contains("cid=" + CID), "实际：" + uri);
            assertTrue(uri.contains("wts=") && uri.contains("w_rid="), "实际：" + uri);
            assertTrue(mock.requestHeader(CONC_PATH, "Referer").contains(BVID),
                    "实际 Referer：" + mock.requestHeader(CONC_PATH, "Referer"));
        }

        @Test
        @DisplayName("只给 bvid：先打 view 取 cid 再打 conclusion（多花一次请求是明说过的代价）")
        void autoCid() throws Exception {
            mock.register(VIEW_PATH, fixture("video-view.json"));
            mock.register(CONC_PATH, fixture("conclusion.json"));

            AiSummary summary = videoExtra.getAiSummary(BVID);

            assertTrue(summary.hasSummary());
            assertEquals(1, mock.hitCount(VIEW_PATH), "view 要打，且只打一次");
            assertEquals(1, mock.hitCount(CONC_PATH));
            assertTrue(mock.requestUri(CONC_PATH).contains("cid=" + CID),
                    "cid 必须来自 view 的 data.cid：" + mock.requestUri(CONC_PATH));
        }

        @Test
        @DisplayName("视频没有摘要时返回对象而【不抛异常】—— \"没有\"是一种正常结果")
        void noSummaryIsNotAnError() throws Exception {
            mock.register(CONC_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"code\":0,\"status\":0,\"stid\":\"1\",\"model_result\":null}}");

            AiSummary summary = videoExtra.getAiSummary(BVID, CID);

            assertNotNull(summary);
            assertFalse(summary.hasSummary());
            // 关键：data.code / data.status 都是 0，拿它们当判据就会把"没有"读反
            assertEquals(0, summary.getCode());
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("bvid 为空：包成 IOException，且没有任何出站")
        void blankBvid() {
            IOException e = assertThrows(IOException.class, () -> videoExtra.getAiSummary(null, null));

            assertTrue(e.getMessage().contains("BV号不能为空"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(NAV_PATH), "本地校验失败不该先跑去取密钥");
            assertEquals(0, mock.hitCount(CONC_PATH));
        }

        @Test
        @DisplayName("cid ≤ 0：也包成 IOException（两个参数先后校验，别只测前一个）")
        void badCid() {
            IOException e = assertThrows(IOException.class, () -> videoExtra.getAiSummary(BVID, 0L));

            assertTrue(e.getMessage().contains("cid不能为空"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(CONC_PATH));
        }

        @Test
        @DisplayName("★ -101 未登录：业务码必须活到 IOException 的 cause 里")
        void notLoggedIn() throws Exception {
            mock.register(CONC_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> videoExtra.getAiSummary(BVID, CID));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-101, cause.getCode(),
                    "调用方靠这个码决定'该去登录取凭据'，只剩一句文案就判不出来了");
            assertTrue(e.getMessage().contains("账号未登录"), "服务端原话要带上：" + e.getMessage());
        }

        @Test
        @DisplayName("-403（缺签名时的表象）：同样是 IOException，不静默返回空摘要")
        void signRejected() throws Exception {
            mock.register(CONC_PATH, "{\"code\":-403,\"message\":\"访问权限不足\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> videoExtra.getAiSummary(BVID, CID));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-403, cause.getCode());
        }
    }
}

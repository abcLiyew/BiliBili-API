package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuItem;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuXml;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Danmaku 门面回归测试</b>（第 15 个门面，2026-09-22 B2 批新增）。
 *
 * <p>与 {@code CommentTest} / {@code ContentTest} 同一套思路：门面没有业务逻辑，
 * 只钉<b>委托正确</b>与<b>异常边界</b>两件事；XML 解析的细节留给 {@code DanmakuXmlTest}。
 *
 * <p>但本文件额外守三条本门面特有的东西：
 * <ol>
 *   <li>🔴 <b>{@code oid} 要的是 {@code cid}</b>，既不是 {@code aid} 也不是 {@code bvid}
 *       —— 传错不报"参数错"，只会给 HTTP 400 或空 XML，所以必须断言 query 里真的是那个数；</li>
 *   <li>🔴 <b>{@code Referer} 对本端点无效</b>（实测三格响应字节数完全相同），
 *       所以它用全库默认的<b>站根</b>就够，调用方不需要额外传 bvid
 *       —— 这条与 {@code Ranking} 的 {@code ranking/v2}（站根会挂）正好相反，
 *       两个端点都在本库，脾气相反，所以两处都要钉；</li>
 *   <li>🔴 <b>它是全库唯一返回 XML 的端点</b>：失败形态很特别 —— 没有业务码可判，
 *       只能靠 HTTP 状态与"响应看起来像不像 XML"来判断。所以"拿到乱码必须响亮地抛"
 *       是一条承重断言（否则未解压的 deflate 字节流会静默变成零条弹幕）。
 * </ol>
 *
 * <p><b>门槛</b>：✅ 匿名可用。每个用例都断言一次 {@code nav} 都不打。
 */
@DisplayName("门面：Danmaku（弹幕）")
class DanmakuTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String DM_PATH = "/x/v1/dm/list.so";
    private static final String SITE_ROOT = "https://www.bilibili.com/";

    /** 与 {@code danmaku.xml} 的 {@code chatid} 一致 —— 它就是分 P 的 cid */
    private static final long CID = 41961327629L;

    /**
     * 上限 = 条数：制造"正好被截断"的形态。
     *
     * <p>服务层会为此打 {@code WARN}，但<b>不该抛</b> —— 被截断是端点的固有限制
     * （没有翻页参数），不是错误。
     */
    private static final String TRUNCATED_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><chatserver>chat.bilibili.com</chatserver>"
                    + "<chatid>1</chatid><maxlimit>2</maxlimit><state>0</state><real_name>0</real_name>"
                    + "<source>k-v</source>"
                    + "<d p=\"1.00000,1,25,16777215,1789865587,0,aa,11,0\">A</d>"
                    + "<d p=\"2.00000,1,25,16777215,1789865588,0,bb,22,0\">B</d></i>";

    /**
     * 零弹幕：实测一个没人发弹幕的视频就是这种形态（只有 {@code <i>} 头、{@code maxlimit=300}）。
     *
     * <p>它是<b>合法结果</b>，与 {@code fav/folder/created/list-all} 的"空列表必须抛"相反 ——
     * 区别在于这边的空<b>无法</b>由"缺凭据"造成（本端点匿名就给全量）。
     */
    private static final String EMPTY_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i><chatserver>chat.bilibili.com</chatserver>"
                    + "<chatid>1</chatid><maxlimit>300</maxlimit><state>0</state><real_name>0</real_name>"
                    + "<source>k-v</source></i>";

    private MockBiliServer mock;
    private Danmaku danmaku;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
        danmaku = new Danmaku();
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
        @DisplayName("getDanmaku：头部信息与弹幕列表都在，条目按 9 段 p 解析")
        void allDanmaku() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            DanmakuXml data = danmaku.getDanmaku(CID);

            // 头部：这是 DanmakuXml 相对 List 多出来的东西，maxlimit 是判"有没有被截断"的唯一依据
            assertEquals("chat.bilibili.com", data.getChatserver());
            assertEquals(CID, data.getChatid());
            assertEquals(1000, data.getMaxlimit(), "本视频的硬上限（实测有 300 / 1000 两种）");
            assertEquals("k-v", data.getSource());

            List<DanmakuItem> list = data.getDanmaku();
            assertEquals(6, list.size(), "夹具裁到 6 条");

            // 第一条把 p 的 9 段全用上：段数、类型、顺序都钉住
            DanmakuItem first = list.get(0);
            assertEquals("何须要这众生知道？", first.getText());
            assertEquals(85.333, first.getTime(), 1e-9, "第 1 段 = 出现时间（秒），是小数");
            assertEquals(1, first.getMode(), "第 2 段 = 弹幕类型（1 滚动 / 5 顶部 / 4 底部）");
            assertEquals(25, first.getFontSize(), "第 3 段 = 字号");
            assertEquals(16777215, first.getColor(), "第 4 段 = 颜色（十进制 16777215 = 白）");
            assertEquals(1789865587L, first.getTimestamp(), "第 5 段 = 发送时间戳（秒）");
            assertEquals(0, first.getPool(), "第 6 段 = 弹幕池");
            assertEquals("99b04462", first.getUserHash(), "第 7 段 = 发送者 hash（不是 uid）");
            assertEquals(2204311331669302272L, first.getDmid(), "第 8 段 = 弹幕 id");
            assertEquals(10, first.getLane(), "第 9 段 = 未知（实测恒 10），归入 lane");
        }

        @Test
        @DisplayName("★ 颜色是逐条的：不同条目可以不同（不能用一个类级默认值糊过去）")
        void colorIsPerItem() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            List<DanmakuItem> list = danmaku.getDanmaku(CID).getDanmaku();

            assertEquals(16777215, list.get(0).getColor());
            assertEquals(16646914, list.get(2).getColor(), "第 3 条实测是自定义色 —— 它证明颜色真从 p 里读出来了");
            assertEquals(5, list.get(2).getMode(), "第 3 条同时是顶部弹幕");
        }

        @Test
        @DisplayName("★ oid 里放的是 cid（不是 aid、不是 bvid），且 Referer 用站根就够")
        void cidGoesIntoOid() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            danmaku.getDanmaku(CID);

            String uri = mock.requestUri(DM_PATH);
            assertTrue(uri.contains("oid=" + CID),
                    "★ 端点的参数名叫 oid，但值必须是分 P 的 cid。实际：" + uri);
            assertFalse(uri.contains("BV"),
                    "★ query 里绝不能出现 BV 号 —— 那样只会得到 400 或空 XML，不会报参数错。实际：" + uri);
            assertEquals(SITE_ROOT, mock.requestHeader(DM_PATH, "Referer"),
                    "★ 实测三格对照（不传 / 站根 / 视频页）响应字节数完全相同 ⇒ 站根就够，"
                            + "调用方不需要再传 bvid。这与 ranking/v2（站根会挂）正好相反");
            assertEquals(0, mock.hitCount(NAV_PATH), "零门槛：既不要签名也不要凭据");
        }

        @Test
        @DisplayName("getDanmakuList：只要文本列表的便捷版本")
        void listShortcut() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            List<DanmakuItem> list = danmaku.getDanmakuList(CID);

            assertEquals(6, list.size());
            assertEquals("何须要这众生知道？", list.get(0).getText());
            assertEquals(1, mock.hitCount(DM_PATH), "两个方法打的是同一个端点，没有额外请求");
        }

        @Test
        @DisplayName("★ 正好等于 maxlimit：是「被截断」不是错误 —— 打 WARN，但完整返回，不抛")
        void truncatedIsWarnNotThrow() throws Exception {
            mock.register(DM_PATH, TRUNCATED_XML);

            DanmakuXml data = danmaku.getDanmaku(CID);

            assertEquals(2, data.getMaxlimit());
            assertEquals(2, data.getDanmaku().size(),
                    "★ 端点数正好等于上限时，本库只打 WARN，不把'被截断'升级成异常 —— "
                            + "该端点没有翻页参数，抛异常也给不了更多数据，只会让调用方以为坏了");
        }

        @Test
        @DisplayName("★ 零弹幕是合法结果：返回空列表，不抛")
        void zeroDanmakuIsLegal() throws Exception {
            mock.register(DM_PATH, EMPTY_XML);

            DanmakuXml data = danmaku.getDanmaku(CID);

            assertTrue(data.getDanmaku().isEmpty());
            assertEquals(300, data.getMaxlimit(),
                    "★ 没有弹幕的视频 maxlimit 是 300、有弹幕的是 1000（实测）—— 它是视频设置，不是常量");

            assertEquals(0, danmaku.getDanmakuList(CID).size(), "便捷版本同样给空列表，而不是 null");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("cid ≤ 0：包成 IOException，且一个出站都不发")
        void badCid() {
            IOException e = assertThrows(IOException.class, () -> danmaku.getDanmaku(0L));

            assertTrue(e.getMessage().contains("cid不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(DM_PATH));

            assertThrows(IOException.class, () -> danmaku.getDanmakuList(-1L));
            assertEquals(0, mock.hitCount(DM_PATH), "便捷版本也要在发请求前拦住");
        }

        @Test
        @DisplayName("★ 响应不像 XML（例如拿到没解压的 deflate 字节流）：必须响亮地抛，不能静默给 0 条")
        void garbageIsLoud() throws Exception {
            mock.register(DM_PATH, "\u0001\u0002\u0003deflate-garbage-not-xml");

            IOException e = assertThrows(IOException.class, () -> danmaku.getDanmaku(CID));

            assertTrue(e.getMessage().contains("不像 XML"), "实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertTrue(cause.getDescription().contains("deflate"),
                    "★ 最可能的成因要写在 description 里，否则排障只能从'不像 XML'这句开始猜。"
                            + "实际：" + cause.getDescription());
        }

        @Test
        @DisplayName("响应体为空：也抛（不是返回 0 条弹幕）")
        void emptyBodyIsLoud() {
            mock.register(DM_PATH, "");

            IOException e = assertThrows(IOException.class, () -> danmaku.getDanmaku(CID));

            assertTrue(e.getMessage().contains("响应体为空"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
        }

        @Test
        @DisplayName("HTTP 412（出口风控）：包成 IOException，码值保住 412")
        void http412() {
            mock.registerStatus(DM_PATH, 412, "");

            IOException e = assertThrows(IOException.class, () -> danmaku.getDanmaku(CID));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(412, cause.getCode(),
                    "★ 本端点没有业务码，HTTP 状态是唯一的码值来源 —— 丢了它就没法区分风控与参数错");
            assertTrue(e.getMessage().contains("风控"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 404：同样是 IOException，码值保住 404")
        void http404() {
            mock.registerStatus(DM_PATH, 404, "");

            IOException e = assertThrows(IOException.class, () -> danmaku.getDanmaku(CID));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(404, cause.getCode());
        }
    }
}

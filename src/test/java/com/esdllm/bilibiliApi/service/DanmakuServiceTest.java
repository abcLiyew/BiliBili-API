package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuItem;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuXml;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code DanmakuService}</b> 的回归测试（2026-09-22 B2 批 #1）。
 *
 * <p>解析规则本身在 {@code DanmakuXmlTest}（零出站）里测；本文件只管<b>出站那一层</b>：
 * URL / Referer / 门槛判断 / HTTP 失败形态。
 *
 * <p>守三件光看代码看不出来事：
 * <ol>
 *   <li>🔴 <b>{@code oid} 要的是 {@code cid}</b>。传 aid / bvid 不会报"参数错"，
 *       只会得到 HTTP 400 或空 XML —— 与"这视频没弹幕"同形。</li>
 *   <li>🔴 <b>不要签名</b>：本端点不需要 WBI，所以一次 {@code nav} 都不该打
 *       （打了只会平白多一个失败面）。</li>
 *   <li>🔴 <b>乱码 = 没解压</b>：这是本端点最可能的坏法，必须响亮地抛。</li>
 * </ol>
 */
@DisplayName("服务：DanmakuService（弹幕）")
class DanmakuServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String DM_PATH = "/x/v1/dm/list.so";

    /** 与 {@code danmaku.xml} 的 {@code chatid} 一致 */
    private static final long CID = 41961327629L;

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
    // 请求形状
    // ================================================================

    @Nested
    @DisplayName("请求形状")
    class RequestTest {

        @Test
        @DisplayName("★ oid 就是 cid，不是 aid / bvid；Referer 用站根")
        void queryShape() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            DanmakuService.INSTANCE.getDanmaku(CID);

            String uri = mock.requestUri(DM_PATH);
            assertTrue(uri.startsWith(DM_PATH + "?oid=" + CID),
                    "★ 端点的参数名叫 oid，但值必须是分 P 的 cid。实际：" + uri);
            assertFalse(uri.contains("BV"), "★ 丢掉 cid 改成 BV 号只会换来 HTTP 400。实际：" + uri);
            assertEquals("https://www.bilibili.com/",
                    mock.requestHeader(DM_PATH, "Referer"),
                    "★ 实测三格对照（不传/站根/视频页）字节数完全相同 ⇒ 站根就够，"
                            + "不必要求调用方再传一个 bvid");
        }

        @Test
        @DisplayName("★ 一次 nav 都不打：本端点不需要 WBI 签名")
        void noSigning() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            DanmakuService.INSTANCE.getDanmaku(CID);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "套签名只会平白多一个 'nav 不可达' 的失败面");
        }

        @Test
        @DisplayName("Accept 用全库统一的 jsonAccept（本端点返回 XML，但实测它不挑 Accept）")
        void acceptHeader() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            DanmakuService.INSTANCE.getDanmaku(CID);

            assertTrue(Objects.requireNonNull(mock.requestHeader(DM_PATH, "Accept")).contains("application/json"),
                    "实际：" + mock.requestHeader(DM_PATH, "Accept"));
        }
    }

    // ================================================================
    // 返回值
    // ================================================================

    @Nested
    @DisplayName("返回值")
    class ResultTest {

        @Test
        @DisplayName("getDanmaku：连头部信息一起给（maxlimit 是判断截断的唯一依据）")
        void envelope() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));

            DanmakuXml xml = DanmakuService.INSTANCE.getDanmaku(CID);

            assertEquals(CID, xml.getChatid());
            assertEquals(1000, xml.getMaxlimit());
            assertEquals(6, xml.getDanmaku().size());
        }

        @Test
        @DisplayName("getDanmakuList：只给列表；没有弹幕时是【空列表】而不是 null")
        void listOverload() throws Exception {
            mock.register(DM_PATH, fixture("danmaku.xml"));
            List<DanmakuItem> list = DanmakuService.INSTANCE.getDanmakuList(CID);
            assertEquals(6, list.size());

            // 真实世界里确实存在"没有弹幕"的视频，形态就是只给 <i> 头
            mock.registerStatus(DM_PATH, 200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<i><chatserver>chat.bilibili.com</chatserver><chatid>41958378053</chatid>"
                    + "<mission>0</mission><maxlimit>300</maxlimit><state>0</state>"
                    + "<real_name>0</real_name></i>");
            List<DanmakuItem> empty = DanmakuService.INSTANCE.getDanmakuList(CID);

            assertNotNull(empty, "★ 返回空列表而不是 null —— 与收藏夹那种'空必须抛'刻意相反，"
                    + "因为本端点匿名就给全量，空不可能是'缺凭据'造成的");
            assertTrue(empty.isEmpty());
        }

        @Test
        @DisplayName("条数达到 maxlimit 时不抛异常（会有 WARN 日志，那是'被截断了'的提示）")
        void truncatedIsNotAnError() throws Exception {
            mock.register(DM_PATH, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>"
                    + "<chatid>1</chatid><maxlimit>2</maxlimit>"
                    + "<d p=\"1,1,25,16777215,1,0,aa,1,1\">a</d>"
                    + "<d p=\"2,1,25,16777215,1,0,bb,2,1\">b</d></i>");

            DanmakuXml xml = DanmakuService.INSTANCE.getDanmaku(CID);

            assertEquals(2, xml.getDanmaku().size());
            assertEquals(2, xml.getMaxlimit(), "size == maxlimit ⇒ 调用方可以据此判断被截断");
        }
    }

    // ================================================================
    // 失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("cid ≤ 0：本地校验，零出站")
        void badCid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DanmakuService.INSTANCE.getDanmaku(0L));

            assertTrue(e.getMessage().contains("cid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(DM_PATH));
        }

        @Test
        @DisplayName("★ 乱码响应（没解压的 deflate）：抛异常并指明成因，不给空列表")
        void garbledBody() {
            mock.registerStatus(DM_PATH, 200, "x\u0001\u0002\u0003not-xml-at-all");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DanmakuService.INSTANCE.getDanmaku(CID));

            assertTrue(e.getMessage().contains("不像 XML"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("deflate"), "实际：" + e.getDescription());
        }

        @Test
        @DisplayName("HTTP 400（cid 传错时的典型形态）：异常带上 400")
        void http400() {
            mock.registerStatus(DM_PATH, 400, "");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DanmakuService.INSTANCE.getDanmaku(CID));

            assertEquals(400, e.getCode());
            assertTrue(e.getMessage().contains("获取弹幕失败"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 412（风控）：码值保住 412，hint 说明不可自动重试")
        void http412() {
            mock.registerStatus(DM_PATH, 412, "");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DanmakuService.INSTANCE.getDanmaku(CID));

            assertEquals(412, e.getCode());
            assertTrue(e.getMessage().contains("412"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 404（端点下线）：异常带上 404，不会被当成'没弹幕'")
        void http404() {
            // 未注册的路径 MockBiliServer 返回 404
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DanmakuService.INSTANCE.getDanmaku(CID));

            assertEquals(404, e.getCode());
        }
    }
}

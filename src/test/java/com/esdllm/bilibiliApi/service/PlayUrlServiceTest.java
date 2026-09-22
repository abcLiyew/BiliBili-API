package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.PlayUrl;
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
 * <b>{@code VideoService#getPlayUrl} 回归测试</b>（2026-09-22 B3.5 批 #1）。
 *
 * <p>本批里只有这一项走过"翻案之后又翻回来再翻出去"的完整过程，所以测试要钉住的东西比别处多：
 *
 * <ol>
 *   <li><b>走的是哪条路径</b>。实测 {@code /wbi/} 那条七格全 412、去掉 {@code /wbi/} 即通，
 *       所以"本方法打的是 {@link BilibiliEndpoint#playUrlPlainUrl}"必须被断言下来 ——
 *       哪天有人"顺手"换成 {@code /wbi/} 版本，这里要立刻红。</li>
 *   <li><b>它【不】签名</b>。与同批的 upstat / 历史 / 粉丝一样是普通 GET，
 *       所以 {@code nav} 一次都不该被访问（走签名出口会先取 WBI 密钥）。
 *       这一条同时守着"别给能用的功能平白加一个 'nav 不可达' 的失败面"。</li>
 *   <li><b>两条通道的字段在完全不同的位置</b>：{@code fnval=1} → {@code durl}；
 *       {@code fnval=16} → {@code dash}。写成一个测试最容易漏掉第二条。</li>
 *   <li>★ <b>{@code code=0} 但一条地址都没给时必须失败</b>，不能把"空壳"当成功交出去。</li>
 * </ol>
 */
@DisplayName("服务：VideoService#getPlayUrl（视频流地址）")
class PlayUrlServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String PLAY_PATH = "/x/player/playurl";
    private static final String SPI_PATH = "/x/frontend/finger/spi";

    private static final String BVID = "BV1tgPie2E3w";
    private static final long CID = 9990001L;

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
    // 两条通道
    // ================================================================

    @Nested
    @DisplayName("MP4 通道（fnval=1）")
    class Mp4Channel {

        @Test
        @DisplayName("默认重载：拿到可播地址，取 durl，且【一次 nav 都不打】—— 本接口不签名")
        void defaultOverload() throws Exception {
            mock.register(PLAY_PATH, fixture("playurl.json"));

            PlayUrl data = VideoService.INSTANCE.getPlayUrl(BVID, CID);

            assertEquals(64, data.getQuality(), "MP4 通道实测上限 720P");
            assertEquals("mp4720", data.getFormat());
            assertEquals(1, data.getDurl().size());
            assertNotNull(data.getDurl().get(0).getUrl());
            assertFalse(data.getDurl().get(0).getUrl().isBlank(), "地址不能是空串");
            assertEquals(1, data.getDurl().get(0).getBackup_url().size(), "备用地址要能取到");
            assertNull(data.getDash(), "MP4 通道不该有 dash —— 取错层是这块最常见的错");

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "本接口不签名。若这里变成 1，说明有人把它接到了 getSigned 上："
                            + "那会让一条本可用的链路平白依赖 'nav 可达'");
        }

        @Test
        @DisplayName("请求形状：qn/fnval/fourk 都要在 query 里，Referer 指向该视频页")
        void requestShape() throws Exception {
            mock.register(PLAY_PATH, fixture("playurl.json"));

            VideoService.INSTANCE.getPlayUrl(BVID, CID);

            String uri = mock.requestUri(PLAY_PATH);
            assertTrue(uri.contains("bvid=" + BVID), "实际：" + uri);
            assertTrue(uri.contains("cid=" + CID), "实际：" + uri);
            assertTrue(uri.contains("qn=64"), "实际：" + uri);
            assertTrue(uri.contains("fnval=1"), "实际：" + uri);
            assertTrue(uri.contains("fourk=1"), "缺 fourk=1 时服务端不会列出 4K 档位。实际：" + uri);

            String referer = mock.requestHeader(PLAY_PATH, "Referer");
            assertTrue(referer != null && referer.contains(BVID),
                    "播放地址是在视频页里请求的。实际 Referer：" + referer);
            assertEquals("application/json, text/plain, */*",
                    mock.requestHeader(PLAY_PATH, "Accept"), "JSON 接口要用 JSON 形状的 Accept");
        }

        @Test
        @DisplayName("qn/fnval 传 0 或负数时回落到 64/1，而不是把非法值原样发出去")
        void invalidQualitiesFallBack() throws Exception {
            mock.register(PLAY_PATH, fixture("playurl.json"));

            VideoService.INSTANCE.getPlayUrl(BVID, CID, 0, 0);

            String uri = mock.requestUri(PLAY_PATH);
            assertTrue(uri.contains("qn=64"), "实际：" + uri);
            assertTrue(uri.contains("fnval=1"), "实际：" + uri);
        }

        @Test
        @DisplayName("qn=80 走 MP4 只会拿到 64 —— 期望不等于承诺，实际值以 quality 为准")
        void qnEightyStillGives720OnMp4() throws Exception {
            mock.register(PLAY_PATH, fixture("playurl.json"));

            PlayUrl data = VideoService.INSTANCE.getPlayUrl(BVID, CID, 80, 1);

            assertEquals(64, data.getQuality(), "夹具即实测：MP4 通道传 80 也只回 64");
        }
    }

    @Nested
    @DisplayName("DASH 通道（fnval=16）")
    class DashChannel {

        @Test
        @DisplayName("1080P 在 dash 里，且音视频是【两条独立流】—— 地址不在 durl")
        void dashCarries1080p() throws Exception {
            mock.register(PLAY_PATH, fixture("playurl-dash.json"));

            PlayUrl data = VideoService.INSTANCE.getPlayUrl(BVID, CID, 80, 16);

            assertEquals(80, data.getQuality(), "DASH 实测能到 1080P，这是它与 MP4 的关键差别");
            assertNull(data.getDurl(), "DASH 通道没有 durl");
            assertNotNull(data.getDash());
            assertEquals(80, data.getDash().getVideo().get(0).getId());
            assertFalse(data.getDash().getVideo().get(0).getBaseUrl().isBlank());
            assertEquals(1, data.getDash().getAudio().size(),
                    "音视频分离是本通道最重要的语义：B 站给两条流，本库不合流");

            assertTrue(mock.requestUri(PLAY_PATH).contains("fnval=16"),
                    "实际：" + mock.requestUri(PLAY_PATH));
        }

        @Test
        @DisplayName("baseUrl 与 base_url 是同一份数据的两个键名，两个都要能取到")
        void bothBaseUrlSpellings() throws Exception {
            mock.register(PLAY_PATH, fixture("playurl-dash.json"));

            PlayUrl.DashMedia video = VideoService.INSTANCE.getPlayUrl(BVID, CID, 80, 16)
                    .getDash().getVideo().get(0);

            assertNotNull(video.getBaseUrl());
            assertNotNull(video.getBase_url());
            assertEquals(video.getBaseUrl(), video.getBase_url(),
                    "B 站两个键发同一份数据；若哪天不同了，需要重新判断该信哪个");
        }
    }

    // ================================================================
    // ★ 空壳必须失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("★ code=0 但既没有 durl 也没有 dash：必须抛异常，不能返回空壳")
        void noAddressIsAnError() {
            mock.register(PLAY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"from\":\"local\",\"quality\":0,\"durl\":null,\"dash\":null}}");

            IOException e = assertThrows(IOException.class,
                    () -> new com.esdllm.bilibiliApi.bilibiliApi.VideoExtra().getPlayUrl(BVID, CID));

            assertTrue(e.getMessage().contains("既没有 durl 也没有 dash"),
                    "要把真正的原因写出来，否则调用方只会对着 null 猜。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode());
        }

        @Test
        @DisplayName("durl 是【空数组】也算没有地址（空数组与 null 一样是'没给'）")
        void emptyDurlIsAlsoAnError() {
            mock.register(PLAY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"quality\":64,\"durl\":[],\"dash\":null}}");

            // 服务层抛 BilibiliException（运行时），不抛 IOException ——
            // IOException 只在门面边界产生，本类里的门面用例见 noAddressIsAnError
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getPlayUrl(BVID, CID));

            assertTrue(e.getMessage().contains("既没有 durl 也没有 dash"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("bvid 为空 / cid ≤ 0：本地校验先失败，一个出站都不发")
        void localValidation() {
            IOException blank = assertThrows(IOException.class,
                    () -> new com.esdllm.bilibiliApi.bilibiliApi.VideoExtra().getPlayUrl("  ", CID));
            assertTrue(blank.getMessage().contains("BV号不能为空"), "实际：" + blank.getMessage());

            IOException badCid = assertThrows(IOException.class,
                    () -> new com.esdllm.bilibiliApi.bilibiliApi.VideoExtra().getPlayUrl(BVID, 0L));
            assertTrue(badCid.getMessage().contains("cid不能为空"), "实际：" + badCid.getMessage());

            assertEquals(0, mock.hitCount(PLAY_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH));
        }

        @Test
        @DisplayName("HTTP 412 风控：服务层抛 BilibiliException，且业务码必须活下来（该码要能区分'该换出口'）")
        void riskControl412() {
            mock.registerStatus(PLAY_PATH, 412, "");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getPlayUrl(BVID, CID));

            assertEquals(412, e.getCode(),
                    "412 是这条链路最真实的失败（实测七格全 412），调用方要靠它判断'不是参数错'");
        }

        @Test
        @DisplayName("业务码 -101（未登录）：透传，不被当成地址缺失")
        void notLoggedIn() {
            mock.register(PLAY_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getPlayUrl(BVID, CID));

            assertEquals(-101, e.getCode());
        }
    }

    /** {@code /x/frontend/finger/spi} 未注册时会被打 404 —— 只说明"没领到指纹"，不该让用例失败 */
    @Test
    @DisplayName("夹具环境里指纹接口未注册也不影响本接口：指纹领不到只是退化为无 Cookie")
    void fingerprintEndpointNotRequired() throws Exception {
        mock.register(PLAY_PATH, fixture("playurl.json"));

        assertNotNull(VideoService.INSTANCE.getPlayUrl(BVID, CID));
        assertTrue(mock.hitCount(SPI_PATH) >= 0, "指纹接口可能被访问（也可能被跳过），都不该致命");
    }
}

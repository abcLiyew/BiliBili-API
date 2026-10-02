package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoShot;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code VideoService#getVideoShot} 的回归测试</b>（C1 批，2026-09-24）。
 *
 * <p>这个端点的产出<b>不是"一串图"而是"一张雪碧图 + 一张坐标表"</b>，
 * 所以本文件做三件事：
 *
 * <ol>
 *   <li>把"怎么从雪碧图里抠出一格"所依赖的四个数（{@code img_x_len} / {@code img_y_len} /
 *       {@code img_x_size} / {@code img_y_size}）与坐标表逐项钉住；</li>
 *   <li>🔴 <b>钉住库内固定只发 {@code index=1}</b> —— 端点的 {@code index} 传 2/3/99 会<b>静默</b>
 *       返回 P1 的雪碧图（实测），所以库内把它写死、也不做成参数。
 *       这条是"参数"级的性质，只有看 query 才能验。</li>
 *   <li>⚠️ 钉住那个<b>只差一个字母</b>的坑：{@code index}（数组，真正要用的）
 *       vs {@code indexs}（对象，恒空）。取错那个会拿到一个 JSONObject 而不是列表。</li>
 * </ol>
 */
@DisplayName("服务：VideoService#getVideoShot（缩略图 / 进度条预览图）")
class VideoShotServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String SHOT_PATH = "/x/player/videoshot";
    private static final String SAMPLE_BVID = "BV1BqhB6nEdN";

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

    @Nested
    @DisplayName("取数")
    class HappyPathTest {

        @Test
        @DisplayName("雪碧图网格与单格尺寸逐项对上（10×10，单格 480×270）")
        void parsesGrid() throws Exception {
            mock.register(SHOT_PATH, fixture("videoshot.json"));

            VideoShot shot = VideoService.INSTANCE.getVideoShot(SAMPLE_BVID);

            assertEquals(10, shot.getImg_x_len(), "网格列数");
            assertEquals(10, shot.getImg_y_len(), "网格行数");
            assertEquals(480, shot.getImg_x_size(), "单格宽");
            assertEquals(270, shot.getImg_y_size(), "单格高");
            assertEquals(1, shot.getImage().size(), "雪碧图只有 1 张（不是一串缩略图）");
            assertEquals(27, shot.getIndex().size(), "坐标表 27 个时间点");
            // 坐标表前三个值（实测）：0 / 0 / 6 —— 头两帧同格是正常的（同一格覆盖一段时间）
            assertEquals(0, shot.getIndex().get(0));
            assertEquals(0, shot.getIndex().get(1));
            assertEquals(6, shot.getIndex().get(2));
        }

        @Test
        @DisplayName("★ image 是协议相对地址（// 开头）—— 直接当 URL 用会失败")
        void imageIsProtocolRelative() throws Exception {
            mock.register(SHOT_PATH, fixture("videoshot.json"));

            String image = VideoService.INSTANCE.getVideoShot(SAMPLE_BVID).getImage().get(0);

            assertTrue(image.startsWith("//"),
                    "实测是 //bimp.hdslb.com/... 形式，用之前要补 https:。实际：" + image);
            assertTrue(image.contains("42082042159"),
                    "★ 地址里的数字是 cid，不是 bvid —— 不要拿 bvid 去比对。实际：" + image);
        }

        @Test
        @DisplayName("匿名即通：一次 nav 都不该打")
        void anonymousNeedsNoCredential() throws Exception {
            mock.register(SHOT_PATH, fixture("videoshot.json"));

            VideoService.INSTANCE.getVideoShot(SAMPLE_BVID);

            assertEquals(0, mock.hitCount(NAV_PATH), "★ 该端点匿名可用");
        }
    }

    @Nested
    @DisplayName("🔴 index 参数：库内固定只发 1")
    class IndexIsPinnedTest {

        @Test
        @DisplayName("★ 出站 query 里必须是 index=1（不是默认值，是唯一能拿到数据的取值）")
        void onlyIndexOneIsSent() throws Exception {
            mock.register(SHOT_PATH, fixture("videoshot.json"));

            VideoService.INSTANCE.getVideoShot(SAMPLE_BVID);

            String uri = mock.requestUri(SHOT_PATH);
            assertTrue(uri.contains("index=1"), "实际：" + uri);
            // 反向断言带分隔符，避免子串误判（本库踩过：assertFalse(uri.contains("mid=2")) 恒真，
            // 因为真实的 URI 是 …?vmid=2，而 "vmid=2" 含 "mid=2"）。
            assertFalse(uri.contains("&index=2"), "★ index 必须是 1。实际：" + uri);
            assertFalse(uri.contains("&index=99"), "★ 实测 3/99 会静默返回 P1 的图。实际：" + uri);
        }
    }

    @Nested
    @DisplayName("⚠️ index 与 indexs 只差一个字母")
    class LookAlikeKeysTest {

        @Test
        @DisplayName("★ index 是数组（27 个值）；indexs 是空对象 —— 取错就拿到 JSONObject")
        void indexVersusIndexs() throws Exception {
            mock.register(SHOT_PATH, fixture("videoshot.json"));

            VideoShot shot = VideoService.INSTANCE.getVideoShot(SAMPLE_BVID);

            assertEquals(27, shot.getIndex().size(),
                    "index 是 List<Integer> —— 本库真正要用的是它");
            assertNotNull(shot.getIndexs(), "indexs 存在，但是个对象");
            assertTrue(shot.getIndexs().isEmpty(),
                    "★ indexs 实测恒为空对象。它与 index 只差一个字母，混用会静默拿到空数据。"
                            + "实际：" + shot.getIndexs());
            assertTrue(shot.getVideo_shots().isEmpty(), "video_shots 实测也是空对象");
            assertNotNull(shot.getPvdata(), "另一套布局的地址，原样透出");
        }
    }

    @Nested
    @DisplayName("失败路径")
    class FailureTest {

        @Test
        @DisplayName("bvid 为空 → 抛异常且没发请求")
        void blankBvid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getVideoShot(""));
            assertTrue(e.getMessage().contains("BV号不能为空"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(SHOT_PATH));
        }

        @Test
        @DisplayName("code=0 但没有 image 地址 → 抛异常，不静默返回空图")
        void missingImageFails() throws Exception {
            mock.register(SHOT_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{\"pvdata\":\"x\",\"image\":[],"
                            + "\"index\":[],\"video_shots\":{},\"indexs\":{}}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getVideoShot(SAMPLE_BVID));
            assertTrue(e.getMessage().contains("image 地址"),
                    "★ 与'取播放地址时既没 durl 也没 dash'同一处理：没有产出就是失败。实际："
                            + e.getMessage());
        }

        @Test
        @DisplayName("业务码非 0 → BilibiliException")
        void businessCode() throws Exception {
            mock.register(SHOT_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            assertEquals(-404, assertThrows(BilibiliException.class,
                    () -> VideoService.INSTANCE.getVideoShot(SAMPLE_BVID)).getCode());
        }
    }
}

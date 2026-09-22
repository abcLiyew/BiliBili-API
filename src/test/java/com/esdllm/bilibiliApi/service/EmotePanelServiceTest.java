package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.comment.EmotePanel;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code CommentService#getEmotePanel}</b>（表情包面板）的回归测试（2026-09-22 B2 批 #7）。
 *
 * <p>🔴 <b>本文件存在的首要理由是"缺凭据的形态"</b>：本端点匿名也返回 {@code code=0}，
 * 只是 {@code data.packages} 是 {@code null}。这与"这个用户真的没有表情包"<b>长得一模一样</b>，
 * 而两者处置完全不同（一个该去注入凭据、一个该正常展示空状态）。
 * 所以这里两个方向都钉：
 * <ul>
 *   <li>匿名那种形态（{@code packages:null} / {@code []}）<b>必须抛</b>，且 hint 要指向凭据；</li>
 *   <li>真拿到数据时字段要对得上（尤其是"表情代码"那个 {@code text}）。</li>
 * </ul>
 *
 * <p>⚠️ 夹具是从 425KB 的真实响应里裁出来的（68 包 → 1 包、该包 225 个表情 → 3 个），
 * <b>逐字保留</b>，没有改写任何值。
 */
@DisplayName("服务：CommentService#getEmotePanel（表情包，需凭据）")
class EmotePanelServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String EMOTE_PATH = "/x/emote/user/panel/web";

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
    // 形状
    // ================================================================

    @Nested
    @DisplayName("响应形状")
    class ShapeTest {

        @Test
        @DisplayName("setting 与 packages 都能拿到")
        void basic() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            EmotePanel data = CommentService.INSTANCE.getEmotePanel("reply");

            assertNotNull(data.getSetting());
            assertEquals(150, data.getSetting().getRecent_limit());
            assertEquals(1, data.getSetting().getFocus_pkg_id(), "默认聚焦的包 id（1 = 小黄脸）");
            assertEquals(-1, data.getSetting().getAttr(),
                    "★ attr 可以是负数（实测 -1）—— 别用无符号思维读它");
            assertNotNull(data.getSetting().getSchema());

            assertEquals(1, data.getPackages().size());
            assertEquals("小黄脸", data.getPackages().get(0).getText());
        }

        @Test
        @DisplayName("★ 表情代码在 text（如 [doge_金箍]）—— 评论里要打的就是它")
        void emoteCode() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            EmotePanel.Package pkg = CommentService.INSTANCE.getEmotePanel("reply").getPackages().get(0);

            assertEquals(3, pkg.getEmote().size());
            assertEquals("[doge_金箍]", pkg.getEmote().get(0).getText());
            assertEquals("[笑哭]", pkg.getEmote().get(1).getText());
            assertEquals(83964, pkg.getEmote().get(0).getId());
            assertNotNull(pkg.getEmote().get(0).getUrl());
        }

        @Test
        @DisplayName("表情级 meta：alias / suggest 都在（suggest 里可能是空串元素）")
        void emoteMeta() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            EmotePanel.Emote first = CommentService.INSTANCE.getEmotePanel("reply")
                    .getPackages().get(0).getEmote().get(0);

            assertEquals("金箍", first.getMeta().getAlias());
            assertEquals(1, first.getMeta().getSuggest().size());
            assertEquals("", first.getMeta().getSuggest().get(0),
                    "★ 联想词里常常是空串 —— 不是漏映射，是服务端就这么给的");
        }

        @Test
        @DisplayName("★ 可选字段可以缺席：gif_url / activity / label 实测都是 null，不当必填")
        void optionalFields() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            EmotePanel.Package pkg = CommentService.INSTANCE.getEmotePanel("reply").getPackages().get(0);

            assertNull(pkg.getLabel(), "68 个包里只有 1 个有 label");
            assertEquals(Boolean.FALSE, pkg.getEmote().get(0).getFlags().getUnlocked());
            assertNull(pkg.getEmote().get(0).getFlags().getRecent_use_forbid(),
                    "recent_use_forbid 只有 8/1555 个表情有");
            assertNull(pkg.getEmote().get(0).getActivity(), "activity 实测全为 null");
            assertNull(pkg.getEmote().get(0).getGif_url(),
                    "★ 只有约 3%（44/1555）的表情带 gif_url —— 拿到 null 是常态");
            assertNull(pkg.getMeta().getAsset_id(), "包级 meta 有 4 种键组合，asset_id 常常缺席");
        }
    }

    // ================================================================
    // 门槛：本文件的核心
    // ================================================================

    @Nested
    @DisplayName("★ 门槛（本文件的核心：缺凭据的形态）")
    class GateTest {

        @Test
        @DisplayName("★ 匿名形态（code=0 + packages:null）必须抛，且 hint 指向凭据")
        void anonymousFormThrows() {
            mock.register(EMOTE_PATH, "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":"
                    + "{\"setting\":{\"recent_limit\":150,\"attr\":-1,\"focus_pkg_id\":1,"
                    + "\"schema\":\"https://www.bilibili.com/h5/mall/emoji-package/home?navhide=1\"},"
                    + "\"packages\":null}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getEmotePanel("reply"));

            assertEquals(0, e.getCode(), "外层码是 0 —— 这正是本端点阴的地方");
            assertTrue(e.getMessage().contains("packages 为空"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("没有给出数据"),
                    "★ 文案必须把'不是没有表情包'说清楚，否则调用方会把它当成空状态展示");
            assertTrue(e.getDescription().contains("凭据"), "实际：" + e.getDescription());
            assertTrue(e.getDescription().contains("getCredentialStatus"),
                    "★ hint 要给出可执行的下一步（先问凭据状态），不是只说'需要登录'");
        }

        @Test
        @DisplayName("★ 空列表 packages:[] 也抛 —— 空数组与 null 在这里同义")
        void emptyListThrows() {
            mock.register(EMOTE_PATH, "{\"code\":0,\"message\":\"0\",\"data\":"
                    + "{\"setting\":{},\"packages\":[]}}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getEmotePanel("reply")).getCode());
        }

        @Test
        @DisplayName("★ 与收藏夹那条刻意同构：都是'B 形态'（code=0 + 空数据）⇒ 都要抛")
        void sameShapeAsFavoriteFolders() {
            // 这条不是废话：两个端点的共同点是"外层码骗人"，
            // 而本库对它们的处置一致 —— 空数据一律当失败，不安静返回空结果。
            mock.register(EMOTE_PATH, "{\"code\":0,\"message\":\"0\",\"data\":null}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getEmotePanel("reply")).getCode());
        }
    }

    // ================================================================
    // 请求形状
    // ================================================================

    @Nested
    @DisplayName("请求形状")
    class RequestTest {

        @Test
        @DisplayName("business 默认 reply；传别的值照传（实测 reply 与 dynamic 结果一致）")
        void business() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            CommentService.INSTANCE.getEmotePanel(null);
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=reply"),
                    "实际：" + mock.requestUri(EMOTE_PATH));

            CommentService.INSTANCE.getEmotePanel("   ");
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=reply"),
                    "空白也按默认。实际：" + mock.requestUri(EMOTE_PATH));

            CommentService.INSTANCE.getEmotePanel(CommentService.EMOTE_BUSINESS_DYNAMIC);
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=dynamic"),
                    "实际：" + mock.requestUri(EMOTE_PATH));

            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不需要签名（但需要凭据）");
        }

        @Test
        @DisplayName("Referer 用站根")
        void referer() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            CommentService.INSTANCE.getEmotePanel("reply");

            assertEquals("https://www.bilibili.com/", mock.requestHeader(EMOTE_PATH, "Referer"));
        }
    }

    // ================================================================
    // 失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("-101 未登录：业务码原样透传")
        void notLoggedIn() {
            mock.register(EMOTE_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getEmotePanel("reply"));

            assertEquals(-101, e.getCode());
            assertTrue(e.getMessage().contains("账号未登录"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 412 风控：码值保住 412")
        void http412() {
            mock.registerStatus(EMOTE_PATH, 412, "");

            assertEquals(412, assertThrows(BilibiliException.class,
                    () -> CommentService.INSTANCE.getEmotePanel("reply")).getCode());
        }
    }
}

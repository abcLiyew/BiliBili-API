package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.comment.CommentPage;
import com.esdllm.bilibiliApi.model.data.pojo.comment.EmotePanel;
import com.esdllm.bilibiliApi.model.data.pojo.comment.SubReplyPage;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Comment 门面回归测试</b>（第 12 个门面，2026-09-22 B1 批新增）。
 *
 * <p>与 {@code ContentTest} / {@code SearchTest} 同一套思路：门面没有业务逻辑，只钉
 * <b>委托正确</b>与<b>异常边界</b>两件事；解析细节留给 {@code CommentServiceTest}。
 *
 * <p>但本文件额外守一条<b>本门面存在的理由</b>：
 * 端点要的是 {@code oid=aid}，而调用方手里常见的是 {@code bvid}；把 {@code BV…} 丢给端点
 * <b>不报错、只静默拿空列表</b>。所以门面提供了 {@code getRepliesByBvid} ——
 * 本文件用它<b>多花的那一次 {@code view}</b> 与<b>换算出来的 aid</b> 来证明这条路真的走通了
 * （{@code hitCount} 断言它打了几次、{@code requestUri} 断言 {@code oid} 不是 BV 号）。
 *
 * <p>另外钉一条边界：本门面<b>没有写方法</b>（发表/删除/点赞评论都不在库里），
 * 这与 {@code Content} 的反向断言是同一类纪律。
 *
 * <p>🆕 <b>B2 批（2026-09-22）本门面扩了两个方法</b>，两者的门槛<b>刚好相反</b>，
 * 所以各开一组、并把"空"的处置也钉住：
 * <ul>
 *   <li>{@code getSubReplies}（楼中楼）—— ✅ 匿名可用，<b>空楼中楼是合法结果，不抛</b>；</li>
 *   <li>{@code getEmotePanel}（表情包）—— 🔴 <b>需凭据</b>，{@code code=0} 但 packages 为空
 *       <b>必须抛</b>（否则"我没带凭据"会伪装成"这人没有表情包"）。
 *       ⚠️ 它<b>不需要签名</b> —— 凭据与签名是两件事，本文件专门有一条反向断言把它们分开。</li>
 * </ul>
 */
@DisplayName("门面：Comment（评论列表）")
class CommentTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String REPLY_PATH = "/x/v2/reply";
    private static final String VIEW_PATH = "/x/web-interface/view?bvid=";
    /** B2 批 #4：楼中楼。注意是 {@code reply/reply}，与上面的 {@code reply} 是<b>两个端点</b> */
    private static final String SUB_REPLY_PATH = "/x/v2/reply/reply";
    /** B2 批 #7：表情包面板。🔴 本域唯一需要凭据的一个（不需要签名，两者别混） */
    private static final String EMOTE_PATH = "/x/emote/user/panel/web";

    /** 与 {@code comment-replies.json} 的 {@code oid} 一致 */
    private static final long AID = 117284131638286L;
    /** 与 {@code video-view.json} 的 {@code data.aid} 一致 —— 换算链路的两端必须对得上 */
    private static final long AID_FROM_VIEW = 114065439463311L;
    private static final String BVID = "BV1tgPie2E3w";

    /** 与 {@code sub-reply-page.json} 的 {@code data.root.rpid} / 每条 {@code replies[].root} 一致 */
    private static final long ROOT = 314487292657L;

    private MockBiliServer mock;
    private Comment comment;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
        comment = new Comment();
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
        @DisplayName("getReplies：拿到评论页，三块内容都在")
        void replies() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentPage data = comment.getReplies(AID, 1, 5);

            assertEquals(990, data.getPage().getCount());
            assertEquals(2, data.getReplies().size());
            assertEquals(1, data.getTop_replies().size());
            assertNotNull(data.getUpper(), "upper 是 UP 主置顶那条，与 top_replies 会重叠");
        }

        @Test
        @DisplayName("getReplies(…, sort)：排序参数照传，非法值回落热度")
        void sortedReplies() throws Exception {
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            comment.getReplies(AID, 1, 5, 1);
            assertTrue(mock.requestUri(REPLY_PATH).contains("sort=1"),
                    "实际：" + mock.requestUri(REPLY_PATH));

            comment.getReplies(AID, 1, 5, 42);
            assertTrue(mock.requestUri(REPLY_PATH).contains("sort=2"),
                    "★ 非法值回落到热度而不是原样发出去 —— -400 在这里很难与'参数名写错'区分。"
                            + "实际：" + mock.requestUri(REPLY_PATH));
        }

        @Test
        @DisplayName("★ getRepliesByBvid：真的多打了一次 view，且 oid 用的是换算出来的 aid（不是 BV 号）")
        void byBvidConvertsAid() throws Exception {
            mock.register(VIEW_PATH, fixture("video-view.json"));
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            CommentPage data = comment.getRepliesByBvid(BVID, 1, 20);

            assertEquals(2, data.getReplies().size());
            assertEquals(1, mock.hitCount(VIEW_PATH), "换算要先打一次 view —— 这是明说过的代价");
            assertEquals(1, mock.hitCount(REPLY_PATH), "换算之后只打一次评论");
            String uri = mock.requestUri(REPLY_PATH);
            assertTrue(uri.contains("oid=" + AID_FROM_VIEW),
                    "★ oid 必须是 view 返回的那个 aid。实际：" + uri);
            assertFalse(uri.contains("BV"), "★ query 里绝不能出现 BV 号 —— 那样只会静默拿到空列表。实际：" + uri);
        }

        @Test
        @DisplayName("两项都是普通 GET：一次 nav 都不打（文档标 Wbi，实测不需要）")
        void noSigning() throws Exception {
            mock.register(VIEW_PATH, fixture("video-view.json"));
            mock.register(REPLY_PATH, fixture("comment-replies.json"));

            comment.getReplies(AID, 1, 5);
            comment.getRepliesByBvid(BVID, 1, 5);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "套上签名只会平白多一个 'nav 不可达' 的失败面");
        }

        /**
         * 反向断言：本门面只读。反射断言在 {@code FacadeContractTest} 里，
         * 这里从调用方视角确认"没有可用的写入口"。
         */
        @Test
        @DisplayName("★ 只读门面：公开方法里没有任何 set/add/delete/remove/clear 之类的前缀")
        void staysReadOnly() {
            for (java.lang.reflect.Method m : Comment.class.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                String name = m.getName();
                assertTrue(name.startsWith("get"),
                        "Comment 门面只应有 getXxx 读方法，发现：" + name
                                + "（发表/删除评论需 csrf 且会改动账号，本库不做）");
            }
        }
    }

    // ================================================================
    // 楼中楼（B2 批 #4）
    // ================================================================

    /**
     * 🔴 本组守两个"看起来像、其实不同"的东西：
     * <ol>
     *   <li>{@code root} 是<b>被回复的那条一级评论的 rpid</b>，不是 aid、不是 bvid；
     *       而每条回复自己还有一个 {@code parent}（我直接回的那条）—— 两者常相等但语义不同。
     *       <b>传错 root 不会报错</b>，只会给你另一条评论的楼中楼；</li>
     *   <li>本端点<b>复用</b>了一级评论的 {@code page} / {@code upper} 嵌套类型，
     *       但它<b>不给</b> {@code page.acount}、{@code upper} <b>也只有 mid</b> ——
     *       读到 null 不是数据丢了，是端点根本没有。写测试把 null 钉住，
     *       免得后人为"查漏"去别的地方补一个不存在的字段。</li>
     * </ol>
     */
    @Nested
    @DisplayName("楼中楼（二级评论，匿名可用）")
    class SubReplyTest {

        @Test
        @DisplayName("getSubReplies：aid 进 oid、root 进 root，且两个 id 不混")
        void subReplies() throws Exception {
            mock.register(SUB_REPLY_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = comment.getSubReplies(AID, ROOT, 1, 20);

            assertEquals(2, data.getReplies().size(), "夹具裁到 2 条");
            assertEquals(ROOT, data.getRoot().getRpid(), "root 是被回复的那条一级评论本体");

            // 注意：这里是 pojo 的 Comment，与门面类 Comment 同名 —— 用 var 避开歧义
            var first = data.getReplies().get(0);
            assertEquals(314487658177L, first.getRpid());
            assertEquals(ROOT, first.getRoot(), "每条回复的 root 指回一级评论，与请求参数一致");
            assertEquals(ROOT, first.getParent(), "本条实测 root == parent（直接回一级评论）");
            assertEquals("企鹅要做好人", first.getMember().getUname());
            // 注意写 int 而不是 118L：like 是 Integer，而 assertEquals 对 (long, Integer)
            // 同时匹配 (long,long) 与 (Long,long) 两个重载 ⇒ 会编译歧义；(int, Integer) 才是精确匹配
            assertEquals(118, first.getLike());
            assertNull(first.getReplies(),
                    "★ 楼中楼只有一层：实测每条 replies[].replies 都是 null —— 不要写递归，那永远是空的");

            var second = data.getReplies().get(1);
            assertEquals(314487658177L, second.getParent(),
                    "★ 第二条的 parent 是第一条的 rpid（它在回复楼里的人），而 root 仍是同一个 —— "
                            + "这正是 root 与 parent 的区别所在");
            assertEquals(ROOT, second.getRoot());

            String uri = mock.requestUri(SUB_REPLY_PATH);
            assertTrue(uri.contains("type=1") && uri.contains("oid=" + AID)
                            && uri.contains("root=" + ROOT) && uri.contains("pn=1") && uri.contains("ps=20"),
                    "实际：" + uri);
            assertFalse(uri.contains("BV"), "oid 同样要是 aid。实际：" + uri);
        }

        @Test
        @DisplayName("★ 复用一级评论的 page/upper，但 acount 与 upper.top 恒为 null —— 端点不给，不是丢了")
        void reusedTypesButMissingFields() throws Exception {
            mock.register(SUB_REPLY_PATH, fixture("sub-reply-page.json"));

            SubReplyPage data = comment.getSubReplies(AID, ROOT, 1, 20);

            assertEquals(16, data.getPage().getCount(), "count 是楼中楼总数，不是本页条数");
            assertEquals(1, data.getPage().getNum());
            assertEquals(20, data.getPage().getSize());
            assertNull(data.getPage().getAcount(),
                    "★ 一级评论才有 acount（回复数），本端点不给 ⇒ 恒 null");

            assertEquals(346749212L, data.getUpper().getMid(), "upper 是被回复的一级评论作者");
            assertNull(data.getUpper().getTop(),
                    "★ upper 只有 mid —— 读到 null 不是数据丢了，是端点不给");
        }

        @Test
        @DisplayName("★ 空楼中楼是合法结果：不抛（与表情包的空 packages 刚好相反）")
        void emptySubRepliesIsLegal() {
            mock.register(SUB_REPLY_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"page\":{\"num\":1,\"size\":20,\"count\":0},\"upper\":{\"mid\":1},"
                    + "\"replies\":[],\"root\":{\"rpid\":1}}}");

            SubReplyPage data = assertDoesNotThrow(() -> comment.getSubReplies(AID, ROOT, 1, 20));

            assertTrue(data.getReplies().isEmpty(),
                    "★ 这条评论确实没人回 —— 本端点匿名就通，所以空<b>无法</b>由'缺凭据'造成，"
                            + "这与表情包的空 packages 是相反的处理");
        }

        @Test
        @DisplayName("pn/ps 归一：≤0 时按 1 / 20")
        void pagingNormalized() throws Exception {
            mock.register(SUB_REPLY_PATH, fixture("sub-reply-page.json"));

            comment.getSubReplies(AID, ROOT, 0, 0);

            String uri = mock.requestUri(SUB_REPLY_PATH);
            assertTrue(uri.contains("pn=1") && uri.contains("ps=20"), "实际：" + uri);
        }

        @Test
        @DisplayName("楼中楼也不走签名（一次 nav 都不打）")
        void noSigning() throws Exception {
            mock.register(SUB_REPLY_PATH, fixture("sub-reply-page.json"));

            comment.getSubReplies(AID, ROOT, 1, 20);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "文档把它所在的域标成 Wbi，实测与 x/v2/reply 一样免签名");
        }
    }

    // ================================================================
    // 表情包（B2 批 #7）
    // ================================================================

    /**
     * 🔴 <b>本组守的是"B2 = 匿名中频"这个批次名的一处出入</b>：表情包面板<b>需要凭据</b>
     * —— 匿名返回 {@code code=0} 但 {@code data.packages = null}。
     *
     * <p>这里<b>没法用 mock 造出"匿名"</b>（mock 永远返回我们给的夹具），所以能钉的只有两件事：
     * <ol>
     *   <li>拿到 packages 时，解析链路是对的（{@code goto}/{@code alias} 这些改名字段尤其要钉）；</li>
     *   <li>★ <b>{@code code=0} 但 packages 为空必须抛</b> —— 这就是"匿名形态"在本库里的处置方式。
     *       如果它安静地返回空列表，"我没带凭据"就会被读成"这个用户没有表情包"。</li>
     * </ol>
     */
    @Nested
    @DisplayName("表情包面板（🔴 需凭据：匿名 code=0 但不给 packages）")
    class EmotePanelTest {

        @Test
        @DisplayName("getEmotePanel：解析 setting 与 packages[].emote[] 三层")
        void panel() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            EmotePanel panel = comment.getEmotePanel();

            assertEquals(150, panel.getSetting().getRecent_limit());
            assertEquals(1, panel.getSetting().getFocus_pkg_id());

            assertEquals(1, panel.getPackages().size(), "夹具裁到 1 个包（真实响应 68 个 / 约 1555 个表情）");
            EmotePanel.Package pkg = panel.getPackages().get(0);
            assertEquals(1, pkg.getId());
            assertEquals("小黄脸", pkg.getText());
            assertEquals(3, pkg.getEmote().size());
            assertTrue(pkg.getFlags().getAdded());

            EmotePanel.Emote doge = pkg.getEmote().get(0);
            assertEquals("[doge_金箍]", doge.getText());
            assertEquals("金箍", doge.getMeta().getAlias(),
                    "★ meta.alias 是关键词别名，与 text（带方括号的展示名）不同，两个都要留");
            assertFalse(doge.getFlags().getUnlocked(), "flags.unlocked 决定这个表情当下能不能用");
        }

        @Test
        @DisplayName("business：null / 空白都回落到 reply，给了值就原样传（实测 reply 与 dynamic 响应一致）")
        void businessParam() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            comment.getEmotePanel();
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=reply"),
                    "无参重载就是 reply，实际：" + mock.requestUri(EMOTE_PATH));

            comment.getEmotePanel(null);
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=reply"),
                    "★ null 也要回落，而不是拼出 'business=null'。实际：" + mock.requestUri(EMOTE_PATH));

            comment.getEmotePanel("   ");
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=reply"),
                    "★ 空白串同理。实际：" + mock.requestUri(EMOTE_PATH));

            comment.getEmotePanel("dynamic");
            assertTrue(mock.requestUri(EMOTE_PATH).contains("business=dynamic"),
                    "给了合法值就原样发出去，实际：" + mock.requestUri(EMOTE_PATH));
        }

        @Test
        @DisplayName("★ code=0 但 packages 为空：必须抛 —— 这就是匿名时的真实形态")
        void emptyPackagesIsNotSilent() {
            mock.register(EMOTE_PATH, "{\"code\":0,\"message\":\"0\",\"data\":{\"packages\":[]}}");

            IOException e = assertThrows(IOException.class, () -> comment.getEmotePanel());

            assertTrue(e.getMessage().contains("packages 为空"),
                    "这条没有非 0 的码可用，文案就是唯一判据。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码是 0 —— 这正是它最阴的地方");
            assertTrue(cause.getDescription().contains("凭据"),
                    "要给出下一步动作：先确认凭据。实际：" + cause.getDescription());
        }

        @Test
        @DisplayName("★ packages 直接为 null（真实匿名形态）：同样抛，不能 NPE")
        void nullPackagesIsNotNpe() {
            mock.register(EMOTE_PATH, "{\"code\":0,\"message\":\"0\",\"data\":{\"packages\":null}}");

            IOException e = assertThrows(IOException.class, () -> comment.getEmotePanel());

            assertInstanceOf(BilibiliException.class, e.getCause(),
                    "★ 必须是库内异常包成的 IOException（门面边界），不能漏出 NullPointerException");
            assertTrue(e.getMessage().contains("packages 为空"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 需凭据 ≠ 需签名：它不走签名出口，一次 nav 都不打")
        void needsCredentialNotSignature() throws Exception {
            mock.register(EMOTE_PATH, fixture("emote-panel.json"));

            comment.getEmotePanel();

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 两件事很容易被混成一件：本端点要的是 Cookie（凭据），不是 w_rid（签名）");
            assertEquals(0, mock.hitCount(REPLY_PATH), "与评论列表是两个端点，别互相写错");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("aid ≤ 0：包成 IOException、内层文案保留，且没有任何出站")
        void badAid() {
            IOException e = assertThrows(IOException.class, () -> comment.getReplies(0L, 1, 5));

            assertTrue(e.getMessage().contains("aid不能小于0"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(REPLY_PATH));
        }

        @Test
        @DisplayName("★ -101 未登录：业务码必须活到 cause 里（调用方靠它决定'重新登录'还是'重试'）")
        void notLoggedIn() {
            mock.register(REPLY_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> comment.getReplies(AID, 1, 5));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-101, cause.getCode());
            assertTrue(e.getMessage().contains("账号未登录"), "服务端原话要带上：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 换算失败（view 没给 aid）：抛 IOException，而不是拿一个坏 aid 继续往下打")
        void conversionFailure() {
            mock.register(VIEW_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"bvid\":\"" + BVID + "\",\"title\":\"没有 aid 的响应\"}}");

            IOException e = assertThrows(IOException.class, () -> comment.getRepliesByBvid(BVID, 1, 20));

            assertTrue(e.getMessage().contains("换算 aid 失败"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(REPLY_PATH),
                    "★ 换算不出来就不该继续打评论 —— 拿坏 aid 打过去只会得到空列表，比直接报错难查得多");
        }

        @Test
        @DisplayName("HTTP 412（出口风控）：也是 IOException，码值保住 412")
        void http412() {
            mock.registerStatus(REPLY_PATH, 412, "");

            IOException e = assertThrows(IOException.class, () -> comment.getReplies(AID, 1, 5));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(412, cause.getCode());
        }

        @Test
        @DisplayName("★ 楼中楼的 root ≤ 0：包成 IOException，文案要说明它是什么，且零出站")
        void badRoot() {
            IOException e = assertThrows(IOException.class,
                    () -> comment.getSubReplies(AID, 0L, 1, 20));

            assertTrue(e.getMessage().contains("root不能小于0"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("rpid"),
                    "★ 文案要把'它是一级评论的 rpid'讲出来 —— "
                            + "否则调用方容易以为该传 aid。实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, mock.hitCount(SUB_REPLY_PATH), "本地校验在发请求之前");

            assertThrows(IOException.class, () -> comment.getSubReplies(0L, ROOT, 1, 20));
            assertEquals(0, mock.hitCount(SUB_REPLY_PATH));
        }

        @Test
        @DisplayName("楼中楼的业务码非 0：同样包成 IOException，码值原样活下来")
        void subReplyBusinessCode() {
            mock.register(SUB_REPLY_PATH, "{\"code\":-404,\"message\":\"啥都木有\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class,
                    () -> comment.getSubReplies(AID, ROOT, 1, 20));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-404, cause.getCode());
            assertTrue(e.getMessage().contains("啥都木有"), "服务端原话要带上：" + e.getMessage());
        }
    }
}

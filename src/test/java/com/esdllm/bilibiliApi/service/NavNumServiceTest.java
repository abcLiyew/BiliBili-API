package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.bilibiliApi.UserSpace;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.user.NavNum;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code UserService#getNavNum} 的回归测试</b>（C2 批，2026-10-02）。
 *
 * <p>本文件重点防守四件事：
 * <ol>
 *   <li>🔴 <b>参数名是 {@code mid}，不是 {@code vmid}</b> —— 同一个服务里
 *       {@code space/top/arc} 用的是 {@code vmid}（B5 那次盘点正是栽在传错这个参数上，
 *       拿到 {@code -400} 差点把能用的端点判死）。这里用"URI 必须含 {@code ?mid=}
 *       且不含 {@code vmid=}"把两条路钉开。</li>
 *   <li><b>{@code channel} / {@code favourite} 是嵌套对象</b>（{@code {guest, master}}），
 *       不是数字 —— 声明成 {@code Long} 会静默变 {@code null}。</li>
 *   <li><b>13 个键一个不少</b> —— 少一个都可能是响应形状变了。</li>
 *   <li>🔴 <b>两个"0"必须分开</b>：{@code data} 是<b>空对象</b>（一个键都没给）要抛异常；
 *       {@code data} 是<b>13 键全 0</b>（实测查无此人就长这样）是<b>合法响应</b>、不能抛
 *       —— 后者恰恰是本端点最容易误判的一格（见 {@link #allZeroIsNotAnEmptyObject}）。</li>
 * </ol>
 *
 * <p>📌 夹具是真机原样（2026-10-02，{@code mid=2}，匿名抓取），未裁剪。
 */
@DisplayName("服务：UserService#getNavNum（UP 主内容概览）")
class NavNumServiceTest {

    private static final String NAVNUM_PATH = "/x/space/navnum?mid=2";

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
        @DisplayName("解析出各内容类型的投稿数（13 个键）")
        void parsesCounts() throws Exception {
            mock.register(NAVNUM_PATH, fixture("space-navnum.json"));

            NavNum data = UserService.INSTANCE.getNavNum(2L);

            assertEquals(43L, data.getVideo(), "视频投稿数");
            assertEquals(127L, data.getAlbum(), "相册数");
            assertEquals(127L, data.getOpus(), "动态（opus）数");
            assertEquals(61L, data.getTag(), "标签数");
            assertEquals(122L, data.getBangumi(), "番剧数");
            assertEquals(34L, data.getCinema(), "影视数");
            assertEquals(1L, data.getAudio(), "音频数");
            assertEquals(0L, data.getArticle(), "专栏数为 0 —— 0 与 null 必须能分开");
            assertEquals(0L, data.getPlaylist());
            assertEquals(0L, data.getPugv());
            assertEquals(0L, data.getSeason_num());
        }

        @Test
        @DisplayName("★ channel / favourite 是嵌套对象，不是数字")
        void parsesNestedGuestMaster() throws Exception {
            mock.register(NAVNUM_PATH, fixture("space-navnum.json"));

            NavNum data = UserService.INSTANCE.getNavNum(2L);

            assertNotNull(data.getChannel(), "channel 是对象，不是 Long");
            assertEquals(0L, data.getChannel().getGuest());
            assertEquals(0L, data.getChannel().getMaster());

            assertNotNull(data.getFavourite(), "favourite 也是同一个形状");
            assertEquals(4L, data.getFavourite().getGuest());
            assertEquals(4L, data.getFavourite().getMaster());
        }
    }

    @Nested
    @DisplayName("出站形状")
    class RequestShapeTest {

        @Test
        @DisplayName("★ 参数名是 mid —— URI 不能出现 vmid（那是 top/arc 的写法）")
        void usesMidNotVmid() throws Exception {
            mock.register(NAVNUM_PATH, fixture("space-navnum.json"));

            UserService.INSTANCE.getNavNum(2L);

            String uri = mock.requestUri(NAVNUM_PATH);
            assertTrue(uri.contains("?mid="), "本端点吃 ?mid=，实际：" + uri);
            assertFalse(uri.contains("vmid="),
                    "传成 vmid 会得 -400（B5 那次盘点栽的就是这个），实际：" + uri);
        }

        @Test
        @DisplayName("Referer 指向该 UP 的空间页")
        void sendsSpaceReferer() throws Exception {
            mock.register(NAVNUM_PATH, fixture("space-navnum.json"));

            UserService.INSTANCE.getNavNum(2L);

            String referer = mock.requestHeader(NAVNUM_PATH, "Referer");
            assertNotNull(referer);
            assertTrue(referer.contains("space.bilibili.com/2"), "实际：" + referer);
        }
    }

    @Nested
    @DisplayName("失败语义")
    class FailureTest {

        @Test
        @DisplayName("mid <= 0 -> 发请求之前就抛（不白打一次出站）")
        void rejectsNonPositiveMid() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> UserService.INSTANCE.getNavNum(0L));
            assertEquals(0, mock.hitCount(NAVNUM_PATH), "参数非法时不该发出任何请求");
            assertNotNull(e.getMessage());
        }

        @Test
        @DisplayName("code 非 0 -> 抛异常")
        void throwsOnNonZeroCode() {
            mock.register(NAVNUM_PATH, "{\"code\":-404,\"message\":\"啥都木有\"}");

            assertThrows(BilibiliException.class, () -> UserService.INSTANCE.getNavNum(2L));
        }
    }

    @Nested
    @DisplayName("两个 0 必须分开：空对象 vs 全 0")
    class EmptyObjectTest {

        /**
         * 13 个键都在、值全是 0 —— 2026-10-02 实测里<b>"查无此人"就长这样</b>
         * （{@code mid=99999999999999} / {@code 123456789} / {@code 4294967296} 三格同形）。
         */
        private static final String ALL_ZERO = "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{"
                + "\"album\":0,\"article\":0,\"audio\":0,\"bangumi\":0,"
                + "\"channel\":{\"guest\":0,\"master\":0},\"cinema\":0,"
                + "\"favourite\":{\"guest\":0,\"master\":0},\"opus\":0,\"playlist\":0,"
                + "\"pugv\":0,\"season_num\":0,\"tag\":0,\"video\":0}}";

        @Test
        @DisplayName("★ code=0 但 data 是空对象：必须抛异常，绝不返回 13 字段全 null 的对象")
        void emptyDataObjectIsAnError() {
            // ResponseParserSupport#unwrap 只在 data == null 时抛 ⇒ 这一格只能由调用点自己守。
            // 若这里返回全 null 的 NavNum，调用方会把"响应形状变了 / 命中风控"读成"这个 UP 主啥都没有"。
            mock.register(NAVNUM_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{}}");

            IOException e = assertThrows(IOException.class, () -> new UserSpace().getNavNum(2L));

            assertTrue(e.getMessage().contains("data 是空对象"),
                    "异常里要说清'这不是投稿数为 0'。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码确实是 0 —— 正因如此才不能在别处静默通过");
            assertNotNull(cause.getDescription());
            assertTrue(cause.getDescription().contains("13 键"),
                    "要说清上游恒给 13 键，好让人据此排除'是我传错了 mid'。实际：" + cause.getDescription());
        }

        @Test
        @DisplayName("★ 13 键全 0 不算空对象 —— 那是合法响应，不能抛")
        void allZeroIsNotAnEmptyObject() throws Exception {
            // 🔴 守卫最危险的写法是把"全 0"也判成失败。实测：不存在的 mid 就是全 0
            //   ⇒ 一旦对全 0 抛异常，"从没投过稿的真实账号"会被同一段代码误杀。
            //   所以边界钉在"键在不在"，不是"值是不是 0"。
            mock.register(NAVNUM_PATH, ALL_ZERO);

            NavNum data = new UserSpace().getNavNum(2L);

            assertEquals(0L, data.getVideo(), "0 是值，不是缺失");
            assertEquals(0L, data.getArticle());
            assertNotNull(data.getFavourite(), "嵌套对象也要给出来，不能因为值是 0 就丢");
            assertEquals(0L, data.getFavourite().getGuest());
        }

        @Test
        @DisplayName("★ 不存在的 mid 不报错：本端点不校验存在性")
        void nonexistentMidIsIndistinguishable() {
            // 六格实测：不存在的 mid 也是 code=0 + 13 键全 0 ⇒ 库只如实透出，
            // 不臆造"用户不存在"这个信号（API 里根本没有）。
            mock.register("/x/space/navnum?mid=99999999999999", ALL_ZERO);

            NavNum data = UserService.INSTANCE.getNavNum(99999999999999L);

            assertEquals(0L, data.getVideo(), "查无此人 = 全 0，与'真的一无所有'同形");
            assertNotNull(data.getTag(), "键仍然是齐的 —— 这正是它无法用来判存在性的原因");
        }
    }
}

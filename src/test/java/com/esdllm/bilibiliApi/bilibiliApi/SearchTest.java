package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.search.HotSearch;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Search 门面回归测试</b>（B3 批新增的第 7 个门面）。
 *
 * <p>门面自身<b>没有业务逻辑</b>，它只是"Service → 调用方"之间的边界层，价值全在两件事上，
 * 所以本测试也只钉这两件：
 * <ol>
 *   <li><b>泛型实参不能写错</b>：{@code searchVideos} 必须回 {@code SearchTypeResult<SearchVideo>}、
 *       {@code searchUsers} 回 {@code SearchTypeResult<SearchUser>}。三者在字节码里被擦成同一个类，
 *       编译期看不见 —— 所以这里靠"把返回值当强类型用"来守：泛型一旦写错（例如两个方法
 *       共用了一个 raw 返回），下面的赋值与取字段就会失败，而不是静默地让调用方拿错类型；</li>
 *   <li><b>异常边界</b>：{@link BilibiliException} 是 <b>runtime</b> 异常，门面若不 catch 它会
 *       原样逃出去。这里断言拿到的是 {@link IOException}、{@code getCause()} 就是原异常、
 *       且 {@code getMessage()} 保留了内层文案 —— "包装成 IOException"是 XatiiBot 的既有契约，
 *       不能悄悄变成"吃掉异常"或"换掉文案"（换掉文案等于把排障线索一起丢掉）。</li>
 * </ol>
 *
 * <p>解析层面的细节（12 个分组的结构、高亮标签剥离、页码夹取、非 JSON 响应）在
 * {@code SearchServiceTest} 里已经逐条断言过，<b>这里不重复造那些夹具断言</b>。
 *
 * <p>⚠️ 两个搜索端点实测<b>免签名</b>，但 {@code SearchService} 仍走 {@code getSigned}
 * （理由见该类注释），所以每个用例都先把 {@code nav} 注册好，否则会先卡在"取不到 WBI 密钥"。
 *
 * <p>🆕 <b>B2 批（2026-09-22）补了热搜榜</b>（{@code x/web-interface/search/square}）。
 * 它与上面三个方法<b>不是同一档</b>：零门槛、不走签名、数据多包一层 {@code trending}
 * —— 所以单独开一组（{@code HotSearchTest}），并且反向断言"它一次 nav 都不打"。
 */
@DisplayName("门面：Search（搜索）")
class SearchTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String ALL_PATH = "/x/web-interface/wbi/search/all/v2";
    private static final String TYPE_PATH = "/x/web-interface/wbi/search/type";
    /** B2 批 #3：热搜榜。注意它<b>没有</b> {@code /wbi/} 这一段，也不走签名出口 */
    private static final String SQUARE_PATH = "/x/web-interface/search/square";

    private static final String NAV_BODY = "{\"code\":-101,\"data\":{\"wbi_img\":{"
            + "\"img_url\":\"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png\","
            + "\"sub_url\":\"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png\"}}}";

    private MockBiliServer mock;
    private Search search;

    @BeforeEach
    void setUp() {
        WbiKeyStore.invalidate();
        mock = MockBiliServer.start().register(NAV_PATH, NAV_BODY);
        search = new Search();
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
    // 委托与泛型
    // ================================================================

    @Nested
    @DisplayName("委托与泛型实参")
    class DelegationTest {

        @Test
        @DisplayName("searchVideos 回 SearchTypeResult<SearchVideo>，元素可当 SearchVideo 用")
        void videos() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-video.json"));

            SearchTypeResult<SearchVideo> page = search.searchVideos("测试", 1);

            // 泛型实参若被写错，下面这两行编译期就会塌
            SearchVideo first = page.getResult().get(0);
            assertFalse(first.getCleanTitle().contains("<em"),
                    "cleanTitle 必须是剥过高亮标签的：" + first.getCleanTitle());
            assertEquals(3, page.size(), "本页 3 条（夹具定值）");
            assertEquals(1, page.getPage());
        }

        @Test
        @DisplayName("searchUsers 回 SearchTypeResult<SearchUser>，且与 videos 是两个类型（不共用 raw 返回）")
        void users() throws Exception {
            mock.register(TYPE_PATH, fixture("search-type-user.json"));

            SearchTypeResult<SearchUser> page = search.searchUsers("测试", 1);

            SearchUser first = page.getResult().get(0);
            assertNotNull(first.getUname());
            assertNotNull(first.getMid());
            assertEquals(3, page.size());
        }

        @Test
        @DisplayName("searchAll 回 SearchAllResult，videos() 是取值主路（12 分组里只有它稳定有内容）")
        void all() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            SearchAllResult result = search.searchAll("测试", 1);

            assertFalse(result.videos().isEmpty(), "综合搜索的主路是 videos()");
            assertNotNull(result.getResult(), "12 个分组原样保留");
        }

        @Test
        @DisplayName("门面照传页码：page=0 被归一成 1（不把 0 原样发出去）")
        void pageNormalized() throws Exception {
            mock.register(ALL_PATH, fixture("search-all.json"));

            search.searchAll("测试", 0);

            assertTrue(mock.requestUri(ALL_PATH).contains("page=1"),
                    "实际：" + mock.requestUri(ALL_PATH));
        }
    }

    // ================================================================
    // 热搜榜（B2 批 #3）
    // ================================================================

    /**
     * 热搜与上面三个搜索方法<b>不是同一档</b>，所以另开一组：
     * <ul>
     *   <li>它<b>不走签名出口</b>（上面三个走签名是"防服务端哪天恢复强制签名"，而这个端点
     *       连响应里都没有签名相关字段）；</li>
     *   <li>它的数据<b>多包了一层</b> {@code trending} —— 这层是本次最容易踩的坑；</li>
     *   <li>它的 {@code trackid} 是<b>超出 {@code long} 范围的字符串</b>。</li>
     * </ul>
     */
    @Nested
    @DisplayName("热搜榜（零门槛：普通 GET，不走签名）")
    class HotSearchTest {

        @Test
        @DisplayName("★ 榜单在 data.trending 里，不在 data 本身 —— 取错一层就什么都拿不到")
        void trendingNesting() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            HotSearch hot = search.getHotSearch(10);

            assertNotNull(hot.getTrending(),
                    "★ data 顶层只有 trending 一个键；把 data 当榜单用会一路 null");
            assertEquals("bilibili热搜", hot.getTrending().getTitle());

            List<HotSearch.Item> items = hot.getTrending().getList();
            assertEquals(3, items.size(), "夹具裁到 3 条");
            assertEquals("深度复盘IG战胜JDG晋级世界赛", items.get(0).getKeyword());
            assertEquals(2976633L, items.get(0).getHeat_score());
            assertEquals("小米发布并开源MiMo V2.6", items.get(2).getKeyword());
        }

        @Test
        @DisplayName("★ trackid 是字符串且已超出 long 范围 —— 谁把它当数字接就会在这里炸")
        void trackidIsOpaqueString() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            String trackid = search.getHotSearch(10).getTrending().getTrackid();

            assertEquals("12414231099029457647", trackid);
            assertThrows(NumberFormatException.class, () -> Long.parseLong(trackid),
                    "★ 它比 Long.MAX_VALUE（9223372036854775807）还大一位 —— "
                            + "字段类型故意留成 String，就是为了让'想算它'的人在这行看见失败");
        }

        @Test
        @DisplayName("★ goto → goTo：Java 保留字只能改名，JSON 键没变")
        void gotoMapping() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            HotSearch.Item first = search.getHotSearch(10).getTrending().getList().get(0);

            assertEquals("", first.getGoTo(),
                    "★ 实测常为空串（这个夹具就是）—— 别当必填。"
                            + "若映射写错，这里会变成 null，与'空串'只差一个字符但业务含义不同");
            assertEquals("", first.getUri(), "同上，实测常为空串");
            assertNotNull(first.getIcon(), "icon 可能为空串，但键存在时不会是 null");
        }

        @Test
        @DisplayName("limit：≤0 走默认 10，越界夹到 50，无参重载也是 10")
        void limitClamped() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            search.getHotSearch(0);
            assertTrue(mock.requestUri(SQUARE_PATH).contains("limit=10"),
                    "★ ≤0 走默认值而不是把 0 原样发出去。实际：" + mock.requestUri(SQUARE_PATH));

            search.getHotSearch(9999);
            assertTrue(mock.requestUri(SQUARE_PATH).contains("limit=50"),
                    "★ 越界夹到上限（实测只验过 10，更大的值未验证）。实际："
                            + mock.requestUri(SQUARE_PATH));

            search.getHotSearch();
            assertTrue(mock.requestUri(SQUARE_PATH).contains("limit=10"),
                    "无参重载 = 默认 10。实际：" + mock.requestUri(SQUARE_PATH));
        }

        @Test
        @DisplayName("★ 普通 GET：一次 nav 都不打（与上面三个走签名的搜索方法不同）")
        void noSigning() throws Exception {
            mock.register(SQUARE_PATH, fixture("hot-search.json"));

            search.getHotSearch(10);

            assertEquals(0, mock.hitCount(NAV_PATH),
                    "★ 给它签名只是白算一次，还平白多一个 'nav 不可达' 的失败面。"
                            + "路径上也看得见区别：本端点没有 /wbi/ 那一段");
        }
    }

    // ================================================================
    // 异常边界：BilibiliException → IOException
    // ================================================================

    @Nested
    @DisplayName("异常边界（BilibiliException → IOException）")
    class BoundaryTest {

        @Test
        @DisplayName("空关键词：包装成 IOException，内层消息保留，且一个出站都没发")
        void blankKeyword() {
            IOException e = assertThrows(IOException.class, () -> search.searchVideos("  ", 1));

            assertTrue(e.getMessage().contains("关键词不能为空"), "实际：" + e.getMessage());
            assertInstanceOf(BilibiliException.class, e.getCause(),
                    "cause 必须是原 BilibiliException —— 丢了 cause 就等于丢了堆栈");
            // 本地参数校验在发请求之前，所以搜索端点一次都没被命中
            assertEquals(0, mock.hitCount(TYPE_PATH));
            assertEquals(0, mock.hitCount(NAV_PATH), "连 WBI 密钥都不该去取");
        }

        @Test
        @DisplayName("业务码非 0：也走 IOException，且业务码与文案都没丢")
        void businessCode() throws Exception {
            mock.register(TYPE_PATH, "{\"code\":-412,\"message\":\"请求被拦截\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> search.searchUsers("测试", 1));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-412, cause.getCode(), "业务码要能被调用方读到，否则无法区分风控与参数错");
            assertTrue(e.getMessage().contains("用户搜索失败"), "实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("请求被拦截"), "服务端原话要带上：" + e.getMessage());
        }

        @Test
        @DisplayName("响应不是 JSON：包成 IOException，而不是漏出 fastjson 异常")
        void notJson() throws Exception {
            mock.register(TYPE_PATH, "<html>502 Bad Gateway</html>");

            IOException e = assertThrows(IOException.class, () -> search.searchVideos("测试", 1));

            assertInstanceOf(BilibiliException.class, e.getCause());
            assertTrue(e.getMessage().contains("无法解析"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("★ 热搜榜 code=0 但 trending.list 为空：必须抛（该端点匿名可用，空榜单只能是形状变了或被风控）")
        void hotSearchEmptyBoardIsNotSilent() {
            mock.register(SQUARE_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{\"trending\":"
                    + "{\"title\":\"bilibili热搜\",\"trackid\":\"1\",\"list\":[],\"top_list\":[]}}}");

            IOException e = assertThrows(IOException.class, () -> search.getHotSearch(10));

            assertTrue(e.getMessage().contains("trending.list 为空"),
                    "这条没有非 0 的码可用，文案就是唯一判据。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码确实是 0 —— 正因如此才不能靠它判成败");
            assertTrue(cause.getDescription().contains("形状"),
                    "要给出下一步动作（先确认路径还在不在）。实际：" + cause.getDescription());
        }
    }
}

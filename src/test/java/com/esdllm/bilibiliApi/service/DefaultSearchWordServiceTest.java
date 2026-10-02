package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.search.DefaultSearchWord;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code SearchService#getDefaultSearchWord} 的回归测试</b>（C2 批，2026-10-02）。
 *
 * <p>本文件重点防守两件事：
 * <ol>
 *   <li><b>无参数</b> —— 端点没有 query，用"出站 URI 逐字等于路径"钉住。
 *       后人给它加参数（或误加 {@code limit} 之类）时这里会红。</li>
 *   <li><b>{@code seid} 必须按字符串接</b> —— 它是 19~20 位随机数字串，<b>大多数时候</b>超出
 *       {@code Long.MAX_VALUE}（本夹具取值 {@code 16451640188548591644} 就是），
 *       但<b>偶尔会落在范围内</b>。⇒ 断言钉的是<b>字段类型</b>，不是"某次的值能不能 parseLong"
 *       （后者是时而绿时而红的检查）。</li>
 * </ol>
 *
 * <p>📌 夹具是<b>真机原样</b>（2026-10-02 匿名抓取，8 个键），未裁剪。
 */
@DisplayName("服务：SearchService#getDefaultSearchWord（默认搜索词）")
class DefaultSearchWordServiceTest {

    private static final String DEFAULT_PATH = "/x/web-interface/search/default";

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
        @DisplayName("解析出 8 个字段（name / seid / url 等）")
        void parsesDefaultSearchWord() throws Exception {
            mock.register(DEFAULT_PATH, fixture("search-default.json"));

            DefaultSearchWord data = SearchService.INSTANCE.getDefaultSearchWord();

            assertEquals("边狱巴士", data.getName());
            assertEquals("边狱巴士", data.getShow_name(), "实测 name 与 show_name 相同");
            assertEquals(2797749738467414230L, data.getId());
            assertEquals(0L, data.getType());
            assertEquals(0L, data.getGoto_type());
            assertEquals("", data.getGoto_value(), "实测为空串，别当必填");
            assertTrue(data.getUrl().contains("search.bilibili.com"),
                    "url 应指向搜索页，实际：" + data.getUrl());
        }

        @Test
        @DisplayName("★ seid 必须是 String 字段（真机值有时超出 long 范围）")
        void keepsSeidAsOpaqueString() throws Exception {
            mock.register(DEFAULT_PATH, fixture("search-default.json"));

            DefaultSearchWord data = SearchService.INSTANCE.getDefaultSearchWord();

            assertEquals("16451640188548591644", data.getSeid());

            // 🔴 主断言：钉**字段类型**。seid 实测是 19~20 位随机数字串，**大多数时候**超出
            //    long 范围、**偶尔**落在范围内 ⇒ 只能钉类型，不能钉"某次的值能不能 parseLong"
            //    （那样是时而绿时而红的检查，见 C2PreflightSmokeTest 的踩坑记录）。
            assertEquals(String.class, DefaultSearchWord.class.getDeclaredField("seid").getType(),
                    "seid 的字段类型必须是 String —— 它是超长随机数字串，按 long 接会溢出/变负");

            // 佐证：本夹具这一个取值恰好超出 long 范围（它只是一次实测的快照）。
            assertThrows(NumberFormatException.class, () -> Long.parseLong(data.getSeid()),
                    "本夹具的 seid 超出 Long.MAX_VALUE —— 说明按数值接是真会炸的");
        }
    }

    @Nested
    @DisplayName("出站形状")
    class RequestShapeTest {

        @Test
        @DisplayName("★ 无参数：出站 URI 逐字等于路径，连 ? 都没有")
        void sendsNoQueryAtAll() throws Exception {
            mock.register(DEFAULT_PATH, fixture("search-default.json"));

            SearchService.INSTANCE.getDefaultSearchWord();

            String uri = mock.requestUri(DEFAULT_PATH);
            assertEquals(DEFAULT_PATH, uri,
                    "该端点不接受任何参数 —— URI 里出现 query 就说明有人加了旋钮，实际：" + uri);
            assertFalse(uri.contains("?"), "不能有 query，实际：" + uri);
        }
    }

    @Nested
    @DisplayName("失败语义")
    class FailureTest {

        @Test
        @DisplayName("code=0 但 name 为空 -> 抛异常（不能返回一个空词）")
        void throwsWhenNameIsBlank() {
            mock.register(DEFAULT_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"data\":{\"seid\":\"1\",\"name\":\"\"}}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.getDefaultSearchWord());
            assertTrue(e.getMessage().contains("name"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("code 非 0 -> 抛异常")
        void throwsOnNonZeroCode() {
            mock.register(DEFAULT_PATH, "{\"code\":-352,\"message\":\"-352\"}");

            assertThrows(BilibiliException.class,
                    () -> SearchService.INSTANCE.getDefaultSearchWord());
        }
    }
}

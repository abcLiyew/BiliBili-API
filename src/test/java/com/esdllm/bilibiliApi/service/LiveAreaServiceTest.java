package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveArea;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveSubArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@code LiveService#getAreaList}</b>（直播分区）的回归测试（2026-09-22 B2 批 #8）。
 *
 * <p>本文件守三件光看代码看不出来事：
 * <ol>
 *   <li>🔴 <b>两层同名不同型</b>：一级分区的 {@code id} 是 {@code Integer}、
 *       二级的是 {@code String}（实测 {@code "86"}）。<b>两层不能共用 POJO</b> ——
 *       钉住这一点，免得有人为了"统一"把它们合成一个类。</li>
 *   <li>🔴 <b>{@code parent_area_id} 这个参数是装饰品</b>：实测四格对照
 *       （不传 / {@code =1} / {@code =2} / {@code =999}）返回完全一致，连不存在的 id 也照样给全树。
 *       所以本库<b>不暴露它</b>，本文件用"请求里绝不出现这个参数"把这条决定钉死。</li>
 *   <li>⚠️ <b>数字也是字符串</b>：{@code id}/{@code parent_id}/{@code act_id}/{@code pk_status}/
 *       {@code lock_status} 全是 String，而 {@code hot_status}/{@code area_type} 是 int，
 *       <b>同一个对象里混着两种</b>。</li>
 * </ol>
 */
@DisplayName("服务：LiveService#getAreaList（直播分区）")
class LiveAreaServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String AREA_PATH = "/room/v1/Area/getList";

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
        @DisplayName("data 是【数组】（不是对象）：一级分区 + 各自的二级分区")
        void listIsArray() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            List<LiveArea> areas = LiveService.INSTANCE.getAreaList();

            assertEquals(2, areas.size(), "夹具裁自真实响应（原 12 个一级分区）");
            assertEquals(2, areas.get(0).getId());
            assertEquals("网游", areas.get(0).getName());
            assertEquals(3, areas.get(0).getList().size(), "每级都裁到 3 条");
            assertEquals("手游", areas.get(1).getName());
        }

        @Test
        @DisplayName("★ 一级 id 是 Integer、二级 id 是 String —— 同名不同型，两层不能共用 POJO")
        void twoLevelsUseDifferentTypes() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            LiveArea parent = LiveService.INSTANCE.getAreaList().get(0);
            LiveSubArea child = parent.getList().get(0);

            assertEquals(2, parent.getId(), "一级 id 是数值（实测 2）");
            assertEquals("86", child.getId(),
                    "★ 二级 id 是【字符串】（实测 \"86\"）—— 用 Integer 接会静默变 null 或抛错，"
                            + "取决于配置；用 String 接才对得上");
            assertEquals(LiveArea.class, parent.getClass());
            assertEquals(LiveSubArea.class, child.getClass());
        }

        @Test
        @DisplayName("★ 二级分区里混着两种类型：字符串 5 个 + int 2 个")
        void mixedTypesInSubArea() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            LiveSubArea child = LiveService.INSTANCE.getAreaList().get(0).getList().get(0);

            assertEquals("2", child.getParent_id(), "★ parent_id 是字符串");
            assertEquals("4", child.getOld_area_id(), "★ old_area_id 不是 id（实测 \"4\" vs id \"86\"）");
            assertEquals("0", child.getAct_id());
            assertEquals("1", child.getPk_status());
            assertEquals("0", child.getLock_status());
            assertEquals(0, child.getHot_status(), "★ 这个是 int");
            assertEquals(0, child.getArea_type(), "★ 这个也是 int");
            assertEquals("英雄联盟", child.getName());
            assertEquals("网游", child.getParent_name());
            assertEquals("", child.getComplex_area_name());
            assertNotNull(child.getPic());
        }

        @Test
        @DisplayName("★ 二级分区自带父分区信息（parent_id + parent_name），与一级层一致")
        void parentInfoIsRedundantOnPurpose() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            LiveArea parent = LiveService.INSTANCE.getAreaList().get(1);

            for (LiveSubArea child : parent.getList()) {
                assertEquals(String.valueOf(parent.getId()), child.getParent_id(),
                        "★ 只拿到二级分区时也能反推父分区 —— 这正是这两组字段存在的意义");
                assertEquals(parent.getName(), child.getParent_name());
            }
            assertEquals("王者荣耀", parent.getList().get(0).getName());
        }
    }

    // ================================================================
    // 请求形状
    // ================================================================

    @Nested
    @DisplayName("请求形状")
    class RequestTest {

        @Test
        @DisplayName("★ 请求里【绝不】出现 parent_area_id —— 那个参数实测不起作用，本库刻意不暴露")
        void noParentAreaIdParam() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            LiveService.INSTANCE.getAreaList();

            String uri = mock.requestUri(AREA_PATH);
            assertFalse(uri.contains("parent_area_id"),
                    "★ 实测 A/B：不传 / =1 / =2 / =999 的响应完全相同（连不存在的 id 也返回全树）。"
                            + "暴露一个'传了没用'的入参只会让人以为是自己传错了。实际：" + uri);
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不需要签名");
        }

        @Test
        @DisplayName("Referer 用直播站（liveReferer），不是主站")
        void referer() throws Exception {
            mock.register(AREA_PATH, fixture("live-area-list.json"));

            LiveService.INSTANCE.getAreaList();

            assertEquals("https://live.bilibili.com/", mock.requestHeader(AREA_PATH, "Referer"));
        }
    }

    // ================================================================
    // 失败
    // ================================================================

    @Nested
    @DisplayName("失败形态")
    class FailureTest {

        @Test
        @DisplayName("★ code=0 但分区为空：抛异常（该端点匿名可用，空列表不能再用'缺凭据'解释）")
        void emptyThrows() {
            mock.register(AREA_PATH, "{\"code\":0,\"message\":\"success\",\"data\":[]}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    LiveService.INSTANCE::getAreaList);

            assertEquals(0, e.getCode());
            assertTrue(e.getMessage().contains("分区列表为空"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("形状"), "实际：" + e.getDescription());
        }

        @Test
        @DisplayName("data 为 null：抛异常")
        void nullData() {
            mock.register(AREA_PATH, "{\"code\":0,\"message\":\"success\",\"data\":null}");

            assertEquals(0, assertThrows(BilibiliException.class,
                    LiveService.INSTANCE::getAreaList).getCode());
        }

        @Test
        @DisplayName("HTTP 412 风控：码值保住 412")
        void http412() {
            mock.registerStatus(AREA_PATH, 412, "");

            assertEquals(412, assertThrows(BilibiliException.class,
                    LiveService.INSTANCE::getAreaList).getCode());
        }
    }
}

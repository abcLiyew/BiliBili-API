package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.bilibiliApi.UserSpace;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.data.pojo.user.RelationList;
import com.esdllm.bilibiliApi.model.data.pojo.user.UpStat;
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
 * <b>UP 累计数据 / 粉丝 / 关注</b>的回归测试（2026-09-22 B3.5 批 #2 与 #4）。
 *
 * <p>合在一个文件里，是因为这三项在实现上同属 {@code UserService} 的"凭据域扩容"，
 * 而且它们要一起回答<b>本批最反直觉的一个问题</b>：同一个服务里，
 * "没带凭据"到底长什么样？实测三种形态里这里占了两种，且<b>正好相反</b>：
 *
 * <table border="1">
 *   <caption>本文件覆盖的两条端点，缺凭据的表现完全相反</caption>
 *   <tr><th>方法</th><th>缺凭据</th><th>靠什么发现</th></tr>
 *   <tr><td>{@code getUpStat}</td><td><b>{@code code=0} + 空 data</b></td>
 *       <td><b>只能靠"data 是不是空的"</b> —— 看 {@code code} 会永远读成成功</td></tr>
 *   <tr><td>{@code getFollowers} / {@code getFollowings}</td><td>{@code -101}</td>
 *       <td>业务码，最省事的一种</td></tr>
 * </table>
 * 所以本文件里 <b>{@code upStatEmptyDataIsAnError} 是那条最该留着的用例</b>：
 * 它守的不是"能不能解析 JSON"，而是"会不会把'没凭据'读成'播放量是 0'"。
 */
@DisplayName("服务：UserService#getUpStat / #getFollowers / #getFollowings")
class UserStatServiceTest {

    private static final String NAV_PATH = "/x/web-interface/nav";
    private static final String UPSTAT_PATH = "/x/space/upstat?mid=";
    private static final String FOLLOWERS_PATH = "/x/relation/followers";
    private static final String FOLLOWINGS_PATH = "/x/relation/followings";

    private static final long MID = 497078180L;

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
    // upstat
    // ================================================================

    @Nested
    @DisplayName("UP 累计数据（x/space/upstat）")
    class UpStatTest {

        @Test
        @DisplayName("三个数都要取到：archive.view / article.view / likes")
        void readsAllThree() throws Exception {
            mock.register(UPSTAT_PATH, fixture("upstat.json"));

            UpStat data = UserService.INSTANCE.getUpStat(MID);

            assertEquals(9065L, data.getArchive().getView());
            assertEquals(308L, data.getArticle().getView());
            assertEquals(408L, data.getLikes());
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不签名");
            assertTrue(mock.requestUri(UPSTAT_PATH).contains("mid=" + MID),
                    "实际：" + mock.requestUri(UPSTAT_PATH));
        }

        @Test
        @DisplayName("★ code=0 但 data 是空对象：必须抛异常，绝不返回三字段全 null 的对象")
        void upStatEmptyDataIsAnError() {
            // 这就是匿名调用的真实形状：外层 code 是 0，data 是 {}。
            // 若这里返回一个全 null 的 UpStat，调用方会把"没带凭据"读成"这个 UP 主播放量是 0"。
            mock.register(UPSTAT_PATH, "{\"code\":0,\"message\":\"OK\",\"ttl\":1,\"data\":{}}");

            IOException e = assertThrows(IOException.class,
                    () -> new UserSpace().getUpStat(MID));

            assertTrue(e.getMessage().contains("data 是空对象"),
                    "异常里要说清'这不是数据为 0'。实际：" + e.getMessage());
            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(0, cause.getCode(), "外层码确实是 0 —— 正因如此才不能在别处静默通过");
            assertNotNull(cause.getDescription());
            assertTrue(cause.getDescription().contains("凭据"),
                    "给出可操作的下一步。实际：" + cause.getDescription());
        }

        @Test
        @DisplayName("部分字段缺失（只有 likes）不算空 —— 不能把'数据不全'也判成失败")
        void partiallyFilledIsNotBlank() throws Exception {
            mock.register(UPSTAT_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":{\"likes\":408}}");

            UpStat data = UserService.INSTANCE.getUpStat(MID);

            assertEquals(408L, data.getLikes());
            assertNull(data.getArchive(), "没给的字段保持 null，不臆造 0");
        }

        @Test
        @DisplayName("mid ≤ 0：本地校验，零出站")
        void badMid() {
            IOException e = assertThrows(IOException.class, () -> new UserSpace().getUpStat(0L));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(UPSTAT_PATH));
        }
    }

    // ================================================================
    // followers / followings
    // ================================================================

    @Nested
    @DisplayName("粉丝 / 关注列表（x/relation/followers | followings）")
    class RelationTest {

        @Test
        @DisplayName("粉丝列表：total / list / re_version 都取到，昵称在 uname 上")
        void followersShape() throws Exception {
            mock.register(FOLLOWERS_PATH, fixture("relation-followers.json"));

            RelationList data = UserService.INSTANCE.getFollowers(MID, 1, 5);

            assertEquals(120, data.getTotal());
            assertEquals(0L, data.getRe_version(), "re_version 是版本号，不是分页令牌");
            assertEquals(2, data.getList().size());
            assertEquals("悠悠娇娃iea3z3", data.getList().get(0).getUname(),
                    "昵称字段是 uname 不是 name —— 照 name 取会永远拿到 null");
            assertNotNull(data.getList().get(0).getVip(), "复用了名片域的 Vip，要能解析出对象");
            assertEquals(0, mock.hitCount(NAV_PATH), "本端点不签名");
        }

        @Test
        @DisplayName("关注列表：与粉丝同形，但打的是另一个端点、Referer 也不同")
        void followingsUseOtherEndpoint() throws Exception {
            mock.register(FOLLOWINGS_PATH,
                    "{\"code\":0,\"message\":\"OK\",\"data\":{\"list\":[],\"re_version\":7,\"total\":97}}");

            RelationList data = UserService.INSTANCE.getFollowings(MID, 2, 10);

            assertEquals(97, data.getTotal());
            assertEquals(7L, data.getRe_version());
            assertEquals(0L, mock.hitCount(FOLLOWERS_PATH), "别把两个端点写反");
            assertEquals(1, mock.hitCount(FOLLOWINGS_PATH));

            String uri = mock.requestUri(FOLLOWINGS_PATH);
            assertTrue(uri.contains("vmid=" + MID) && uri.contains("pn=2") && uri.contains("ps=10"),
                    "实际：" + uri);
            assertEquals(0, mock.hitCount(NAV_PATH));
        }

        @Test
        @DisplayName("Referer 要是对应标签页：粉丝 /fans、关注 /follow")
        void refererPointsAtTheRightTab() throws Exception {
            mock.register(FOLLOWERS_PATH, fixture("relation-followers.json"));
            mock.register(FOLLOWINGS_PATH, "{\"code\":0,\"message\":\"OK\",\"data\":"
                    + "{\"list\":[],\"re_version\":0,\"total\":97}}");

            UserService.INSTANCE.getFollowers(MID, 1, 5);
            UserService.INSTANCE.getFollowings(MID, 1, 5);

            String fans = mock.requestHeader(FOLLOWERS_PATH, "Referer");
            String follow = mock.requestHeader(FOLLOWINGS_PATH, "Referer");
            assertTrue(fans != null && fans.endsWith("/fans"), "实际：" + fans);
            assertTrue(follow != null && follow.endsWith("/follow"), "实际：" + follow);
        }

        @Test
        @DisplayName("★ -101 未登录：业务码必须活到 IOException 的 cause（与 upstat 的静默空形成对照）")
        void notLoggedIn() {
            mock.register(FOLLOWERS_PATH, "{\"code\":-101,\"message\":\"账号未登录\",\"ttl\":1}");

            IOException e = assertThrows(IOException.class, () -> new UserSpace().getFollowers(MID, 1, 5));

            BilibiliException cause = assertInstanceOf(BilibiliException.class, e.getCause());
            assertEquals(-101, cause.getCode(),
                    "这里和 upstat 正好相反：-101 是敞亮的，调用方该据此重新登录");
            assertTrue(e.getMessage().contains("账号未登录"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("分页参数被夹到 ≥1：pn/ps 传 0 不该原样发出去")
        void pagingIsClamped() throws Exception {
            mock.register(FOLLOWERS_PATH, fixture("relation-followers.json"));

            UserService.INSTANCE.getFollowers(MID, 0, 0);

            String uri = mock.requestUri(FOLLOWERS_PATH);
            assertTrue(uri.contains("pn=1"), "实际：" + uri);
            assertTrue(uri.contains("ps=1"), "实际：" + uri);
        }

        @Test
        @DisplayName("vmid ≤ 0：本地校验，零出站")
        void badVmid() {
            IOException e = assertThrows(IOException.class,
                    () -> new UserSpace().getFollowers(0L, 1, 5));

            assertTrue(e.getMessage().contains("mid不能小于0"), "实际：" + e.getMessage());
            assertEquals(0, mock.hitCount(FOLLOWERS_PATH));
        }
    }
}

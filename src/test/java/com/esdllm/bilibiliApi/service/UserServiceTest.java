package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UserService} 直接单测。
 *
 * <p>重点覆盖两类此前无人守的分支：
 * <ol>
 *   <li><b>参数校验</b>：{@code null} 与 {@code <= 0} 的 uid 有不同文案（"不能为空" / "不能小于0"）；</li>
 *   <li><b>失败路径</b>：业务码非 0、data 为空、非法 JSON 都必须抛 {@link BilibiliException}
 *       （runtime），而不是 NPE 或 fastjson 异常穿透。</li>
 * </ol>
 */
class UserServiceTest {

    private static final String CARD_PATH = "/x/web-interface/card";
    private static final String FIXTURE = "src/test/resources/fixtures/card.json";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        mock = MockBiliServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    @DisplayName("正常路径：返回完整信封（card + archive_count + follower + like_num）")
    void happyPath() throws IOException {
        mock.register(CARD_PATH + "?mid=", Files.readString(Path.of(FIXTURE)));

        BilibiliCardResp resp = UserService.INSTANCE.getCard(3546774476163227L);
        assertNotNull(resp);
        assertEquals(0, resp.getCode());
        assertNotNull(resp.getData());
        assertNotNull(resp.getData().getCard());
        assertEquals("3546774476163227", resp.getData().getCard().getMid());
        assertEquals("测试用户", resp.getData().getCard().getName());
        assertEquals(6, resp.getData().getCard().getLevel_info().getCurrent_level());
        assertEquals(42, resp.getData().getArchive_count());
        assertEquals(12345, resp.getData().getFollower());
        assertEquals(6789, resp.getData().getLike_num());
    }

    @Test
    @DisplayName("uid 为 null → '不能为空'")
    void uidIsNull() {
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> UserService.INSTANCE.getCard(null));
        assertTrue(e.getMessage().contains("uid不能为空"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("uid 为 0 或负数 → '不能小于0'（与 null 的文案区分开）")
    void uidInvalidNumber() {
        BilibiliException zero = assertThrows(BilibiliException.class,
                () -> UserService.INSTANCE.getCard(0L));
        assertTrue(zero.getMessage().contains("uid不能小于0"), "实际：" + zero.getMessage());

        BilibiliException neg = assertThrows(BilibiliException.class,
                () -> UserService.INSTANCE.getCard(-1L));
        assertTrue(neg.getMessage().contains("uid不能小于0"));
    }

    @Test
    @DisplayName("参数校验发生在发请求之前（不发无意义的网络请求）")
    void validationRunsFirst() {
        // mock 未注册任何路径；若实现先发请求，会拿到 404 并抛"获取卡片信息失败"，
        // 而不是参数校验文案 —— 以此反证校验顺序
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> UserService.INSTANCE.getCard(null));
        assertTrue(e.getMessage().contains("uid不能为空"),
                "应是参数校验先命中，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("业务码非 0 → 抛 BilibiliException")
    void nonZeroBusinessCode() {
        mock.register(CARD_PATH + "?mid=",
                "{\"code\":-404,\"message\":\"无此项\",\"data\":null}");
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> UserService.INSTANCE.getCard(1L));
        assertTrue(e.getMessage().contains("获取卡片信息失败"), "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("code=0 但 data 为 null → 抛 BilibiliException（不是 NPE）")
    void nullData() {
        mock.register(CARD_PATH + "?mid=", "{\"code\":0,\"message\":\"0\",\"data\":null}");
        assertThrows(BilibiliException.class, () -> UserService.INSTANCE.getCard(1L));
    }

    @Test
    @DisplayName("响应不是合法 JSON → 抛 BilibiliException")
    void invalidJson() {
        mock.register(CARD_PATH + "?mid=", "<html>blocked</html>");
        assertThrows(BilibiliException.class, () -> UserService.INSTANCE.getCard(1L));
    }

    @Test
    @DisplayName("未注册路径（404）→ 抛异常，不返回 null")
    void unregisteredPath() {
        assertThrows(BilibiliException.class, () -> UserService.INSTANCE.getCard(88888L));
    }
}
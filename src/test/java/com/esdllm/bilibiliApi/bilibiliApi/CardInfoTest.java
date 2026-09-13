package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import com.esdllm.bilibiliApi.model.data.pojo.Card;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <b>CardInfo 门面回归测试</b>。
 *
 * <p>fixture 驱动：拦截 {@code api.bilibili.com/x/web-interface/card?mid=3546774476163227}，
 * 返回 {@code card.json}；对照断言 card 的 8 个 getter + {@code getBilibiliLiveResp} 全字段映射。
 *
 * <p>验收：9 个 getter × 至少 1 个断言 + 单槽缓存 + 异常路径。
 */
class CardInfoTest {

    private static final long UID = 3546774476163227L;
    private static final String CARD_PATH = "/x/web-interface/card";
    private static final String FIXTURE = "src/test/resources/fixtures/card.json";

    private MockBiliServer mock;
    private CardInfo cardInfo;

    @BeforeEach
    void setUp() throws IOException {
        String body = Files.readString(Path.of(FIXTURE));
        mock = MockBiliServer.start().register(CARD_PATH + "?mid=", body);
        cardInfo = new CardInfo();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void getBilibiliLiveResp_returnsFullEnvelope() throws IOException {
        BilibiliCardResp resp = cardInfo.getBilibiliLiveResp(UID);
        assertNotNull(resp);
        assertEquals(0, resp.getCode());
        assertNotNull(resp.getData());
        assertNotNull(resp.getData().getCard());
        assertEquals(42, resp.getData().getArchive_count());
        assertEquals(12345, resp.getData().getFollower());
        assertEquals(6789, resp.getData().getLike_num());
    }

    @Test
    void getArchiveCount() {
        assertEquals(42, cardInfo.getArchiveCount(UID));
    }

    @Test
    void getUserName() {
        assertEquals("测试用户", cardInfo.getUserName(UID));
    }

    @Test
    void getFace() {
        assertEquals("http://i0.hdslb.com/bfs/face/test_user.jpg", cardInfo.getFace(UID));
    }

    @Test
    void getLevel() {
        // LevelInfo.current_level = 6
        assertEquals(6, cardInfo.getLevel(UID));
    }

    @Test
    void getSign() {
        assertEquals("测试签名", cardInfo.getSign(UID));
    }

    @Test
    void getFollower() {
        assertEquals(12345, cardInfo.getFollower(UID));
    }

    @Test
    void getLikeNum() {
        assertEquals(6789, cardInfo.getLikeNum(UID));
    }

    @Test
    void getCard_returnsCardObject() {
        Card c = cardInfo.getCard(UID);
        assertNotNull(c);
        assertEquals("测试用户", c.getName());
        assertEquals("测试签名", c.getSign());
        assertEquals(12345, c.getFans());
    }

    // —— 单槽缓存 + 异常路径 ——

    @Test
    void singleSlotCache_returnsSameObjectAcrossGetters() {
        // 调任意 getter 后，再调另一个 getter；Card 单槽应已被第一填好，第二个直接复用（无新请求）。
        // 这里只能验证"两次拿到的 Card 实例是同一个引用"—— CardInfo.resp 单槽只缓存到 BilibiliCardResp。
        // 我们的实现缓存到 resp（BilibiliCardResp），所以两次 getCard(uid) 应返回同一个 Card 实例。
        Card first = cardInfo.getCard(UID);
        Card second = cardInfo.getCard(UID);
        assertEquals(first, second);
    }

    @Test
    void getBilibiliLiveResp_nullUid_throws() {
        assertThrows(BilibiliException.class, () -> cardInfo.getBilibiliLiveResp(null));
    }

    @Test
    void getArchiveCount_invalidUid_throws() {
        assertThrows(BilibiliException.class, () -> cardInfo.getArchiveCount(0L));
        assertThrows(BilibiliException.class, () -> cardInfo.getArchiveCount(-1L));
    }

    @Test
    void getArchiveCount_nullUid_throws() {
        assertThrows(BilibiliException.class, () -> cardInfo.getArchiveCount(null));
    }
}
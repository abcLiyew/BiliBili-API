package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import com.esdllm.bilibiliApi.model.data.pojo.Card;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * <b>角色</b>：联网手测用例（演示 + 集成验证），{@code @Disabled} 不参与自动构建。
 *
 * <p>历史背景：本类曾用 Selenium + Jsoup 做空间主页探索（{@code testJsoup} 截图、
 * {@code testHtmlUnit} 占位桩），那个路径已经在 {@code REFACTOR_PLAN.md §4.9 +
 * P2-收尾} 中退役（库内不再依赖 ChromeDriver / Jsoup）。
 *
 * <p>当前只保留直接调门面的 7 个用例，断言层的事归 {@code contract/} 与 {@code smoke/}。
 */
@Slf4j
@Disabled("联网手测用例：依赖 B 站线上接口，无断言、不参与自动构建（见 REFACTOR_PLAN.md P3）")
class CardInfoTest {
    private final CardInfo cardInfo = new CardInfo();
    private final long uid = 3546774476163227L;

    @Test
    void getBilibiliLiveResp() {
        try {
            BilibiliCardResp resp = cardInfo.getBilibiliLiveResp(uid);
            System.out.println(resp);
        } catch (BilibiliException | IOException e) {
            log.error(e.getMessage());
        }
    }

    @Test
    void getArchiveCount() {
        Integer archiveCount = cardInfo.getArchiveCount(uid);
        System.out.println(archiveCount);
    }

    @Test
    void getUserName() {
        String userName = cardInfo.getUserName(uid);
        System.out.println(userName);
    }

    @Test
    void getFace() {
        String face = cardInfo.getFace(uid);
        System.out.println(face);
    }

    @Test
    void getLevel() {
        Integer level = cardInfo.getLevel(uid);
        System.out.println(level);
    }

    @Test
    void getSign() {
        String sign = cardInfo.getSign(uid);
        System.out.println(sign);
    }

    @Test
    void getFollower() {
        Integer follower = cardInfo.getFollower(uid);
        System.out.println(follower);
    }

    @Test
    void getLikeNum() {
        Integer likeNum = cardInfo.getLikeNum(uid);
        System.out.println(likeNum);
    }

    @Test
    void getCardTest() {
        Card card = cardInfo.getCard(uid);
        System.out.println(card);
    }
}

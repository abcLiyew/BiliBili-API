package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import com.esdllm.bilibiliApi.model.data.pojo.Card;
import com.esdllm.bilibiliApi.service.UserService;

import java.io.IOException;
import java.util.Objects;

/**
 * 名片信息获取门面。
 *
 * <p>P1 起本门面的数据获取部分迁到 {@code service.UserService.getCard(Long)}，
 * 本门面仅做"取一次 → 单槽缓存 → 字段映射"。
 *
 * <p><b>单槽缓存</b>（§6.4 "现状即如此，别改坏"）：{@link #resp} 字段沿用现状，
 * 跨多次 getter 复用，避免对同一 uid 重复打接口。{@code isCached(uid)} 是命中检查。
 *
 * @author 饿死的流浪猫
 */
public class CardInfo {
    /** 单槽缓存，与原版逐字保持一致（非线程安全——见 §6.4）。 */
    private BilibiliCardResp resp;

    /**
     * 获取 Bilibili 名片信息（每次调 {@link UserService#getCard} 取一次结果写入 {@link #resp}）。
     *
     * @param bilibiliUid bilibili 用户的 uid
     * @return 不可为 null 的 {@link BilibiliCardResp}
     * @throws BilibiliException uid 为空 / ≤ 0、网络/JSON/业务错误
     * @throws IOException 保留的 {@code throws} 声明——实现路径上并不会抛（Service 层已转译）
     * @deprecated 见 §2.1，必保签名——保留兼容，XatiiBot 不直接调，仅保留给历史引用方
     */
    @Deprecated
    public BilibiliCardResp getBilibiliLiveResp(Long bilibiliUid) throws BilibiliException, IOException {
        return loadCard(bilibiliUid);
    }

    /**
     * 取卡片信息：缓存命中直接复用，否则重新拉取并统一转成 {@link BilibiliException}。
     */
    private BilibiliCardResp loadCard(Long bilibiliUid) {
        if (isCached(bilibiliUid)) {
            return resp;
        }
        BilibiliCardResp card = UserService.INSTANCE.getCard(bilibiliUid);
        this.resp = card;
        return card;
    }

    /**
     * 判断当前缓存是否命中指定 uid（参 §6.4）。
     */
    private boolean isCached(Long bilibiliUid) {
        if (resp == null || resp.getData() == null || resp.getData().getCard() == null) {
            return false;
        }
        return Objects.equals(resp.getData().getCard().getMid(), String.valueOf(bilibiliUid));
    }

    /** 用户稿件数量 */
    public Integer getArchiveCount(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getArchive_count();
    }

    /** 名片核心信息 */
    public Card getCard(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard();
    }

    /** 用户名 */
    public String getUserName(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getName();
    }

    /** 用户头像 URL */
    public String getFace(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getFace();
    }

    /** 用户等级 */
    public Integer getLevel(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getLevel_info().getCurrent_level();
    }

    /** 用户签名 */
    public String getSign(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getSign();
    }

    /** 用户粉丝数 */
    public Integer getFollower(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getFollower();
    }

    /** 用户点赞数 */
    public Integer getLikeNum(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getLike_num();
    }
}

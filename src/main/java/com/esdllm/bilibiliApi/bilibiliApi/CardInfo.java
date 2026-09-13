package com.esdllm.bilibiliApi.bilibiliApi;



import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import com.esdllm.bilibiliApi.model.data.pojo.Card;
import kong.unirest.HttpResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * @decription 名片信息获取
 * @author 饿死的流浪猫
 */
public class CardInfo {
    private BilibiliCardResp resp;
    private static final String BaseUrl = BilibiliConfig.cardBaseUrl;

    /**
     * 获取Bilibili名片信息
     * @param bilibiliUid bilibili用户的Uid
     * @return BilibiliCardResp对象
     */
    public BilibiliCardResp getBilibiliLiveResp(Long bilibiliUid) throws BilibiliException, IOException {
        if (Objects.isNull(bilibiliUid)){
            throw new BilibiliException("uid不能为空");
        }
        if (bilibiliUid <= 0){
            throw new BilibiliException("uid不能小于0");
        }
        if (isCached(bilibiliUid)){
            return resp;
        }
        String url = BaseUrl + bilibiliUid;

        HttpResponse<String> response = ApiBase.getCloseableHttpResponse(url);

        BilibiliCardResp cardResp;
        try {
            cardResp = JSON.parseObject(response.getBody(), BilibiliCardResp.class);
        }catch (Exception e){
            throw new  BilibiliException(e);
        }
        if (Objects.isNull(cardResp) || Objects.isNull(cardResp.getData()) || cardResp.getCode()!=0){
            throw new BilibiliException("获取卡片信息失败");
        }
        this.resp = cardResp;
        return cardResp;
    }

    /**
     * 判断当前缓存是否命中指定 uid
     * @param bilibiliUid bilibili用户的Uid
     * @return 命中返回 true
     */
    private boolean isCached(Long bilibiliUid) {
        if (resp == null || resp.getData() == null || resp.getData().getCard() == null) {
            return false;
        }
        return Objects.equals(resp.getData().getCard().getMid(), String.valueOf(bilibiliUid));
    }

    /**
     * 取名片信息：缓存命中直接复用，否则重新拉取并统一转成 BilibiliException
     * @param bilibiliUid bilibili用户的Uid
     * @return BilibiliCardResp对象
     */
    private BilibiliCardResp loadCard(Long bilibiliUid) {
        if (isCached(bilibiliUid)) {
            return resp;
        }
        try {
            return getBilibiliLiveResp(bilibiliUid);
        } catch (IOException e) {
            throw new  BilibiliException("获取卡片信息失败"+e);
        }
    }

    /**
     * 获取用户稿件数量
     * @param bilibiliUid bilibili用户的Uid
     * @return int 用户稿件数量
     */
    public Integer getArchiveCount(Long bilibiliUid)  {
        return loadCard(bilibiliUid).getData().getArchive_count();
    }

    /**
     * 获取用户名片信息
     * @param bilibiliUid bilibili用户的Uid
     * @return 名片信息
     */

    public Card getCard(Long bilibiliUid)  {
        return loadCard(bilibiliUid).getData().getCard();
    }

    /**
     * 获取用户名
     * @param bilibiliUid bilibili用户的Uid
     * @return String 用户名
     */
    public String getUserName(Long bilibiliUid)  {
        return loadCard(bilibiliUid).getData().getCard().getName();
    }
    /**
     * 获取用户头像
     * @param bilibiliUid bilibili用户的Uid
     * @return String 用户头像url
     */
    public String getFace(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getFace();
    }
    /**
     * 获取用户等级
     * @param bilibiliUid bilibili用户的Uid
     * @return Integer 用户等级
     */
    public Integer getLevel(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getLevel_info().getCurrent_level();
    }
    /**
     * 获取用户签名
     * @param bilibiliUid bilibili用户的Uid
     * @return String 用户签名
     */
    public String getSign(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getCard().getSign();
    }
    /**
     * 获取用户粉丝数
     * @param bilibiliUid bilibili用户的Uid
     * @return Integer 用户关注数
     */
    public Integer getFollower(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getFollower();
    }
    /**
     * 获取用户点赞数
     * @param bilibiliUid bilibili用户的Uid
     * @return Integer 用户关注数
     */
    public Integer getLikeNum(Long bilibiliUid) {
        return loadCard(bilibiliUid).getData().getLike_num();
    }
}

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
        if (resp!=null&&resp.getData().getCard().getMid().equals(bilibiliUid.toString())){
            return resp;
        }
        String url = BaseUrl + bilibiliUid;

        HttpResponse<String> response = ApiBase.getCloseableHttpResponse(url);

        BilibiliCardResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliCardResp.class);
        }catch (Exception e){
            throw new  BilibiliException(e);
        }
        if (Objects.isNull(resp.getData())||resp.getCode()!=0){
            throw new BilibiliException("获取卡片信息失败");
        }
        this.resp = resp;
        return resp;
    }

    /**
     * 获取用户稿件数量
     * @param bilibiliUid bilibili用户的Uid
     * @return int 用户稿件数量
     */
    public Integer getArchiveCount(Long bilibiliUid)  {
        if (Objects.isNull(resp)){
            try {
                return getBilibiliLiveResp(bilibiliUid).getData().getArchive_count();
            } catch (IOException e) {
                throw new BilibiliException("获取卡片信息失败"+e);

            }
        }
        if (resp.getData().getCard().getMid().equals(bilibiliUid.toString())){
            return resp.getData().getArchive_count();
        }
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getArchive_count();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }

    /**
     * 获取用户名片信息
     * @param bilibiliUid bilibili用户的Uid
     * @return 名片信息
     */

    public Card getCard(Long bilibiliUid)  {
       try {
           return getBilibiliLiveResp(bilibiliUid).getData().getCard();
       } catch (IOException e) {
           throw new  BilibiliException(e);
       }
     }

    /**
     * 获取用户名
     * @param bilibiliUid bilibili用户的Uid
     * @return String 用户名
     */
    public String getUserName(Long bilibiliUid)  {
        if (Objects.isNull(resp)) {
            try {
                return getBilibiliLiveResp(bilibiliUid).getData().getCard().getName();
            } catch (IOException e) {
                throw new  BilibiliException("获取卡片信息失败"+e);

            }
        }
        if (resp.getData().getCard().getMid().equals(bilibiliUid.toString())){
            return resp.getData().getCard().getName();
        }
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getCard().getName();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }
    /**
     * 获取用户头像
     * @param bilibiliUid bilibili用户的Uid
     * @return String 用户头像url
     */
    public String getFace(Long bilibiliUid) {
        if (Objects.isNull(resp)){
            try {
                return getBilibiliLiveResp(bilibiliUid).getData().getCard().getFace();
            } catch (IOException e) {
                throw new  BilibiliException("获取卡片信息失败"+e);

            }
        }
        if (resp.getData().getCard().getMid().equals(bilibiliUid.toString())){
            return resp.getData().getCard().getFace();
        }
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getCard().getFace();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }
    /**
     * 获取用户等级
     * @param bilibiliUid bilibili用户的Uid
     * @return Integer 用户等级
     */
    public Integer getLevel(Long bilibiliUid) {
        if (Objects.isNull(resp)){
            try {
                return getBilibiliLiveResp(bilibiliUid).getData().getCard().getLevel_info().getCurrent_level();
            } catch (IOException e) {
                throw new  BilibiliException("获取卡片信息失败"+e);

            }
        }
        if (resp.getData().getCard().getMid().equals(bilibiliUid.toString())){
            return resp.getData().getCard().getLevel_info().getCurrent_level();
        }
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getCard().getLevel_info().getCurrent_level();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }
    /**
     * 获取用户签名
     * @param bilibiliUid bilibili用户的Uid
     * @return String 用户签名
     */
    public String getSign(Long bilibiliUid) {
        if (Objects.isNull(resp)) {
            try {
                return getBilibiliLiveResp(bilibiliUid).getData().getCard().getSign();
            } catch (IOException e) {
                throw new  BilibiliException("获取卡片信息失败"+e);

            }
        }
        if (resp.getData().getCard().getMid().equals(bilibiliUid.toString())){
            return resp.getData().getCard().getSign();
        }
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getCard().getSign();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }
    /**
     * 获取用户粉丝数
     * @param bilibiliUid bilibili用户的Uid
     * @return Integer 用户关注数
     */
    public Integer getFollower(Long bilibiliUid) {
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getFollower();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }
    /**
     * 获取用户点赞数
     * @param bilibiliUid bilibili用户的Uid
     * @return Integer 用户关注数
     */
    public Integer getLikeNum(Long bilibiliUid) {
        try {
            return getBilibiliLiveResp(bilibiliUid).getData().getLike_num();
        } catch (IOException e) {
            throw new  BilibiliException(e);
        }
    }
}

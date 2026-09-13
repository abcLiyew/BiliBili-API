package com.esdllm.bilibiliApi.bilibiliApi;


import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliVideoResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.Staff;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * @description 视频信息获取
 * @author 饿死的流浪猫
 */
@Slf4j
public class BilibiliClient {
    private VideoInfo videoInfo = null;
    /**
     * 获取视频信息
     * @param bvid 视频BV号
     * @return 视频信息
     */
    public VideoInfo getVideoInfo(String bvid) throws IOException {
        if (bvid == null ) {
            throw new BilibiliException("BV号不能为空");
        }
        if (isCached(bvid)){
            return videoInfo;
        }
        return getVideoInfoBase(BilibiliConfig.videoBaseUrl + bvid);
    }

    /**
     * 获取视频信息
     * @param aid 视频AV号
     * @return 视频信息
     */
    public VideoInfo getVideoInfo(Long aid) throws IOException {
        if (aid == null||aid<=0) {
            throw new BilibiliException("AV号不能为空");
        }
        if (isCached(aid)){
            return videoInfo;
        }
        return getVideoInfoBase(BilibiliConfig.videoAvBaseUrl + aid);
    }

    /**
     * 获取视频信息
     * @param reqUrl 请求地址
     * @return 视频信息
     */
    private VideoInfo getVideoInfoBase(String reqUrl) throws IOException {
        HttpResponse<String> response = ApiBase.getCloseableHttpResponse(reqUrl);
        BilibiliVideoResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliVideoResp.class);
        } catch (Exception e) {
            throw new BilibiliException("获取视频信息失败 错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
        if (resp == null || resp.getCode() != 0) {
            throw new BilibiliException("获取视频信息失败");
        }
        VideoInfo data = resp.getData();
        videoInfo = data;
        return data;
    }

    /**
     * 当前缓存是否命中指定 BV 号
     * @param bvid 视频BV号
     * @return 命中返回 true
     */
    private boolean isCached(String bvid) {
        return videoInfo != null && bvid != null && Objects.equals(videoInfo.getBvid(), bvid);
    }

    /**
     * 当前缓存是否命中指定 AV 号
     * @param aid 视频AV号
     * @return 命中返回 true
     */
    private boolean isCached(Long aid) {
        return videoInfo != null && aid != null && Objects.equals(videoInfo.getAid(), aid);
    }

    /**
     * 取视频信息：缓存命中直接复用，否则重新拉取；IO 异常统一转成 BilibiliException
     * @param bvid 视频BV号
     * @return 视频信息
     */
    private VideoInfo resolve(String bvid) {
        if (bvid == null) {
            throw new BilibiliException("BV号不能为空");
        }
        if (isCached(bvid)) {
            return videoInfo;
        }
        try {
            return getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+"，错误消息："+e);
        }
    }

    /**
     * 取视频信息：缓存命中直接复用，否则重新拉取；IO 异常统一转成 BilibiliException
     * @param aid 视频AV号
     * @return 视频信息
     */
    private VideoInfo resolve(Long aid) {
        if (aid == null || aid <= 0) {
            throw new BilibiliException("AV号不能为空");
        }
        if (isCached(aid)) {
            return videoInfo;
        }
        try {
            return getVideoInfo(aid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+"，错误消息："+e);
        }
    }

    /**
     * 获取视频av号
     * @param bvid 视频BV号
     * @return 视频av号
     */
    public Long getVideoAv(String bvid) {
        return resolve(bvid).getAid();
    }

    /**
     * 获取视频bv号
     * @param aid 视频AV号
     * @return 视频bv号
     */
    public String getVideoBv(Long aid) {
        return resolve(aid).getBvid();
    }

    /**
     * 获取视频封面url
     * @param bvid 视频BV号
     * @return 视频封面url
     */
    public String getVideoCoverUrl(String bvid) {
        return resolve(bvid).getPic();
    }

    /**
     * 获取视频封面url
     * @param aid 视频AV号
     * @return 视频封面url
     */
    public String getVideoCoverUrl(Long aid) {
        return resolve(aid).getPic();
    }

    /**
     * 获取视频标题
     * @param bvid 视频BV号
     * @return 视频标题
     */
    public String getVideoTitle(String bvid) {
        return resolve(bvid).getTitle();
    }

    /**
     * 获取视频标题
     * @param aid 视频AV号
     * @return 视频标题
     */
    public String getVideoTitle(Long aid) {
        return resolve(aid).getTitle();
    }

    /**
     * 获取视频简介
     * @param bvid 视频BV号
     * @return 视频简介
     */
    public String getVideoDesc(String bvid) {
        return resolve(bvid).getDesc();
    }

    /**
     * 获取视频简介
     * @param aid 视频AV号
     * @return 视频简介
     */
    public String getVideoDesc(Long aid) {
        return resolve(aid).getDesc();
    }

    /**
     * 获取视频时长
     * @param bvid 视频BV号
     * @return 视频时长
     */
    public Integer getVideoDuration(String bvid) {
        return resolve(bvid).getDuration();
    }

    /**
     * 获取视频时长
     * @param aid 视频AV号
     * @return 视频时长
     */
    public Integer getVideoDuration(Long aid) {
        return resolve(aid).getDuration();
    }

    /**
     * 获取视频发布时间
     * @param bvid 视频BV号
     * @return 视频发布时间
     */
    public Long getVideoPubdate(String bvid) {
        return resolve(bvid).getPubdate();
    }

    /**
     * 获取视频发布时间
     * @param aid 视频AV号
     * @return 视频发布时间
     */
    public Long getVideoPubdate(Long aid) {
        return resolve(aid).getPubdate();
    }

    /**
     * 获取视频播放量
     * @param bvid 视频BV号
     * @return 视频播放量
     */
    public Long getVideoPlayCount(String bvid) {
        return resolve(bvid).getStat().getView();
    }

    /**
     * 获取视频播放量
     * @param aid 视频AV号
     * @return 视频播放量
     */
    public Long getVideoPlayCount(Long aid) {
        return resolve(aid).getStat().getView();
    }

    /**
     * 获取视频弹幕数
     * @param bvid 视频BV号
     * @return 视频弹幕数
     */
    public Long getVideoDanmuCount(String bvid) {
        return resolve(bvid).getStat().getDanmaku();
    }

    /**
     * 获取视频弹幕数
     * @param aid 视频AV号
     * @return 视频弹幕数
     */
    public Long getVideoDanmuCount(Long aid) {
        return resolve(aid).getStat().getDanmaku();
    }

    /**
     * 获取视频评论数
     * @param bvid 视频BV号
     * @return 视频评论数
     */
    public Long getVideoCommentCount(String bvid) {
        return resolve(bvid).getStat().getReply();
    }

    /**
     * 获取视频评论数
     * @param aid 视频AV号
     * @return 视频评论数
     */
    public Long getVideoCommentCount(Long aid) {
        return resolve(aid).getStat().getReply();
    }

    /**
     * 获取视频收藏数
     * @param bvid 视频BV号
     * @return 视频收藏数
     */
    public Long getVideoFavoriteCount(String bvid) {
        return resolve(bvid).getStat().getFavorite();
    }

    /**
     * 获取视频收藏数
     * @param aid 视频AV号
     * @return 视频收藏数
     */
    public Long getVideoFavoriteCount(Long aid) {
        return resolve(aid).getStat().getFavorite();
    }

    /**
     * 获取视频硬币数
     * @param bvid 视频BV号
     * @return 视频硬币数
     */
    public Long getVideoCoinCount(String bvid) {
        return resolve(bvid).getStat().getCoin();
    }

    /**
     * 获取视频硬币数
     * @param aid 视频AV号
     * @return 视频硬币数
     */
    public Long getVideoCoinCount(Long aid) {
        return resolve(aid).getStat().getCoin();
    }

    /**
     * 获取视频分享数
     * @param bvid 视频BV号
     * @return 视频分享数
     */
    public Long getVideoShareCount(String bvid) {
        return resolve(bvid).getStat().getShare();
    }

    /**
     * 获取视频分享数
     * @param aid 视频AV号
     * @return 视频分享数
     */
    public Long getVideoShareCount(Long aid) {
        return resolve(aid).getStat().getShare();
    }

    /**
     * 获取视频当前排名
     * @param bvid 视频BV号
     * @return 视频当前排名
     */
    public Long getVideoCurrentRank(String bvid) {
        return resolve(bvid).getStat().getNow_rank();
    }

    /**
     * 获取视频当前排名
     * @param aid 视频AV号
     * @return 视频当前排名
     */
    public Long getVideoCurrentRank(Long aid) {
        return resolve(aid).getStat().getNow_rank();
    }

    /**
     * 获取视频历史最高排名
     * @param bvid 视频BV号
     * @return 视频历史最高排名
     */
    public Long getVideoHistoryRank(String bvid) {
        return resolve(bvid).getStat().getHis_rank();
    }

    /**
     * 获取视频历史最高排名
     * @param aid 视频AV号
     * @return 视频历史最高排名
     */
    public Long getVideoHistoryRank(Long aid) {
        return resolve(aid).getStat().getHis_rank();
    }

    /**
     * 获取up主的uid
     * @param bvid 视频BV号
     * @return up主的uid
     */
    public Long getVideoUpUid(String bvid) {
        return resolve(bvid).getOwner().getMid();
    }

    /**
     * 获取up主的uid
     * @param aid 视频AV号
     * @return up主的uid
     */
    public Long getVideoUpUid(Long aid) {
        return resolve(aid).getOwner().getMid();
    }

    /**
     * 获取up主的昵称
     * @param bvid 视频BV号
     * @return up主的昵称
     */
    public String getVideoUpName(String bvid) {
        return resolve(bvid).getOwner().getName();
    }

    /**
     * 获取up主的昵称
     * @param aid 视频AV号
     * @return up主的昵称
     */
    public String getVideoUpName(Long aid) {
        return resolve(aid).getOwner().getName();
    }

    /**
     * 获取up主的头像url
     * @param bvid 视频BV号
     * @return up主的头像url
     */
    public String getVideoUpFace(String bvid) {
        return resolve(bvid).getOwner().getFace();
    }

    /**
     * 获取up主的头像url
     * @param aid 视频AV号
     * @return up主的头像url
     */
    public String getVideoUpFace(Long aid) {
        return resolve(aid).getOwner().getFace();
    }

    /**
     * 获取视频分P数
     * @param bvid 视频BV号
     * @return 视频分P数
     */
    public Integer getVideoPartCount(String bvid) {
        return resolve(bvid).getPages().size();
    }

    /**
     * 获取视频分P数
     * @param aid 视频AV号
     * @return 视频分P数
     */
    public Integer getVideoPartCount(Long aid) {
        return resolve(aid).getPages().size();
    }

    /**
     * 获取是否为互动视频
     * @param bvid 视频BV号
     * @return 是否为互动视频
     */
    public Boolean getVideoIsInteraction(String bvid) {
        return Integer.valueOf(1).equals(resolve(bvid).getRights().getIs_stein_gate());
    }

    /**
     * 获取是否为互动视频
     * @param aid 视频AV号
     * @return 是否为互动视频
     */
    public Boolean getVideoIsInteraction(Long aid) {
        return Integer.valueOf(1).equals(resolve(aid).getRights().getIs_stein_gate());
    }

    /**
     * 获取合作成员列表
     * @param bvid 视频BV号
     * @return 合作成员列表
     */
    public List<Staff> getStaffList(String bvid) {
        return resolve(bvid).getStaff();
    }

    /**
     * 获取合作成员列表
     * @param aid 视频AV号
     * @return 合作成员列表
     */
    public List<Staff> getStaffList(Long aid) {
        return resolve(aid).getStaff();
    }

    /**
     * 获取视频分P标题
     * @param bvid 视频BV号
     * @param page 视频分P序号
     * @return 视频分P标题
     */
    public String getVideoPartTitle(String bvid, Integer page) {
        return resolve(bvid).getPages().get(page-1).getPart();
    }

    /**
     * 获取视频分P标题
     * @param aid 视频AV号
     * @param page 视频分P序号
     * @return 视频分P标题
     */
    public String getVideoPartTitle(Long aid, Integer page) {
        return resolve(aid).getPages().get(page-1).getPart();
    }
}

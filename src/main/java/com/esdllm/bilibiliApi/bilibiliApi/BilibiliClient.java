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
        if (videoInfo!=null&&videoInfo.getBvid().equals(bvid)){
            return videoInfo;
        }
        String videoBaseUrl = BilibiliConfig.videoBaseUrl;
        return getVideoInfoBase(videoBaseUrl + bvid);
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
        if (videoInfo!=null&& Objects.equals(videoInfo.getAid(), aid)){
            return videoInfo;
        }
        String videoAvBaseUrl = BilibiliConfig.videoAvBaseUrl;
        return getVideoInfoBase(videoAvBaseUrl + aid);
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
     * 获取视频av号
     * @param bvid 视频BV号
     * @return 视频av号
     */
    public Long getVideoAv(String bvid) {
        if (bvid == null ) {
            throw new BilibiliException("BV号不能为空");
        }
        if (videoInfo!=null&&videoInfo.getBvid().equals(bvid)){
            return videoInfo.getAid();
        }
        try {
            return getVideoInfo(bvid).getAid();
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败 错误在"+BilibiliClient.class.getName()+"错误消息： "+e);
        }
    }
    /**
     * 获取视频bv号
     * @param aid 视频AV号
     * @return 视频bv号
     */
    public String getVideoBv(Long aid) {
        if (aid == null||aid<=0) {
            throw new BilibiliException("AV号不能为空");
        }
        if (videoInfo!=null&& Objects.equals(videoInfo.getAid(), aid)){
            return videoInfo.getBvid();
        }
        try {
            return getVideoInfo(aid).getBvid();
        } catch (IOException e) {
            throw new BilibiliException("获取BV号异常 错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
    }
    /**
     * 获取视频封面url
     * @param bvid 视频BV号
     * @return 视频封面url
     */
    public String getVideoCoverUrl(String bvid) {
        if (videoInfo !=null&& videoInfo.getBvid().equals(bvid)){
            return videoInfo.getPic();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+",错误消息："+e);
        }
        return info.getPic();
    }
    /**
     * 获取视频封面url
     * @param aid 视频AV号
     * @return 视频封面url
     */
    public String getVideoCoverUrl(Long aid) {
        if (videoInfo !=null&& Objects.equals(videoInfo.getAid(), aid)){
            return videoInfo.getPic();
        }
        try {
            return getVideoCoverUrl(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取封面失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
    }
    /**
     * 获取视频标题
     * @param bvid 视频BV号
     * @return 视频标题
     */
    public String getVideoTitle(String bvid) {
        if (videoInfo != null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getTitle();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频消息失败，错误在"+BilibiliClient.class.getName()+",错误消息："+e);
        }
        return info.getTitle();
    }
    /**
     * 获取视频标题
     * @param aid 视频AV号
     * @return 视频标题
     */
    public String getVideoTitle(Long aid) {
        if (videoInfo != null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getTitle();
        }
        try {
            return getVideoTitle(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取标题失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
    }
     /**
     * 获取视频简介
     * @param bvid 视频BV号
     * @return 视频简介
     */
    public String getVideoDesc(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getDesc();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
        return info.getDesc();
    }
    /**
     * 获取视频简介
     * @param aid 视频AV号
     * @return 视频简介
     */
    public String getVideoDesc(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getDesc();
        }
        try {
            return getVideoDesc(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频简介信息失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
    }
    /**
     * 获取视频时长
     * @param bvid 视频BV号
     * @return 视频时长
     */
    public Integer getVideoDuration(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getDuration();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频消息失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
        return info.getDuration();
    }
    /**
     * 获取视频时长
     * @param aid 视频AV号
     * @return 视频时长
     */
    public Integer getVideoDuration(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getDuration();
        }
        try {
            return getVideoDuration(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频时长失败，错误在"+BilibiliClient.class.getName()+",错误消息："+e);
        }
    }
    /**
     * 获取视频发布时间
     * @param bvid 视频BV号
     * @return 视频发布时间
     */
    public Long getVideoPubdate(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getPubdate();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
        return info.getPubdate();
    }
    /**
     * 获取视频发布时间
     * @param aid 视频AV号
     * @return 视频发布时间
     */
    public Long getVideoPubdate(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getPubdate();
        }
        try {
            return getVideoPubdate(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取发布时间失败，错误在"+BilibiliClient.class.getName()+"，错误消息："+e);
        }
    }
    /**
     * 获取视频播放量
     * @param bvid 视频BV号
     * @return 视频播放量
     */
    public Long getVideoPlayCount(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getView();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
        return info.getStat().getView();
    }
    /**
     * 获取视频播放量
     * @param aid 视频AV号
     * @return 视频播放量
     */
    public Long getVideoPlayCount(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getView();
        }
        try {
            return getVideoPlayCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取播放量失败，错误在"+BilibiliClient.class.getName()+"错误消息："+e);
        }
    }
    /**
     * 获取视频弹幕数
     * @param bvid 视频BV号
     * @return 视频弹幕数
     */
    public Long getVideoDanmuCount(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getDanmaku();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);
        }
        return info.getStat().getDanmaku();
    }
    /**
     * 获取视频弹幕数
     * @param aid 视频AV号
     * @return 视频弹幕数
     */
    public Long getVideoDanmuCount(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getDanmaku();
        }
        try {
            return getVideoDanmuCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取弹幕数量失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);
        }
    }
    /**
     * 获取视频评论数
     * @param bvid 视频BV号
     * @return 视频评论数
     */
    public Long getVideoCommentCount(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getReply();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStat().getReply();
    }
    /**
     * 获取视频评论数
     * @param aid 视频AV号
     * @return 视频评论数
     */
    public Long getVideoCommentCount(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getReply();
        }
        try {
            return getVideoCommentCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频收藏数
     * @param bvid 视频BV号
     * @return 视频收藏数
     */
    public Long getVideoFavoriteCount(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getFavorite();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStat().getFavorite();
    }
    /**
     * 获取视频收藏数
     * @param aid 视频AV号
     * @return 视频收藏数
     */
    public Long getVideoFavoriteCount(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getFavorite();
        }
        try {
            return getVideoFavoriteCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频硬币数
     * @param bvid 视频BV号
     * @return 视频硬币数
     */
    public Long getVideoCoinCount(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getCoin();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStat().getCoin();
    }
    /**
     * 获取视频硬币数
     * @param aid 视频AV号
     * @return 视频硬币数
     */
    public Long getVideoCoinCount(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getCoin();
        }
        try {
            return getVideoCoinCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频分享数
     * @param bvid 视频BV号
     * @return 视频分享数
     */
    public Long getVideoShareCount(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getShare();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStat().getShare();
    }
    /**
     * 获取视频分享数
     * @param aid 视频AV号
     * @return 视频分享数
     */
    public Long getVideoShareCount(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getShare();
        }
        try {
            return getVideoShareCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频当前排名
     * @param bvid 视频BV号
     * @return 视频当前排名
     */
    public Long getVideoCurrentRank(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getNow_rank();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStat().getNow_rank();
    }
    /**
     * 获取视频当前排名
     * @param aid 视频AV号
     * @return 视频当前排名
     */
    public Long getVideoCurrentRank(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getNow_rank();
        }
        try {
            return getVideoCurrentRank(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频历史最高排名
     * @param bvid 视频BV号
     * @return 视频历史最高排名
     */
    public Long getVideoHistoryRank(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStat().getHis_rank();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStat().getHis_rank();
    }
    /**
     * 获取视频历史最高排名
     * @param aid 视频AV号
     * @return 视频历史最高排名
     */
    public Long getVideoHistoryRank(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStat().getHis_rank();
        }
        try {
            return getVideoHistoryRank(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取up主的uid
     * @param bvid 视频BV号
     * @return up主的uid
     */
    public Long getVideoUpUid(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getOwner().getMid();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getOwner().getMid();
    }
    /**
     * 获取up主的uid
     * @param aid 视频AV号
     * @return up主的uid
     */
    public Long getVideoUpUid(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getOwner().getMid();
        }
        try {
            return getVideoUpUid(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取up主的昵称
     * @param bvid 视频BV号
     * @return up主的昵称
     */
    public String getVideoUpName(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getOwner().getName();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getOwner().getName();
    }
    /**
     * 获取up主的昵称
     * @param aid 视频AV号
     * @return up主的昵称
     */
    public String getVideoUpName(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getOwner().getName();
        }
        try {
            return getVideoUpName(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取up主的头像url
     * @param bvid 视频BV号
     * @return up主的头像url
     */
    public String getVideoUpFace(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getOwner().getFace();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getOwner().getFace();
    }
    /**
     * 获取up主的头像url
     * @param aid 视频AV号
     * @return up主的头像url
     */
    public String getVideoUpFace(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getOwner().getFace();
        }
        try {
            return getVideoUpFace(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频分P数
     * @param bvid 视频BV号
     * @return 视频分P数
     */
    public Integer getVideoPartCount(String bvid) {
        if (videoInfo != null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getPages().size();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getPages().size();
    }
    /**
     * 获取视频分P数
     * @param aid 视频AV号
     * @return 视频分P数
     */
    public Integer getVideoPartCount(Long aid) {
        if (videoInfo != null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getPages().size();
        }
        try {
            return getVideoPartCount(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取是否为互动视频
     * @param bvid 视频BV号
     * @return 是否为互动视频
     */
    public Boolean getVideoIsInteraction(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getRights().getIs_stein_gate()==1;
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getRights().getIs_stein_gate()==1;
    }
    /**
     * 获取是否为互动视频
     * @param aid 视频AV号
     * @return 是否为互动视频
     */
    public Boolean getVideoIsInteraction(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getRights().getIs_stein_gate()==1;
        }
        try {
            return getVideoIsInteraction(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取合作成员列表
     * @param bvid 视频BV号
     * @return 合作成员列表
     */
    public List<Staff> getStaffList(String bvid) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getStaff();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getStaff();
    }
    /**
     * 获取合作成员列表
     * @param aid 视频AV号
     * @return 合作成员列表
     */
    public List<Staff> getStaffList(Long aid) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getStaff();
        }
        try {
            return getStaffList(getVideoInfo(aid).getBvid());
        } catch (IOException e) {
            throw new BilibiliException("获取合作成员失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
    /**
     * 获取视频分P标题
     * @param bvid 视频BV号
     * @param page 视频分P序号
     * @return 视频分P标题
     */
    public String getVideoPartTitle(String bvid, Integer page) {
        if (videoInfo!= null && videoInfo.getBvid().equals(bvid)) {
            return videoInfo.getPages().get(page-1).getPart();
        }
        VideoInfo info;
        try {
            info = getVideoInfo(bvid);
        } catch (IOException e) {
            throw new BilibiliException("获取视频信息失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
        return info.getPages().get(page-1).getPart();
    }
    /**
     * 获取视频分P标题
     * @param aid 视频AV号
     * @param page 视频分P序号
     * @return 视频分P标题
     */
    public String getVideoPartTitle(Long aid, Integer page) {
        if (videoInfo!= null && Objects.equals(videoInfo.getAid(), aid)) {
            return videoInfo.getPages().get(page-1).getPart();
        }
        try {
            return getVideoPartTitle(getVideoInfo(aid).getBvid(), page);
        } catch (IOException e) {
            throw new BilibiliException("获取分P标题失败，错误在"+BilibiliClient.class.getName()+" ，错误消息："+e);        }
    }
}

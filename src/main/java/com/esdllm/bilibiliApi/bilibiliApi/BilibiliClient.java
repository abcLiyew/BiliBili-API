package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.Staff;
import com.esdllm.bilibiliApi.service.VideoService;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 视频信息获取门面。
 *
 * <p>P1 起本门面的数据获取部分迁到 {@code service.VideoService.getVideoInfo(String/Long)}，
 * 本门面仅做"取一次 → 单槽缓存 → 字段映射"。
 *
 * <p><b>单槽缓存</b>（§6.4 "现状即如此，别改坏"）：{@link #videoInfo} 字段沿用现状，
 * 跨多次 getter 复用，避免对同一 bvid/aid 重复打接口。{@code isCached} 重载两个版本
 * （BV 号 / AV 号）。
 *
 * @author 饿死的流浪猫
 */
public class BilibiliClient {

    /** 单槽缓存，与原版逐字保持一致（非线程安全——见 §6.4）。 */
    private VideoInfo videoInfo = null;

    /**
     * 取视频信息（按 BV 号）。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return 不可为 null 的 {@link VideoInfo}
     * @throws IOException 业务/网络异常
     */
    public VideoInfo getVideoInfo(String bvid) throws IOException {
        try {
            return resolve(bvid);
        } catch (BilibiliException e) {
            throw new IOException("获取视频信息失败，错误在" + BilibiliClient.class.getName() + "，错误消息：" + e.getMessage(), e);
        }
    }

    /**
     * 取视频信息（按 AV 号）。
     *
     * @param aid AV 号（数字 id）
     * @return 不可为 null 的 {@link VideoInfo}
     * @throws IOException 业务/网络异常
     */
    public VideoInfo getVideoInfo(Long aid) throws IOException {
        try {
            return resolve(aid);
        } catch (BilibiliException e) {
            throw new IOException("获取视频信息失败，错误在" + BilibiliClient.class.getName() + "，错误消息：" + e.getMessage(), e);
        }
    }

    /**
     * 命中检查（BV 号）。
     */
    private boolean isCached(String bvid) {
        return videoInfo != null && bvid != null && Objects.equals(videoInfo.getBvid(), bvid);
    }

    /**
     * 命中检查（AV 号）。
     */
    private boolean isCached(Long aid) {
        return videoInfo != null && aid != null && Objects.equals(videoInfo.getAid(), aid);
    }

    /**
     * 单槽缓存 + 委托 VideoService 取一次的统一入口。
     */
    private VideoInfo resolve(String bvid) {
        if (bvid == null) {
            throw new BilibiliException("BV号不能为空");
        }
        if (isCached(bvid)) {
            return videoInfo;
        }
        VideoInfo v = VideoService.INSTANCE.getVideoInfo(bvid);
        videoInfo = v;
        return v;
    }

    /**
     * 单槽缓存 + 委托 VideoService 取一次的统一入口（AV 号版）。
     */
    private VideoInfo resolve(Long aid) {
        if (aid == null || aid <= 0) {
            throw new BilibiliException("AV号不能为空");
        }
        if (isCached(aid)) {
            return videoInfo;
        }
        VideoInfo v = VideoService.INSTANCE.getVideoInfo(aid);
        videoInfo = v;
        return v;
    }

    // —— 字段访问（41 个：bvid/aid 两个重载集合，共 22 个 getter）——

    /** 取视频 av 号 */
    public Long getVideoAv(String bvid) {
        return resolve(bvid).getAid();
    }

    /** 取视频 bv 号（按 av） */
    public String getVideoBv(Long aid) {
        return resolve(aid).getBvid();
    }

    /** 视频封面（按 bvid） */
    public String getVideoCoverUrl(String bvid) {
        return resolve(bvid).getPic();
    }

    /** 视频封面（按 aid） */
    public String getVideoCoverUrl(Long aid) {
        return resolve(aid).getPic();
    }

    /** 视频标题（按 bvid） */
    public String getVideoTitle(String bvid) {
        return resolve(bvid).getTitle();
    }

    /** 视频标题（按 aid） */
    public String getVideoTitle(Long aid) {
        return resolve(aid).getTitle();
    }

    /** 视频简介（按 bvid） */
    public String getVideoDesc(String bvid) {
        return resolve(bvid).getDesc();
    }

    /** 视频简介（按 aid） */
    public String getVideoDesc(Long aid) {
        return resolve(aid).getDesc();
    }

    /** 视频时长（按 bvid） */
    public Integer getVideoDuration(String bvid) {
        return resolve(bvid).getDuration();
    }

    /** 视频时长（按 aid） */
    public Integer getVideoDuration(Long aid) {
        return resolve(aid).getDuration();
    }

    /** 视频发布时间戳（按 bvid） */
    public Long getVideoPubdate(String bvid) {
        return resolve(bvid).getPubdate();
    }

    /** 视频发布时间戳（按 aid） */
    public Long getVideoPubdate(Long aid) {
        return resolve(aid).getPubdate();
    }

    /** 视频播放量（按 bvid） */
    public Long getVideoPlayCount(String bvid) {
        return resolve(bvid).getStat().getView();
    }

    /** 视频播放量（按 aid） */
    public Long getVideoPlayCount(Long aid) {
        return resolve(aid).getStat().getView();
    }

    /** 视频弹幕数（按 bvid） */
    public Long getVideoDanmuCount(String bvid) {
        return resolve(bvid).getStat().getDanmaku();
    }

    /** 视频弹幕数（按 aid） */
    public Long getVideoDanmuCount(Long aid) {
        return resolve(aid).getStat().getDanmaku();
    }

    /** 视频评论数（按 bvid） */
    public Long getVideoCommentCount(String bvid) {
        return resolve(bvid).getStat().getReply();
    }

    /** 视频评论数（按 aid） */
    public Long getVideoCommentCount(Long aid) {
        return resolve(aid).getStat().getReply();
    }

    /** 视频收藏数（按 bvid） */
    public Long getVideoFavoriteCount(String bvid) {
        return resolve(bvid).getStat().getFavorite();
    }

    /** 视频收藏数（按 aid） */
    public Long getVideoFavoriteCount(Long aid) {
        return resolve(aid).getStat().getFavorite();
    }

    /** 视频硬币数（按 bvid） */
    public Long getVideoCoinCount(String bvid) {
        return resolve(bvid).getStat().getCoin();
    }

    /** 视频硬币数（按 aid） */
    public Long getVideoCoinCount(Long aid) {
        return resolve(aid).getStat().getCoin();
    }

    /** 视频分享数（按 bvid） */
    public Long getVideoShareCount(String bvid) {
        return resolve(bvid).getStat().getShare();
    }

    /** 视频分享数（按 aid） */
    public Long getVideoShareCount(Long aid) {
        return resolve(aid).getStat().getShare();
    }

    /** 视频当前排名（按 bvid） */
    public Long getVideoCurrentRank(String bvid) {
        return resolve(bvid).getStat().getNow_rank();
    }

    /** 视频当前排名（按 aid） */
    public Long getVideoCurrentRank(Long aid) {
        return resolve(aid).getStat().getNow_rank();
    }

    /** 视频历史最高排名（按 bvid） */
    public Long getVideoHistoryRank(String bvid) {
        return resolve(bvid).getStat().getHis_rank();
    }

    /** 视频历史最高排名（按 aid） */
    public Long getVideoHistoryRank(Long aid) {
        return resolve(aid).getStat().getHis_rank();
    }

    /** up 主 uid（按 bvid） */
    public Long getVideoUpUid(String bvid) {
        return resolve(bvid).getOwner().getMid();
    }

    /** up 主 uid（按 aid） */
    public Long getVideoUpUid(Long aid) {
        return resolve(aid).getOwner().getMid();
    }

    /** up 主昵称（按 bvid） */
    public String getVideoUpName(String bvid) {
        return resolve(bvid).getOwner().getName();
    }

    /** up 主昵称（按 aid） */
    public String getVideoUpName(Long aid) {
        return resolve(aid).getOwner().getName();
    }

    /** up 主头像 URL（按 bvid） */
    public String getVideoUpFace(String bvid) {
        return resolve(bvid).getOwner().getFace();
    }

    /** up 主头像 URL（按 aid） */
    public String getVideoUpFace(Long aid) {
        return resolve(aid).getOwner().getFace();
    }

    /** 视频分 P 数（按 bvid） */
    public Integer getVideoPartCount(String bvid) {
        return resolve(bvid).getPages().size();
    }

    /** 视频分 P 数（按 aid） */
    public Integer getVideoPartCount(Long aid) {
        return resolve(aid).getPages().size();
    }

    /** 是否为互动视频（按 bvid） */
    public Boolean getVideoIsInteraction(String bvid) {
        return Integer.valueOf(1).equals(resolve(bvid).getRights().getIs_stein_gate());
    }

    /** 是否为互动视频（按 aid） */
    public Boolean getVideoIsInteraction(Long aid) {
        return Integer.valueOf(1).equals(resolve(aid).getRights().getIs_stein_gate());
    }

    /** 合作成员列表（按 bvid） */
    public List<Staff> getStaffList(String bvid) {
        return resolve(bvid).getStaff();
    }

    /** 合作成员列表（按 aid） */
    public List<Staff> getStaffList(Long aid) {
        return resolve(aid).getStaff();
    }

    /** 视频分 P 标题（按 bvid） */
    public String getVideoPartTitle(String bvid, Integer page) {
        return resolve(bvid).getPages().get(page - 1).getPart();
    }

    /** 视频分 P 标题（按 aid） */
    public String getVideoPartTitle(Long aid, Integer page) {
        return resolve(aid).getPages().get(page - 1).getPart();
    }
}

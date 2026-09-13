package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.Staff;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Disabled("联网手测用例：依赖 B 站线上接口、无断言，不参与自动构建（见 REFACTOR_PLAN.md P3）")
class BilibiliClientTest {
    private final BilibiliClient bilibiliClient = new BilibiliClient();

    String bvid = "BV1tgPie2E3w";
    long aid = 114065439463311L;

    @Test
    void getVideoInfo() {
        VideoInfo videoInfo = null;
        try {
            videoInfo = bilibiliClient.getVideoInfo(bvid);
            System.out.println(videoInfo);
            videoInfo = bilibiliClient.getVideoInfo(aid);
            System.out.println(videoInfo);
        } catch (IOException e) {
            throw new BilibiliException(e);
        }
    }

    @Test
    void getVideoAv() {
        Long videoAv = bilibiliClient.getVideoAv(bvid);
        System.out.println(videoAv+"与预期一样？"+(aid==videoAv?"是":"否"));
    }

    @Test
    void getVideoBv() {
        String videoId = bilibiliClient.getVideoBv(aid);
        System.out.println(videoId+"与预期一样？"+(bvid.equals(videoId)?"是":"否"));
    }

    @Test
    void getVideoCoverUrl() {
        String videoCoverUrl = bilibiliClient.getVideoCoverUrl(bvid);
        System.out.println(videoCoverUrl);
        String videoCoverUrlByAid = bilibiliClient.getVideoCoverUrl(aid);
        System.out.println(videoCoverUrlByAid);
        System.out.println(videoCoverUrl.equals(videoCoverUrlByAid));
    }

    @Test
    void getVideoTitle() {
        String title = bilibiliClient.getVideoTitle(bvid);
        System.out.println(title);
        String titleByAid = bilibiliClient.getVideoTitle(aid);
        System.out.println(titleByAid);
        System.out.println(title.equals(titleByAid));
    }

    @Test
    void getVideoDesc() {
        String videoDesc = bilibiliClient.getVideoDesc(bvid);
        System.out.println(videoDesc);
        String videoDescByAid = bilibiliClient.getVideoDesc(aid);
        System.out.println(videoDescByAid);
        System.out.println(videoDesc.equals(videoDescByAid));
    }

    @Test
    void getVideoDuration() {
        Integer videoDuration = bilibiliClient.getVideoDuration(bvid);
        System.out.println(videoDuration);
        Integer videoDurationByAid = bilibiliClient.getVideoDuration(aid);
        System.out.println(videoDurationByAid);
        System.out.println(videoDuration.equals(videoDurationByAid));
    }

    @Test
    void getVideoPubdate() {
        long videoPubdate = bilibiliClient.getVideoPubdate(bvid);
        Date date = new Date(videoPubdate*1000);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
        System.out.println(dateFormat.format(date));
        long videoPubdateByAid = bilibiliClient.getVideoPubdate(aid);
        Date dateByAid = new Date(videoPubdateByAid*1000);
        System.out.println(dateFormat.format(dateByAid));
        System.out.println(videoPubdate==videoPubdateByAid);
    }

    @Test
    void getVideoPlayCount() {
        long videoPlayCount = bilibiliClient.getVideoPlayCount(bvid);
        System.out.println(videoPlayCount);
        long videoPlayCountByAid = bilibiliClient.getVideoPlayCount(aid);
        System.out.println(videoPlayCountByAid);
        System.out.println(videoPlayCount==videoPlayCountByAid);
    }


    @Test
    void getVideoDanmuCount() {
        long videoDanmuCount = bilibiliClient.getVideoDanmuCount(bvid);
        System.out.println(videoDanmuCount);
        long videoDanmuCountByAid = bilibiliClient.getVideoDanmuCount(aid);
        System.out.println(videoDanmuCountByAid);
        System.out.println(videoDanmuCount==videoDanmuCountByAid);
    }


    @Test
    void getVideoCommentCount() {
        long videoCommentCount = bilibiliClient.getVideoCommentCount(bvid);
        System.out.println(videoCommentCount);
        long videoCommentCountByAid = bilibiliClient.getVideoCommentCount(aid);
        System.out.println(videoCommentCountByAid);
        System.out.println(videoCommentCount==videoCommentCountByAid);
    }


    @Test
    void getVideoFavoriteCount() {
        long videoFavoriteCount = bilibiliClient.getVideoFavoriteCount(bvid);
        System.out.println(videoFavoriteCount);
        long videoFavoriteCountByAid = bilibiliClient.getVideoFavoriteCount(aid);
        System.out.println(videoFavoriteCountByAid);
        System.out.println(videoFavoriteCount==videoFavoriteCountByAid);
    }

    @Test
    void getVideoCoinCount() {
        long videoCoinCount = bilibiliClient.getVideoCoinCount(bvid);
        System.out.println(videoCoinCount);
        long videoCoinCountByAid = bilibiliClient.getVideoCoinCount(aid);
        System.out.println(videoCoinCountByAid);
        System.out.println(videoCoinCount==videoCoinCountByAid);
    }


    @Test
    void getVideoShareCount() {
        long videoShareCount = bilibiliClient.getVideoShareCount(bvid);
        System.out.println(videoShareCount);
        long videoShareCountByAid = bilibiliClient.getVideoShareCount(aid);
        System.out.println(videoShareCountByAid);
        System.out.println(videoShareCount==videoShareCountByAid);
    }


    @Test
    void getVideoCurrentRank() {
        long videoCurrentRank = bilibiliClient.getVideoCurrentRank(bvid);
        System.out.println(videoCurrentRank);
        long videoCurrentRankByAid = bilibiliClient.getVideoCurrentRank(aid);
        System.out.println(videoCurrentRankByAid);
        System.out.println(videoCurrentRank==videoCurrentRankByAid);
    }


    @Test
    void getVideoHistoryRank() {
        long videoHistoryRank = bilibiliClient.getVideoHistoryRank(bvid);
        System.out.println(videoHistoryRank);
        long videoHistoryRankByAid = bilibiliClient.getVideoHistoryRank(aid);
        System.out.println(videoHistoryRankByAid);
        System.out.println(videoHistoryRank==videoHistoryRankByAid);
    }


    @Test
    void getVideoUpUid() {
        long videoUpUid = bilibiliClient.getVideoUpUid(bvid);
        System.out.println(videoUpUid);
        long videoUpUidByAid = bilibiliClient.getVideoUpUid(aid);
        System.out.println(videoUpUidByAid);
        System.out.println(videoUpUid==videoUpUidByAid);
    }


    @Test
    void getVideoUpName() {
        String videoUpName = bilibiliClient.getVideoUpName(bvid);
        System.out.println(videoUpName);
        String videoUpNameByAid = bilibiliClient.getVideoUpName(aid);
        System.out.println(videoUpNameByAid);
    }


    @Test
    void getVideoUpFace() {
        String videoUpFace = bilibiliClient.getVideoUpFace(bvid);
        System.out.println(videoUpFace);
        String videoUpFaceByAid = bilibiliClient.getVideoUpFace(aid);
        System.out.println(videoUpFaceByAid);
    }


    @Test
    void getVideoPartCount() {
        Integer videoPartCount = bilibiliClient.getVideoPartCount(bvid);
        System.out.println(videoPartCount);
        Integer videoPartCountByAid = bilibiliClient.getVideoPartCount(aid);
        System.out.println(videoPartCountByAid);
    }

    @Test
    void getVideoIsInteraction() {
        Boolean videoIsInteraction = bilibiliClient.getVideoIsInteraction(bvid);
        System.out.println(videoIsInteraction);
        Boolean videoIsInteractionByAid = bilibiliClient.getVideoIsInteraction(aid);
        System.out.println(videoIsInteractionByAid);
    }

    @Test
    void getStaffList() {
        List<Staff> staffList = bilibiliClient.getStaffList("BV1zR28YrEMP");
        for (Staff staff : staffList) {
            System.out.println(staff);
        }
        System.out.println();
        List<Staff> staffListByAid = bilibiliClient.getStaffList(bilibiliClient.getVideoAv("BV1zR28YrEMP"));
        for (Staff staff : staffListByAid) {
            System.out.println(staff);
        }
        System.out.println(staffList.equals(staffListByAid));
    }


    @Test
    void getVideoPartTitle() {
        String videoPartTitle = bilibiliClient.getVideoPartTitle(bvid, 1);
        System.out.println(videoPartTitle);
        String videoPartTitleByAid = bilibiliClient.getVideoPartTitle(aid, 1);
        System.out.println(videoPartTitleByAid);
    }

}
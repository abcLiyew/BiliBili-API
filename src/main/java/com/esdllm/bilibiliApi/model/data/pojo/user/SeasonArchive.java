package com.esdllm.bilibiliApi.model.data.pojo.user;

import com.esdllm.bilibiliApi.model.data.pojo.video.Stat;
import lombok.Data;

/**
 * <b>合集中的一条稿件</b> —— {@code polymer/web-space/seasons_archives_list} 的 {@code archives[]}。
 *
 * <p>与 {@link ArchiveSearchResult.ArchiveItem} <b>不是同一个形状</b>：那边是"空间页的投稿表"
 * （标题/简介/分区/评论…），这边是"合集里的稿件表"（更少字段，但带 {@code stat} / {@code ctime} /
 * {@code interactive_video}）。刻意不复用，否则调用方会以为两个端点能互换。
 *
 * <p>⚠️ 时长字段名与含义都与空间页不同：这里是 {@code duration}（<b>秒数</b>），
 * 空间页是 {@code length}（形如 {@code "27:12"} 的<b>文案</b>）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SeasonArchive {

    /** 稿件 aid */
    private Long aid;

    /** 稿件 bvid */
    private String bvid;

    /** 加入合集的时间（秒级时间戳） */
    private Long ctime;

    /** 稿件发布时间（秒级时间戳） */
    private Long pubdate;

    /** 时长（<b>秒</b>） */
    private Long duration;

    /** 封面 */
    private String pic;

    /** 标题 */
    private String title;

    /** 是否互动视频 */
    private Boolean interactive_video;

    /** 是否充电专属 */
    private Integer ugc_pay;

    /** 播放进度（服务端记住的观看位置） */
    private Integer playback_position;

    /** 观看状态 */
    private Integer state;

    /** 统计数（复用视频域的 {@code Stat}：{@code view}/{@code danmaku}/{@code like}…） */
    private Stat stat;

    /** 是否更新提示 */
    private Boolean enable_vt;

    /** VT 文案 */
    private String vt_display;

    /** 是否课程视频 */
    private Integer is_lesson_video;
}

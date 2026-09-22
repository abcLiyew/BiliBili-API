package com.esdllm.bilibiliApi.model.data.pojo.live;

import lombok.Data;

import java.util.List;

/**
 * <b>直播流地址</b> —— {@code room/v1/Room/playUrl} 的 {@code data}（B1 批 #7）。
 *
 * <p>字段按 2026-09-22 实测逐字映射，实测响应（匿名、{@code qn=10000}、{@code platform=web}）：
 * <pre>
 * {"current_quality": 4, "accept_quality": ["4"], "current_qn": 10000,
 *  "quality_description": [{"qn": 10000, "desc": "原画"}],
 *  "durl": [{"url": "https://…flv?…", "length": 0, "order": 1, "stream_type": 0, "p2p_type": 1}, …]}
 * </pre>
 *
 * <p>🔴 <b>{@link #accept_quality} 是字符串数组</b>（{@code ["4"]}）——
 * 与点播域 {@code x/player/playurl} 的 {@code accept_quality}（数字数组）<b>形状不同</b>，
 * 两个域的类不能互套。
 *
 * <p>🔴 <b>直播地址给的是 {@code .flv} 流，不是可下载的整文件</b>（与视频域 MP4 通道的区别）：
 * {@link Durl#length} 实测是 {@code 0}（直播没有"总时长"），地址也带 {@code expires} 时效。
 * 播放器可以直接吃它，但<b>不要尝试"下载完再播"</b>。
 *
 * <p>⚠️ {@code durl} 通常给<b>多条</b>（实测 2 条，实测 {@code order=1/2}）——
 * 它们是同一路流的不同 CDN，<b>不是"分片要拼接"</b>（那是视频域 MP4 通道的语义）。
 * 按 {@link Durl#order} 取第一条即可，后续是备用。
 *
 * @author 饿死的流浪猫
 */
@Data
public class LiveStream {

    /** 当前清晰度数值（实测 4；与 {@link #current_qn} 不是同一套编号，别混用） */
    private Integer current_quality;

    /** 可选清晰度列表（<b>字符串数组</b>，如 {@code ["4"]}） */
    private List<String> accept_quality;

    /** 当前清晰度（实测 {@code 10000} = 原画） */
    private Integer current_qn;

    /** 清晰度中文说明（与 {@link #accept_quality} 对应） */
    private List<QualityDescription> quality_description;

    /** <b>直播流地址</b>（多条 CDN，见类注释：不是分片） */
    private List<Durl> durl;

    /** 清晰度说明 */
    @Data
    public static class QualityDescription {

        /** 清晰度数值（与 {@link LiveStream#accept_quality} 同源） */
        private Integer qn;

        /** 中文说明（实测 {@code "原画"}） */
        private String desc;
    }

    /** 一路直播流地址 */
    @Data
    public static class Durl {

        /** <b>可播放地址</b>（{@code .flv}；带 {@code expires} 时效，不要长期缓存） */
        private String url;

        /** 流的"长度"（实测恒为 {@code 0} —— 直播没有总时长，别指望它） */
        private Long length;

        /** 序号（实测从 1 开始） */
        private Integer order;

        /** 流类型（实测 0） */
        private Integer stream_type;

        /** P2P 类型（实测 1） */
        private Integer p2p_type;
    }
}

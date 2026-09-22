package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

import java.util.List;

/**
 * <b>热门视频</b> —— {@code x/web-interface/popular} 的 {@code data}（B1 批 #12）。
 *
 * <p>实测（2026-09-22）：{@code data} <b>只有两个键</b> —— {@link #list} 与 {@link #no_more}。
 * 元素形状与相关推荐 / 排行榜一致，共用 {@link VideoBrief}。
 *
 * <p>⚠️ <b>没有 {@code page} 字段</b>：所以"现在翻到第几页了"本端点不告诉你，
 * 只能由调用方自己记住传进来的 {@code pn}。这与观看历史（给别人游标）正好相反，
 * 两处都对，但用法不能互换。
 *
 * @author 饿死的流浪猫
 */
@Data
public class PopularList {

    /** 热门条目（实测传 {@code ps=20} 时给 20 条） */
    private List<VideoBrief> list;

    /** 是否已经没有更多了（{@code true} 时再翻页只会拿到空列表） */
    private Boolean no_more;
}

package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

import java.util.List;

/**
 * <b>排行榜</b> —— {@code x/web-interface/ranking/v2} 的 {@code data}（B1 批 #11）。
 *
 * <p>实测（2026-09-22）：{@code list} 95 条、{@code note} 是一个说明性字符串。
 * 元素形状与相关推荐 / 热门视频一致，因此共用 {@link VideoBrief}
 * （其中 {@link VideoBrief#getScore()} 只有本端点会给值）。
 *
 * <p>🔴 <b>本端点对 Referer 敏感，且只有"站根"这一种会挂</b> ——
 * 见 {@code BilibiliEndpoint#rankingUrl} 的实测表（两轮 4 次复现）。
 * 那是<b>库内</b>要保证的事，调用方不必关心；记在这里是提醒后人：改 Referer 会把整条链路改坏。
 *
 * @author 饿死的流浪猫
 */
@Data
public class RankingList {

    /** 榜单条目（实测 95 条） */
    private List<VideoBrief> list;

    /**
     * 说明文案（实测非空）。
     *
     * <p>⚠️ 它是"榜单口径说明"这类文案，<b>不是错误信息</b> —— 别拿它判成败，成败看外层 {@code code}。
     */
    private String note;
}

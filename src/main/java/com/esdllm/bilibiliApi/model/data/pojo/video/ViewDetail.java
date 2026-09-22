package com.esdllm.bilibiliApi.model.data.pojo.video;

import com.esdllm.bilibiliApi.model.data.BilibiliData;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.comment.Comment;
import lombok.Data;

import java.util.List;

/**
 * <b>视频一站式详情</b> —— {@code x/web-interface/view/detail} 的 {@code data}（B1 批 #1）。
 *
 * <p>它一次顶掉 B1 清单里的四项（详情 / 状态数 / 标签 / 相关推荐），是本批"用最少出站换最多能力"
 * 的关键端点。2026-09-22 实测响应有 <b>16 个顶层键</b>，本类映射其中 5 个有用的：
 *
 * <table border="1">
 *   <caption>实测规模与对应关系（本机、匿名、{@code code=0}）</caption>
 *   <tr><th>字段</th><th>JSON 键</th><th>实测规模</th><th>备注</th></tr>
 *   <tr><td>{@link #View}</td><td>{@code View}</td><td>49 键</td>
 *       <td>直接复用 {@link VideoInfo}；<b>内含完整 {@code stat}（13 项）</b> ⇒ #5 状态数 0 次出站</td></tr>
 *   <tr><td>{@link #Card}</td><td>{@code Card}</td><td>7 键</td>
 *       <td>UP 主概览；复用名片域结构，⚠️ 与 {@code CardInfo} 重叠</td></tr>
 *   <tr><td>{@link #Tags}</td><td>{@code Tags}</td><td>11 条</td><td>#2 视频标签</td></tr>
 *   <tr><td>{@link #Related}</td><td>{@code Related}</td><td>40 条</td><td>#3 相关推荐</td></tr>
 *   <tr><td>{@link #Reply}</td><td>{@code Reply}</td><td><b>{@code replies} 只有 1 条</b></td>
 *       <td>🔴 <b>只有一条热评</b>，完整评论仍须另调 {@code x/v2/reply}</td></tr>
 * </table>
 *
 * <p>🔴 <b>"一站式"最大的误读风险就是它在评论上的能力边界</b>：{@code Reply} 的
 * {@code page} 实测是 {@code null}、{@code replies} 只有一条。所以本类<b>不</b>承担"评论列表"
 * 的职责 —— 要完整评论请走 {@code Comment#getReplies}。
 *
 * <p>另有 {@code participle}（分词）/ {@code hot_share} / {@code emergency} / {@code view_addit} /
 * {@code module_ctrl} / {@code replace_recommend} 等字段映射不到这里；
 * {@code Spec} / {@code elec} / {@code guide} / {@code query_tags} 实测为 {@code null}
 * —— <b>可空是常态，不要把它们当必填</b>。未映射的键由 fastjson 直接忽略，不会报错。
 *
 * @author 饿死的流浪猫
 */
@Data
public class ViewDetail {

    /**
     * 视频主体（{@code View}）。
     *
     * <p>⚠️ 字段名首字母大写是<b>刻意的</b>：JSON 键就是 {@code View}，**字段名与键逐字相同**
     * ⇒ <b>不需要任何映射注解</b>（本库沿用同包 {@code Card.DisplayRank} 的做法）。
     *
     * <p>🔴 <b>别把"逐字同名"和"名字不同名"混为一谈</b>（2026-09-22 迁 fastjson2 时订正了这条注释）：
     * 原注释写"靠 fastjson 的 smartMatch 匹配"，其实这里用不到任何宽容度 —— 逐字对应走的是精确匹配。
     * fastjson2 <b>没有</b>名字宽容度（实测 2.0.56：只认同名），真正靠宽容度才好使的
     * {@code goto} / {@code switch} 两处已改加 {@code @JSONField(name = ...)}，
     * 并由 {@code KeyNameMappingGuardTest} 钉住。此处<b>刻意不加</b>注解 —— 加了是多余的。
     */
    private VideoInfo View;

    /** UP 主概览（{@code Card}）—— 含 {@code follower} / {@code archive_count} / {@code like_num} 等 */
    private BilibiliData Card;

    /** 视频标签（{@code Tags}）—— 实测 11 条 */
    private List<VideoTag> Tags;

    /** 附带的热评（{@code Reply}）—— ⚠️ <b>只有一条</b>，别当评论列表用 */
    private HotReply Reply;

    /** 相关推荐（{@code Related}）—— 实测 40 条 */
    private List<VideoBrief> Related;

    /**
     * 视频详情里附带的热评段。
     *
     * <p>🔴 <b>它是"看起来像评论列表、其实不是"的典型</b>（2026-09-22 实测）：
     * {@link #page} 是 {@code null}，{@link #replies} 只有一个元素。要做"评论列表"必须另调
     * {@code x/v2/reply} —— 这条边界写在这里，是因为仅凭字段名很容易以为详情已经把评论带回来了。
     */
    @Data
    public static class HotReply {

        /** 分页信息；<b>实测为 {@code null}</b>（不是对象，所以这里是 {@code Object} 而非某个分页 POJO） */
        private Object page;

        /** 附带的热评；<b>实测只有 1 条</b> */
        private List<Comment> replies;
    }
}

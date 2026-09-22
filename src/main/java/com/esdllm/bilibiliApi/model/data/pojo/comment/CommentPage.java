package com.esdllm.bilibiliApi.model.data.pojo.comment;

import lombok.Data;

import java.util.List;

/**
 * <b>评论列表（一页）</b> —— {@code x/v2/reply} 的 {@code data}（B1 批 #6）。
 *
 * <p>字段按 2026-09-22 实测逐字映射（{@code type=1&oid=<aid>&pn=1&ps=5&sort=2}，
 * 实测 {@code data} 有 13 个顶层键，本类映射其中 4 个有用的）。
 *
 * <p>🔴 <b>{@code oid} 是 {@code aid} 不是 {@code bvid}</b>（实测：传 aid 才有 {@code replies}）。
 * 门面收 bvid 时会先经 {@code view} 换算，见 {@code Comment} 门面。
 *
 * <p><b>四块内容，用途完全不同，别混读</b>：
 * <table border="1">
 *   <caption>实测形状</caption>
 *   <tr><th>字段</th><th>含义</th><th>实测规模</th></tr>
 *   <tr><td>{@link #page}</td><td>分页信息（总数 / 当前页 / 每页条数）</td>
 *       <td>{@code count=990}</td></tr>
 *   <tr><td>{@link #replies}</td><td><b>正文评论</b>（含楼中楼预览）</td><td>3 条</td></tr>
 *   <tr><td>{@link #top_replies}</td><td>置顶评论</td><td>1 条</td></tr>
 *   <tr><td>{@link #upper}</td><td>UP 主自己的评论（含置顶那条）</td><td>{@code mid} + {@code top}</td></tr>
 * </table>
 * ⇒ <b>{@link #replies} 与 {@link #top_replies} / {@link #upper} 会重叠</b>：
 * 同一条置顶评论可能同时出现在三处。要做"评论列表"请只读 {@link #replies}，
 * 否则会看到重复项（这不是 bug，是端点的形状）。
 *
 * <p>⚠️ 未映射的 {@code config} / {@code control} / {@code folder} 等是评论区 UI 与输入框状态
 * （如"勇敢的少年啊快去创造热评"这类占位文案），<b>与评论数据无关</b>，本库不收。
 *
 * @author 饿死的流浪猫
 */
@Data
public class CommentPage {

    /** 分页信息 */
    private Page page;

    /** <b>正文评论</b>（本页；楼中楼预览嵌在每条评论的 {@code replies} 里） */
    private List<Comment> replies;

    /** 置顶评论（可能为空列表） */
    private List<Comment> top_replies;

    /** UP 主相关（UP 主自己的评论 / 参与情况） */
    private Upper upper;

    /** 评论区分页信息 */
    @Data
    public static class Page {

        /** 当前页码 */
        private Integer num;

        /** 每页条数 */
        private Integer size;

        /** <b>评论总数</b>（实测 990） */
        private Integer count;

        /** 全部评论数（实测与 {@link #count} 相同） */
        private Integer acount;
    }

    /**
     * UP 主相关块。
     *
     * <p>⚠️ 实测它<b>只有</b> {@code mid} 与 {@code top} 两个键（{@code vote} 为 {@code null}）。
     * {@code top} 是 UP 主置顶的那条评论 —— <b>与 {@link #top_replies} 里的可能是同一条</b>。
     */
    @Data
    public static class Upper {

        /** UP 主 mid */
        private Long mid;

        /** UP 主的置顶评论（可能为 {@code null}） */
        private Comment top;
    }
}

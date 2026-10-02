package com.esdllm.bilibiliApi.model.data.pojo.comment;

import lombok.Data;

import java.util.List;

/**
 * <b>新版主评论列表（一页）</b> —— {@code x/v2/reply/main} 的 {@code data}（C1 批，2026-09-24）。
 *
 * <p>与旧版 {@link CommentPage}（{@code x/v2/reply}）的差别<b>只有一处但很关键</b>：
 * <b>分页方式不同</b> —— 本端点用 {@code cursor.next} 游标，旧版用 {@code pn} 页码。
 * 因为游标在响应里，{@link #cursor} <b>必须映射</b>，否则调用方无法翻页。
 * 也正因外层容器不同，本类<b>不能与 {@code CommentPage} 互相接收</b>（虽然
 * {@link #replies} / {@link #top_replies} / {@link #upper} 三块的形状一模一样）。
 *
 * <p>🔴 <b>本类最重要的用途是让调用方"看见"匿名指纹档的假终止信号</b>（2026-09-24 实测）：
 * 带匿名指纹时 {@link #replies} 只有 <b>3</b> 条，而 {@link Cursor#all_count} 是 <b>11062</b>，
 * 同时 {@link Cursor#is_end} 被写成 {@link Boolean#TRUE}。
 * （同日下午变量分离：<b>零 Cookie 反而拿到完整 20 条</b> —— 截断的触发器是指纹，不是"缺凭据"。）
 * ⇒ <b>判"是不是真到底了"不能只看 {@code is_end}</b>，要拿
 * {@code cursor.all_count} 与 {@code replies.size()} 对照。
 * 完整的对照表在 {@code BilibiliEndpoint#replyMainUrl} 的注释里。
 *
 * <p>⚠️ <b>未映射的块与 {@code CommentPage} 同一处理</b>：{@code control}（输入框状态）、
 * {@code effects}、{@code config}、{@code top}（实测三个值全是 {@code null}）、
 * {@code assist} / {@code blacklist} / {@code vote} / {@code note}（都是 0）
 * —— 全部是评论区 UI 与交互状态，<b>与评论数据无关</b>，本库不收。
 *
 * @author 饿死的流浪猫
 */
@Data
public class MainReplyPage {

    /** <b>游标</b> —— 翻页的唯一依据（回传 {@link Cursor#next}） */
    private Cursor cursor;

    /** <b>正文评论</b>（本页）。🔴 匿名指纹档只有 3 条（零 Cookie / 带凭据 20 条），翻页时可能为 {@code null} */
    private List<Comment> replies;

    /** 置顶评论（实测为空列表） */
    private List<Comment> top_replies;

    /**
     * UP 主相关。
     *
     * <p>复用 {@link CommentPage.Upper}：本端点实测<b>只给 {@code mid}</b>，
     * 所以 {@code top} 恒为 {@code null} —— 那是"端点不给"，不是数据丢了。
     */
    private CommentPage.Upper upper;

    /**
     * <b>游标块</b> —— 本端点与旧版评论列表最本质的差别。
     *
     * <p>翻页用法：首页传 {@code next=0}，之后把上一页的 {@link #next} <b>原样回传</b>。
     *
     * <p>🔴 <b>匿名指纹档的两个值不可信</b>（2026-09-24 实测）：
     * {@link #is_end} 会被写成 {@code true}（而 {@link #all_count} 是 11062），
     * 且真按它去翻第二页会拿到 {@code replies=null}（{@code all_count} 可能一并消失）。
     * 带凭据（或零 Cookie 身份）时 {@link #is_end} 才回到 {@code false}、第 2 页才有 20 条。
     */
    @Data
    public static class Cursor {

        /** 是否第一页 */
        private Boolean is_begin;

        /** 上一页游标（实测首页为 {@code 0}） */
        private Integer prev;

        /** <b>下一页游标</b> —— 翻页就回传它。⚠️ 匿名指纹档回传它拿不到数据（{@code replies=null}） */
        private Integer next;

        /**
         * 是否已到末页。
         *
         * <p>🔴 <b>匿名指纹档会谎报 {@code true}</b> —— 实测第 1 页只有 3 条、
         * 而 {@link #all_count} 是 11062，它却说"到底了"。
         * <b>别用这个字段单独判终止</b>，配合 {@link #all_count} 使用。
         */
        private Boolean is_end;

        /** 评论总数（实测 11062）—— 匿名指纹档第 1 页里唯一<b>没有</b>被篡改的计数（⚠️ 翻页时可能一并消失） */
        private Integer all_count;

        /** 服务端实际采用的排序（实测：传 {@code 0} 会被归一成 {@code 3}；传 {@code 4} 直接 {@code -400}） */
        private Integer mode;

        /** 排序文案（实测空串，不要拿它当判据） */
        private String mode_text;

        /** 会话 id */
        private String session_id;

        /** 支持的排序集合（实测 {@code [2, 3]}） */
        private List<Integer> support_mode;

        /** 名称（实测空串） */
        private String name;

        /** 分页回复信息（实测空对象 {@code {}} —— 不是 {@code null}） */
        private com.alibaba.fastjson2.JSONObject pagination_reply;
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.comment;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>评论正文</b> —— 评论条目里的 {@code content} 段（B1 批 #6）。
 *
 * <p>字段按 2026-09-22 实测逐字映射（实测该段是 {@code message} / {@code members} /
 * {@code jump_url} / {@code max_line} 四个键）。
 *
 * <p>⚠️ <b>正文里可能带 {@code @某人} 与表情的占位标记</b>：{@code message} 原文是纯文本，
 * 其中的 "@某某" 是靠 {@link #members} 里的 mid 映射到具体用户的。
 * <b>本库不改写 {@code message}</b>（改了就与 B 站页面上的原文不一致），
 * 需要渲染 @ 效果时请自行用 {@link #members} 做替换。
 *
 * <p>⚠️ {@link #jump_url} 实测<b>通常是空对象</b>（只有 UP 主评论里带 B 站内链时才有内容，
 * 且结构是"链接原文 → 卡片详情"的字典）。低频且形状随链接类型变化，本类保留原始 JSON。
 *
 * @author 饿死的流浪猫
 */
@Data
public class CommentContent {

    /** <b>评论正文</b>（纯文本，可能含换行与 {@code @} 标记；本库不做任何改写） */
    private String message;

    /** {@code @} 到的用户列表（正文里做 @ 渲染时用它） */
    private List<JSONObject> members;

    /** B 站内链卡片（形如 {@code {"https://b23.tv/ep835819": {…}}}）；实测通常为空对象 */
    private JSONObject jump_url;

    /** 折叠前最多显示行数（实测 6） */
    private Integer max_line;
}

package com.esdllm.bilibiliApi.model.data.pojo.comment;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>楼中楼（二级评论）一页</b> —— {@code x/v2/reply/reply} 的 {@code data}（B2 批 #4）。
 *
 * <p>实测响应（2026-09-22，匿名，{@code type=1&oid=<aid>&root=<rpid>&pn=1&ps=20}）：
 * <pre>
 * {"page":    {"num": 1, "size": 20, "count": 16},
 *  "upper":   {"mid": 3546774476163227},
 *  "replies": [ …16 条… ],
 *  "root":    {…被回复的那条一级评论本身（32 键）…},
 *  "config":  {"showtopic": 0, "show_up_flag": false, "read_only": false},
 *  "control": {…评论区输入框状态…}}
 * </pre>
 *
 * <p>🔴 <b>{@code root} 是一级评论本身（{@link Comment}），不是"楼中楼的容器"</b> ——
 * {@code replies} 才是这一层的内容。要"某条评论下的全部回复"就是
 * {@code replies}，而 {@code root} 用来拿"我在回复谁"。
 *
 * <p>🔴 <b>与一级评论列表（{@link CommentPage}）形状不同</b>，两处差异是实测确认的：
 * <table border="1">
 *   <caption>一级 vs 二级</caption>
 *   <tr><th></th><th>{@code data} 顶层键</th><th>{@code page}</th><th>{@code upper}</th></tr>
 *   <tr><td>{@code x/v2/reply}（一级）</td>
 *       <td>13 个，含 {@code top_replies} / {@code vote} / {@code blacklist}</td>
 *       <td>有 {@code acount}</td><td>有 {@code top}</td></tr>
 *   <tr><td>{@code x/v2/reply/reply}（二级）</td>
 *       <td>6 个：{@code config}/{@code control}/{@code page}/{@code replies}/{@code root}/{@code upper}</td>
 *       <td><b>无 {@code acount}</b></td><td><b>只有 {@code mid}</b></td></tr>
 * </table>
 * ⇒ 所以严格说这里该有自己的一套 {@code Page}/{@code Upper}。本库<b>刻意复用</b>
 * {@link CommentPage.Page} / {@link CommentPage.Upper}：字段是<b>超集</b>，
 * 缺的键反序列化后就是 {@code null}，不会出错，而少两对同形状的类。
 * <b>代价记在这里</b>：{@code page.acount} 与 {@code upper.top} 在本端点恒为 {@code null}，
 * 用之前先想一下自己在读哪个端点。
 *
 * <p>⚠️ <b>每条 {@code replies[]} 自己的 {@code replies} 字段实测为 {@code null}</b> ——
 * 楼中楼<b>只有一层</b>，不存在"三层嵌套"。别写递归。
 *
 * <p>⚠️ {@link #config} / {@link #control} 是评论区 UI 与输入框状态
 * （占位文案、能否上传图片等），<b>与评论数据无关</b>，本库不映射（保留原始 {@link JSONObject}
 * 只为"需要时能看到"）。一级评论那边是同样的处理。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SubReplyPage {

    /** 分页信息（复用一级的 {@link CommentPage.Page}；⚠️ 本端点<b>没有 {@code acount}</b>） */
    private CommentPage.Page page;

    /** 楼中楼本体（实测 16 条）；⚠️ 每一项自己的 {@code replies} 为 {@code null} */
    private List<Comment> replies;

    /** 被回复的那条一级评论本身 */
    private Comment root;

    /** UP 主信息（复用 {@link CommentPage.Upper}；⚠️ 本端点<b>只有 {@code mid}</b>） */
    private CommentPage.Upper upper;

    /** 评论区设置（UI 用，未映射） */
    private JSONObject config;

    /** 评论区输入框状态（UI 用，未映射） */
    private JSONObject control;
}

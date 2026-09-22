package com.esdllm.bilibiliApi.model.data.pojo.comment;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>一条评论</b> —— {@code x/v2/reply} 中 {@code data.replies} / {@code data.top_replies} 的元素，
 * 同时也是楼中楼（{@code replies}）的元素（B1 批 #6）。
 *
 * <p>字段按 2026-09-22 实测逐字映射（实测一条评论有 30+ 个键，本类映射其中的核心字段）。
 *
 * <p><b>三种 id 别混用</b>（这是评论区最容易搞错的地方）：
 * <table border="1">
 *   <caption>实测值举例</caption>
 *   <tr><th>字段</th><th>含义</th><th>实测</th></tr>
 *   <tr><td>{@link #rpid}</td><td><b>这条评论自己的 id</b>（主评论与楼中楼都用它）</td>
 *       <td>{@code 314487292657}</td></tr>
 *   <tr><td>{@link #oid}</td><td>所属稿件 id（<b>是 aid</b>）</td><td>{@code 117284131638286}</td></tr>
 *   <tr><td>{@link #root} / {@link #parent}</td>
 *       <td><b>楼中楼的归属</b>：主评论两者都是 {@code 0}；楼中楼的 {@code root} 指主评论、
 *           {@code parent} 指被回复的那条</td><td>主评论 {@code 0} / 楼中楼指向主评论</td></tr>
 * </table>
 * ⇒ 判断"这是主评论还是楼中楼"<b>看 {@code root} 是不是 0</b>，不要看它出现在哪个字段里
 * （{@code replies} 里嵌的也可能有多层）。
 *
 * <p>⚠️ {@link #count} 与 {@link #rcount} 是两个不同的数，实测值也不同：
 * {@code count=22} / {@code rcount=16}。前者是本条评论的总回复数口径，后者是当前可见的楼中楼条数。
 * <b>要显示"共 N 条回复"用 {@link #count}</b>（{@code count=0} 表示没人回复）。
 *
 * <p>⚠️ {@link #replies} 是<b>预览</b>，不是全部楼中楼（实测主评论给 3 条）。
 * 要完整楼中楼需要另调 {@code x/v2/reply/reply}（那是 B2 批次的事，本批不做）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class Comment {

    /** 评论 id（本条的 id，楼中楼也用它） */
    private Long rpid;

    /** 所属稿件 id（<b>aid</b>，不是 bvid） */
    private Long oid;

    /** 评论区类型（{@code 1}=视频） */
    private Integer type;

    /** 发评人 mid */
    private Long mid;

    /** 楼中楼归属：主评论为 {@code 0}，楼中楼指向所属主评论的 rpid */
    private Long root;

    /** 被回复的评论 rpid：主评论为 {@code 0} */
    private Long parent;

    /** 会话 id（B 站内部用） */
    private Long dialog;

    /** <b>总回复数</b>口径（实测 22；{@code 0} 表示无人回复） */
    private Integer count;

    /** 当前可见的楼中楼条数（实测 16，与 {@link #count} 不同） */
    private Integer rcount;

    /** 楼层号（只有主评论有） */
    private Integer floor;

    /** 状态（实测 0） */
    private Integer state;

    /** 粉丝等级门槛（实测 0） */
    private Integer fansgrade;

    /** 属性位（实测 0；UP 主置顶评论实测为 2）*/
    private Integer attr;

    /** 发布时间（秒级时间戳） */
    private Long ctime;

    /** {@link #rpid} 的字符串形态 */
    private String rpid_str;

    /** {@link #oid} 的字符串形态 */
    private String oid_str;

    /** {@link #mid} 的字符串形态 */
    private String mid_str;

    /** {@link #root} 的字符串形态 */
    private String root_str;

    /** {@link #parent} 的字符串形态 */
    private String parent_str;

    /** 点赞数（实测 2001） */
    private Integer like;

    /** 交互状态（实测 0） */
    private Integer action;

    /** 是否不可见（被折叠/删除时为 true） */
    private Boolean invisible;

    /** 发评人（昵称 / 头像 / 等级 / 认证 / 大会员） */
    private CommentMember member;

    /** 评论正文 */
    private CommentContent content;

    /** 楼中楼<b>预览</b>（不是全部；要完整请另调 {@code x/v2/reply/reply}） */
    private List<Comment> replies;

    /** 辅助位（实测 0） */
    private Integer assist;

    /** UP 主是否对这条评论点过赞 / 回复过 */
    private UpAction up_action;

    /** 回复控制信息（含<b>相对时间文案</b>与楼中楼入口文案） */
    private ReplyControl reply_control;

    /** 折叠信息（结构简单，低频，保留原始 JSON） */
    private JSONObject folder;

    /** 关联动态 id（字符串形态，实测 {@code "0"}） */
    private String dynamic_id_str;

    /** 关联笔记 id（实测 {@code "0"}） */
    private String note_cvid_str;

    /** 埋点串（不用于业务） */
    private String track_info;

    /** UP 主行为 */
    @Data
    public static class UpAction {

        /** UP 主是否点过赞 */
        private Boolean like;

        /** UP 主是否回复过 */
        private Boolean reply;
    }

    /**
     * 回复控制信息。
     *
     * <p>🔴 <b>{@link #time_desc} 是"相对时间文案"</b>（实测 {@code "2天前发布"}）——
     * 与动态列表里的 {@code module_author.pub_time} 是同一类东西：<b>它只存在于列表类端点</b>。
     * 本库另有 {@link #ctime}（绝对时间戳）可用，所以这里不把相对文案当唯一时间来源，
     * 但它在"直接展示给人看"时比自己格式化更贴近 B 站页面。
     */
    @Data
    public static class ReplyControl {

        /** <b>相对时间文案</b>（如 {@code "2天前发布"}） */
        private String time_desc;

        /** 最大行数（实测 6） */
        private Integer max_line;

        /** 楼中楼入口文案（实测 {@code "共16条回复"}） */
        private String sub_reply_entry_text;

        /** 楼中楼标题文案（实测 {@code "相关回复共16条"}） */
        private String sub_reply_title_text;

        /** 是否 UP 主置顶（只有置顶评论有该键） */
        private Boolean is_up_top;

        /** 翻译开关（实测 1） */
        private Integer translation_switch;

        /** 是否支持分享（实测 true） */
        private Boolean support_share;
    }
}

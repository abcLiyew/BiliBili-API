package com.esdllm.bilibiliApi.model.data.pojo.content;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>收藏夹内容（一页）</b> —— {@code x/v3/fav/resource/list} 的 {@code data}（B2 批 #6）。
 *
 * <p>实测响应（2026-09-22，匿名，{@code media_id=3526698880&pn=1&ps=20}）：
 * <pre>
 * {"info": {…与 x/v3/fav/folder/info 的 data 同形状…},
 *  "medias": [ {…20 条…} ],
 *  "has_more": true, "ttl": 1790057068}
 * </pre>
 *
 * <p>🔴 <b>门槛与夹的可见性绑定，且两种失败长得完全不同</b>（实测）：
 * <table border="1">
 *   <caption>匿名访问</caption>
 *   <tr><th>{@code media_id}</th><th>结果</th></tr>
 *   <tr><td>{@code 3526698880}（{@code attr=2}）</td><td>{@code code=0}，{@code medias} 20 条</td></tr>
 *   <tr><td>{@code 1095405480}（{@code attr=1}，默认夹）</td><td><b>{@code -403 访问权限不足}</b></td></tr>
 * </table>
 *
 * <p>⚠️ 这与 {@code fav/folder/created/list-all} 的 <b>"{@code code=0} 但不给列表"</b>
 * （静默空，见 {@code FavFolderList}）<b>不是一个形态</b>：
 * 这里私密夹是<b>明确报 {@code -403}</b>。两种都要抛异常，但异常说明不同。
 *
 * <p>⚠️ <b>{@code -403} 不是缺 WBI 签名</b>，是"资源权限不足"（{@code ErrorMapper} 里
 * {@code -403} 的两种成因之一）。
 *
 * <p>🔴 <b>{@link Media#season} / {@link Media#ogv} 实测 20 条全为 {@code null}</b> ——
 * 因为它们只对"番剧 / 课程"类内容有意义，而本批 20 条<code>type</code> 全是 2（视频）。
 * 所以这里是 {@link JSONObject} 而非具体 POJO：<b>形状未验证的东西不要假装有形状</b>。
 *
 * <p>🔴 <b>{@link Media#id} 是 {@code aid}，{@link Media#bvid} 是 {@code BV…}</b>，
 * 而 {@link Media#bv_id} 与 {@code bvid} <b>实测完全相同</b>（同一份数据存了两遍）——
 * 别以为 {@code bv_id} 是另一个 id。
 *
 * <p>⚠️ {@link #ttl} 实测值 {@code 1790057068} 是<b>时间戳形态</b>（不是"剩余秒数"），
 * 语义未确认，原样映射不做解释。
 *
 * @author 饿死的流浪猫
 */
@Data
public class FavResourceList {

    /** 夹自身信息（与 {@code x/v3/fav/folder/info} 同形状，共用 {@link FavFolderInfo}） */
    private FavFolderInfo info;

    /** 本页内容（实测 20 条，默认 {@code ps=20} 封顶） */
    private List<Media> medias;

    /** 是否还有下一页（实测 {@code true}） */
    private Boolean has_more;

    /** ⚠️ 实测是时间戳形态的整数，语义未确认 */
    private Integer ttl;

    /**
     * 夹内一条内容（通常是视频）。
     *
     * <p>⚠️ 「收藏夹里可以有多种内容」是端点的能力，但<b>本批只验证了 {@code type=2}（视频）</b>：
     * 实测 20/20 条都是 2。其它 {@code type}（番剧 / 课程 / 音乐 …）的字段差异<b>未验证</b>，
     * 见到新增的 {@code type} 值时请先看原始 JSON。
     */
    @Data
    public static class Media {

        /** 🔴 内容 id —— 视频时是 <b>{@code aid}</b>（实测 117283494035779） */
        private Long id;

        /** 内容类型（实测恒 {@code 2} = 视频） */
        private Integer type;

        /** 标题（实测 {@code 甜嗓翻唱『执迷不悟』所以会忙忙碌碌～}） */
        private String title;

        /** 封面地址 */
        private String cover;

        /** 简介（实测 {@code "-"} —— 注意是减号，不是空串） */
        private String intro;

        /** 分 P 序号（实测 1） */
        private Integer page;

        /** 时长，单位秒（实测 33） */
        private Integer duration;

        /** 作者 */
        private Upper upper;

        /** 内容属性位（实测 {@code 0}；含义未验证） */
        private Integer attr;

        /** 播放相关统计（⚠️ 与 {@link FavFolderInfo.CntInfo} <b>形状不同</b>） */
        private CntInfo cnt_info;

        /** 客户端跳转链接（{@code bilibili://video/…}） */
        private String link;

        /** 投稿时间（秒级时间戳） */
        private Long ctime;

        /** 发布时间（秒级时间戳，实测比 {@link #ctime} 小 1 秒） */
        private Long pubtime;

        /** 收藏时间（秒级时间戳） */
        private Long fav_time;

        /** 🔴 BV 号 —— 与 {@link #bvid} <b>实测完全相同</b>（同值两存，不是另一个 id） */
        private String bv_id;

        /** BV 号 */
        private String bvid;

        /** 番剧 / 课程信息（⚠️ 视频内容实测为 {@code null}，形状未验证） */
        private JSONObject season;

        /** OGV（番剧）信息（⚠️ 视频内容实测为 {@code null}，形状未验证） */
        private JSONObject ogv;

        /** UGC 附加信息（实测只有 {@code first_cid}） */
        private Ugc ugc;

        /** 媒体列表跳转链接（实测是 {@code bilibili://music/playlist/…}） */
        private String media_list_link;
    }

    /**
     * 夹内内容的作者。
     *
     * <p>⚠️ 与 {@link FavFolderInfo.Upper} <b>不是同一个形状</b>：这里没有
     * {@code followed} / {@code vip_type} / {@code vip_statue}，多了一个 {@code jump_link}。
     */
    @Data
    public static class Upper {

        /** 用户 mid（实测 3546774476163227） */
        private Long mid;

        /** 昵称 */
        private String name;

        /** 头像地址 */
        private String face;

        /** 跳转链接（实测空串） */
        private String jump_link;
    }

    /**
     * 夹内内容的播放统计。
     *
     * <p>⚠️ 与 {@link FavFolderInfo.CntInfo} <b>不是同一个形状</b>：多了
     * {@code danmaku} / {@code vt} / {@code play_switch} / {@code reply}，
     * 少了 {@code thumb_up} / {@code share}。
     */
    @Data
    public static class CntInfo {

        /** 被收藏数（实测 11） */
        private Long collect;

        /** 播放数（实测 306） */
        private Long play;

        /** 弹幕数（实测 0） */
        private Long danmaku;

        /** 实测 {@code 0}，含义未验证 */
        private Integer vt;

        /** 实测 {@code 0}，含义未验证 */
        private Integer play_switch;

        /** 评论数（实测 0） */
        private Long reply;

        /** 播放数的展示文本（实测 {@code "306"}，是字符串） */
        private String view_text_1;
    }

    /** UGC 附加信息。 */
    @Data
    public static class Ugc {

        /** 首个分 P 的 {@code cid}（实测 41958378053）—— 想拉这个视频的弹幕就用它 */
        private Long first_cid;
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.content;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>收藏夹目录</b> —— {@code x/v3/fav/folder/created/list-all} 的 {@code data}（B3.5 批 #6）。
 *
 * <p>实测响应（2026-09-22，带凭据）：
 * <pre>
 * {"count": 12,
 *  "list": [ {"id": 1095405480, "fid": 10954054, "mid": 497078180, "attr": 1,
 *             "title": "默认收藏夹", "fav_state": 0, "media_count": 103,
 *             "is_kid_playlist": false, "kid_playlist_desc": ""}, … ],
 *  "season": null}
 * </pre>
 *
 * <p>🔴 <b>{@link FavFolder#id} 才是"夹内内容"要用的 {@code media_id}，别用 {@link FavFolder#fid}</b>
 * —— 两个都是 id，但 {@code fid} 是另一套短 id，拿它去查内容会查不到。
 *
 * <p>⚠️ <b>本端点外层 {@code code=0} 骗人</b>（与 {@code x/space/upstat} 同类）：
 * 实测<b>匿名也返回 {@code code=0}</b>，但拿不到列表。所以判据不能看 {@code code}，
 * 要看 {@link #list} 有没有内容。
 *
 * <p>⚠️ {@link #season} 实测恒为 {@code null} —— 保留字段只为不让人以为"我们漏映射了"。
 *
 * <p>⚠️ 本类只覆盖"<b>我创建的</b>"收藏夹。别人创建的、以及我收藏的别人的夹，
 * 不在这条链路里（{@code /created/list-all} 的语义就是 creator 维度）。
 * 夹内内容（{@code x/v3/fav/resource/list}）<b>本批不做</b>，且它对<b>私密</b>夹匿名会返回
 * {@code -403} —— 那是"资源权限不足"而不是"缺 WBI 签名"，两回事（见 {@code ErrorMapper}）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class FavFolderList {

    /** 收藏夹数量（实测 12） */
    private Integer count;

    /** 收藏夹数组 */
    private List<FavFolder> list;

    /** 合集类收藏夹（实测恒 {@code null}；保留以免被误认为漏映射） */
    private JSONObject season;

    /**
     * 一个收藏夹。
     *
     * <p>该条目实测只有 9 个键，全部映射（没有可省的了）。
     */
    @Data
    public static class FavFolder {

        /** 🔴 夹 id —— <b>这才是查夹内内容要用的 {@code media_id}</b> */
        private Long id;

        /** 短 id（<b>不是</b> {@code media_id}，别混用） */
        private Long fid;

        /** 归属用户 mid */
        private Long mid;

        /**
         * 可见性属性（实测：{@code 1}=公开、{@code 2}=私密）。
         *
         * <p>⚠️ <b>它决定后续查询会不会 {@code -403}</b>：私密夹要先有本人的凭据才读得到内容。
         */
        private Integer attr;

        /** 收藏夹标题（实测默认夹名为 {@code 默认收藏夹}） */
        private String title;

        /** 是否已收藏该夹（B 站"收藏收藏夹"功能用，实测 0） */
        private Integer fav_state;

        /** 夹内内容数（实测默认夹 103） */
        private Integer media_count;

        /** 是否"青少年模式播放列表"（实测 {@code false}） */
        private Boolean is_kid_playlist;

        /** 青少年模式说明（实测空串） */
        private String kid_playlist_desc;
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.content;

import lombok.Data;

/**
 * <b>收藏夹详情</b> —— {@code x/v3/fav/folder/info} 的 {@code data}（B2 批 #5）；
 * 同时也是 {@code x/v3/fav/resource/list} 的 {@code data.info}（B2 批 #6，同一形状、同一类）。
 *
 * <p>实测响应（2026-09-22，匿名，{@code media_id=3526698880}）：
 * <pre>
 * {"id": 3526698880, "fid": 35266988, "mid": 497078180, "attr": 2, "title": "小雨绒Candy",
 *  "cover": "http://i0.hdslb.com/bfs/medialist/cover/…jpg",
 *  "upper": {"mid": 497078180, "name": "可可小绒猫", "face": "https://…jpg",
 *            "followed": false, "vip_type": 2, "vip_statue": 1},
 *  "cover_type": 0, "cnt_info": {"collect": 0, "play": 1, "thumb_up": 0, "share": 0},
 *  "type": 11, "intro": "", "ctime": 1742619602, "mtime": 1742622269, "state": 0,
 *  "fav_state": 0, "like_state": 0, "media_count": 101, "is_top": false,
 *  "is_kid_playlist": false, "kid_playlist_desc": ""}
 * </pre>
 *
 * <p>🔴 <b>{@link #attr} 决定匿名读不读得到，而它的取值方向与直觉相反</b>（2026-09-22 同一分钟实测）：
 * <table border="1">
 *   <caption>同一端点、同一时刻、只换 media_id</caption>
 *   <tr><th>{@code media_id}</th><th>标题</th><th>{@code attr}</th><th>匿名 {@code folder/info}</th><th>匿名 {@code resource/list}</th></tr>
 *   <tr><td>1095405480</td><td>默认收藏夹</td><td>1</td><td><b>{@code -403}</b></td><td><b>{@code -403}</b></td></tr>
 *   <tr><td>3526698880</td><td>小雨绒Candy</td><td>2</td><td>{@code code=0}</td><td>{@code code=0}</td></tr>
 * </table>
 * ⇒ 低位 {@code 1} 疑似"私密"位（含 {@code 1} 的读不到），而<b>不含该位不等于"公开"</b>。
 * <b>不要用 {@code attr} 反推公开性</b> —— 判据是响应码本身。
 *
 * <p>⚠️ 本条订正了 B3.5 批的一处错注：{@code FavFolderList.FavFolder#attr} 曾写"1=公开、2=私密"，
 * 与上表实测<b>正好相反</b>（见该类的 javadoc，已同步改正）。
 *
 * <p>🔴 <b>{@code -403} 在这里不是"缺 WBI 签名"</b>，是"资源权限不足"（服务端原文就是
 * {@code 访问权限不足}）。这条与 {@code ErrorMapper} 里 {@code -403} 的两种成因是同一件事。
 *
 * <p>⚠️ {@link #type} 实测 {@code 11}、{@link #state} 实测 {@code 0} ——
 * 这两个枚举值的完整含义<b>未在本库验证</b>，原样映射不做解释。
 *
 * @author 饿死的流浪猫
 */
@Data
public class FavFolderInfo {

    /** 夹 id —— <b>这才是查夹内内容要用的 {@code media_id}</b>（实测 3526698880） */
    private Long id;

    /** 短 id（<b>不是</b> {@code media_id}，别混用） */
    private Long fid;

    /** 归属用户 mid（实测 497078180） */
    private Long mid;

    /**
     * 可见性属性位。
     *
     * <p>🔴 实测：含 {@code 1} 的夹（如 {@code 1095405480 默认收藏夹}）匿名为 {@code -403}；
     * {@code 2} 的夹匿名可读。详见类注释的对照表 —— <b>别按直觉理解这个字段</b>。
     */
    private Integer attr;

    /** 收藏夹标题（实测 {@code 小雨绒Candy}） */
    private String title;

    /** 封面图地址 */
    private String cover;

    /** 归属用户（昵称 / 头像 / 是否已关注） */
    private Upper upper;

    /** 封面的类型标记（实测 {@code 0}） */
    private Integer cover_type;

    /** 夹内统计（收藏 / 播放 / 点赞 / 分享） */
    private CntInfo cnt_info;

    /** 夹的形态标记（实测 {@code 11}；含义未验证） */
    private Integer type;

    /** 简介（实测空串） */
    private String intro;

    /** 创建时间（秒级时间戳） */
    private Long ctime;

    /** 修改时间（秒级时间戳） */
    private Long mtime;

    /** 状态（实测 {@code 0}；含义未验证） */
    private Integer state;

    /** 当前凭据对该夹的收藏状态（匿名实测 {@code 0}） */
    private Integer fav_state;

    /** 当前凭据对该夹的点赞状态（匿名实测 {@code 0}） */
    private Integer like_state;

    /** 夹内内容数（实测 101） */
    private Integer media_count;

    /** 是否置顶（实测 {@code false}） */
    private Boolean is_top;

    /** 是否"青少年模式播放列表"（实测 {@code false}） */
    private Boolean is_kid_playlist;

    /** 青少年模式说明（实测空串） */
    private String kid_playlist_desc;

    /** 收藏夹归属用户。 */
    @Data
    public static class Upper {

        /** 用户 mid */
        private Long mid;

        /** 昵称 */
        private String name;

        /** 头像地址 */
        private String face;

        /** 当前凭据是否已关注（匿名实测 {@code false}） */
        private Boolean followed;

        /** 大会员类型（实测 {@code 2}） */
        private Integer vip_type;

        /** 大会员状态（实测 {@code 1}） */
        private Integer vip_statue;
    }

    /**
     * 收藏夹统计。
     *
     * <p>⚠️ 与 {@code FavResourceList.Media#cnt_info} <b>不是同一个形状</b>
     * （那里还有 {@code danmaku} / {@code reply} / {@code play_switch} 等），<b>两类不能互套</b>。
     */
    @Data
    public static class CntInfo {

        /** 被收藏数 */
        private Long collect;

        /** 播放数（实测 1） */
        private Long play;

        /** 点赞数 */
        private Long thumb_up;

        /** 分享数 */
        private Long share;
    }
}

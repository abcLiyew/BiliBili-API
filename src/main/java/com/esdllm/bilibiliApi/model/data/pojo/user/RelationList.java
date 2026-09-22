package com.esdllm.bilibiliApi.model.data.pojo.user;

import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.model.data.pojo.card.Official;
import com.esdllm.bilibiliApi.model.data.pojo.card.Vip;
import lombok.Data;

import java.util.List;

/**
 * <b>粉丝 / 关注列表</b> —— {@code x/relation/followers} 与 {@code x/relation/followings} 的 {@code data}（B3.5 批 #4）。
 *
 * <p>两个端点的<b>响应形状完全一样</b>（实测 2026-09-22），所以共用一个 POJO：
 * <pre>
 * {"list": [ {…}, {…} ], "re_version": 0, "total": 120}
 * </pre>
 * 差别只在语义方向（前者是"谁关注了我"，后者是"我关注了谁"），这在<b>方法名</b>里体现，不在类型上。
 *
 * <p>🔴 <b>本端点是"敞亮的真需登录"</b>：匿名直接 {@code code=-101 账号未登录}，
 * 无降级、拿不到任何数据 —— 与 {@code x/space/upstat} 那种"匿名 code=0 但 data 为空"的静默形态
 * 完全不同。这一点决定了调用方的处置方式：这里报 {@code -101} 就该重新登录，
 * 那边得靠"data 是不是空的"来判断。
 *
 * <p>⚠️ <b>不要把它当"查任意 UP 的粉丝榜"用</b>：{@code vmid} 实际只对<b>自己的</b> mid 有效。
 * 想看别人的粉丝，公开信息里没有这条路。
 *
 * <p>⚠️ {@code list} 里的昵称字段叫 <b>{@code uname}</b>，不是 {@code name}
 * —— 与 {@code acc/info} / {@code card} 的 {@code name} 不同名，照 {@code name} 取会永远拿到 null。
 *
 * @author 饿死的流浪猫
 */
@Data
public class RelationList {

    /** 本页用户数组 */
    private List<RelationUser> list;

    /**
     * 列表版本号（实测 <b>{@code 0}</b>，即"从未变更过"）。
     *
     * <p>🔴 <b>它不是分页令牌</b> —— 翻页仍然用 {@code pn}。
     */
    private Long re_version;

    /** 总数（实测：粉丝 120、关注 97） */
    private Integer total;

    /**
     * 列表里的一个用户。
     *
     * <p>该端点每条实测有 18 个键，这里收<b>有业务价值</b>的那些。
     */
    @Data
    public static class RelationUser {

        /** 该用户的 mid */
        private Long mid;

        /**
         * 与"我"的关系位。
         *
         * <p>实测 <b>{@code 0}</b>（未关注）。B 站内部按位组合，本库不解释语义，原样透出。
         */
        private Integer attribute;

        /** 关系建立时间（秒级时间戳） */
        private Long mtime;

        /** 分组标签（实测 {@code null}） */
        private String tag;

        /** 是否特别关注（实测 0） */
        private Integer special;

        /** 昵称（<b>注意是 {@code uname} 不是 {@code name}</b>） */
        private String uname;

        /** 头像 URL */
        private String face;

        /** 个性签名（实测空串表示没填） */
        private String sign;

        /** 是否 NFT 头像（0/1） */
        private Integer face_nft;

        /** 关注时间文案（实测是<b>空串</b>，不是时间戳） */
        private String follow_time;

        /** 认证信息（复用名片域的 {@code Official}：只映射 {@code type} / {@code desc}） */
        private Official official_verify;

        /**
         * 大会员信息（复用名片域的 {@link Vip}）。
         *
         * <p>⚠️ <b>是刻意的部分映射</b>：{@link Vip} 的字段是 camelCase 家族
         * （{@code vipType} / {@code vipStatus} / {@code accessStatus} / {@code dueRemark}），
         * 与本端点实测的 {@code vip} 对得上；但本端点还多给了 {@code vipDueDate} / {@code label} /
         * {@code avatar_subscript} / {@code nickname_color} 等，{@link Vip} 里没有 ⇒ <b>会解析成 null</b>。
         * 需要到期时间时不要看这里（{@code AccVip} 是<b>另一个</b>端点的形状，别混用，见其类注释）。
         */
        private Vip vip;

        /** 昵称渲染信息（结构较深、低频，保留原始 JSON） */
        private JSONObject name_render;

        /** NFT 头像图标（实测空串） */
        private String nft_icon;

        /** 推荐理由（低频） */
        private String rec_reason;

        /** 埋点字段（不用于业务） */
        private String track_id;

        /** 合作信息（实测空对象） */
        private JSONObject contract_info;
    }
}

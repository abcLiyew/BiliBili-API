package com.esdllm.bilibiliApi.model.data.pojo.comment;

import com.esdllm.bilibiliApi.model.data.pojo.card.LevelInfo;
import lombok.Data;

/**
 * <b>评论的发评人</b> —— 评论条目里的 {@code member} 段（B1 批 #6）。
 *
 * <p>字段按 2026-09-22 实测逐字映射（实测该段有 23 个键，本类映射展示与非展示都常用的那些）。
 *
 * <p>🔴 <b>昵称字段是 {@code uname}，不是 {@code name}</b> —— 与 {@code RelationList}、
 * {@code SearchUser} 同一口径。照 {@code name} 取会永远拿到 null（而且不报错）。
 *
 * <p>⚠️ 本段<b>与名片域 {@code Card} 是两个形状</b>，别互相套用：
 * 评论的 {@code vip} 用 camelCase（{@code vipType}/{@code vipStatus}），名片那边用
 * snake_case（{@code type}/{@code status}）—— 同一件事、两套键名，见 {@link Vip} 的说明。
 *
 * @author 饿死的流浪猫
 */
@Data
public class CommentMember {

    /** 发评人 mid */
    private Long mid;

    /** <b>昵称</b>（注意不是 {@code name}） */
    private String uname;

    /** 短 id（实测空串） */
    private String handle;

    /** 性别（实测为中文：{@code "男"} / {@code "女"} / {@code "保密"}） */
    private String sex;

    /** 个性签名（可能为空串或单个空格） */
    private String sign;

    /** 头像地址 */
    private String avatar;

    /** 硬核会员等级（实测 {@code "10000"} 字符串） */
    private String rank;

    /** 等级信息（复用名片域同形结构：{@code current_level} 等） */
    private LevelInfo level_info;

    /** 是否新版 NFT 头像（实测 0） */
    private Integer face_nft_new;

    /** 是否年度大会员（{@code 0}/{@code 1}） */
    private Integer is_senior_member;

    /** 认证信息（{@code type} + {@code desc}） */
    private OfficialVerify official_verify;

    /** 大会员信息（camelCase 形状，见类注释） */
    private Vip vip;

    /** 认证信息（与名片域的 {@code Official} 不同：这里只有 type/desc 两个键） */
    @Data
    public static class OfficialVerify {

        /** 认证类型（{@code -1}=无认证，实测；{@code 0}=个人认证） */
        private Integer type;

        /** 认证文案（无认证时为空串） */
        private String desc;
    }

    /**
     * 评论域的大会员信息。
     *
     * <p>⚠️ <b>它与名片域的 {@code card.Vip} 键名不同</b>（这里是 {@code vipType} /
     * {@code vipStatus} / {@code vipDueDate}，那边是 {@code type} / {@code status} /
     * {@code due_date}），所以<b>不能复用</b>那一个类 —— 套用会静默拿到 null。
     *
     * <p>判断"是不是大会员"看 {@link #vipStatus} 与 {@link #vipType}（两者都非 0 才是）。
     * {@code label} 那段结构很深（17 个键），低频，本库不映射。
     */
    @Data
    public static class Vip {

        /** 大会员类型（{@code 0}=非会员、{@code 1}=月度、{@code 2}=年度） */
        private Integer vipType;

        /** 大会员状态（{@code 0}=过期、{@code 1}=生效中） */
        private Integer vipStatus;

        /** 到期时间（毫秒时间戳；实测值远大于秒级，别当秒用） */
        private Long vipDueDate;

        /** 昵称配色（如 {@code #FB7299}） */
        private String nickname_color;

        /** 头像挂件角标样式（实测 1） */
        private Integer avatar_subscript;

        /** 到期提示文案（实测空串） */
        private String dueRemark;

        /** 访问状态（实测 0） */
        private Integer accessStatus;

        /** 状态警示文案（实测空串） */
        private String vipStatusWarn;

        /** 主题类型（实测 0） */
        private Integer themeType;
    }
}

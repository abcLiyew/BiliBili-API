package com.esdllm.bilibiliApi.model.data.pojo.user;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

/**
 * 大会员信息（{@code acc/info} 的 {@code data.vip}）。
 *
 * <p>⚠️ <b>不要与 {@code model.data.pojo.card.Vip} 混用</b>：那个类映射的是
 * {@code x/web-interface/card} 里的 {@code vip} —— 字段是 camelCase 的
 * （{@code vipType} / {@code vipStatus}），而本端点的 {@code vip} 是<b>下划线风格且字段更多</b>
 * （多了 {@code due_date} / {@code label} / {@code tv_vip_status} / {@code super_vip} 等）。
 * 两个端点用同一个类名会让人以为能互换，实际上会解析出一片 null。
 *
 * @author 饿死的流浪猫
 */
@Data
public class AccVip {

    /** 大会员类型（0=无、1=月度、2=年度及以上） */
    private Integer type;

    /** 大会员状态（0=无、1=有效） */
    private Integer status;

    /** 到期时间（毫秒级时间戳） */
    private Long due_date;

    /** 付费类型 */
    private Integer vip_pay_type;

    /** 主题类型 */
    private Integer theme_type;

    /** 大会员标签（结构较深，保留原始 JSON） */
    private JSONObject label;

    /** 是否展示头像角标（0/1） */
    private Integer avatar_subscript;

    /** 昵称颜色（如 {@code #FB7299}） */
    private String nickname_color;

    /** 角色值 */
    private Integer role;

    /** 头像角标图片 */
    private String avatar_subscript_url;

    /** 电视端大会员状态 */
    private Integer tv_vip_status;

    /** 电视端付费类型 */
    private Integer tv_vip_pay_type;

    /** 电视端到期时间（秒级时间戳 —— 注意与 {@code due_date} 的单位不同，实测就是这样） */
    private Long tv_due_date;

    /** 年度大会员信息（结构较深，保留原始 JSON） */
    private JSONObject super_vip;
}

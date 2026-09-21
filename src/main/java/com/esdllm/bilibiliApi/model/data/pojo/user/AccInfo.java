package com.esdllm.bilibiliApi.model.data.pojo.user;

import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.model.data.pojo.UserHonourInfo;
import com.esdllm.bilibiliApi.model.data.pojo.card.Nameplate;
import com.esdllm.bilibiliApi.model.data.pojo.card.Official;
import com.esdllm.bilibiliApi.model.data.pojo.card.Pendant;
import lombok.Data;

/**
 * <b>用户空间信息</b> —— {@code x/space/wbi/acc/info} 的 {@code data}。
 *
 * <p>字段名与响应 JSON <b>逐字对应</b>（含下划线），映射是 fastjson 按名匹配，
 * "看着像但改一个字"会让字段静默为 null —— 所以不要"顺手改名"。
 *
 * <p><b>🔴 本端点需要"WBI 签名 + 登录凭据"两样</b>（2026-09-21 两次复核一致）：
 * 匿名时<b>无论签不签名都是 {@code -352 风控校验失败}</b>，带凭据才是
 * {@code -403}（缺签名）/ {@code code=0}（齐了）。
 * ⚠️ 它一度被记成"匿名带签名就能拿到完整数据"，已推翻 —— 详见
 * {@code BilibiliEndpoint} 的实测表与"第三处翻案"。
 * 所以这张表里的 {@code is_followed} 之类字段<b>必须先有凭据</b>才会出现。
 *
 * <p>响应共有 42 个顶层键，这里映射了<b>有实际用途的那些</b>。刻意没映射的：
 * {@code attestation} / {@code contract} / {@code mcn_info} / {@code gaia_*} / {@code name_render} /
 * {@code theme} / {@code top_photo_v2} / {@code certificate_show} —— 它们要么是风控/埋点字段、
 * 要么与"展示用户信息"无关。需要时再加字段即可（纯增量，不影响既有字段）。
 *
 * <p><b>可空字段别当必填</b>：{@code tags} / {@code mcn_info} / {@code gaia_data} / {@code name_render} /
 * {@code theme} 在本机实测里就是 {@code null}；{@code user_honour_info} / {@code fans_medal} 等
 * 结构较深、低频，直接以 {@code JSONObject} 透出（不猜它的形状）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class AccInfo {

    /** 用户 mid */
    private Long mid;

    /** 昵称 */
    private String name;

    /** 性别（{@code 男} / {@code 女} / {@code 保密}） */
    private String sex;

    /** 头像 URL */
    private String face;

    /** 是否 NFT 头像（0/1） */
    private Integer face_nft;

    /** NFT 头像类型 */
    private Integer face_nft_type;

    /** 个性签名 */
    private String sign;

    /** 硬核会员等级（{@code 10000} 表示不是硬核会员） */
    private Integer rank;

    /** 当前等级（0–6） */
    private Integer level;

    /** 注册时间（秒级时间戳） */
    private Long jointime;

    /** 节操值 */
    private Integer moral;

    /** 是否被封禁（0 = 正常） */
    private Integer silence;

    /** 是否被青少年模式限制 */
    private Integer control;

    /** 拥有的硬币数 */
    private Integer coins;

    /** 是否点亮粉丝勋章 */
    private Boolean fans_badge;

    /** 粉丝勋章详情（结构较深、低频，保留原始 JSON） */
    private JSONObject fans_medal;

    /** 认证信息（复用名片域的 {@code Official}：role / title / desc / type） */
    private Official official;

    /** 大会员信息 */
    private AccVip vip;

    /** 头像挂件（复用名片域的 {@code Pendant}） */
    private Pendant pendant;

    /** 名牌（复用名片域的 {@code Nameplate}） */
    private Nameplate nameplate;

    /** 荣誉信息（复用 {@code UserHonourInfo}） */
    private UserHonourInfo user_honour_info;

    /**
     * 当前凭据是否已关注 TA。
     *
     * <p>匿名时恒为 {@code false}（不是"没关注"，是"问不出"）—— 需要登录态才有意义。
     */
    private Boolean is_followed;

    /** 空间头图（注意是<b>相对路径</b> {@code bfs/space/xxx}，不是完整 URL） */
    private String top_photo;

    /** 空间公告（结构较深、低频，保留原始 JSON） */
    private JSONObject sys_notice;

    /** 直播间状态（未开播时也有值：{@code liveStatus=0}） */
    private UserLiveRoom live_room;

    /** 生日（{@code MM-DD}，未填时为空串） */
    private String birthday;

    /** 是否年度大会员（0/1） */
    private Integer is_senior_member;

    /** 职业（低频，保留原始 JSON） */
    private JSONObject profession;

    /** 学校（低频，保留原始 JSON） */
    private JSONObject school;

    /** 系列（低频，保留原始 JSON） */
    private JSONObject series;
}

package com.esdllm.bilibiliApi.model.data.pojo.user;

import lombok.Data;

/**
 * <b>用户关系数</b> —— {@code x/relation/stat} 的 {@code data}（B1 批 #9）。
 *
 * <p>字段按 2026-09-22 实测逐字映射，实测响应（匿名、{@code vmid=2}）：
 * <pre>
 * {"mid": 2, "following": 429, "whisper": 0, "black": 0, "follower": 1429244,
 *  "fans_medal_toast": null, "fans_effect": null}
 * </pre>
 *
 * <p>🔴 <b>本端点是"匿名可读"的，别与粉丝/关注列表混为一谈</b>：
 * <table border="1">
 *   <caption>同一域内两种门槛（2026-09-22 实测）</caption>
 *   <tr><th>要什么</th><th>端点</th><th>匿名</th></tr>
 *   <tr><td><b>数量</b>（本类）</td><td>{@code x/relation/stat}</td><td><b>{@code code=0}</b>，任意用户</td></tr>
 *   <tr><td><b>名单</b></td><td>{@code x/relation/followers|followings}</td>
 *       <td>{@code -101 账号未登录}，且只对本人有效</td></tr>
 * </table>
 * ⇒ 想做"查任意 UP 的粉丝数"用本类；想取名单必须带凭据，且只拿得到自己的。
 *
 * <p>⚠️ 与名片域 {@code CardInfo#getCard} 的 {@code follower} 是同一份数据的两个入口 ——
 * 已经打过名片请求的调用方<b>不必</b>再打这一条。
 *
 * @author 饿死的流浪猫
 */
@Data
public class RelationStat {

    /** 用户 mid */
    private Long mid;

    /** <b>关注数</b>（TA 关注了多少人） */
    private Long following;

    /** 悄悄关注数（实测 0；只有本人带凭据时才可能非 0） */
    private Long whisper;

    /** 黑名单人数（实测 0） */
    private Long black;

    /** <b>粉丝数</b>（实测 1429244） */
    private Long follower;

    /** 粉丝勋章提示（实测为 {@code null}，形状随活动变化，本库不映射） */
    private Object fans_medal_toast;

    /** 粉丝特效（实测为 {@code null}，本库不映射） */
    private Object fans_effect;
}

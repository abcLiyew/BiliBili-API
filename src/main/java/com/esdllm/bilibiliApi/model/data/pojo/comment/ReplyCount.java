package com.esdllm.bilibiliApi.model.data.pojo.comment;

import lombok.Data;

/**
 * <b>评论总数</b> —— {@code x/v2/reply/count} 的 {@code data}（C1 批，2026-09-24）。
 *
 * <p>只有一个字段 {@link #count}（实测 {@code 11062}）。
 *
 * <p>📌 <b>为什么这么小还要单独一个类</b>：与 {@code NoteForbid} 同一考虑 ——
 * 端点现在只给一个数，但它是<b>独立的一条能力</b>（"这条稿件有多少评论"），
 * 用类承载比让门面返回裸 {@code long} 更好扩展（服务端将来加字段不必改签名）。
 *
 * <p>📌 <b>它还是"评论有没有被静默截断"的那把尺子</b>：带匿名指纹调 {@code x/v2/reply/main}
 * 只回 3 条、却谎报 {@code is_end=true}；<b>只有本端点给的数字不受影响</b>
 * （匿名与带凭据都是同一个值，2026-09-24 实测）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class ReplyCount {

    /** <b>评论总数</b>（实测 11062；匿名与带凭据取值相同） */
    private Long count;
}

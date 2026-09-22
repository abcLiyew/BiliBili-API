package com.esdllm.bilibiliApi.model.data.pojo.live;

import lombok.Data;

/**
 * <b>直播二级分区</b> —— {@code room/v1/Area/getList} 的一级分区 {@code list} 里的一个元素（B2 批 #8）。
 *
 * <p>实测条目（2026-09-22，匿名）：
 * <pre>
 * {"id": "86", "parent_id": "2", "old_area_id": "4", "name": "英雄联盟", "act_id": "0",
 *  "pk_status": "1", "hot_status": 0, "lock_status": "0", "pic": "https://…png",
 *  "complex_area_name": "", "parent_name": "网游", "area_type": 0}
 * </pre>
 *
 * <p>🔴 <b>数字也是字符串</b>：{@link #id} / {@link #parent_id} / {@link #act_id} /
 * {@link #pk_status} / {@link #lock_status} <b>全是 {@code String}</b>（实测 {@code "86"} 而不是 {@code 86}），
 * 而 {@link #hot_status} / {@link #area_type} 是 {@code int} —— <b>同一个对象里混着两种</b>。
 * 反序列化时按 JSON 的实际类型来，别"统一成 int"。
 *
 * <p>🔴 <b>{@link #id} 与 {@link LiveArea#id} 同名不同型</b>（这里 {@code String}，那里 {@code Integer}）
 * ⇒ 两层不能共用 POJO。用这里的分区去拉直播间时，参数是 {@code area_id=<字符串>}。
 *
 * <p>⚠️ {@link #parent_id} 与 {@link #parent_name} 是一级分区信息的<b>两份冗余表达</b>
 * （id + 名字），实测与 {@link LiveArea} 里的一致 —— 只拿到二级分区时也能反推父分区。
 *
 * <p>⚠️ {@link #complex_area_name} 实测多为空串；{@link #old_area_id} 是旧版分区体系遗留
 * （实测 {@code "4"}，与 {@link #id} 不同）。<b>不要拿 {@code old_area_id} 当 {@code area_id} 用。</b>
 *
 * @author 饿死的流浪猫
 */
@Data
public class LiveSubArea {

    /** 二级分区 id（<b>字符串</b>，实测 {@code "86"} = 英雄联盟） */
    private String id;

    /** 父分区 id（<b>字符串</b>，实测 {@code "2"} = 网游） */
    private String parent_id;

    /** 旧版分区 id（<b>字符串</b>，实测 {@code "4"}）—— <b>不是</b> {@link #id}，别混用 */
    private String old_area_id;

    /** 二级分区名（实测 {@code 英雄联盟}） */
    private String name;

    /** 活动 id（<b>字符串</b>，实测 {@code "0"}） */
    private String act_id;

    /** PK 状态（<b>字符串</b>，实测 {@code "1"}） */
    private String pk_status;

    /** ⚠️ 热门标记（<b>这里是 {@code int}</b>，实测 {@code 0}） */
    private Integer hot_status;

    /** 锁定状态（<b>字符串</b>，实测 {@code "0"}） */
    private String lock_status;

    /** 分区图标地址 */
    private String pic;

    /** 复合分区名（实测多为空串，含义未验证） */
    private String complex_area_name;

    /** 父分区名（实测 {@code 网游}） */
    private String parent_name;

    /** ⚠️ 分区类型（<b>这里是 {@code int}</b>，实测 {@code 0}，含义未验证） */
    private Integer area_type;
}

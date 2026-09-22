package com.esdllm.bilibiliApi.model.data.pojo.live;

import lombok.Data;

import java.util.List;

/**
 * <b>直播一级分区</b> —— {@code room/v1/Area/getList} 的 {@code data} 里的一个元素（B2 批 #8）。
 *
 * <p>实测响应（2026-09-22，匿名，<b>不带</b> {@code parent_area_id}）：
 * <pre>
 * {"code": 0, "message": "success",
 *  "data": [ {"id": 2, "name": "网游", "list": [ …115 个二级分区… ]},
 *            {"id": 3, "name": "手游", "list": [ …195 个… ]},
 *            … 共 12 个一级分区、合计 450 个二级分区 ]}
 * </pre>
 *
 * <p>🔴 <b>{@code parent_area_id} 这个参数是装饰品 —— 实测四格对照完全一致</b>
 * （2026-09-22，{@code .workbuddy/_b2_area_ab.py}）：
 * <table border="1">
 *   <caption>{@code room/v1/Area/getList} 的 {@code parent_area_id} A/B</caption>
 *   <tr><th>请求</th><th>{@code code}</th><th>一级分区</th><th>二级分区</th></tr>
 *   <tr><td>不带参数</td><td>0</td><td>12</td><td>450</td></tr>
 *   <tr><td>{@code parent_area_id=1}</td><td>0</td><td>12</td><td>450</td></tr>
 *   <tr><td>{@code parent_area_id=2}</td><td>0</td><td>12</td><td>450</td></tr>
 *   <tr><td>{@code parent_area_id=999}（不存在）</td><td>0</td><td>12</td><td>450</td></tr>
 * </table>
 * ⇒ <b>传一个不存在的父分区也照样返回全树</b>，说明服务端根本没读这个参数。
 * 所以本库<b>不暴露它</b>（暴露一个"传了没用"的入参只会让人以为是自己用错了）。
 * 要按父分区筛，请在返回结果上自己挑 {@link #id}。
 *
 * <p>⚠️ <b>不带参数时拿到的是一整棵树</b>：12 个一级分区，
 * 每个的 {@link #list} 是它<b>自己的</b>子分区（实测 12 份子列表互不相同）。
 * 这跟"只拿分区名列表"的直觉不一样 —— 一次调用就把 450 个二级分区全带回来了。
 *
 * <p>🔴 <b>{@link #id} 是 {@code int}，而二级分区的 {@code id} 是 {@code String}</b>
 * （本类 vs {@link LiveSubArea}）—— 同名不同型，<b>两层不能共用 POJO</b>。
 * 拿一级 id 去请求时要按数值传（如 {@code parent_area_id=2}），
 * 拿二级 id 去请求时要按字符串传（{@code area_id=86}）。
 *
 * <p>⚠️ 一级分区的数量与内容会随运营调整（实测 12 个：网游 / 手游 / 单机游戏 / 娱乐 / 电台 /
 * 虚拟主播 / 聊天室 / 生活 / 知识 / 赛事 / 互动玩法 / 购物）——
 * <b>不要把某个 id 硬编码进业务逻辑</b>，要按 {@link #name} 匹配或先查表。
 *
 * @author 饿死的流浪猫
 */
@Data
public class LiveArea {

    /** 一级分区 id（<b>数值型</b>，实测 2 / 3 / 6 / 1 / 5 / 9 / 14 / 10 / 11 / 13 / 15 / 16） */
    private Integer id;

    /** 一级分区名（实测 {@code 网游} / {@code 手游} …） */
    private String name;

    /** 该分区下的二级分区（实测 115 / 195 / 90 / 6 / 3 / 7 / 4 / 10 / 8 / 2 / 5 / 5 个） */
    private List<LiveSubArea> list;
}

package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

import java.util.Map;

/**
 * <b>全站各分区在线人数</b> —— {@code x/web-interface/online} 的 {@code data}（C2 批）。
 *
 * <p>实测响应（2026-10-02，匿名；{@code data} 顶层<b>只有这一个键</b>）：
 * <pre>
 * {"region_count": {"1": 15130, "3": 10580, "4": 59739, "5": 7896,
 *                   "160": 159948, "188": 32680, … 共 26 个键}}
 * </pre>
 *
 * <p>🔴 <b>它不是 {@link OnlineTotal} 的"另一种写法"</b>（2026-10-02 同一分钟两格实测）：
 * <table border="1">
 *   <caption>两个"在线"端点</caption>
 *   <tr><th></th><th>{@link OnlineTotal}</th><th>本类</th></tr>
 *   <tr><td>问的是</td><td><b>某一个视频</b>此刻多少人在看</td>
 *       <td><b>全站各分区</b>此刻多少人在看</td></tr>
 *   <tr><td>量级</td><td>{@code total=690}</td><td>26 个分区合计 {@code 349420}</td></tr>
 * </table>
 * ⇒ <b>差三个数量级</b>。混用会得出"这个视频有 34 万人在看"这种结论，而且不会有任何报错。
 *
 * <p>⚠️ 键是<b>字符串形式的数字</b>（分区 rid，如 {@code "1"} / {@code "160"}）——
 * 用 {@code String} 做键是刻意的：JSON 里它本来就是字符串，别为了"好看"改成 {@code Integer}。
 * ⚠️ 这套分区编号与 {@code ranking/v2} 的 {@code rid}、直播分区的 id <b>不是同一套</b>。
 *
 * @author 饿死的流浪猫
 */
@Data
public class RegionOnline {

    /** 分区 id（字符串形式的数字）→ 当前在线人数。 */
    private Map<String, Long> region_count;
}

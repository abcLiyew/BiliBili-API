package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

import java.util.List;

/**
 * <b>入站必刷</b> —— {@code x/web-interface/popular/precious} 的 {@code data}（C1 批，2026-09-24）。
 *
 * <p>实测 {@code data} 只有 4 个键（本类全映射）：
 * <table border="1">
 *   <caption>2026-09-24 实测</caption>
 *   <tr><th>字段</th><th>实测值</th></tr>
 *   <tr><td>{@link #title}</td><td>{@code "入站必刷"}</td></tr>
 *   <tr><td>{@link #media_id}</td><td>{@code 496307088}</td></tr>
 *   <tr><td>{@link #explain}</td><td>{@code "我不允许还有人没看过这98个宝藏视频！"}</td></tr>
 *   <tr><td>{@link #list}</td><td><b>98 条</b></td></tr>
 * </table>
 *
 * <p>🔴 <b>它是一份"策划单"，不是可以翻页的流</b>：{@code page} / {@code page_size}
 * 传什么都是这 98 条（五格实测，连首条 aid 都相同）⇒ 库内<b>没有分页参数</b>，
 * 详见 {@code BilibiliEndpoint#popularPreciousUrl} 的实测表。
 * 因此本类也<b>没有 {@code page} / {@code no_more} 之类的字段</b> —— 别去 {@code PopularList} 里找对照。
 *
 * <p>⚠️ {@link #list} 的元素是 {@link VideoBrief}（与热门/排行榜同一代形状），
 * 但其中<b>三个键未映射</b>：{@code achievement} / {@code ai_rcmd} / {@code ogv_info}
 * —— 实测样本里有值但无业务用途，fastjson2 会直接忽略（不报错）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class PreciousList {

    /** 专题名（实测 {@code "入站必刷"}） */
    private String title;

    /** 专题 id（实测 {@code 496307088}）—— 报给其它收藏夹类端点时用它 */
    private Long media_id;

    /**
     * 专题说明文案（实测 {@code "我不允许还有人没看过这98个宝藏视频！"}）。
     *
     * <p>⚠️ 它是<b>策划文案</b>，不是错误信息 —— 别拿它判成败。
     */
    private String explain;

    /** 榜单条目（实测 <b>98 条</b>，且不随分页参数变化） */
    private List<VideoBrief> list;
}

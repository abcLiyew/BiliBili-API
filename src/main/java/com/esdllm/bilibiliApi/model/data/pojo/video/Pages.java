package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

/**
 * <b>一个分P</b> —— 两个端点的元素共用同一个类（B1 批立，C1 批扩容）。
 *
 * <table border="1">
 *   <caption>两个来源，字段是包含关系</caption>
 *   <tr><th>来源</th><th>字段</th><th>备注</th></tr>
 *   <tr><td>{@code x/web-interface/view} 的 {@code data.pages[]}</td>
 *       <td>{@link #cid} / {@link #page} / {@link #from} / {@link #part} /
 *           {@link #duration} / {@link #dimension}</td>
 *       <td>下游 {@code BilibiliClient#getVideoPageCount} / {@code getVideoPageTitle}
 *           直接读它，是<b>冻结契约的一部分</b></td></tr>
 *   <tr><td>{@code x/player/pagelist} 的 {@code data}（<b>裸数组</b>，C1 批新增）</td>
 *       <td>上面 6 个 <b>+</b> {@link #vid} / {@link #weblink} /
 *           {@link #first_frame} / {@link #ctime}</td>
 *       <td>2026-09-24 逐键对比确认：{@code view.pages[]} 的键是本端点元素的<b>子集</b>
 *           ⇒ 同一代形状，可安全共用一个类</td></tr>
 * </table>
 * ⇒ <b>{@link #first_frame} / {@link #ctime} 只在 {@code pagelist} 那条路上有值</b>，
 * 走 {@code view} 拿到的是 {@code null} —— 那是"端点不给"，不是"数据坏了"。
 *
 * @author 饿死的流浪猫
 */
@Data
public class Pages {

    /** 分P 的 {@code cid}（<b>取弹幕、取播放地址都用它</b>，与 aid/bvid 是三套不同口径） */
    private Long cid;

    /** 第几个分P（从 1 开始） */
    private Integer page;

    /** 来源标记（实测 {@code "vupload"}） */
    private String from;

    /** <b>分P 标题</b>（实测形如 {@code "《原神》角色预告-「沃雅妮莎：此夜共沦」"}） */
    private String part;

    /** 分P 时长（秒） */
    private Long duration;

    /** 视频 id（实测空串；本库不消费） */
    private String vid;

    /** 外部链接（实测空串；投稿类视频恒空） */
    private String weblink;

    /** 分辨率 */
    private Dimension dimension;

    /**
     * 该分P 的<b>首帧图</b>（可当缩略图用）。
     *
     * <p>⚠️ 只有 {@code x/player/pagelist} 给这个键；走 {@code x/web-interface/view} 的
     * {@code pages[]} 恒为 {@code null}。
     *
     * <p>⚠️ 实测是 {@code http://} 开头的地址（与 {@code VideoBrief#getPic()} 同一情况），
     * 展示前记得过归一化。
     */
    private String first_frame;

    /** 该分P 的投稿时间（秒级时间戳）。⚠️ 同 {@link #first_frame}，只有 {@code pagelist} 给 */
    private Long ctime;
}

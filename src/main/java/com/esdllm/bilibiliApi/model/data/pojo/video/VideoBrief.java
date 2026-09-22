package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

/**
 * <b>视频摘要条目</b> —— 视频列表类响应的元素，三个端点共用同一形状（B1 批 #3 / #11 / #12）：
 * <ul>
 *   <li>{@code x/web-interface/view/detail} 的 {@code data.Related}（相关推荐，实测 40 条）</li>
 *   <li>{@code x/web-interface/ranking/v2} 的 {@code data.list}（排行榜，实测 95 条）</li>
 *   <li>{@code x/web-interface/popular} 的 {@code data.list}（热门视频，实测 20 条）</li>
 * </ul>
 * 三者字段高度重合，所以<b>刻意只做一个类</b> —— 拆三个类会让调用方为同一份数据写三套取值代码。
 *
 * <p>字段按 2026-09-22 实测逐字映射。多数字段三个端点都有；个别字段是某个端点专有，
 * 见各字段注释（典型：{@link #score} 只有排行榜给，{@link #rcmd_reason} 热门/相关会给但排行榜不给）。
 *
 * <p>⚠️ <b>不要假设 {@link #stat} 的字段齐全</b>：相关推荐与热门实测给 15 项
 * （多出 {@code vv} / {@code fav_g} / {@code like_g}），排行榜实测给 14 项。缺的字段为 null，
 * 不是 0 —— 想"当成 0 用"请自己兜底。
 *
 * @author 饿死的流浪猫
 */
@Data
public class VideoBrief {

    /** 稿件 avid */
    private Long aid;

    /** BV 号 */
    private String bvid;

    /** 默认分 P 的 cid（要取播放地址时用它） */
    private Long cid;

    /** 分 P 数量（实测 1） */
    private Integer videos;

    /** 分区 id（老口径） */
    private Integer tid;

    /** 分区 id（新口径，实测与 {@link #tid} 可能不同） */
    private Integer tidv2;

    /** 分区名（老口径；实测可能为空串） */
    private String tname;

    /** 分区名（新口径） */
    private String tnamev2;

    /** 二级分区 id（新口径） */
    private Integer pid_v2;

    /** 二级分区名（新口径） */
    private String pid_name_v2;

    /** 版权标记（{@code 1}=自制、{@code 2}=转载） */
    private Integer copyright;

    /** 封面地址（实测为 {@code http://} —— 展示前记得过 {@code normalizeUrl}） */
    private String pic;

    /** 标题 */
    private String title;

    /** 简介 */
    private String desc;

    /** 发布时间（秒级时间戳） */
    private Long pubdate;

    /** 投稿时间（秒级时间戳） */
    private Long ctime;

    /** 状态（实测 0） */
    private Integer state;

    /** 时长（秒） */
    private Integer duration;

    /** 活动 id（实测有值，不用于业务） */
    private Long mission_id;

    /** 动态文案（实测空串） */
    private String dynamic;

    /** 属性位（实测 4） */
    private Integer attribute_v3;

    /** 当前状态（实测 0） */
    private Integer current_state;

    /** 全局状态（实测 0） */
    private Integer global_state;

    /** 所属合集 id（不在合集里时为 0） */
    private Long season_id;

    /** 合集类型（实测 0） */
    private Integer season_type;

    /** 是否是番剧类内容（实测 false） */
    private Boolean is_ogv;

    /** 短链（形如 {@code https://b23.tv/BV…}） */
    private String short_link_v2;

    /** 首帧图（列表页占位图，可能为空） */
    private String first_frame;

    /** 4:3 封面（部分端点给、部分不给，可能为 null） */
    private String cover43;

    /** 发布地区（中文，可能为空串） */
    private String pub_location;

    /** 是否启用互动视频（实测 0） */
    private Integer enable_vt;

    /**
     * 排行榜名次。
     *
     * <p>⚠️ <b>只有排行榜（{@code ranking/v2}）给这个字段</b>，相关推荐与热门恒为 null ——
     * 别拿"它为 null"当"这条数据有问题"。
     */
    private Long score;

    /** UP 主 */
    private Owner owner;

    /** 统计数据（字段数随端点不同，见类注释） */
    private Stat stat;

    /** 分辨率 */
    private Dimension dimension;

    /** 权限位（相关推荐与热门给 14 项、排行榜给 14 项，形状略有差异） */
    private Rights rights;

    /**
     * 推荐理由。
     *
     * <p>🔴 <b>同一个字段在两个端点里形状不同 —— 这是本批实测挖出来的一个坑，不要按一种形状接</b>：
     * <table border="1">
     *   <caption>2026-09-22 实测</caption>
     *   <tr><th>来源</th><th>{@code rcmd_reason} 的实际类型</th><th>实测值</th></tr>
     *   <tr><td>{@code view/detail} 的 {@code Related}</td><td><b>字符串</b></td><td>{@code ""}（空串）</td></tr>
     *   <tr><td>{@code x/web-interface/popular} 的 {@code list}</td><td><b>对象</b></td>
     *       <td>{@code {"content":"百万播放","corner_mark":0}}</td></tr>
     * </table>
     * ⇒ 若声明成上面那个嵌套类，<b>相关推荐那条路径会在反序列化时直接失败</b>（字符串不是对象）。
     * 因此本字段声明为 {@code Object} —— 想读结构化内容时先 {@code instanceof JSONObject} 再取
     * {@code content} / {@code corner_mark}；字符串形态直接当展示文案。
     *
     * <p>这与 {@code SearchUser#getOfficial_verify()} 的处理是同一思路：
     * <b>形状随上下文变化的字段，保留原始值比勉强声明一个类型安全</b>。
     */
    private Object rcmd_reason;
}

package com.esdllm.bilibiliApi.model.data.pojo.content;

import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.model.data.pojo.video.Dimension;
import com.esdllm.bilibiliApi.model.data.pojo.video.Owner;
import com.esdllm.bilibiliApi.model.data.pojo.video.Stat;
import lombok.Data;

import java.util.List;

/**
 * <b>稍后再看</b> —— {@code x/v2/history/toview} 的 {@code data}（B3.5 批 #3）。
 *
 * <p>实测响应（2026-09-22，带凭据）：{@code {"count": 37, "list": [ {…}, … ]}}
 * —— 只有这两个键，<b>没有</b>分页：本端点一次把整个列表给完（不像历史那样游标翻页）。
 *
 * <p>📌 <b>分类纠错</b>：本端点一度被归进 {@code INTERFACE_PLAN.md} 的 B4"写操作"一栏，
 * 那是个分类错误 —— 它是 <b>GET 只读</b>。"稍后再看"的<b>增 / 删</b>才是写操作（另有端点，
 * 且要带 {@code csrf=bili_jct}），那些仍然不做。
 *
 * <p>🔴 <b>真需登录</b>：匿名 {@code -101 账号未登录}（实测），无降级。
 *
 * <p>⚠️ 条目字段与"视频投稿"高度相似但不完全一样：它有 {@code aid} 但<b>没有</b>顶层
 * {@code title} 之外的 {@code stat.aid} 之别（{@code stat} 里另有 aid），
 * 且多了 {@code progress}（看到哪了）与 {@code add_at}（什么时候加进来的）。
 * 复用 {@link Owner} / {@link Stat} / {@link Dimension} 是因为这三块形状与 {@code web-interface/view} 一致。
 *
 * @author 饿死的流浪猫
 */
@Data
public class ToViewList {

    /** 列表总条数（实测 37） */
    private Integer count;

    /** 条目数组（与 {@link #count} 等长 —— 该端点不分页） */
    private List<ToViewItem> list;

    /**
     * 稍后再看里的一条。
     *
     * <p>该条目实测有 <b>50+ 个键</b>（比普通稿件条目还多），这里只收有业务价值的那些 ——
     * 其中不少是历史遗留的 UI 文案字段（{@code left_text} / {@code right_text} / {@code view_text_1} 等），
     * 那些由前端根据 {@code stat} 自己渲染，没有映射价值。
     */
    @Data
    public static class ToViewItem {

        /** 稿件 aid */
        private Long aid;

        /** 稿件 bvid */
        private String bvid;

        /** 当前分 P 的 cid —— 要拿播放地址就用它 */
        private Long cid;

        /** 标题 */
        private String title;

        /** 封面（实测 {@code http://} 开头，取用前请过一次 {@code normalizeUrl}） */
        private String pic;

        /** 时长（秒） */
        private Long duration;

        /** 分 P 数 */
        private Integer videos;

        /** 分区 id */
        private Integer tid;

        /** 分区名 */
        private String tname;

        /** 二级分区 id */
        private Integer tidv2;

        /** 二级分区名 */
        private String tnamev2;

        /** 简介 */
        private String desc;

        /** 发布时间（秒级时间戳） */
        private Long pubdate;

        /** 加入"稍后再看"的时间（秒级时间戳） —— 比 {@code pubdate} 更能反映"我什么时候想看的" */
        private Long add_at;

        /** 观看进度（秒） */
        private Long progress;

        /** UP 主信息（复用 {@code video} 域） */
        private Owner owner;

        /** 互动统计（复用 {@code video} 域） */
        private Stat stat;

        /** 分辨率（复用 {@code video} 域） */
        private Dimension dimension;

        /** 短链（形如 {@code https://b23.tv/BV…}） */
        private String short_link_v2;

        /** 跳转地址（实测是 {@code bilibili://} 客户端 scheme，不是 http 链接） */
        private String uri;

        /** 分 P 数（`count` 是客户端列表用的计数，实测与 {@link #videos} 同值） */
        private Integer count;

        /** 合集标题（实测空串） */
        private String season_title;

        /** 长标题（实测空串） */
        private String long_title;

        /** 稿件状态（实测 0） */
        private Integer state;

        /** 版权类型（1=自制 2=转载） */
        private Integer copyright;

        /** 发布地（如 {@code 上海}） */
        private String pub_location;

        /** 当前分 P 详情（保留原始 JSON：字段多且低频） */
        private JSONObject page;
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.user;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>UP 主投稿列表</b> —— {@code x/space/wbi/arc/search} 的 {@code data}。
 *
 * <p>响应结构（2026-09-21 实测）：
 * <pre>
 * {"list": {"tlist": {...}, "vlist": [...]}, "page": {count,num,size},
 *  "episodic_button": {...}, "is_risk": false, "gaia_res_type": 0, "gaia_data": null}
 * </pre>
 * ⚠️ 注意 {@code page} 在 <b>{@code data.page}</b> 上，<b>不是</b> {@code data.list.page}
 * —— 后者在本端点上并不存在（写成 {@code list.page} 会永远拿到 0/分页失效）。
 *
 * <p><b>本端点需要"签名 + 登录"两样</b>（实测：匿名签名仍 {@code -352}，带凭据签名才 {@code code=0}）。
 *
 * <p>🔴 <b>它还是"合集"链路的前置</b>：{@code vlist[].season_id} 是取真实合集 id 的<b>唯一</b>可用入口
 * —— 原定的 {@code polymer/web-space/seasons/list} 已 HTTP 404 下线（见 {@code BilibiliEndpoint}）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class ArchiveSearchResult {

    /** 稿件列表容器（{@code tlist} 分类计数 + {@code vlist} 稿件数组） */
    private ArchiveList list;

    /** 分页信息（{@code count} / {@code num} / {@code size}） */
    private ArchivesPage page;

    /** 是否被风控（{@code true} 时 {@code vlist} 可能为空但 {@code code} 仍是 0） */
    private Boolean is_risk;

    /** "查看全部合集"按钮（低频，保留原始 JSON） */
    private JSONObject episodic_button;

    /** 埋点字段（不用于业务） */
    private Integer gaia_res_type;

    /** 埋点字段（不用于业务） */
    private JSONObject gaia_data;

    /**
     * 投稿列表容器。
     *
     * <p>{@code tlist} 是"按分区计数的统计表"（键为 typeid，值为 {count,name,tid}），
     * 结构随分区变化，故保留原始 JSON；真正要的是 {@link #vlist}。
     */
    @Data
    public static class ArchiveList {

        /** 分区计数表（低频，保留原始 JSON） */
        private JSONObject tlist;

        /** 稿件数组（本端点的核心） */
        private List<ArchiveItem> vlist;
    }

    /** 分页信息（{@code arc/search} 用 count/num/size 这套命名） */
    @Data
    public static class ArchivesPage {

        /** 稿件总数 */
        private Integer count;

        /** 当前页码 */
        private Integer num;

        /** 每页条数 */
        private Integer size;
    }

    /**
     * 一条投稿。
     *
     * <p>字段按实测响应逐字映射（该端点返回 38 个键，这里收<b>有业务价值</b>的那些）。
     * {@code season_id} 为 0 表示该稿件不属于任何合集 —— 这正是筛"哪些稿件能进合集"的判据。
     */
    @Data
    public static class ArchiveItem {

        /** 评论数 */
        private Long comment;

        /** 分区 id */
        private Integer typeid;

        /** 播放数 */
        private Long play;

        /** 封面（实测是 {@code http://} 开头，取用前请过一次 {@code normalizeUrl}） */
        private String pic;

        /** 副标题 */
        private String subtitle;

        /** 简介 */
        private String description;

        /** 版权（1=自制 2=转载，实测为字符串 {@code "3"}） */
        private String copyright;

        /** 标题 */
        private String title;

        /** 弹幕数 */
        private Long video_review;

        /** 作者昵称 */
        private String author;

        /** 作者 mid */
        private Long mid;

        /** 投稿时间（秒级时间戳） */
        private Long created;

        /** 时长文案（形如 {@code 27:12}） */
        private String length;

        /** 稿件 aid */
        private Long aid;

        /** 稿件 bvid */
        private String bvid;

        /** 是否"联合投稿" */
        private Integer is_union_video;

        /** 是否充电专属 */
        private Boolean is_charging_arc;

        /** 合集 id（<b>0 表示不属于任何合集</b>） */
        private Long season_id;

        /** 属性位（B 站内部用） */
        private Integer attribute;
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.user;

import lombok.Data;

import java.util.List;

/**
 * <b>视频合集内容</b> —— {@code x/polymer/web-space/seasons_archives_list} 的 {@code data}。
 *
 * <p>响应结构（2026-09-21 实测）：{@code {aids, archives, meta, page}}，其中
 * {@code aids} 与 {@code archives[].aid} 是同一批 id（前者是纯 id 数组，后者是完整对象）。
 *
 * <p><b>🔴 两个前置，缺一个都拿不到数据</b>：
 * <ol>
 *   <li>必须有<b>真实</b> {@code season_id} —— 传 {@code 1} 只会得到 {@code -404 啥都木有}。
 *       取法是先在 {@link ArchiveSearchResult} 的 {@code vlist[].season_id} 里找一个非 0 值
 *       （本库把这个链路封在 {@code UserService#findSeasonId} 里）；</li>
 *   <li>原定的"合集目录"端点 {@code polymer/web-space/seasons/list} <b>已 HTTP 404 下线</b>，
 *       所以第 1 条的前置不是可选优化，而是唯一入口。</li>
 * </ol>
 *
 * <p>✅ 与本批其它端点不同，本端点<b>既不需要签名也不需要登录</b>（匿名无签名即 {@code code=0}）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SeasonsArchives {

    /** 合集内全部稿件 id（与 {@code archives[].aid} 同源） */
    private List<Long> aids;

    /** 稿件数组 */
    private List<SeasonArchive> archives;

    /** 合集元信息（标题/封面/总数…） */
    private SeasonsMeta meta;

    /** 分页信息 */
    private SeasonsPage page;

    /**
     * 分页信息（{@code seasons_archives_list} 用 page_num/page_size/total 这套命名）。
     *
     * <p>⚠️ 与 {@link ArchiveSearchResult.ArchivesPage} 的 {@code count/num/size} <b>不是同一套</b> ——
     * B 站不同端点的分页字段名就是不统一，别想着抽象成一个类。
     */
    @Data
    public static class SeasonsPage {

        /** 当前页码 */
        private Integer page_num;

        /** 每页条数 */
        private Integer page_size;

        /** 合集总稿件数 */
        private Integer total;
    }

    /** 合集元信息 */
    @Data
    public static class SeasonsMeta {

        /** 合集 id */
        private Long season_id;

        /** 合集标题 */
        private String title;

        /**
         * 合集展示名。
         *
         * <p>实测形如 {@code 合集·去了一趟……}（带"合集·"前缀），而 {@link #title} 是纯标题 ——
         * 要展示给用户时用 {@code name}，要参与匹配时用 {@code title}。
         */
        private String name;

        /** UP 主 mid */
        private Long mid;

        /** 封面 */
        private String cover;

        /** 简介 */
        private String description;

        /** 合集内稿件总数 */
        private Integer total;

        /** 分类（B 站内部值） */
        private Integer category;

        /** 合集创建/更新时间（秒级时间戳） */
        private Long ptime;
    }
}

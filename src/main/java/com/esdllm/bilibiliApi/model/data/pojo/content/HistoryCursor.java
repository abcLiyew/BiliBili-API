package com.esdllm.bilibiliApi.model.data.pojo.content;

import lombok.Data;

import java.util.List;

/**
 * <b>观看历史</b> —— {@code x/web-interface/history/cursor} 的 {@code data}（B3.5 批 #5）。
 *
 * <p>实测响应（2026-09-22，带凭据，{@code ps=5}）：
 * <pre>
 * {"cursor": {"max": 116904916159077, "view_at": 1789988333, "business": "archive", "ps": 5},
 *  "tab":    [ {"type": "archive", "name": "视频"}, {"type": "live", "name": "直播"}, … ],
 *  "list":   [ {…}, … ]}
 * </pre>
 *
 * <p>🔴 <b>翻页靠 {@link #cursor}，不是靠页码</b> —— 它是游标式：
 * 把上一条响应的 {@code cursor} 里的 {@code max} / {@code view_at} / {@code business}
 * <b>原样回传</b>即得下一页。所以 {@code cursor} 必须映射成 POJO；
 * 只映射 {@code list} 会让调用方永远只能看到第一页，而且<b>不报任何错</b>。
 *
 * <p>🔴 <b>真需登录</b>：匿名 {@code -101 账号未登录}（实测），无降级。
 *
 * <p>⚠️ 每个条目里的 {@link HistoryItem#history} 才是"这个视频在播放器里的身份"
 * （{@code oid} / {@code cid} / {@code bvid} / {@code page}）；<b>条目标题与作者在条目本层</b>，
 * 两者不在同一个对象里，取用别找错层。
 *
 * @author 饿死的流浪猫
 */
@Data
public class HistoryCursor {

    /** 下一页游标（回传 {@link CursorPos} 的三个值即翻页） */
    private CursorPos cursor;

    /** 分类页签（实测 3 项：视频 / 直播 / 专栏） */
    private List<Tab> tab;

    /** 本页历史条目 */
    private List<HistoryItem> list;

    /**
     * 游标位置。
     *
     * <p>翻下一页的做法：把本对象的 {@code max} / {@code view_at} / {@code business}
     * 作为<b>同名 query 参数</b>再请求一次。三个值缺一不可 —— 只带 {@code max}
     * 在跨业务（如从视频翻到直播）时会跳错位置。
     */
    @Data
    public static class CursorPos {

        /** 游标主键（实测是个 15 位大整数） */
        private Long max;

        /** 该条目的观看时间（秒级时间戳） */
        private Long view_at;

        /** 业务类型（{@code archive}=视频 / {@code live}=直播 / {@code article}=专栏） */
        private String business;

        /** 本页条数（服务端回显，与请求的 {@code ps} 一致） */
        private Integer ps;
    }

    /** 页签（用于在历史页切换"视频 / 直播 / 专栏"） */
    @Data
    public static class Tab {

        /** 业务类型，对应 {@link CursorPos#business} 的取值 */
        private String type;

        /** 中文名（如 {@code 视频}） */
        private String name;
    }

    /**
     * 一条观看记录。
     *
     * <p>该条目实测有 22 个键，这里收<b>有业务价值</b>的那些。
     */
    @Data
    public static class HistoryItem {

        /** 标题（历史页标题可能被平台改写，正式标题看 {@link #long_title} 或 {@code history} 里的 bvid） */
        private String title;

        /** 长标题（实测空串） */
        private String long_title;

        /** 封面（实测是 {@code http://} 开头，取用前请过一次 {@code normalizeUrl}） */
        private String cover;

        /** 多封面（实测 {@code null}） */
        private List<String> covers;

        /** 跳转地址（形如 {@code https://www.bilibili.com/video/BV…}） */
        private String uri;

        /** <b>播放器身份</b>（oid / cid / bvid / page 都在这一层） */
        private HistoryRef history;

        /** 分 P 数 */
        private Integer videos;

        /** UP 主昵称 */
        private String author_name;

        /** UP 主头像 */
        private String author_face;

        /** UP 主 mid */
        private Long author_mid;

        /** 观看时间（秒级时间戳） */
        private Long view_at;

        /** 观看进度（秒） */
        private Long progress;

        /** 角标文案（实测空串） */
        private String badge;

        /** 展示用标题（实测空串） */
        private String show_title;

        /** 时长（秒） */
        private Long duration;

        /** 当前分 P 文案（实测空串） */
        private String current;

        /** 分 P 总数 */
        private Integer total;

        /** 更新说明（实测空串） */
        private String new_desc;

        /** 是否已看完（0/1） */
        private Integer is_finish;

        /** 是否已收藏（0/1） */
        private Integer is_fav;

        /** 条目 id（实测与 {@code history.oid} 同值） */
        private Long kid;

        /** 分区名（如 {@code 日常}） */
        private String tag_name;

        /** 直播状态（视频条目恒 0） */
        private Integer live_status;
    }

    /**
     * 播放器身份。
     *
     * <p>⚠️ {@code oid} 与 {@code kid} 实测同值，但语义不同（前者是历史记录的业务 id），
     * 别互相替代。
     */
    @Data
    public static class HistoryRef {

        /** 业务 id（视频条目即 aid） */
        private Long oid;

        /** 番剧剧集 id（视频条目为 0） */
        private Long epid;

        /** 稿件 bvid */
        private String bvid;

        /** 分 P 序号 */
        private Integer page;

        /** 分 P cid —— 要拿播放地址就用它 */
        private Long cid;

        /** 分 P 标题 */
        private String part;

        /** 业务类型（{@code archive} / {@code live} / {@code article}） */
        private String business;

        /** 设备类型（B 站内部值，实测 3 = 网页端） */
        private Integer dt;
    }
}

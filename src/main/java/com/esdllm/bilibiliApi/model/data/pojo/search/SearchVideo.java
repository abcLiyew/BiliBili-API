package com.esdllm.bilibiliApi.model.data.pojo.search;

import com.esdllm.bilibiliApi.parse.HighlightStripper;
import lombok.Data;

import java.util.List;

/**
 * 搜索结果里的<b>视频</b>条目 —— {@code wbi/search/type?search_type=video} 的 {@code result[]}
 * 与 {@code wbi/search/all/v2} 中 {@code result_type=video} 段的元素（两者形状一致）。
 *
 * <p>字段按 2026-09-21 实测响应逐字映射（该条目有 60+ 个键，这里收<b>有业务价值</b>的那些）。
 * 没映射的多数是直播/番剧/课程才用的字段（{@code area} / {@code ep_size} / {@code live_status}…），
 * 需要时再加即可（纯增量）。
 *
 * <p>⚠️ <b>{@link #title} 是原始值，带 {@code <em class="keyword">} 高亮标签</b>。
 * 直接展示请用 {@link #getCleanTitle()}（剥离后的），别自己写正则 ——
 * 这一条是 {@code SearchService} 的硬约定，也是 {@code HighlightStripper} 存在的原因。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SearchVideo {

    /** 结果类型，恒为 {@code video} */
    private String type;

    /** 稿件 aid（数字 id） */
    private Long id;

    /** UP 主昵称 */
    private String author;

    /** UP 主 mid（这个值超过 int 范围，必须用 Long） */
    private Long mid;

    /** 分区 id（字符串形态） */
    private String typeid;

    /** 分区名 */
    private String typename;

    /** 网页地址（{@code http://www.bilibili.com/video/av…}，需要时过 {@code normalizeUrl}） */
    private String arcurl;

    /** 稿件 aid（与 {@link #id} 同值，B 站两个键都给） */
    private Long aid;

    /** 稿件 bvid */
    private String bvid;

    /** 标题（<b>带高亮标签</b>，见类注释） */
    private String title;

    /** 简介（可能也带高亮标签） */
    private String description;

    /** 封面（可能带高亮标签，也可能是 {@code //} 开头的协议相对地址） */
    private String pic;

    /** 播放数 */
    private Long play;

    /** 弹幕数 */
    private Long danmaku;

    /** 收藏数 */
    private Long favorites;

    /** 点赞数 */
    private Long like;

    /** 评论数 */
    private Long review;

    /** 弹幕数（与 {@link #danmaku} 同值，B 站别名） */
    private Long video_review;

    /** 投稿时间（秒级时间戳） */
    private Long pubdate;

    /** 时长文案（形如 {@code 2909:39}，注意可能是<b>分钟</b>数） */
    private String duration;

    /** 标签（逗号分隔的串） */
    private String tag;

    /** 是否付费内容 */
    private Boolean badgepay;

    /** UP 主头像 */
    private String upic;

    /** 命中字段列表（B 站标注"这个词命中了哪里"） */
    private List<String> hit_columns;

    /** 排名序号 */
    private Integer rank_index;

    /**
     * 剥离高亮标签后的标题。
     *
     * <p>惰性计算：不改动 {@link #title} 字段本身，需要原始值时随时可取。
     *
     * @return 干净标题
     */
    public String getCleanTitle() {
        return HighlightStripper.strip(title);
    }

    /**
     * 剥离高亮标签后的简介。
     *
     * @return 干净简介
     */
    public String getCleanDescription() {
        return HighlightStripper.strip(description);
    }
}

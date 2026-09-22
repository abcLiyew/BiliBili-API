package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

/**
 * <b>视频标签</b>（B1 批 #2）—— 出现在两个地方，形状一致：
 * <ul>
 *   <li>{@code x/tag/archive/tags?bvid=} 的 {@code data} —— ⚠️ <b>顶层就是数组</b>，不是对象；</li>
 *   <li>{@code x/web-interface/view/detail} 的 {@code data.Tags}（本库走这条，省一次出站）。</li>
 * </ul>
 *
 * <p>字段按 2026-09-22 实测响应逐字映射（实测该条目有 18 个键）。多数标签是"BGM 标签"
 * （{@code tag_type=bgm} 且带 {@code music_id}），真正的分区标签排在后面。
 *
 * @author 饿死的流浪猫
 */
@Data
public class VideoTag {

    /** 标签 id（实测有 {@code 0} 的情况 —— 那是 BGM 类标签，不是"错数据"） */
    private Long tag_id;

    /**
     * 标签名。
     *
     * <p>⚠️ 实测 {@code message} 字段的值是 {@code "0"} 而不是 {@code "OK"} ——
     * 本端点的外层 message 不可靠，<b>判成功只看 {@code code} 与 data</b>。
     */
    private String tag_name;

    /** 封面（通常为空串） */
    private String cover;

    /** 头部封面（通常为空串） */
    private String head_cover;

    /** 标签简介（通常为空串） */
    private String content;

    /** 短简介（通常为空串） */
    private String short_content;

    /** 标签类型（实测 {@code 3}；BGM 类标签另有 {@code tag_type} 字段承载 {@code bgm}） */
    private Integer type;

    /** 标签名以外的细分类型（BGM 类标签实测为 {@code bgm}） */
    private String tag_type;

    /** 音乐 id（BGM 类标签才有，形如 {@code MA471265943482274588}） */
    private String music_id;

    /** 点击跳转地址（BGM 类标签指向音乐详情页） */
    private String jump_url;

    /** 状态（实测 0） */
    private Integer state;

    /** 创建时间（秒级时间戳） */
    private Long ctime;

    /** 是否已关注该标签（{@code 0}/{@code 1}） */
    private Integer is_atten;

    /** 标签的统计数据（观看 / 使用 / 关注） */
    private Count count;

    /** 点赞数（实测 0） */
    private Integer likes;

    /** 点踩数（实测 0） */
    private Integer hates;

    /** 属性位（实测 0） */
    private Integer attribute;

    /** 当前凭据是否点过赞（{@code 0}/{@code 1}） */
    private Integer liked;

    /** 当前凭据是否点过踩（{@code 0}/{@code 1}） */
    private Integer hated;

    /** 附加属性位（实测 0） */
    private Integer extra_attr;

    /** 标签统计（实测三项都是 0 —— 该端点不给真实计数，需要计数请另找入口） */
    @Data
    public static class Count {

        /** 观看数 */
        private Integer view;

        /** 使用数 */
        private Integer use;

        /** 关注数 */
        private Integer atten;
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

import java.util.List;

/**
 * <b>AI 视频摘要</b> —— {@code x/web-interface/view/conclusion/get} 的 {@code data}。
 *
 * <p>响应结构（2026-09-21 实测，{@code BV1cSec6tEux}）：
 * <pre>
 * {"code": 0, "status": 0, "stid": 796069221560950154, "like_num": …, "dislike_num": …,
 *  "model_result": {"result_type": 2,
 *                   "summary": "…一句话总纲…",
 *                   "outline":   [{"title": "…", "timestamp": 1, "part_outline": [{"timestamp": 16, "content": "…"}]}],
 *                   "subtitle":  [{"title": "", "timestamp": 1, "part_subtitle": [{"start_timestamp": 0, "end_timestamp": 1, "content": "…"}]}]}}
 * </pre>
 *
 * <p><b>🔴 本端点要"签名 + 登录"两样，缺一不可</b>（2026-09-21 的 2×2 实测）：
 * <table border="1">
 *   <caption>四种组合</caption>
 *   <tr><th>条件</th><th>结果</th><th>含义</th></tr>
 *   <tr><td>匿名 · 无签名</td><td>{@code -403}</td><td>看起来像"权限不足"</td></tr>
 *   <tr><td>匿名 · 有签名</td><td>{@code -101}</td><td>🔴 <b>签名对了，暴露出真正缺的是登录</b></td></tr>
 *   <tr><td>凭据 · 无签名</td><td>{@code -403}</td><td>登录也救不了缺签名</td></tr>
 *   <tr><td>凭据 · 有签名</td><td><b>{@code code=0}</b></td><td>两者齐了才有摘要</td></tr>
 * </table>
 * 这一组对照是"两步判据"的又一例证：只看匿名 {@code -403} 会得出"这是权限问题、做不了"的结论。
 *
 * <p>⚠️ <b>别把 {@code status} 当判据</b>：实测 {@code status=0} 且 {@code code=0} 时摘要正常返回。
 * 要判断"有没有摘要"，判 {@link #getSummary()} 是否为空（这是唯一稳定的判据）——
 * 与"有没有拿到 SESSDATA"同一条思路：判内容，不判标注。
 *
 * @author 饿死的流浪猫
 */
@Data
public class AiSummary {

    /** 摘要接口自身的状态码（实测 0；与"有没有摘要"无关，别用它判断） */
    private Integer code;

    /** 状态（实测 0；语义未见于文档，本库不据此判断） */
    private Integer status;

    /** 本次摘要会话 id（B 站内部用） */
    private Long stid;

    /** 点赞数 */
    private Integer like_num;

    /** 点踩数 */
    private Integer dislike_num;

    /** <b>摘要正文</b>（真正的内容都在这下面） */
    private Result model_result;

    /**
     * 是否有可用的摘要内容。
     *
     * <p>判据只有一条：{@code model_result.summary} 非空 —— 见类注释。
     *
     * @return true 表示拿到了非空摘要
     */
    public boolean hasSummary() {
        return model_result != null && model_result.getSummary() != null
                && !model_result.getSummary().isBlank();
    }

    /** 摘要正文（含总纲 / 分段大纲 / 字幕级分段） */
    @Data
    public static class Result {

        /** 结果类型（实测 2，B 站内部值） */
        private Integer result_type;

        /** <b>一句话总纲</b>（最常用的字段） */
        private String summary;

        /** 分段大纲（每个分段带标题 + 时间点 + 若干要点） */
        private List<Outline> outline;

        /** 字幕级分段（粒度更细，可能很长） */
        private List<Subtitle> subtitle;
    }

    /** 大纲里的一个分段 */
    @Data
    public static class Outline {

        /** 分段标题 */
        private String title;

        /** 分段起始时间（秒） */
        private Integer timestamp;

        /** 该分段下的要点 */
        private List<OutlinePart> part_outline;
    }

    /** 大纲分段里的一条要点 */
    @Data
    public static class OutlinePart {

        /** 该要点对应的时间点（秒） */
        private Integer timestamp;

        /** 要点内容 */
        private String content;
    }

    /** 字幕级分段的一个容器 */
    @Data
    public static class Subtitle {

        /** 容器标题（实测为空串） */
        private String title;

        /** 时间点（秒） */
        private Integer timestamp;

        /** 字幕片段 */
        private List<SubtitlePart> part_subtitle;
    }

    /** 一条字幕片段 */
    @Data
    public static class SubtitlePart {

        /** 起始时间（秒） */
        private Integer start_timestamp;

        /** 结束时间（秒） */
        private Integer end_timestamp;

        /** 字幕文本 */
        private String content;
    }
}

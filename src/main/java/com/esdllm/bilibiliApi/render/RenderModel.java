package com.esdllm.bilibiliApi.render;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态长图渲染用的归一化视图模型。
 *
 * <p>它是"JSON 响应"与"画布"之间的中间层：上游把两套/三套 schema 的差异收敛成这个结构，
 * 下游渲染器只认它。这样换端点、换 schema 都不会波及绘制代码。
 *
 * <p>与冻结模型 {@code BilibiliDynamicResp} 的关系：那个模型是给 XatiiBot 读
 * {@code dynamic_id_str} / {@code uname} 用的，字段远不够画图（没有段落顺序、没有图片宽高、
 * 没有 emoji 贴图），所以渲染链路单独用本模型，两者互不影响。
 */
@Data
public class RenderModel {

    /**
     * 动态类型（来源：{@code item.type}）。
     *
     * <p>渲染器按 {@link Type} 选绘制分支：{@link #DRAW} 走"图文 + 图片网格"；
     * {@link #VIDEO}/{@link #FORWARD} 走"视频卡片"（封面图 + 标题 + BV 号 + 互动数）；
     * 其它类型（ARTICLE / LIVE / UNKNOWN）目前一律按 VIDEO 分支兜底。
     */
    public enum Type {
        /** 图文 + 多图（DYNAMIC_TYPE_DRAW） */
        DRAW,
        /** 视频投稿（DYNAMIC_TYPE_AV） */
        VIDEO,
        /** 转发动态（DYNAMIC_TYPE_FORWARD —— 原动态类型留 orig.type） */
        FORWARD,
        /** 专栏文章（DYNAMIC_TYPE_ARTICLE） —— 当前按 VIDEO 分支兜底 */
        ARTICLE,
        /** 直播推荐（DYNAMIC_TYPE_LIVE_RCMD） —— 当前按 VIDEO 分支兜底 */
        LIVE,
        /** 未识别（端点回空 modules，且 fallback 没拿到 type 时降级） */
        UNKNOWN
    }

    /** 动态类型（默认 DRAW，渲染器据此选绘制分支） */
    private Type type = Type.DRAW;

    /** 动态 ID */
    private String dynamicId;

    /**
     * 动态标题（来源：opus 端点的 {@code MODULE_TYPE_TITLE.module_title.text}）。
     *
     * <p><b>为什么单独一个字段而不是塞进 {@link #blocks}</b>：标题在版式上属于"头部信息"，
     * 画在作者行下面、正文上面，字号字重都和正文不同；放进 blocks 会让"正文顺序"这个概念被污染。
     *
     * <p>注意：图文类动态<b>多数没有标题</b>（B 站只有"带标题的动态 / opus 文章"才有），
     * 所以为 {@code null} 是常态，渲染器要按"没有就跳过"处理。
     * 另外 {@code v1/detail} 端点<b>根本不返回</b>标题字段，只有 opus 端点有。
     */
    private String title;

    /** 作者信息 */
    private Author author = new Author();

    /** 是否置顶动态 */
    private boolean top;

    /** 按原始顺序排列的内容块（正文段落与图片段落的相对顺序由此保留） */
    private List<Block> blocks = new ArrayList<>();

    /** 互动数 */
    private Stat stat = new Stat();

    /** 作者 */
    @Data
    public static class Author {
        /** 昵称 */
        private String name;
        /** 头像地址 */
        private String faceUrl;
        /** 发布时间，绝对时间文本（如 {@code 2026年09月13日 14:25}） */
        private String pubTime;
        /** 勋章/标签文案（如"十年大会员"），可为 null */
        private String badge;
    }

    /** 互动数据 */
    @Data
    public static class Stat {
        private long forward;
        private long comment;
        private long like;
    }

    /** 内容块标记接口 */
    public interface Block {
    }

    /** 文本段落 */
    @Data
    public static class TextBlock implements Block {
        /** 段落内的富文本片段（顺序即展示顺序） */
        private List<Span> spans = new ArrayList<>();
    }

    /** 图片段落 */
    @Data
    public static class ImageBlock implements Block {
        private List<Pic> pics = new ArrayList<>();
    }

    /** 片段类型 */
    public enum SpanKind {
        /** 普通文本（可能含 unicode emoji，已由 loader 拆成 EMOJI 片段） */
        TEXT,
        /** 表情：B 站自有表情走 imageUrl（icon_url），unicode emoji 走 CDN 贴图 */
        EMOJI,
        /** 话题 #{@code #xxx#} */
        TOPIC,
        /** 网页链接 */
        LINK,
        /** @某人 */
        AT,
        /** 其它富文本（投票/商品等），按纯文本兜底渲染 */
        OTHER
    }

    /** 行内片段 */
    @Data
    public static class Span {
        private SpanKind kind = SpanKind.TEXT;
        /** 展示文本 */
        private String text;
        /** kind=EMOJI 时的贴图地址；为空时渲染器会画占位框而不是静默丢字 */
        private String imageUrl;
    }

    /** 一张图片 */
    @Data
    public static class Pic {
        private String url;
        private int width;
        private int height;
    }
}

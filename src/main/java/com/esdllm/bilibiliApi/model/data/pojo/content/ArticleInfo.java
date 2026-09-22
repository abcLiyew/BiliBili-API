package com.esdllm.bilibiliApi.model.data.pojo.content;

import lombok.Data;

import java.util.List;

/**
 * <b>专栏（图文）信息</b> —— {@code x/article/viewinfo} 的 {@code data}（B4 批 #1，2026-09-22）。
 *
 * <p>实测响应（2026-09-22，匿名，{@code id=4538122}）= <b>23 个键</b>：
 * <pre>
 * {"stats": {"view": 3231, "favorite": 120, "like": 35, "dislike": 0,
 *            "reply": 9, "share": 8, "coin": 2, "dynamic": 4},
 *  "title": "辉煌禄来——从2.8（3.5）a~2.8（3.5）e3",
 *  "banner_url": "", "mid": 5842315, "author_name": "凯申物流公司CEO",
 *  "is_author": false, "in_list": false,  // 注意：这是【零 Cookie】快照，两个字段归因不同，见下方 2×2
 *  "type": 0, "video_url": "", "location": "",
 *  "image_urls": ["https://i0.hdslb.com/bfs/article/banner/….png"],
 *  "origin_image_urls": ["（同上）"],
 *  "shareable": true, "disable_share": false,
 *  "show_later_watch": false, "show_small_window": false,
 *  "like": 0, "coin": 0, "favorite": false, "attention": false, "pre": 0, "next": 0,
 *  "share_channels": [{"name": "QQ", "picture": "…", "share_channel": "QQ"}, …共 5 项]}
 * </pre>
 *
 * <p>🔴 <b>整个响应分三层含义，混用必错</b>（2026-09-22 匿名／凭据 A/B 实测）：
 * <ol>
 *   <li><b>① 全局统计</b> —— 只在 {@link #stats} 里。
 *       <b>要"这篇文章有多少赞"读 {@code stats.like}（实测 35），不是 {@link #like}（实测 0）。</b>
 *       {@link #stats} 共 8 项：{@code view} / {@code favorite} / {@code like} / {@code dislike} /
 *       {@code reply} / {@code share} / {@code coin} / {@code dynamic}。</li>
 *   <li><b>② "我视角"的交互状态</b> —— {@link #like} / {@link #coin} / {@link #favorite} /
 *       {@link #attention}。它们表达"<b>当前凭据</b>对这篇文章做过什么"，
 *       所以匿名恒为 {@code 0}／{@code false}。⚠️ 实测本库这次的凭据恰好也没互动过，
 *       <b>两次读到的值相同</b> —— 这不代表"登录后才有值"，而是这两个字段本来就与我有关，
 *       而"我"没做过。判断"要不要注入凭据"别拿这四个字段当证据。</li>
 *   <li><b>③ 两个布尔字段</b> —— {@link #is_author} / {@link #in_list}。
 *       <b>它们归因不同，不能一起理解</b>（见下）。</li>
 * </ol>
 *
 * <p>🔴 <b>{@link #is_author} / {@link #in_list} 归因完全不同</b> —— 2026-09-22 真机 2×2 实测：
 *
 * <table border="1">
 *   <caption>2×2 归因（cv4538122；作者 mid=5842315，凭据 mid=497078180 ⇒ 四格都是"读别人的文章"）</caption>
 *   <tr><th>请求</th><th>{@code is_author}</th><th>{@code in_list}</th></tr>
 *   <tr><td>零 Cookie</td><td>false</td><td><b>false</b></td></tr>
 *   <tr><td>仅匿名指纹</td><td>false</td><td><b>true</b></td></tr>
 *   <tr><td>仅凭据（不含指纹）</td><td><b>true</b></td><td><b>true</b></td></tr>
 *   <tr><td>凭据 + 指纹</td><td><b>true</b></td><td><b>true</b></td></tr>
 * </table>
 *
 * <ul>
 *   <li>{@link #is_author} 与凭据<b>完全同向</b>（四格一致）⇒ 可以当"<b>已登录</b>"的指示器；
 *       但它与"<b>是不是作者</b>"无关 —— 四格都在读<b>别人的</b>文章（{@link #mid} 是
 *       {@code 5842315}，与凭据自身的 mid 不相等），有凭据时照样 {@code true}。</li>
 *   <li>{@link #in_list} <b>不跟凭据走，只跟"请求有没有会话标识"走</b>：零 Cookie 是 {@code false}，
 *       一旦带上匿名指纹（或凭据）就变 {@code true} ⇒ <b>它连"已登录"都指示不了</b>。
 *       ⚠️ 本库运行时<b>必然</b>携带匿名指纹（{@code buvid3} / {@code buvid4}）⇒ 真实调用拿到的
 *       {@code in_list} 通常是 {@code true}；而夹具是零 Cookie 快照（{@code false}），两者本就不同。
 *       ⇒ <b>任何"用 in_list 判断未收藏 / 未登录"的代码都是错的。</b></li>
 * </ul>
 *
 * <p>⇒ 本库对这两个字段<b>只做原样映射，不做任何语义承诺</b>，也不基于它们做任何判断。
 * <p>⚠️ {@link #type}（实测 {@code 0}）、{@link #banner_url} / {@link #video_url} /
 * {@link #location}（实测都是空串）—— 这些字段的完整取值域<b>未在本库验证</b>，原样映射。
 *
 * <p>⚠️ {@link #pre} / {@link #next} 是<b>上一篇／下一篇的专栏号</b>，无相邻文章时实测为 {@code 0}。
 * 它们是 {@code 0} 而不是 {@code null}，别用 {@code != null} 判断"有没有下一篇"。
 *
 * @author 饿死的流浪猫
 */
@Data
public class ArticleInfo {

    /**
     * <b>全局统计</b>（文章维度的汇总数）。
     *
     * <p>🔴 这是本响应里<b>唯一</b>的全局统计 —— 与下面"我视角"的
     * {@link ArticleInfo#like} / {@link ArticleInfo#coin} 是两回事。
     */
    private Stats stats;

    /** 标题（实测带 emoji 与全角字符，原样保留） */
    private String title;

    /** 头图／横幅地址（实测空串） */
    private String banner_url;

    /** <b>作者</b>的 mid（实测 {@code 5842315}）—— 不是"当前凭据"的 mid */
    private Long mid;

    /** 作者昵称（实测 {@code 凯申物流公司CEO}） */
    private String author_name;

    /**
     * ⚠️ <b>语义与字面不符，勿据此判断作者身份</b>：它与<b>凭据</b>完全同向
     * （无凭据 {@code false}，有凭据 {@code true}）⇒ 只能当"<b>已登录</b>"的指示器。
     * 但它与"<b>是不是作者</b>"无关 —— 真机四格都在读<b>别人的</b>文章
     * （{@link #mid} = {@code 5842315}，与凭据自身 mid 不等），有凭据时照样 {@code true}。
     * 见类注释的 2×2 归因表。
     */
    private Boolean is_author;

    /** 正文图片地址列表（实测 1 条，{@code i0.hdslb.com/bfs/article/banner/…}） */
    private List<String> image_urls;

    /** 正文图片的原图地址列表（实测与 {@link #image_urls} 同值） */
    private List<String> origin_image_urls;

    /** 是否允许分享（实测 {@code true}） */
    private Boolean shareable;

    /** 是否显示"稍后再看"按钮（实测 {@code false}） */
    private Boolean show_later_watch;

    /** 是否显示"小窗播放"按钮（实测 {@code false}） */
    private Boolean show_small_window;

    /**
     * ⚠️ <b>语义与字面不符，勿据此判断"我收藏了没"</b>：它<b>不跟凭据走，只跟"请求有没有
     * 会话标识"走</b> —— 零 Cookie 是 {@code false}，一旦带上匿名指纹（{@code buvid3} /
     * {@code buvid4}）或凭据就变 {@code true}（<b>未收藏也是 {@code true}</b>）
     * ⇒ <b>它连"已登录"都指示不了</b>。⚠️ 本库运行时<b>必然</b>携带匿名指纹
     * ⇒ 真实调用拿到的通常是 {@code true}，而夹具是零 Cookie 快照（{@code false}）。
     * 见类注释的 2×2 归因表。
     */
    private Boolean in_list;

    /** 上一篇专栏号；没有时实测 {@code 0}（<b>不是</b> {@code null}） */
    private Long pre;

    /** 下一篇专栏号；没有时实测 {@code 0}（<b>不是</b> {@code null}） */
    private Long next;

    /** 分享渠道列表（实测 5 项：QQ / QQ空间 / 微信 / 朋友圈 / 微博） */
    private List<ShareChannel> share_channels;

    /** 文章类型标记（实测 {@code 0}；取值域未验证） */
    private Integer type;

    /** 视频地址（实测空串；图文专栏用不到） */
    private String video_url;

    /** 定位信息（实测空串） */
    private String location;

    /** 是否禁用分享（实测 {@code false}） */
    private Boolean disable_share;

    /**
     * 🔴 <b>"我"是否点过赞</b>（我视角），<b>不是全局点赞数</b> —— 匿名实测 {@code 0}。
     * 要全局数请读 {@link Stats#like}（同一篇文章实测 35）。
     */
    private Integer like;

    /** 🔴 "我"是否收藏（我视角）；匿名实测 {@code false} */
    private Boolean favorite;

    /** 🔴 "我"是否关注了作者（我视角）；匿名实测 {@code false} */
    private Boolean attention;

    /** 🔴 "我"投的币数（我视角）；匿名实测 {@code 0} */
    private Integer coin;

    /**
     * <b>文章全局统计</b> —— 本响应里唯一与"我"无关的一组数。
     *
     * <p>⚠️ 与 {@code FavFolderInfo.CntInfo} <b>不是同一形状</b>（那是什么收藏／播放／点赞／分享），
     * 两者<b>不能互套</b>。
     */
    @Data
    public static class Stats {

        /** 阅读数（实测 3231） */
        private Long view;

        /** 被收藏数（实测 120） */
        private Long favorite;

        /** 点赞数（实测 35） */
        private Long like;

        /** 点踩数（实测 0） */
        private Long dislike;

        /** 评论数（实测 9） */
        private Long reply;

        /** 分享数（实测 8） */
        private Long share;

        /** 投币数（实测 2） */
        private Long coin;

        /** 转发成动态的次数（实测 4） */
        private Long dynamic;
    }

    /** 一个分享渠道。 */
    @Data
    public static class ShareChannel {

        /** 渠道显示名（实测 {@code QQ} / {@code QQ空间} / {@code 微信} / {@code 朋友圈} / {@code 微博}） */
        private String name;

        /** 渠道图标地址 */
        private String picture;

        /** 渠道标识（实测 {@code QQ} / {@code QZONE} / {@code WEIXIN} / {@code WEIXIN_MONMENT} / {@code SINA}） */
        private String share_channel;
    }
}

package com.esdllm.bilibiliApi.parse;

import java.util.regex.Pattern;

/**
 * 搜索结果的<b>高亮标签剥离</b>。
 *
 * <p>B 站的搜索接口会把命中的关键词用 HTML 包起来返回，例如：
 * <pre>
 * 【2026最新】B站最全最细的软件&lt;em class="keyword"&gt;测试&lt;/em&gt;全套教程
 * </pre>
 * 这个串直接展示会露出标签、直接参与字符串比较会匹配失败（"看起来一样的标题"其实不相等）。
 * 所以<b>门面返回之前必须剥离</b>，而不是把问题留给每个调用方。
 *
 * <p>处理两件事：
 * <ol>
 *   <li>去掉所有形如 {@code <...>} 的标签；</li>
 *   <li>反转义常见 HTML 实体（{@code &amp;} / {@code &lt;} / {@code &gt;} / {@code &quot;} / {@code &#39;} / {@code &nbsp;}）。</li>
 * </ol>
 * <b>用正则而不是 HTML 解析器</b>：这里只需要处理 B 站自己产出的、形态极其有限的一小段文本，
 * 为它引入 jsoup 之类的依赖不划算（本库刚从依赖里移除过 jsoup）。
 * 也正因为输入有限，正则足够安全 —— 不适用于"解析任意第三方 HTML"。
 *
 * @author 饿死的流浪猫
 */
public final class HighlightStripper {

    /** 任意 HTML 标签（B 站只产出 {@code <em class="keyword">} 与 {@code </em>}，这里放宽到全部标签） */
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private HighlightStripper() {
        throw new AssertionError("static helper; do not instantiate");
    }

    /**
     * 剥离高亮标签并反转义实体。
     *
     * @param text 原始文本，可为 null
     * @return 干净文本；输入为 null 时返回 null（保持"没有值"与"空串"的区别）
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String plain = TAG.matcher(text).replaceAll("");
        if (plain.indexOf('&') < 0) {
            return plain;
        }
        // &amp; 必须最后处理：先处理它会把 &amp;lt; 变成 &lt;，再被后续规则变成 <
        return plain
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }
}

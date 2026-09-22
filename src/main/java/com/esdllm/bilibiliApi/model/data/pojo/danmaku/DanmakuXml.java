package com.esdllm.bilibiliApi.model.data.pojo.danmaku;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>弹幕列表（一次拉取的全量）</b> —— {@code x/v1/dm/list.so} 的响应体（B2 批 #1）。
 *
 * <p>🔴 <b>这是本库唯一返回 XML 的端点，所以它没有 {@code code}/{@code data} 外层</b> ——
 * {@code ResponseParserSupport.requireData} 那套 JSON 判据在这里<b>完全不适用</b>。
 * 成功与否只能看"有没有解析出 {@code <i>}` 根元素"。
 *
 * <p>实测响应头（2026-09-22，匿名）：
 * <pre>
 * Content-Encoding: deflate
 * </pre>
 * 实测形态（截断）：
 * <pre>
 * &lt;?xml version="1.0" encoding="UTF-8"?&gt;&lt;i&gt;&lt;chatserver&gt;chat.bilibili.com&lt;/chatserver&gt;
 * &lt;chatid&gt;41961327629&lt;/chatid&gt;&lt;mission&gt;0&lt;/mission&gt;&lt;maxlimit&gt;1000&lt;/maxlimit&gt;
 * &lt;state&gt;0&lt;/state&gt;&lt;real_name&gt;0&lt;/real_name&gt;&lt;source&gt;k-v&lt;/source&gt;
 * &lt;d p="85.33300,1,25,16777215,1789865587,0,99b04462,2204311331669302272,10"&gt;何须要这众生知道？&lt;/d&gt;…
 * </pre>
 *
 * <p>✅ <b>{@code Content-Encoding: deflate} 由 HTTP 层自动解开</b>（2026-09-22 实测）：
 * 库里拿到的 {@code String} 已经是 {@code <?xml} 开头的明文，<b>不需要自己
 * {@code InflaterInputStream}</b>。所以本类只负责"从明文 XML 里切片"。
 * （这条是本批推翻了计划的一处 —— 计划原本要求业务层自己解压。）
 *
 * <p>⚠️ <b>{@link #maxlimit} 是硬上限</b>：实测见过 {@code 300} 与 {@code 1000} 两种取值
 * （随视频设置而变），即单个 {@code cid} 最多只会给这么多条。<b>没有分页参数</b> ——
 * 想要更多弹幕这个端点给不了，这不是本库没做。
 *
 * <p>⚠️ <b>零弹幕是一个合法形态</b>：实测一个没弹幕的视频，整份 XML 就是
 * <pre>
 * &lt;?xml version="1.0" encoding="UTF-8"?&gt;&lt;i&gt;&lt;chatserver&gt;chat.bilibili.com&lt;/chatserver&gt;
 * &lt;chatid&gt;41958378053&lt;/chatid&gt;&lt;mission&gt;0&lt;/mission&gt;&lt;maxlimit&gt;300&lt;/maxlimit&gt;
 * &lt;state&gt;0&lt;/state&gt;&lt;real_name&gt;0&lt;/real_name&gt;&lt;/i&gt;
 * </pre>
 * ⇒ 此时 {@link #danmaku} 是空列表，<b>不是错误</b>（服务层不会抛异常）。
 *
 * <p>⚠️ 文本里的 XML 实体（{@code &amp;amp;} {@code &amp;lt;} 等）由
 * {@link #parse(String)} 还原，调用方拿到的 {@link DanmakuItem#getText()} 是**可直接展示的明文**。
 *
 * @author 饿死的流浪猫
 */
@Data
public class DanmakuXml {

    /** 弹幕服务器（实测 {@code chat.bilibili.com}） */
    private String chatserver;

    /** 弹幕对应的 {@code cid}（实测 {@code 41961327629}；与请求里的 {@code oid} 相同） */
    private Long chatid;

    /** 实测 {@code 0} */
    private Integer mission;

    /** ⚠️ 单次拉取的条数上限（实测 {@code 1000}）—— 见类注释，没有翻页参数 */
    private Integer maxlimit;

    /** 实测 {@code 0} */
    private Integer state;

    /** 实测 {@code 0} */
    private Integer real_name;

    /** 弹幕来源标记（实测 {@code k-v}） */
    private String source;

    /** 弹幕条目（实测 371 条） */
    private List<DanmakuItem> danmaku;

    /** {@code <d p="…">文本</d>}；同时容忍自闭合写法 {@code <d p="…"/>} */
    private static final Pattern D_PATTERN =
            Pattern.compile("<d\\s+p=\"([^\"]*)\"\\s*(?:/>|>(.*?)</d>)", Pattern.DOTALL);

    /**
     * 从响应体明文 XML 切片出弹幕列表。
     *
     * <p>🔴 <b>不依赖任何 XML 库</b>（不引 jsoup、不用 {@code DocumentBuilder}）：
     * 这个端点的结构固定到可以手写切片，而引入一个 XML 解析器只为读 9 个标量 + 一个重复元素
     * 不划算（本库的依赖克制是老约定）。代价是：<b>只认双引号属性、不处理 CDATA</b> ——
     * 这两点实测都成立，真变了会在这里抛异常而不是静默给空列表。
     *
     * <p>放在 POJO 而不是 Service 里，是为了让"XML → 对象"这段能<b>零出站</b>单测
     * （见 {@code DanmakuItemTest}），不必起 Mock 服务器。
     *
     * @param xml 响应体明文，可为 {@code null}
     * @return 解析结果；{@link #danmaku} 保证非 {@code null}（可能为空列表）
     * @throws BilibiliException 当响应体不像弹幕 XML 时（含"忘了解压"这种形态，
     *                           message 里会直接说明，见下）
     */
    public static DanmakuXml parse(String xml) {
        if (xml == null || xml.isEmpty()) {
            throw new BilibiliException(0, "解析弹幕 XML 失败：响应体为空",
                    "弹幕端点正常时至少会给出 <i> 根元素；空响应通常是请求被拦截或 oid 无效");
        }
        if (!xml.contains("<i>") && !xml.startsWith("<?xml")) {
            // 形状不对 —— 最常见的成因是"拿到的还是 deflate 字节被 toString 成了乱码"。
            int shown = Math.min(xml.length(), 60);
            throw new BilibiliException(0,
                    "解析弹幕 XML 失败：响应体不像 XML（开头为 " + xml.substring(0, shown) + "）",
                    "若开头是乱码，说明拿到的是未解压的 deflate 字节流；"
                            + "本库的 HTTP 层会自动解压，出现这种形态说明 Content-Encoding 变了，"
                            + "需要给 BilibiliHttp 补字节出口");
        }

        DanmakuXml result = new DanmakuXml();
        result.chatserver = tag(xml, "chatserver");
        result.chatid = toLong(tag(xml, "chatid"));
        result.mission = toInt(tag(xml, "mission"));
        result.maxlimit = toInt(tag(xml, "maxlimit"));
        result.state = toInt(tag(xml, "state"));
        result.real_name = toInt(tag(xml, "real_name"));
        result.source = tag(xml, "source");

        List<DanmakuItem> list = new ArrayList<>();
        Matcher m = D_PATTERN.matcher(xml);
        while (m.find()) {
            list.add(DanmakuItem.of(unescape(m.group(1)), unescape(m.group(2))));
        }
        result.danmaku = list;
        return result;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? unescape(m.group(1)) : null;
    }

    /**
     * 还原 XML 实体。
     *
     * <p>为什么必须做：弹幕正文里的 {@code &} {@code <} {@code >} 会被服务端转义，
     * 直接展示给用户就会看到 {@code &amp;amp;} 这种原文。
     */
    private static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int end = s.indexOf(';', i + 1);
            if (end < 0 || end - i > 12) {
                out.append(c);
                i++;
                continue;
            }
            String name = s.substring(i + 1, end);
            String replaced = namedEntity(name);
            if (replaced != null) {
                out.append(replaced);
            } else if (name.startsWith("#")) {
                Integer cp = codePoint(name.substring(1));
                if (cp == null) {
                    out.append(s, i, end + 1);
                } else {
                    out.appendCodePoint(cp);
                }
            } else {
                out.append(s, i, end + 1);
            }
            i = end + 1;
        }
        return out.toString();
    }

    private static String namedEntity(String name) {
        return switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> null;
        };
    }

    private static Integer codePoint(String body) {
        try {
            if (body.startsWith("x") || body.startsWith("X")) {
                return Integer.valueOf(body.substring(1), 16);
            }
            return Integer.valueOf(body);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

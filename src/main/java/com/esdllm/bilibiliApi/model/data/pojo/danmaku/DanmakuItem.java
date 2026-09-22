package com.esdllm.bilibiliApi.model.data.pojo.danmaku;

import lombok.Data;

/**
 * <b>一条弹幕</b> —— {@code x/v1/dm/list.so} 响应 XML 里一个 {@code <d>} 元素（B2 批 #1）。
 *
 * <p>本库<b>唯一</b>不返回 JSON 的数据端点，所以本类<b>不由 fastjson 填</b>，
 * 而是由 {@link #of(String, String)} 解析 XML 属性后赋值（见下）。
 *
 * <p>📌 <b>为什么叫 {@code DanmakuItem} 而不是 {@code Danmaku}</b>：门面类已经叫
 * {@code com.esdllm.bilibiliApi.bilibiliApi.Danmaku}，
 * 同名的 POJO 会让调用方必须靠全限定名区分。命名与本库既有的 {@code ToViewItem} 一致。
 *
 * <p>实测原始形态（2026-09-22，匿名，{@code oid=<cid>}）：
 * <pre>
 * &lt;d p="85.33300,1,25,16777215,1789865587,0,99b04462,2204311331669302272,10"&gt;何须要这众生知道？&lt;/d&gt;
 * </pre>
 *
 * <p><b>{@code p} 的段位含义</b>（实测 371 条<b>全部 9 段</b>，无一条 7 段或 8 段）：
 * <table border="1">
 *   <caption>p 属性段位</caption>
 *   <tr><th>#</th><th>字段</th><th>含义</th><th>实测取值</th></tr>
 *   <tr><td>0</td><td>{@link #time}</td><td>出现时间（秒，带 5 位小数）</td><td>{@code 85.33300}</td></tr>
 *   <tr><td>1</td><td>{@link #mode}</td><td>弹幕模式</td><td>{@code 1}/ {@code 4} / {@code 5}</td></tr>
 *   <tr><td>2</td><td>{@link #fontSize}</td><td>字号</td><td>{@code 25}</td></tr>
 *   <tr><td>3</td><td>{@link #color}</td><td>颜色（十进制 RGB）</td><td>{@code 16777215}=白</td></tr>
 *   <tr><td>4</td><td>{@link #timestamp}</td><td>发送时间（秒级时间戳）</td><td>{@code 1789865587}</td></tr>
 *   <tr><td>5</td><td>{@link #pool}</td><td>弹幕池</td><td>{@code 0}</td></tr>
 *   <tr><td>6</td><td>{@link #userHash}</td><td>发送者 ID 的哈希（<b>不是</b> mid，无法反查用户）</td><td>{@code 99b04462}</td></tr>
 *   <tr><td>7</td><td>{@link #dmid}</td><td>弹幕 ID（19 位雪花号）</td><td>{@code 2204311331669302272}</td></tr>
 *   <tr><td>8</td><td>{@link #lane}</td><td>⚠️ <b>推断字段</b>，见下</td><td>{@code 1}…{@code 10}</td></tr>
 * </table>
 *
 * <p>🔴 <b>老文档写"7 段"，实测是 9 段</b> ⇒ 解析<b>不能断言段数</b>。
 * {@link #of(String, String)} 按<b>下标取、越界给 {@code null}</b> 的容错方式处理：
 * 将来服务端加段或减段都只会让个别字段为空，<b>不会让整条弹幕解析失败</b>。
 *
 * <p>⚠️ <b>{@link #lane} 是推断，不是确认</b>：实测取值恒在 {@code 1}–{@code 10} 且分布均匀
 * （371 条里每个值约 37 条），形状像屏幕上的"轨道号"；但没有官方文档支持这个命名，
 * 本库保留它只为"9 段信息不丢"，<b>不要用它做业务判断</b>。
 *
 * <p>⚠️ {@link #userHash} 只有 8 位十六进制，<b>与 mid 无对应关系</b>（B 站刻意做的匿名化），
 * 想按用户筛弹幕是做不到的 —— 这是端点本身的限制，不是本库没映射。
 *
 * @author 饿死的流浪猫
 */
@Data
public class DanmakuItem {

    /** 原始 {@code p} 属性（9 段全量保留；任何解码字段有疑问都可以回到它） */
    private String p;

    /** 弹幕正文（{@code <d>} 的文本节点；XML 实体已由解析层还原） */
    private String text;

    /** 出现时间，单位秒（实测带 5 位小数） */
    private Double time;

    /**
     * 弹幕模式。
     *
     * <p>常见取值：{@code 1}/{@code 2}/{@code 3}=滚动，{@code 4}=底部，{@code 5}=顶部，
     * {@code 6}=逆向，{@code 7}=高级，{@code 8}=代码，{@code 9}=BAS。
     * <b>本批实测只见到 {@code 1}（325 条）/{@code 5}（44 条）/{@code 4}（2 条）</b>，
     * 其余取值源自社区文档、<b>未在本库复现</b>。
     */
    private Integer mode;

    /** 字号（实测 {@code 25}） */
    private Integer fontSize;

    /** 颜色，十进制 RGB（实测 {@code 16777215}=白、{@code 16646914}、{@code 15138834}、{@code 9033215}） */
    private Integer color;

    /** 发送时间（秒级时间戳） */
    private Long timestamp;

    /** 弹幕池（实测恒 {@code 0}；{@code 0}=普通池，{@code 1}=字幕池，{@code 2}=特殊池） */
    private Integer pool;

    /** 发送者哈希（8 位十六进制，<b>无法反查 mid</b>） */
    private String userHash;

    /** 弹幕 ID（雪花号，19 位） */
    private Long dmid;

    /** ⚠️ 推断字段：疑似屏幕轨道号（实测 {@code 1}–{@code 10} 均匀分布）—— 未确认，别依赖 */
    private Integer lane;

    /**
     * 由 {@code p} 属性与文本节点构造一条弹幕。
     *
     * <p>🔴 <b>容错语义</b>：段数不足时对应字段为 {@code null}，数字段不可解析时同样为
     * {@code null}，<b>任何情况下都不抛异常</b>。原因见类注释 —— 老文档与实际段数已经不一致过一次，
     * 解析器不该因为服务端改格式就整批失败。
     *
     * <p>放在 POJO 里（而不是 Service 里）是刻意的：段位布局就是这些字段的定义，
     * 两者放在一起才不会各自漂移；而且这样能在<b>零出站</b>的单测里直接验证解析规则。
     *
     * @param p    原始 {@code p} 属性，可为 {@code null}
     * @param text 弹幕正文，可为 {@code null}
     * @return 绝不返回 {@code null}
     */
    public static DanmakuItem of(String p, String text) {
        DanmakuItem d = new DanmakuItem();
        d.p = p;
        d.text = text;
        if (p == null || p.isEmpty()) {
            return d;
        }
        String[] seg = p.split(",");
        d.time = toDouble(seg, 0);
        d.mode = toInt(seg, 1);
        d.fontSize = toInt(seg, 2);
        d.color = toInt(seg, 3);
        d.timestamp = toLong(seg, 4);
        d.pool = toInt(seg, 5);
        d.userHash = at(seg, 6);
        d.dmid = toLong(seg, 7);
        d.lane = toInt(seg, 8);
        return d;
    }

    private static String at(String[] seg, int i) {
        if (i >= seg.length) {
            return null;
        }
        String s = seg[i].trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer toInt(String[] seg, int i) {
        String s = at(seg, i);
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(String[] seg, int i) {
        String s = at(seg, i);
        if (s == null) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double toDouble(String[] seg, int i) {
        String s = at(seg, i);
        if (s == null) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

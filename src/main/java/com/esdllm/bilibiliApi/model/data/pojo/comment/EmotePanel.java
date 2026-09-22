package com.esdllm.bilibiliApi.model.data.pojo.comment;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>表情包面板</b> —— {@code x/emote/user/panel/web} 的 {@code data}（B2 批 #7）。
 *
 * <p>实测响应（2026-09-22，{@code business=reply}）：
 * <pre>
 * {"setting": {"recent_limit": 150, "attr": -1, "focus_pkg_id": 1,
 *              "schema": "https://www.bilibili.com/h5/mall/emoji-package/home?navhide=1"},
 *  "packages": [ {"id": 1, "text": "小黄脸", "url": "https://…png", "mtime": 1784613867,
 *                 "type": 1, "attr": 322, "meta": {"size": 1, "item_id": 958},
 *                 "emote": [ {…225 个…} ], "flags": {"added": true, "preview": true},
 *                 "label": null, "package_sub_title": "", "ref_mid": 0,
 *                 "resource_type": 0}, … 共 68 个包 ]
 * </pre>
 *
 * <p>🔴 <b>本端点需要凭据 —— 匿名拿不到任何表情包</b>（2026-09-22 双向实测）：
 * <table border="1">
 *   <caption>同一分钟、只换凭据</caption>
 *   <tr><th>请求</th><th>{@code code}</th><th>{@code data.packages}</th></tr>
 *   <tr><td>匿名</td><td>{@code 0}</td><td><b>{@code null}</b></td></tr>
 *   <tr><td>带凭据</td><td>{@code 0}</td><td><b>{@code list} × 68</b></td></tr>
 * </table>
 * ⇒ 🔴 <b>它属于"B 形态"（{@code code=0} + 空数据），必须由 service 抛异常</b>，
 * 不能安静返回空列表 —— 否则"我没带凭据"会被下游当成"这个用户没有表情包"。
 * 这条与 {@code x/space/upstat}（{@code code=0} + {@code data={}}）同一类，见 {@code MEMORY} 红线 #14。
 *
 * <p>⚠️ <b>{@code code=0} + {@code message="0"}</b> —— 这个端点的 {@code message} 不是 {@code "OK"}，
 * 是字符串 {@code "0"}。<b>不要用 message 判成败</b>（任何端点都不该）。
 *
 * <p>⚠️ {@code business} 实测传 {@code reply} 与 {@code dynamic} <b>返回完全一致</b>
 * （都是同一批 68 个包）。所以本库把它做成可传参但默认 {@code reply}，
 * <b>不声称两者结果不同</b> —— 实测是相同的。
 *
 * <p>⚠️ <b>响应体很大</b>：实测 425912 字符（68 包 × 若干表情，总计约 1555 个表情）。
 * 如果只需要"某个包的表情"，请在本类结果上自己筛，<b>端点没有按包查询的参数</b>。
 *
 * <p>⚠️ 字段不是定形的（实测包 {@code meta} 有 4 种键组合、表情 {@code meta} 有 3 种），
 * 所以可选字段一律保留、缺就是 {@code null}，<b>不要假设它们都在</b>。
 *
 * @author 饿死的流浪猫
 */
@Data
public class EmotePanel {

    /** 面板设置 */
    private Setting setting;

    /**
     * 表情包列表（带凭据实测 68 个）。
     *
     * <p>🔴 <b>匿名时本字段为 {@code null}</b> —— 见类注释，service 必须把它翻译成异常。
     */
    private List<Package> packages;

    /** 面板设置。 */
    @Data
    public static class Setting {

        /** 最近使用区容量（实测 150） */
        private Integer recent_limit;

        /** 属性位（<b>实测 {@code -1}</b>，可为负，别用无符号思维读它） */
        private Integer attr;

        /** 默认聚焦的包 id（实测 1，即"小黄脸"） */
        private Integer focus_pkg_id;

        /** H5 表情商城地址 */
        private String schema;
    }

    /** 一个表情包。 */
    @Data
    public static class Package {

        /** 包 id（实测 1 = 小黄脸、53 = 热词系列一 …） */
        private Integer id;

        /** 包名（实测 {@code 小黄脸}） */
        private String text;

        /** 包封面地址 */
        private String url;

        /** 修改时间（秒级时间戳） */
        private Long mtime;

        /** 包类型（实测取值 {@code 1}/{@code 2}/{@code 3}/{@code 4}/{@code 12}，含义未验证） */
        private Integer type;

        /** 属性位（实测 {@code 322} / {@code 0}，含义未验证） */
        private Integer attr;

        /** 包级附加信息（⚠️ 实测有 4 种键组合，见 {@link Meta}） */
        private Meta meta;

        /** <b>包内表情</b>（首个包实测 225 个） */
        private List<Emote> emote;

        /** 该包对本用户的状态（是否已添加 / 是否预览） */
        private Flags flags;

        /** 运营标签（⚠️ 实测 68 个包里只有 1 个非 {@code null}，形状未验证） */
        private JSONObject label;

        /** 包副标题（实测多空串） */
        private String package_sub_title;

        /** 实测 {@code 0}，含义未验证 */
        private Integer ref_mid;

        /** 实测 {@code 0}，含义未验证 */
        private Integer resource_type;
    }

    /**
     * 包级 {@code meta}。
     *
     * <p>⚠️ 实测键组合有 4 种：{@code {item_id,size}}（51 个）、
     * {@code {asset_id,item_id,item_url,size}}（12 个）、{@code {asset_id,item_id,size}}（4 个）、
     * {@code {size}}（1 个）。所以 {@code asset_id} / {@code item_url} <b>可以缺席</b>。
     */
    @Data
    public static class Meta {

        /** 实测恒为 {@code 1} */
        private Integer size;

        /** 商城条目 id（⚠️ 可能缺席） */
        private Integer item_id;

        /** 资产 id（⚠️ 多数包的 {@code meta} 里没有这个键） */
        private Integer asset_id;

        /** 资产地址（⚠️ 多数包的 {@code meta} 里没有这个键） */
        private String item_url;
    }

    /** 包对本用户的状态。 */
    @Data
    public static class Flags {

        /** 是否已添加该包（实测 {@code true}） */
        private Boolean added;

        /** 是否可预览（实测 {@code true}） */
        private Boolean preview;
    }

    /** 一个表情。 */
    @Data
    public static class Emote {

        /** 表情 id（实测 83964） */
        private Integer id;

        /** 所属包 id */
        private Integer package_id;

        /** 🔴 <b>表情代码</b>，就是评论里要打的那串（实测 {@code [doge_金箍]} / {@code [泡姆泡姆_加油]}） */
        private String text;

        /** 图片地址 */
        private String url;

        /**
         * 动图地址。
         *
         * <p>⚠️ <b>只有部分表情有</b>：实测 1555 个里 44 个带 {@code gif_url}（约 3%）。
         * 拿到 {@code null} 是常态，<b>不是漏映射</b>。
         */
        private String gif_url;

        /** 修改时间（秒级时间戳） */
        private Long mtime;

        /** 表情类型（实测 {@code 1}） */
        private Integer type;

        /** 属性位（实测 {@code 0}） */
        private Integer attr;

        /** 表情级附加信息（⚠️ 实测有 3 种键组合，见 {@link EmoteMeta}） */
        private EmoteMeta meta;

        /** 该表情对本用户的状态 */
        private EmoteFlags flags;

        /** 活动信息（⚠️ 实测<b>全部为 {@code null}</b>，形状未验证） */
        private JSONObject activity;
    }

    /**
     * 表情级 {@code meta}。
     *
     * <p>⚠️ 实测键组合有 3 种：{@code {alias,size,suggest}}（1477 个）、
     * {@code {alias,size}}（83 个）、{@code {size,suggest}}（3 个）。
     * 所以 {@code alias} 与 {@code suggest} <b>都可能缺席</b>。
     */
    @Data
    public static class EmoteMeta {

        /** 实测恒为 {@code 1} */
        private Integer size;

        /**
         * 联想词（用于评论输入框的补全）。
         *
         * <p>⚠️ 实测里面常常是<b>空串元素</b>（如 {@code [""]}），不是一定有意义。
         */
        private List<String> suggest;

        /** 别名（实测 {@code 金箍} / {@code 加油}） */
        private String alias;
    }

    /** 表情对本用户的状态。 */
    @Data
    public static class EmoteFlags {

        /** 是否已解锁（实测小黄脸包里为 {@code false}，即"要大会员"那类） */
        private Boolean unlocked;

        /** 是否禁止出现在"最近使用"（⚠️ 实测 1555 个里只有 8 个有本键） */
        private Boolean recent_use_forbid;
    }
}

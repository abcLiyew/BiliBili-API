package com.esdllm.bilibiliApi.model.data.pojo.search;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.util.List;

/**
 * <b>热搜榜</b> —— {@code x/web-interface/search/square} 的 {@code data}（B2 批 #3）。
 *
 * <p>实测响应（2026-09-22，匿名，{@code limit=10}）：
 * <pre>
 * {"trending": {"title": "bilibili热搜", "trackid": "12414231099029457647",
 *               "list": [{"keyword": "深度复盘IG战胜JDG晋级世界赛", "show_name": "…",
 *                         "icon": "http://i0.hdslb.com/…png", "uri": "", "goto": "",
 *                         "heat_score": 2976633}, … 共 10 条],
 *               "top_list": []}}
 * </pre>
 *
 * <p>✅ <b>完全不需要凭据</b>（匿名 {@code code=0}，实测两次一致）。
 *
 * <p>🔴 <b>{@link Trending#trackid} 是字符串，不是数字 —— 刻意如此</b>：实测值
 * {@code 12414231099029457647} 已经<b>超过 {@code Long.MAX_VALUE}（9.22e18）</b>。
 * 若按数值反序列化会溢出/变负数，所以这里是 {@link String}，<b>不要 {@code parseLong}</b>。
 *
 * <p>⚠️ {@link Item#icon} 实测"有则有、无则空串"（10 条里 3 条带图），<b>别当必填</b>。
 *
 * @author 饿死的流浪猫
 */
@Data
public class HotSearch {

    /** 热搜数据块（实测 {@code data} 顶层就只有这一个键） */
    private Trending trending;

    /**
     * 热搜数据块。
     *
     * <p>⚠️ 实测键名是 {@code trending} 而不是 {@code hotSearch} 之类 ——
     * 这里没有"更好听的改名"，字段名与 JSON 键逐字对应。
     */
    @Data
    public static class Trending {

        /** 榜单标题（实测 {@code bilibili热搜}） */
        private String title;

        /** 🔴 榜单跟踪 ID —— <b>字符串</b>，实测已超出 {@code long} 范围 */
        private String trackid;

        /** 热搜条目（实测 10 条） */
        private List<Item> list;

        /**
         * 置顶条目（可能带运营标记）。
         *
         * <p>⚠️ <b>实测为空数组 {@code []}</b> —— 元素形状<b>未验证</b>。
         * 这里按姐妹字段 {@link #list} 的形状假定（同一个榜、同一种条目），
         * 若将来发现形状不同会改为 {@code JSONObject}。
         */
        private List<Item> top_list;
    }

    /** 一条热搜。 */
    @Data
    public static class Item {

        /** 搜索词（实测即展示词，与 {@link #show_name} 相同） */
        private String keyword;

        /** 展示用词（实测与 {@link #keyword} 相同；运营改词的榜可能不同） */
        private String show_name;

        /** 图标地址（⚠️ 实测可为空串） */
        private String icon;

        /** 跳转用的 bilibili URI（⚠️ 实测 10 条全为空串） */
        private String uri;

        /**
         * 跳转类型（{@code av} / {@code live} 一类）。
         *
         * <p>🔴 <b>字段名与 JSON 键不同名</b>：JSON 键是 {@code goto}，而 {@code goto} 是 Java
         * 保留字，不能做字段名 ⇒ 字段只能叫 {@code goTo}。
         *
         * <p>🔴 <b>因此必须显式写 {@code @JSONField(name = "goto")}</b>（2026-09-22 迁 fastjson2 时补的）。
         * 这条映射原先<b>靠 fastjson 1.x 的大小写不敏感"智能匹配"接上</b>，而
         * <b>fastjson2 完全没有名字宽容度</b> —— 实测 2.0.56：首字母大小写（{@code View}→{@code view}）、
         * 中段大小写（{@code goto}→{@code goTo}）、下划线↔驼峰（{@code show_name}→{@code showName}）
         * <b>一律不匹配，只认同名</b>。
         * ⇒ 少了这个注解，它会<b>静默变 null、不抛任何异常</b>。这是"隐式行为被解析器默认值承载"的
         * 典型翻车点：迁移时代码注释还在、编译也过，只有真数据能发现。
         * 📌 同一个坑本库共 3 处（另见 {@code WatchedShow.Switch}、{@code PlayUrl} 的 {@code segment_base}），
         * 已由一个只扫"字段名 vs 夹具键"的脚本一次性找出，见 `.workbuddy/_audit_fieldnames.py`。
         *
         * <p>⚠️ 本批实测 10 条<b>全是空串</b> —— 别指望它一定有值。
         */
        @JSONField(name = "goto")
        private String goTo;

        /** 热度分值（实测 2976633 / 2118603 / 858032 …，递减） */
        private Long heat_score;
    }
}

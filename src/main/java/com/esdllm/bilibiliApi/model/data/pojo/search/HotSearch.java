package com.esdllm.bilibiliApi.model.data.pojo.search;

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
         * <p>🔴 <b>这是全库唯一"字段名不能与 JSON 键同名"的地方</b>：JSON 键是
         * {@code goto}，而 {@code goto} 是 Java 保留字，不能做字段名。
         * 这里用 {@code goTo}，靠 fastjson 的<b>大小写不敏感回退匹配</b>接上 ——
         * 同一机制本库已在 {@code ViewDetail.View} / {@code ViewDetail.Card}
         * 上验证过（那两个的 JSON 键首字母大写，一样能填进来），所以这里不是新赌注。
         *
         * <p>⚠️ 本批实测 10 条<b>全是空串</b> —— 别指望它一定有值。
         */
        private String goTo;

        /** 热度分值（实测 2976633 / 2118603 / 858032 …，递减） */
        private Long heat_score;
    }
}

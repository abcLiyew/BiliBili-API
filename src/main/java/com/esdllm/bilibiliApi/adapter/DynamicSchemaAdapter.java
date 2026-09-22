package com.esdllm.bilibiliApi.adapter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;

import java.util.Locale;

/**
 * 动态响应适配器：把 B 站返回的 {@code item} 结构填进<b>冻结</b>的
 * {@link BilibiliDynamicResp.Data.Card}。
 *
 * <h2>为什么必须有这一层</h2>
 * 门面 {@code Dynamic#getDynamicDetail} 的返回类型 {@code BilibiliDynamicResp.Data.Card}
 * 是下游冻结契约（XatiiBot 直接读 {@code card.getDesc().getDynamic_id_str()} 与
 * {@code card.getDesc().getUser_profile().getInfo().getUname()}）。
 * 而 B 站现在返回的是 {@code data.item.id_str + data.item.modules}，
 * 两者形状完全不同 —— 不做转换，下游读到的就是 null。
 *
 * <h2>为什么要处理两套 schema</h2>
 * B 站目前<b>同时存在两套动态响应结构</b>（实测 2026-09-13）：
 *
 * <table border="1">
 *   <caption>两套 schema 对照</caption>
 *   <tr><th></th><th>LEGACY（旧）</th><th>DESKTOP（新）</th></tr>
 *   <tr><td>可用端点</td><td>列表 {@code v1/feed/space}（需 Cookie）<br>详情 {@code v1/detail?id=}（匿名）</td>
 *       <td>列表 {@code desktop/v1/feed/space}（匿名）<br>详情 {@code desktop/v1/detail?id=}（需 Cookie）</td></tr>
 *   <tr><td>{@code item.modules}</td><td>JSON <b>对象</b></td><td>JSON <b>数组</b></td></tr>
 *   <tr><td>作者</td><td>{@code module_author.{mid,name,face}}</td><td>{@code module_author.user.{mid,name,face}}</td></tr>
 *   <tr><td>时间</td><td>{@code module_author.pub_time}</td><td>{@code module_author.pub_text}</td></tr>
 *   <tr><td>正文</td><td>{@code module_dynamic.desc.text}</td><td>{@code module_desc.text}</td></tr>
 *   <tr><td>视频投稿</td><td>{@code module_dynamic.major.archive}</td><td>{@code module_dynamic.dyn_archive}</td></tr>
 *   <tr><td>转发原动态</td><td>{@code item.orig}</td><td>{@code module_dynamic.dyn_forward.item}</td></tr>
 * </table>
 *
 * <p>判别式是<b>无歧义</b>的：{@code modules} 是数组就是新 schema，是对象就是旧 schema。
 *
 * <h2>本库采用的组合</h2>
 * 列表走新 schema（匿名可用）+ 详情走旧 schema（匿名可用）= <b>全程零 Cookie</b>。
 * 这是刻意选择：两套 schema 的匿名可用性正好互补（详见 {@code REFACTOR_PLAN.md} §1.8 / §9-Q5）。
 */
public final class DynamicSchemaAdapter {

    /** 动态响应结构版本 */
    public enum SchemaShape {
        /** 旧结构：{@code modules} 是对象，键形如 {@code module_author} */
        LEGACY,
        /** 新结构：{@code modules} 是数组，元素带 {@code module_type} */
        DESKTOP
    }

    /** 动态类型字符串 → 旧接口的魔法数字（下游 XatiiBot 不读该字段，仅作完整性填充） */
    private static final String TYPE_FORWARD = "DYNAMIC_TYPE_FORWARD";
    private static final String TYPE_DRAW = "DYNAMIC_TYPE_DRAW";
    private static final String TYPE_WORD = "DYNAMIC_TYPE_WORD";
    private static final String TYPE_AV = "DYNAMIC_TYPE_AV";
    private static final String TYPE_ARTICLE = "DYNAMIC_TYPE_ARTICLE";
    private static final String TYPE_MUSIC = "DYNAMIC_TYPE_MUSIC";

    private DynamicSchemaAdapter() {
    }

    /**
     * 判别 {@code item} 属于哪套 schema。
     *
     * @param item 响应里的 {@code data.item}
     * @throws BilibiliException item 或 modules 缺失时
     */
    public static SchemaShape detect(JSONObject item) {
        if (item == null) {
            throw new BilibiliException("动态详情失败：响应里没有 item");
        }
        Object modules = item.get("modules");
        if (modules == null) {
            throw new BilibiliException("动态详情失败：item 里没有 modules");
        }
        return (modules instanceof JSONArray) ? SchemaShape.DESKTOP : SchemaShape.LEGACY;
    }

    /** 自动判别 schema 并转换 */
    public static BilibiliDynamicResp.Data.Card toCard(JSONObject item) {
        return toCard(item, detect(item));
    }

    /**
     * 把 {@code item} 转换成冻结的 {@code Data.Card}。
     *
     * <p><b>保证</b>：返回值与 {@code getDesc()}、{@code getDesc().getUser_profile()}、
     * {@code getDesc().getUser_profile().getInfo()} 均不为 null ——
     * 下游 XatiiBot 是链式取值（{@code card.getDesc().getUser_profile().getInfo().getUname()}），
     * 任何一环为 null 都会在它那边炸出 NPE。
     *
     * @param item  响应里的 {@code data.item}
     * @param shape schema 版本
     */
    public static BilibiliDynamicResp.Data.Card toCard(JSONObject item, SchemaShape shape) {
        if (item == null) {
            throw new BilibiliException("动态详情失败：响应里没有 item");
        }
        String idStr = item.getString("id_str");
        if (idStr == null || idStr.isBlank()) {
            throw new BilibiliException("动态详情失败：item 缺少 id_str");
        }

        JSONObject author = module(item, shape, "module_author", "MODULE_TYPE_AUTHOR");
        JSONObject dynamic = module(item, shape, "module_dynamic", "MODULE_TYPE_DYNAMIC");
        JSONObject stat = module(item, shape, "module_stat", "MODULE_TYPE_STAT");
        JSONObject basic = item.getJSONObject("basic");

        // 作者信息来自 author 模块内的 user 子对象（新 schema）或 author 本身（旧 schema）
        JSONObject user = authorUser(author, shape);
        Long mid = user == null ? null : parseLongOrNull(user.getString("mid"));
        String uname = user == null ? null : user.getString("name");
        String face = user == null ? null : user.getString("face");

        BilibiliDynamicResp.Data.Card.Desc desc = new BilibiliDynamicResp.Data.Card.Desc();
        desc.setDynamic_id_str(idStr);
        desc.setDynamic_id(parseLongOrNull(idStr));
        desc.setUid(mid);
        desc.setTimestamp(author == null ? null : parseLongOrNull(author.getString("pub_ts")));
        desc.setUser_profile(userProfile(mid, uname, face));

        if (basic != null) {
            String ridStr = basic.getString("rid_str");
            desc.setRid_str(ridStr);
            desc.setRid(parseLongOrNull(ridStr));
        }

        JSONObject archive = archiveNode(dynamic, shape);
        if (archive != null) {
            desc.setBvid(archive.getString("bvid"));
        }

        desc.setType(toLegacyType(item.getString("type")));

        if (stat != null) {
            desc.setLike(statLong(stat, "like"));
            desc.setComment(statInteger(stat, "comment"));
            desc.setRepost(statLong(stat, "forward"));
        }

        BilibiliDynamicResp.Data.Card card = new BilibiliDynamicResp.Data.Card();
        card.setDesc(desc);
        return card;
    }

    // ================================================================
    // 内部工具：把两套 schema 的差异全部关在这里
    // ================================================================

    /**
     * 取某个 module 的内容对象，抹平两套 schema 的定位差异。
     *
     * <ul>
     *   <li>DESKTOP：遍历 {@code modules[]}，按 {@code module_type} 命中后，
     *       内容在同级的 {@code module_xxx} 字段里（{@code MODULE_TYPE_AUTHOR} → {@code module_author}）</li>
     *   <li>LEGACY：直接 {@code modules[legacyKey]}</li>
     * </ul>
     */
    private static JSONObject module(JSONObject item, SchemaShape shape, String legacyKey, String desktopModuleType) {
        Object modules = item.get("modules");
        if (modules instanceof JSONArray array) {
            for (Object element : array) {
                if (!(element instanceof JSONObject m)) {
                    continue;
                }
                if (desktopModuleType.equals(m.getString("module_type"))) {
                    return m.getJSONObject(moduleKeyOf(desktopModuleType));
                }
            }
            return null;
        }
        if (modules instanceof JSONObject object) {
            return object.getJSONObject(legacyKey);
        }
        return null;
    }

    /** {@code MODULE_TYPE_AUTHOR} → {@code module_author} */
    private static String moduleKeyOf(String desktopModuleType) {
        return desktopModuleType.toLowerCase(Locale.ROOT).replace("module_type_", "module_");
    }

    private static JSONObject authorUser(JSONObject author, SchemaShape shape) {
        if (author == null) {
            return null;
        }
        if (shape == SchemaShape.DESKTOP) {
            JSONObject user = author.getJSONObject("user");
            return user != null ? user : author;
        }
        return author;
    }

    private static JSONObject archiveNode(JSONObject dynamic, SchemaShape shape) {
        if (dynamic == null) {
            return null;
        }
        if (shape == SchemaShape.DESKTOP) {
            return dynamic.getJSONObject("dyn_archive");
        }
        JSONObject major = dynamic.getJSONObject("major");
        return major == null ? null : major.getJSONObject("archive");
    }

    private static BilibiliDynamicResp.Data.Card.Desc.UserProfile userProfile(Long uid, String uname, String face) {
        BilibiliDynamicResp.Data.Card.Desc.UserProfile.Info info =
                new BilibiliDynamicResp.Data.Card.Desc.UserProfile.Info();
        info.setUid(uid);
        info.setUname(uname);
        info.setFace(face);

        BilibiliDynamicResp.Data.Card.Desc.UserProfile profile =
                new BilibiliDynamicResp.Data.Card.Desc.UserProfile();
        profile.setInfo(info);
        return profile;
    }

    private static Long statLong(JSONObject stat, String key) {
        JSONObject node = stat.getJSONObject(key);
        return node == null ? null : node.getLong("count");
    }

    private static Integer statInteger(JSONObject stat, String key) {
        JSONObject node = stat.getJSONObject(key);
        return node == null ? null : node.getInteger("count");
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            // id 理论上都是数字字符串；异常形态不该让整个详情接口失败
            return null;
        }
    }

    /**
     * 动态类型字符串 → 旧接口的魔法数字。
     *
     * <p>只映射实测/文档确认过的类型，其余返回 null —— 宁可留空也不臆造。
     * 下游 XatiiBot 不读该字段。
     */
    private static Integer toLegacyType(String dynamicType) {
        if (dynamicType == null) {
            return null;
        }
        return switch (dynamicType) {
            case TYPE_FORWARD -> 1;
            case TYPE_DRAW -> 2;
            case TYPE_WORD -> 4;
            case TYPE_AV -> 8;
            case TYPE_ARTICLE -> 64;
            case TYPE_MUSIC -> 256;
            default -> null;
        };
    }
}

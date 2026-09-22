package com.esdllm.bilibiliApi.model.data.pojo;

import com.alibaba.fastjson2.JSON;
import com.esdllm.bilibiliApi.model.data.pojo.live.WatchedShow;
import com.esdllm.bilibiliApi.model.data.pojo.search.HotSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「字段名 ≠ JSON 键」的映射守卫（2026-09-22 迁 fastjson2 时立）。
 *
 * <p>🔴 <b>为什么必须有这个类</b>：fastjson 1.x 有"大小写不敏感 + 忽略下划线"的智能匹配，
 * 于是"字段名与 JSON 键不完全同名"也能填上值；<b>fastjson2 完全没有这个宽容度</b> ——
 * 实测 2.0.56：{@code View}→{@code view}（首字母大小写）、{@code goto}→{@code goTo}（中段大小写）、
 * {@code show_name}→{@code showName}（下划线↔驼峰）<b>一律不匹配，只认同名</b>。
 * ⇒ 迁移后这类字段会<b>静默变 null、不抛任何异常</b>；而它们的真实取值又常常是空串/null，
 * 用普通夹具断言根本发现不了（空串与"没映射"在断言上长得一样）。
 *
 * <p>本库此类字段只有 2 个（都是 Java 保留字导致的改名），且都靠显式
 * {@code @JSONField(name = ...)} 固定下来。这个类就是防止有人把注解删掉 ——
 * <b>删掉注解不会有任何编译错误，也不会有别的测试报错</b>，只有真数据能发现。
 *
 * <p>📌 <b>排查手法可复用</b>：把"POJO 字段名集合"与"夹具 JSON 键集合"各自做
 * <b>去下划线 + 转小写</b>后比对，凡是"规范化后撞上、但并非精确同名"的字段就是目标
 * （一次性脚本：`.workbuddy/_audit_fieldnames.py`；本库靠它一次找出 3 处，
 * 其中 1 处是与 {@code segment_base} 重复的字段，已删）。
 */
@DisplayName("模型：字段名与 JSON 键不同名的映射守卫")
class KeyNameMappingGuardTest {

    @Test
    @DisplayName("goto → goTo：Java 保留字导致的改名，必须靠 @JSONField 接上")
    void gotoKeyMapsToGoToField() {
        HotSearch.Item item = JSON.parseObject(
                "{\"keyword\":\"k\",\"goto\":\"av\"}", HotSearch.Item.class);

        assertEquals("av", item.getGoTo(),
                "字段上的 @JSONField(name=\"goto\") 失效了 —— 这个字段会静默变 null，"
                        + "而真机响应里它常常是空串，光看真机数据发现不了");
    }

    @Test
    @DisplayName("switch → Switch：Java 保留字导致的改名，必须靠 @JSONField 接上")
    void switchKeyMapsToSwitchField() {
        WatchedShow show = JSON.parseObject(
                "{\"switch\":true,\"num\":3}", WatchedShow.class);

        assertEquals(Boolean.TRUE, show.getSwitch(),
                "字段上的 @JSONField(name=\"switch\") 失效了 —— 这个字段会静默变 null");
        assertEquals(Integer.valueOf(3), show.getNum(),
                "对照组：与 JSON 键同名的字段（num）本来就不需要注解");
    }
}

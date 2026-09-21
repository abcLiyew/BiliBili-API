package com.esdllm.bilibiliApi.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.adapter.DynamicSchemaAdapter.SchemaShape;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DynamicSchemaAdapter} 的确定性单测。
 *
 * <p>用两份 fixture 覆盖 B 站现阶段并存的两套 schema，**完全不联网**：
 * <ul>
 *   <li>{@code fixtures/dynamic-detail-legacy.json} —— 旧 schema（{@code modules} 是对象），
 *       详情端点 {@code v1/detail?id=} 返回的就是这套，本库的详情路径走它</li>
 *   <li>{@code fixtures/dynamic-detail-desktop.json} —— 新 schema（{@code modules} 是数组），
 *       列表端点 {@code desktop/v1/feed/space} 返回的就是这套</li>
 * </ul>
 * fixture 内容按 2026-09-13 的真实响应结构裁剪而成（只保留本测试用到的字段）。
 */
@DisplayName("DynamicSchemaAdapter：两套 schema → 冻结模型")
class DynamicSchemaAdapterTest {

    private static JSONObject loadFixture(String name) throws IOException {
        try (InputStream in = DynamicSchemaAdapterTest.class.getResourceAsStream("/fixtures/" + name)) {
            Objects.requireNonNull(in, "fixture 不存在：/fixtures/" + name);
            return JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static JSONObject itemOf(String fixtureName) throws IOException {
        return loadFixture(fixtureName).getJSONObject("data").getJSONObject("item");
    }

    // ---------------- schema 判别 ----------------

    @Test
    @DisplayName("modules 是对象 → LEGACY")
    void detectsLegacySchema() throws IOException {
        assertEquals(SchemaShape.LEGACY, DynamicSchemaAdapter.detect(itemOf("dynamic-detail-legacy.json")));
    }

    @Test
    @DisplayName("modules 是数组 → DESKTOP")
    void detectsDesktopSchema() throws IOException {
        assertEquals(SchemaShape.DESKTOP, DynamicSchemaAdapter.detect(itemOf("dynamic-detail-desktop.json")));
    }

    // ---------------- 旧 schema 映射 ----------------

    @Test
    @DisplayName("旧 schema：作者 / 关联视频 / 互动数 全部落到冻结模型")
    void mapsLegacySchema() throws IOException {
        BilibiliDynamicResp.Data.Card card = DynamicSchemaAdapter.toCard(itemOf("dynamic-detail-legacy.json"));
        BilibiliDynamicResp.Data.Card.Desc desc = card.getDesc();

        assertNotNull(desc, "desc 不能为 null，下游是链式取值");
        assertEquals("1247016318199136288", desc.getDynamic_id_str());
        assertEquals(Long.valueOf(1247016318199136288L), desc.getDynamic_id());

        // 作者：旧 schema 直接放在 module_author 上
        assertEquals(Long.valueOf(946974L), desc.getUid());
        assertEquals("影视飓风", desc.getUser_profile().getInfo().getUname());
        assertEquals("https://i0.hdslb.com/bfs/face/c1733474892caa45952b2c09a89323157df7129a.jpg",
                desc.getUser_profile().getInfo().getFace());
        assertEquals(Long.valueOf(946974L), desc.getUser_profile().getInfo().getUid());
        assertEquals(Long.valueOf(1789182012L), desc.getTimestamp());

        // 关联内容：major.archive.bvid
        assertEquals("BV1xVY26dEbz", desc.getBvid());
        assertEquals("117253177676089", desc.getRid_str());
        assertEquals(Long.valueOf(117253177676089L), desc.getRid());

        // 类型：DYNAMIC_TYPE_AV → 8
        assertEquals(Integer.valueOf(8), desc.getType());

        // 互动数
        assertEquals(Long.valueOf(10106L), desc.getLike());
        assertEquals(Integer.valueOf(382), desc.getComment());
        assertEquals(Long.valueOf(48L), desc.getRepost());
    }

    // ---------------- 新 schema 映射 ----------------

    @Test
    @DisplayName("新 schema：作者多一层 user，内容在 dyn_archive / dyn_draw")
    void mapsDesktopSchema() throws IOException {
        BilibiliDynamicResp.Data.Card card = DynamicSchemaAdapter.toCard(itemOf("dynamic-detail-desktop.json"));
        BilibiliDynamicResp.Data.Card.Desc desc = card.getDesc();

        assertEquals("1247440317376888835", desc.getDynamic_id_str());
        // 作者：新 schema 在 module_author.user 里
        assertEquals("影视飓风", desc.getUser_profile().getInfo().getUname());
        assertEquals(Long.valueOf(946974L), desc.getUser_profile().getInfo().getUid());
        assertEquals(Long.valueOf(946974L), desc.getUid());
        assertEquals(Long.valueOf(1789280732L), desc.getTimestamp());

        // 该条是图文（dyn_archive 为 null）→ bvid 应为 null，而不是抛异常
        assertNull(desc.getBvid());

        // rid 来自 basic.rid_str
        assertEquals("409000337", desc.getRid_str());

        // 类型：DYNAMIC_TYPE_DRAW → 2
        assertEquals(Integer.valueOf(2), desc.getType());
        assertEquals(Long.valueOf(10106L), desc.getLike());
    }

    // ---------------- 下游链式取值 ----------------

    @Test
    @DisplayName("两套 schema 都必须保证下游 getDesc().getUser_profile().getInfo().getUname() 可用")
    void downstreamChainedGettersNotNull() throws IOException {
        for (String fixture : new String[]{"dynamic-detail-legacy.json", "dynamic-detail-desktop.json"}) {
            BilibiliDynamicResp.Data.Card card = DynamicSchemaAdapter.toCard(itemOf(fixture));
            // 与 XatiiBot BilibiliAnalysisImpl.java:132/137 的取值方式逐字一致
            assertNotNull(card.getDesc().getDynamic_id_str(), fixture + "：dynamic_id_str 不能为 null");
            assertNotNull(card.getDesc().getUser_profile().getInfo().getUname(), fixture + "：uname 不能为 null");
        }
    }

    // ---------------- 异常路径 ----------------

    @Test
    @DisplayName("item 缺失 → BilibiliException（不得抛 NPE）")
    void itemMissing() {
        BilibiliException e = assertThrows(BilibiliException.class, () -> DynamicSchemaAdapter.toCard(null));
        assertTrue(e.getMessage().contains("item"), "异常消息应说明 item 缺失，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("modules 缺失 → BilibiliException")
    void modulesMissing() {
        JSONObject item = new JSONObject();
        item.put("id_str", "1");
        BilibiliException e = assertThrows(BilibiliException.class, () -> DynamicSchemaAdapter.toCard(item));
        assertTrue(e.getMessage().contains("modules"), "异常消息应说明 modules 缺失，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("id_str 缺失 → BilibiliException")
    void idMissing() {
        JSONObject item = new JSONObject();
        item.put("modules", new JSONObject());
        BilibiliException e = assertThrows(BilibiliException.class, () -> DynamicSchemaAdapter.toCard(item));
        assertTrue(e.getMessage().contains("id_str"), "异常消息应说明 id_str 缺失，实际：" + e.getMessage());
    }
}

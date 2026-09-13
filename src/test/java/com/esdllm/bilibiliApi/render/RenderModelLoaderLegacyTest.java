package com.esdllm.bilibiliApi.render;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线单测：覆盖 {@link RenderModelLoader#parseLegacy} 与新增的 {@link RenderModel.Type} 映射。
 *
 * <p>走真实 fixture —— 不联网。
 */
class RenderModelLoaderLegacyTest {

    @Test
    @DisplayName("VIDEO（DYNAMIC_TYPE_AV / MAJOR_TYPE_ARCHIVE）映射并产出封面+标题+BV号块")
    void videoDynamic() throws Exception {
        JSONObject item = itemOf("dynamic-detail-legacy.json");

        // type 映射
        assertEquals(RenderModel.Type.VIDEO, RenderModelLoader.mapType(item.getString("type")));

        RenderModel model = RenderModelLoader.parseLegacy(item);
        assertNotNull(model.getDynamicId(), "id_str 应当保留");
        assertEquals(RenderModel.Type.VIDEO, model.getType());

        // 至少要有 imageBlock（封面）和 textBlock（标题+BV号）
        boolean hasImage = false;
        boolean hasText = false;
        for (RenderModel.Block b : model.getBlocks()) {
            if (b instanceof RenderModel.ImageBlock) {
                hasImage = true;
                RenderModel.ImageBlock ib = (RenderModel.ImageBlock) b;
                assertFalse(ib.getPics().isEmpty(), "封面图不能为空");
                assertNotNull(ib.getPics().get(0).getUrl(), "封面 url 不能为 null");
            }
            if (b instanceof RenderModel.TextBlock) {
                hasText = true;
            }
        }
        assertTrue(hasImage, "VIDEO 应当至少有一个 ImageBlock（封面）");
        assertTrue(hasText, "VIDEO 应当至少有一个 TextBlock（标题/BV号/desc）");

        // author 的时间格式应是 legacy 的 pub_time（绝对时间），不是 opus 的 pub_text（相对时间）
        assertNotNull(model.getAuthor().getName(), "author.name 不能为 null");
    }

    /**
     * DRAW 在 legacy schema 中的形态：fixture {@code dynamic-detail-desktop.json} 实际是
     * OPUS-shape 的数组 modules，{@code parseLegacy} 不适用 —— DRAW 的离线回归交给
     * 已有测试 / {@code DynamicSchemaAdapterTest}；这里不再冗余。
     */

    @Test
    @DisplayName("type 字面量全集映射")
    void typeMapping() {
        assertEquals(RenderModel.Type.DRAW, RenderModelLoader.mapType("DYNAMIC_TYPE_DRAW"));
        assertEquals(RenderModel.Type.VIDEO, RenderModelLoader.mapType("DYNAMIC_TYPE_AV"));
        assertEquals(RenderModel.Type.FORWARD, RenderModelLoader.mapType("DYNAMIC_TYPE_FORWARD"));
        assertEquals(RenderModel.Type.ARTICLE, RenderModelLoader.mapType("DYNAMIC_TYPE_ARTICLE"));
        assertEquals(RenderModel.Type.LIVE, RenderModelLoader.mapType("DYNAMIC_TYPE_LIVE_RCMD"));
        assertEquals(RenderModel.Type.LIVE, RenderModelLoader.mapType("DYNAMIC_TYPE_LIVE"));
        assertEquals(RenderModel.Type.UNKNOWN, RenderModelLoader.mapType("WHAT_IS_THIS"));
        assertEquals(RenderModel.Type.UNKNOWN, RenderModelLoader.mapType(null));
    }

    /** 读 fixture 里的 {@code data.item} 并返回 raw JSONObject */
    private static JSONObject itemOf(String filename) throws Exception {
        String text = Files.readString(Path.of("src/test/resources/fixtures", filename));
        return JSONObject.parseObject(text).getJSONObject("data").getJSONObject("item");
    }
}

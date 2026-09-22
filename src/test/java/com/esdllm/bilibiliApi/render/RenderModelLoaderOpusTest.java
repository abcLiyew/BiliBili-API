package com.esdllm.bilibiliApi.render;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 离线单测：opus schema（数组型 {@code modules}）的解析。
 *
 * <p>走内联 JSON，不联网、不依赖 fixture 文件。
 */
class RenderModelLoaderOpusTest {

    @Test
    @DisplayName("★ MODULE_TYPE_TITLE 必须被解析 —— 否则长图「有作者、有正文、有图，就是没标题」")
    void titleModule() {
        RenderModel model = RenderModelLoader.parse(JSON.parseObject("""
                {"id_str":"1247599097996640256","type":"DYNAMIC_TYPE_DRAW","modules":[
                  {"module_type":"MODULE_TYPE_TOP","module_top":{"display":{"album":{"pics":[
                      {"url":"http://i0.hdslb.com/a.png","width":1600,"height":900}]}}}},
                  {"module_type":"MODULE_TYPE_TITLE","module_title":{"text":"好的标题","tag":null,"tags":[]}},
                  {"module_type":"MODULE_TYPE_AUTHOR","module_author":{
                      "name":"可可小绒猫","pub_time":"2026年09月14日 00:41",
                      "face":"https://i2.hdslb.com/bfs/face/x.jpg"}},
                  {"module_type":"MODULE_TYPE_CONTENT","module_content":{"paragraphs":[
                      {"para_type":1,"text":{"nodes":[
                          {"type":"TEXT_NODE_TYPE_WORD","word":{"words":"分享的内容"}}]}}]}}
                ]}
                """));

        assertEquals("好的标题", model.getTitle(), "标题模块必须落进 model.title");
        assertEquals("可可小绒猫", model.getAuthor().getName());
        assertFalse(model.getBlocks().isEmpty(), "正文块也要照常解析");
    }

    @Test
    @DisplayName("没有标题模块时 title 为 null —— 这是多数图文动态的正常形态，渲染器要跳过而不是画空行")
    void noTitle() {
        RenderModel model = RenderModelLoader.parse(JSON.parseObject("""
                {"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":[
                  {"module_type":"MODULE_TYPE_AUTHOR","module_author":{"name":"up","pub_time":"刚刚"}}
                ]}
                """));

        assertNull(model.getTitle());
        assertEquals("up", model.getAuthor().getName());
    }

    @Test
    @DisplayName("标题为空白时按「没有标题」处理（不能画出一条空标题行）")
    void blankTitle() {
        RenderModel model = RenderModelLoader.parse(JSON.parseObject("""
                {"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":[
                  {"module_type":"MODULE_TYPE_TITLE","module_title":{"text":"   "}}
                ]}
                """));

        assertNull(model.getTitle());
    }

    @Test
    @DisplayName("标题是纯 emoji 时保留原文（渲染器会拆成贴图，不能丢字）")
    void emojiTitle() {
        RenderModel model = RenderModelLoader.parse(JSON.parseObject("""
                {"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":[
                  {"module_type":"MODULE_TYPE_TITLE","module_title":{"text":"🌙"}}
                ]}
                """));

        assertEquals("🌙", model.getTitle());
        // 渲染前的拆分会把它变成 EMOJI 片段（走 Twemoji 贴图）
        var spans = RenderModelLoader.splitUnicodeEmoji(model.getTitle());
        assertEquals(1, spans.size());
        assertEquals(RenderModel.SpanKind.EMOJI, spans.get(0).getKind());
        assertTrue(spans.get(0).getImageUrl().contains("1f319"), spans.get(0).getImageUrl());
    }

    @Test
    @DisplayName("title 与 blocks 相互独立：只有标题没有正文时也能拿到标题")
    void titleOnly() {
        JSONObject item = JSON.parseObject("""
                {"id_str":"1","type":"DYNAMIC_TYPE_DRAW","modules":[
                  {"module_type":"MODULE_TYPE_TITLE","module_title":{"text":"只有标题"}}
                ]}
                """);

        RenderModel model = RenderModelLoader.parse(item);
        assertEquals("只有标题", model.getTitle());
        assertTrue(model.getBlocks().isEmpty());
    }
}

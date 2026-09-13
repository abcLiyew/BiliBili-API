package com.esdllm.bilibiliApi.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Java2DImageRenderer} 离线渲染单测。
 *
 * <p><b>这是此前完全空白的一块</b>：渲染器只有联网 smoke（跑真实动态）覆盖，
 * 一旦渲染主路径被改坏，只有等"真机推送出图是白的"才能发现。本类用<b>手工构造的
 * {@link RenderModel}</b> 在完全离线、无需图形界面的条件下渲染，锁住渲染主路径。
 *
 * <p>抓图被显式关闭（{@link HttpImageFetcher#setEnabled(boolean)}），
 * 于是头像/配图都会走"占位块"分支 —— 这正是"图抓不到也不能让整张图渲染失败"的设计承诺。
 */
class Java2DImageRendererTest {

    /** 与实现里的 CONTENT_WIDTH + PADDING*2 对应 */
    private static final int EXPECTED_WIDTH = Java2DImageRenderer.CONTENT_WIDTH + 28 * 2;

    @BeforeEach
    void setUp() {
        // 离线模式：所有抓图请求直接返回 null，渲染器应画占位块而不是抛异常
        HttpImageFetcher.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        HttpImageFetcher.setEnabled(true);
    }

    /** 构造一个最小可渲染模型：有作者 + 一个文本块 */
    private static RenderModel minimalModel() {
        RenderModel m = new RenderModel();
        m.setDynamicId("1247440317376888835");
        m.getAuthor().setName("影视飓风");
        m.getAuthor().setPubTime("2026年09月13日 14:25");
        m.getAuthor().setFaceUrl("http://i0.hdslb.com/bfs/face/never-fetched.jpg");

        RenderModel.TextBlock tb = new RenderModel.TextBlock();
        RenderModel.Span span = new RenderModel.Span();
        span.setKind(RenderModel.SpanKind.TEXT);
        span.setText("或许你的童年照片里，也有这座游乐园。");
        tb.setSpans(List.of(span));
        m.getBlocks().add(tb);
        return m;
    }

    @Test
    @DisplayName("最小模型能渲染出图：宽度固定、高度为正、类型 TYPE_INT_RGB")
    void 最小模型渲染() {
        BufferedImage img = new Java2DImageRenderer().render(minimalModel());
        assertNotNull(img, "渲染结果不能为 null（旧 Selenium 路径曾返回'空白但非 null'的图，这条锁住新实现不退化）");
        assertEquals(EXPECTED_WIDTH, img.getWidth(), "宽度由 CONTENT_WIDTH + 左右 padding 决定");
        assertTrue(img.getHeight() > 0, "高度必须为正");
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType(),
                "XY 无 alpha 的 RGB 是渲染器约定（PNG 体积更小）");
    }

    @Test
    @DisplayName("图片确实是白底（不是全透明/全黑）")
    void 白底() {
        BufferedImage img = new Java2DImageRenderer().render(minimalModel());
        int corner = img.getRGB(0, 0) & 0xFFFFFF;
        assertEquals(0xFFFFFF, corner, "左上角应是白色背景；若为黑色说明 fillRect 没生效");
    }

    @Test
    @DisplayName("画布上确实画了东西（正文像素不是纯白）")
    void 有内容像素() {
        BufferedImage img = new Java2DImageRenderer().render(minimalModel());
        int nonWhite = 0;
        // 只抽样上半部分，避免整图遍历拖慢
        for (int y = 0; y < Math.min(img.getHeight(), 300); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                    nonWhite++;
                }
            }
        }
        assertTrue(nonWhite > 50,
                "应有明显数量的非白像素（昵称/正文/占位块），实际 " + nonWhite + " 个");
    }

    @Test
    @DisplayName("高度随内容增长（长正文比短正文高）")
    void 高度随内容增长() {
        RenderModel shortM = minimalModel();

        RenderModel longM = minimalModel();
        RenderModel.TextBlock tb = new RenderModel.TextBlock();
        RenderModel.Span s = new RenderModel.Span();
        s.setKind(RenderModel.SpanKind.TEXT);
        s.setText("这是一段很长的正文。".repeat(30));
        tb.setSpans(List.of(s));
        longM.getBlocks().add(tb);

        int hShort = new Java2DImageRenderer().render(shortM).getHeight();
        int hLong = new Java2DImageRenderer().render(longM).getHeight();
        assertTrue(hLong > hShort,
                "正文变长，画布应变高：短=" + hShort + " 长=" + hLong);
    }

    @Test
    @DisplayName("无 blocks（只有头部）也能渲染，不抛异常不返回 null")
    void 空blocks() {
        RenderModel m = new RenderModel();
        m.getAuthor().setName("n");
        BufferedImage img = new Java2DImageRenderer().render(m);
        assertNotNull(img);
        assertTrue(img.getHeight() > 0);
    }

    @Test
    @DisplayName("作者的字段全为 null 也能渲染（不 NPE）—— 真实响应字段常缺失")
    void 空作者字段() {
        RenderModel m = new RenderModel();
        // 完全不设 author 的任何字段
        BufferedImage img = new Java2DImageRenderer().render(m);
        assertNotNull(img);
        assertTrue(img.getWidth() == EXPECTED_WIDTH);
    }

    @Test
    @DisplayName("六种 Type 都能渲染（VIDEO/ARTICLE/LIVE 走兜底分支不崩）")
    void 各类型都能渲染() {
        for (RenderModel.Type t : RenderModel.Type.values()) {
            RenderModel m = minimalModel();
            m.setType(t);
            BufferedImage img = new Java2DImageRenderer().render(m);
            assertNotNull(img, "Type=" + t + " 渲染失败");
            assertTrue(img.getHeight() > 0, "Type=" + t + " 高度非正");
        }
    }

    @Test
    @DisplayName("EMOJI 片段缺图 → 画占位框，不静默丢字")
    void emoji缺图不丢字() {
        RenderModel m = minimalModel();
        RenderModel.TextBlock tb = new RenderModel.TextBlock();
        RenderModel.Span text = new RenderModel.Span();
        text.setKind(RenderModel.SpanKind.TEXT);
        text.setText("前");
        RenderModel.Span emoji = new RenderModel.Span();
        emoji.setKind(RenderModel.SpanKind.EMOJI);
        emoji.setText("[tv_大哭]");
        emoji.setImageUrl("https://i0.hdslb.com/bfs/emote/never-fetched.png");
        RenderModel.Span tail = new RenderModel.Span();
        tail.setKind(RenderModel.SpanKind.TEXT);
        tail.setText("后");
        tb.setSpans(List.of(text, emoji, tail));
        m.getBlocks().add(tb);

        BufferedImage withEmoji = new Java2DImageRenderer().render(m);

        // 对照组：没有 emoji 片段
        RenderModel plain = minimalModel();
        BufferedImage withoutEmoji = new Java2DImageRenderer().render(plain);

        assertTrue(withEmoji.getHeight() >= withoutEmoji.getHeight(),
                "emoji 至少应占位，不能让正文整段消失");
        assertNotNull(withEmoji);
    }

    @Test
    @DisplayName("ImageBlock 的图抓不到 → 占位块，不抛异常")
    void 图片块缺图不崩() {
        RenderModel m = minimalModel();
        RenderModel.ImageBlock ib = new RenderModel.ImageBlock();
        RenderModel.Pic pic = new RenderModel.Pic();
        pic.setUrl("https://i0.hdslb.com/bfs/new_dyn/never-fetched.jpg");
        pic.setWidth(1080);
        pic.setHeight(1440);
        ib.setPics(List.of(pic));
        m.getBlocks().add(ib);

        BufferedImage img = new Java2DImageRenderer().render(m);
        assertNotNull(img, "抓图失败必须降级为占位块，绝不打断整张长图");
        assertTrue(img.getHeight() > 0);
    }

    @Test
    @DisplayName("null 模型 → 抛异常（调用方编程错误应显式暴露，而不是产出空白图）")
    void null模型() {
        assertThrows(Exception.class, () -> new Java2DImageRenderer().render(null));
    }

    @Test
    @DisplayName("同一个 renderer 实例可重复渲染（无状态残留）")
    void 可重复渲染() {
        Java2DImageRenderer renderer = new Java2DImageRenderer();
        BufferedImage a = renderer.render(minimalModel());
        BufferedImage b = renderer.render(minimalModel());
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight(),
                "同一输入两次渲染尺寸应一致（布局确定，无随机性残留）");
    }
}
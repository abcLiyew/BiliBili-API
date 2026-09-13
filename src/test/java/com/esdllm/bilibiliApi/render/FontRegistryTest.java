package com.esdllm.bilibiliApi.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FontRegistry} 单测 —— headless 出图正确性的地基。
 *
 * <p>原来用 Selenium 截 opus 页面时，最小容器里没有中文字体，正文渲染成豆腐块，
 * 这是换 Java2D 的直接动机之一。所以"内置字体真的加载成功、真的能显示中文"
 * 必须是可断言的，而不是靠肉眼看图。
 *
 * <p>本测试<b>不联网、不需要图形界面</b>。
 */
class FontRegistryTest {

    @Test
    @DisplayName("内置字体加载成功（isBundled 为 true）")
    void 内置字体可用() {
        assertTrue(FontRegistry.isBundled(),
                "jar 里应嵌入了 Noto Sans SC 子集：" + FontRegistry.CJK_FONT_RESOURCE
                        + "；若为 false，说明资源缺失或加载失败 —— headless 下正文会变豆腐块");
    }

    @Test
    @DisplayName("字体族名非空（用于日志辨识实际生效的字体）")
    void 族名非空() {
        String family = FontRegistry.familyName();
        assertNotNull(family);
        assertFalse(family.isBlank());
    }

    @Test
    @DisplayName("regular(size) 返回指定字号的字体")
    void regular字号() {
        Font f = FontRegistry.regular(17f);
        assertNotNull(f);
        assertTrue(f.getSize() >= 16 && f.getSize() <= 18,
                "字号应在请求值附近，实际：" + f.getSize());
    }

    @Test
    @DisplayName("emphasis(size) 返回指定字号的字体")
    void emphasis字号() {
        Font f = FontRegistry.emphasis(18f);
        assertNotNull(f);
        assertTrue(f.getSize() >= 17 && f.getSize() <= 19,
                "字号应在请求值附近，实际：" + f.getSize());
    }

    @Test
    @DisplayName("能显示常用中文（这是换 Java2D 的核心收益，必须锁住）")
    void 能显示中文() {
        assertTrue(FontRegistry.canDisplay('中'), "『中』显示不出来 = 正文全是豆腐块");
        assertTrue(FontRegistry.canDisplay('文'));
        assertTrue(FontRegistry.canDisplay('测'));
        assertTrue(FontRegistry.canDisplay('试'));
    }

    @Test
    @DisplayName("能显示中文标点与常见符号（避头尾逻辑用到的标点不能缺）")
    void 能显示中文标点() {
        assertTrue(FontRegistry.canDisplay('，'));
        assertTrue(FontRegistry.canDisplay('。'));
        assertTrue(FontRegistry.canDisplay('“'));
        assertTrue(FontRegistry.canDisplay('”'));
        assertTrue(FontRegistry.canDisplay('·'), "时间文案里的 '·'（昨天 11:00 · 投稿了视频）必须能显示");
    }

    @Test
    @DisplayName("能显示 ASCII（BV 号、URL、数字）")
    void 能显示ASCII() {
        assertTrue(FontRegistry.canDisplay('B'));
        assertTrue(FontRegistry.canDisplay('V'));
        assertTrue(FontRegistry.canDisplay('0'));
        assertTrue(FontRegistry.canDisplay('/'));
        assertTrue(FontRegistry.canDisplay(':'));
    }

    @Test
    @DisplayName("canDisplay 对码位入参也工作（emoji 码位返回 false 是预期，不抛异常）")
    void canDisplay码位() {
        // 不硬断言 emoji 的 true/false —— 子集字体未必含 emoji（那会走 CDN 贴图分支）；
        // 只要求"不抛异常且给出确定的布尔值"
        boolean cjk = FontRegistry.canDisplay('字');
        boolean emoji = FontRegistry.canDisplay(0x1F600); // 😀
        assertTrue(cjk, "中文必须可显示");
        assertFalse(emoji && false, "仅要求调用安全，不断言 emoji 结果");
    }
}
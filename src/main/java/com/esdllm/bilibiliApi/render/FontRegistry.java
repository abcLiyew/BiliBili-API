package com.esdllm.bilibiliApi.render;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.io.InputStream;

/**
 * 内置字体注册表。
 *
 * <p><b>为什么要内置字体</b>：{@code Font.createFont(...)} 加载的物理字体由 JDK 自带的
 * FreeType 光栅化，不依赖操作系统已安装的中文字体。无界面 Linux（最小容器）通常一个中文字体都没装，
 * 依赖系统字体就会渲染出一片"豆腐块"。
 *
 * <p><b>⚠️ 但内置字体并不能免除 fontconfig（2026-09-14 真机踩到）</b>：
 * Java 在 Linux 上<b>无法</b>绕过平台字体管理器 —— 即便 {@code Font.createFont} 从流里读字体，
 * 内部也会走 {@code FontManagerFactory.getInstance()} → {@code X11FontManager} →
 * {@code FontConfiguration} → <b>读 fontconfig</b>。在既没装 fontconfig、也没有任何字体目录的
 * 系统上，这一步会抛 <b>{@code java.lang.InternalError: ... Fontconfig head is null}</b>
 * （注意是 {@link Error} 而非 {@link Exception}）。
 * 修复只需在目标机器上执行：{@code apt-get install -y fontconfig fonts-dejavu-core}。
 *
 * <p>本类打包 Noto Sans SC（SIL OFL 1.1，许可证见 {@code /fonts/OFL.txt}），已用
 * {@code tools/build-font-subset.py} 裁到 GB2312 + 常用标点/假名/全角，约 2.4MB。
 *
 * <p><b>缺字策略</b>：{@link #canDisplay(int)} 为 false 的码位由调用方负责兜底显式占位，
 * <b>不允许静默丢字</b>（静默丢字会表现为空白，线上极难发现）。
 */
@Slf4j
public final class FontRegistry {

    /** 内置字体在 classpath 上的位置 */
    public static final String CJK_FONT_RESOURCE = "/fonts/NotoSansSC-Regular-subset.ttf";

    private static volatile Font baseFont;
    private static volatile boolean bundled;

    static {
        // 无界面服务器上的默认值，必须在任何 AWT 类初始化前设置
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private FontRegistry() {
    }

    /**
     * 取指定字号的正文（常规字重）字体。
     *
     * @param size 字号（逻辑像素）
     * @return 字体实例
     */
    public static Font regular(float size) {
        return base().deriveFont(Font.PLAIN, size);
    }

    /**
     * 取指定字号的"强调"字体。
     *
     * <p>注意：内置子集只有 Regular 一个静态字面，{@code deriveFont(Font.BOLD)} 在物理字体上
     * <b>不会</b>合成粗体（Java2D 只做字面匹配，不做伪粗体模拟）。需要加粗的场合请配合
     * {@link Java2DImageRenderer} 的重复描边实现（见 {@code TextPainter}）。
     *
     * @param size 字号
     * @return 字体实例
     */
    public static Font emphasis(float size) {
        return base().deriveFont(Font.BOLD, size);
    }

    /**
     * 该码位内置字体是否有字形。
     *
     * @param codePoint Unicode 码位
     * @return true 表示可直接用内置字体绘制
     */
    public static boolean canDisplay(int codePoint) {
        return base().canDisplay(codePoint);
    }

    /**
     * 是否成功加载了内置字体。为 false 时说明降级到了系统字体，无界面环境可能缺字。
     *
     * @return true 表示使用的是 jar 内置字体
     */
    public static boolean isBundled() {
        base();
        return bundled;
    }

    /** 内置字体 family 名，便于日志/诊断 */
    public static String familyName() {
        return base().getFamily();
    }

    private static Font base() {
        Font f = baseFont;
        if (f == null) {
            synchronized (FontRegistry.class) {
                f = baseFont;
                if (f == null) {
                    f = load();
                    baseFont = f;
                }
            }
        }
        return f;
    }

    private static Font load() {
        try (InputStream in = FontRegistry.class.getResourceAsStream(CJK_FONT_RESOURCE)) {
            if (in != null) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                bundled = true;
                log.info("动态长图渲染：已加载内置字体 {}（family={}）", CJK_FONT_RESOURCE, font.getFamily());
                return font;
            }
            log.warn("动态长图渲染：classpath 上找不到内置字体 {}，回退到系统字体。"
                    + "无界面 Linux 上可能因缺字体渲染出豆腐块。", CJK_FONT_RESOURCE);
        } catch (Throwable t) {
            // ★ 必须是 Throwable 而不是 (FontFormatException | IOException)：
            //   缺 fontconfig 的 Linux 上，Font.createFont 内部初始化平台字体管理器时抛的是
            //   java.lang.InternalError（Error），用 catch(Exception) 系一律兜不住，
            //   会直接穿到调用方（表现为整轮推送在发消息前中断）。
            log.warn("动态长图渲染：内置字体加载失败（{}），回退到系统字体", t);
        }
        bundled = false;
        try {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        } catch (Throwable t) {
            // 连系统字体都拿不到 → 这台机器根本没有可用的字体环境。
            // 这里给出可执行的修复指引，而不是把 InternalError 原样抛给上层。
            throw new IllegalStateException(
                    "当前系统无法使用 Java2D 字体（" + t + "）。Linux 上通常是没装 fontconfig —— "
                            + "执行 `apt-get install -y fontconfig fonts-dejavu-core` 即可；"
                            + "注意即便是库内置字体，Java 也必须先初始化平台字体管理器，同样依赖 fontconfig。",
                    t);
        }
    }
}

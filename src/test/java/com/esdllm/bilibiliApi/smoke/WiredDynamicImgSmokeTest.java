package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.render.FontRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网冒烟测试：{@code Dynamic.getDynamicImg(dynamicId)} 走完整产品门面
 * （默认跳过）。
 *
 * <p>区别于 {@link DynamicRenderPocTest} —— 后者直接调
 * {@code RenderModelLoader.load} + {@code Java2DImageRenderer.render} 两步，
 * 这一支<b>只走门面</b>（{@code Dynamic} 实例 → {@code .getDynamicImg(id)} →
 * 返回 {@link BufferedImage}），看从 XatiiBot 真正会调到的入口到 PNG 的整条链
 * 是否正常。
 *
 * <pre>{@code
 * mvn -o -B test -Dtest=WiredDynamicImgSmokeTest -Dbili.smoke=true
 * mvn -o -B test -Dtest=WiredDynamicImgSmokeTest -Dbili.smoke=true -Dbili.imgId=<动态ID>
 * mvn -o -B test -Dtest=WiredDynamicImgSmokeTest -Dbili.smoke=true -DargLine=-Djava.awt.headless=true
 * }</pre>
 *
 * <p><b>断言重点</b>：
 * <ul>
 *   <li>{@code assertNotNull(img)} —— 关键：门面不能返回 {@code null}
 *       （旧 Selenium 路线在转发/视频类动态上"渲染成功但内容是另一条的空白"，
 *       新路径在取数失败时直接抛异常，不会静默糊一张空壳图）</li>
 *   <li>宽高 ≥ 200 —— 防止字体漏字或图片全抓空导致缩成一条线</li>
 *   <li>PNG 类型严格为 {@code TYPE_INT_RGB}（{@link com.esdllm.bilibiliApi.render.Java2DImageRenderer} 的约定）</li>
 *   <li>PNG 大小 ≥ 5KB —— 防止拿到的是 1×1 的空图</li>
 *   <li>运行环境确认 headless=true、内置字体加载成功</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("联网冒烟：Dynamic.getDynamicImg 完整门面（默认跳过）")
class WiredDynamicImgSmokeTest {

    /**
     * 默认样本：影视飓风 ID {@code 1247440317376888835}（图文 + 9 图，与
     * {@code DynamicRenderPocTest} 用同一个，便于横向对比）。
     */
    private static final String DEFAULT_ID = "1247440317376888835";

    @Test
    @DisplayName("Dynamic.getDynamicImg 走完整门面渲出真实 PNG")
    void wiredRender() throws Exception {
        String id = System.getProperty("bili.imgId", DEFAULT_ID);
        Path outDir = Path.of("target", "smoke");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("wired-" + id + ".png");

        // —— 截图前先打一行"运行环境"，与 DynamicRenderPocTest 风格保持一致 ——
        System.out.printf("运行环境：java.awt.headless=%s GraphicsEnvironment.isHeadless=%s | 内置字体=%s(%s)%n",
                System.getProperty("java.awt.headless"), GraphicsEnvironment.isHeadless(),
                FontRegistry.isBundled(), FontRegistry.familyName());

        Dynamic dynamic = new Dynamic();
        BufferedImage img = dynamic.getDynamicImg(id);

        assertNotNull(img, "getDynamicImg 不可返回 null");
        assertTrue(img.getWidth() > 200, "宽度异常：" + img.getWidth());
        assertTrue(img.getHeight() > 200,
                "高度异常：" + img.getHeight() + "（通常意味着字体漏字或图片全抓空）");
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType(),
                "类型应为 TYPE_INT_RGB（Java2DImageRenderer 的约定）");

        assertTrue(ImageIO.write(img, "png", out.toFile()), "PNG 写盘失败");
        long size = Files.size(out);
        assertTrue(size > 5_000,
                "PNG 文件异常小：" + size + " bytes（" + out.toAbsolutePath() + "）");

        System.out.printf("门面渲染成功：id=%s 尺寸=%dx%d 文件=%s 大小=%d bytes%n",
                id, img.getWidth(), img.getHeight(), out, size);
    }
}

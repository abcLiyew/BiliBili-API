package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.render.FontRegistry;
import com.esdllm.bilibiliApi.render.Java2DImageRenderer;
import com.esdllm.bilibiliApi.render.RenderModel;
import com.esdllm.bilibiliApi.render.RenderModelLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网冒烟测试：Java2D 长图渲染 PoC（默认跳过）。
 *
 * <pre>
 * mvn -o -B test -Dtest=DynamicRenderPocTest -Dbili.smoke=true
 * </pre>
 * 可追加 {@code -Dbili.renderId=&lt;动态id&gt;} 指定被测动态。
 *
 * <p>产物写在 {@code target/smoke/}。它验证的是"无浏览器、无系统字体依赖"这条路走得通：
 * 字体全部来自 jar 内置，绘制全程 headless。
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("联网冒烟：Java2D 长图渲染 PoC（默认跳过）")
class DynamicRenderPocTest {

    /** 默认样本：影视飓风 — 258 字正文 + 9 图网格 */
    private static final String DEFAULT_ID = "1247440317376888835";

    @Test
    @DisplayName("图文动态能渲染出长图（含正文、图片网格、互动数）")
    void 渲染图文动态() throws Exception {
        String id = System.getProperty("bili.renderId", DEFAULT_ID);

        RenderModel model = RenderModelLoader.load(id);
        assertNotNull(model.getDynamicId());
        assertFalse(model.getBlocks().isEmpty(), "内容块不能为空");

        BufferedImage image = new Java2DImageRenderer().render(model);
        assertNotNull(image);
        assertTrue(image.getWidth() > 200, "宽度异常：" + image.getWidth());
        assertTrue(image.getHeight() > 300, "高度异常：" + image.getHeight());

        Path out = Path.of("target", "smoke", "render-" + id + ".png");
        Files.createDirectories(out.getParent());
        assertTrue(ImageIO.write(image, "png", out.toFile()), "PNG 写盘失败");

        System.out.printf("渲染成功：id=%s 尺寸=%dx%d 文件=%s (%d bytes) | 内置字体=%s(%s)%n",
                id, image.getWidth(), image.getHeight(), out.toAbsolutePath(), Files.size(out),
                FontRegistry.isBundled(), FontRegistry.familyName());
        System.out.printf("作者=%s 时间=%s 置顶=%s 块数=%d 转发=%d 评论=%d 点赞=%d%n",
                model.getAuthor().getName(), model.getAuthor().getPubTime(), model.isTop(),
                model.getBlocks().size(), model.getStat().getForward(),
                model.getStat().getComment(), model.getStat().getLike());
        // 无界面环境的证明：headless=true 时 AWT 不会尝试连接显示服务，整条链路仍能出图
        System.out.printf("运行环境：java.awt.headless=%s GraphicsEnvironment.isHeadless=%s%n",
                System.getProperty("java.awt.headless"), GraphicsEnvironment.isHeadless());
    }
}

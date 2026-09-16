package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.render.FontRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 联网冒烟测试：{@code Dynamic.getDynamicImg} 走完整产品门面，
 * <b>覆盖视频/转发动态</b>（默认跳过）。
 *
 * <p>区别于 {@link WiredDynamicImgSmokeTest}（图文 DRAW）：那条用 opus 单端点直出图；
 * 本条通过 opus → v1/detail 回退路径处理 VIDEO 类型，
 * 验证视频卡片（封面 + 标题 + BV 号 + 互动数）能否正常出图。
 *
 * <pre>{@code
 * mvn -o -B test -Dtest=WiredVideoDynamicSmokeTest -Dbili.smoke=true -Dbili.videoId=<动态ID>
 * mvn -o -B test -Dtest=WiredVideoDynamicSmokeTest -Dbili.smoke=true -DargLine=-Djava.awt.headless=true
 * }</pre>
 *
 * <p>默认样本 {@code 1247016318199136288} 是 fixture {@code dynamic-detail-legacy.json}
 * 的同款 ID（DYNAMIC_TYPE_AV：video 类型动态）。
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("联网冒烟：Dynamic.getDynamicImg 视频/转发门面（默认跳过）")
class WiredVideoDynamicSmokeTest {

    /** fixture 已收录的视频样本：DYNAMIC_TYPE_AV（'1247016318199136288'，影视飓风） */
    private static final String DEFAULT_VIDEO_ID = "1247016318199136288";

    @Test
    @DisplayName("视频动态渲染出含封面+标题+BV号的视频卡片图")
    void wiredVideoRender() throws Exception {
        String id = System.getProperty("bili.videoId", DEFAULT_VIDEO_ID);
        Path outDir = Path.of("target", "smoke");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("wired-video-" + id + ".png");

        System.out.printf("运行环境：headless=%s | 内置字体=%s(%s)%n",
                GraphicsEnvironment.isHeadless(),
                FontRegistry.isBundled(), FontRegistry.familyName());

        Dynamic dynamic = new Dynamic();
        BufferedImage img = dynamic.getDynamicImg(id);

        assertNotNull(img, "视频类型动态的渲染结果不可为 null（v1/detail 回退失败会让 load 抛异常）");
        assertTrue(img.getWidth() > 200, "宽度异常：" + img.getWidth());
        assertTrue(img.getHeight() > 200,
                "高度异常：" + img.getHeight() + "（视频卡片至少要有封面 + 标题两块）");
        assertEquals(BufferedImage.TYPE_INT_RGB, img.getType(),
                "类型应为 TYPE_INT_RGB（Java2DImageRenderer 的约定）");

        assertTrue(ImageIO.write(img, "png", out.toFile()), "PNG 写盘失败");
        long size = Files.size(out);
        assertTrue(size > 5_000,
                "PNG 文件异常小：" + size + " bytes（" + out.toAbsolutePath() + "）");

        System.out.printf("视频门面渲染成功：id=%s 尺寸=%dx%d 文件=%s 大小=%d bytes%n",
                id,
                img.getWidth(), img.getHeight(), out, size);
    }
}

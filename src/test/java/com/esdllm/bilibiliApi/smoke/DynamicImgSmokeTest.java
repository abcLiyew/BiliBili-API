package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网冒烟测试：验证「动态长截图 {@code getDynamicImg}」在真实环境里能出图。
 *
 * <p><b>默认不执行</b>，依赖 B 站线上页面 + 无头 Chrome。需要时显式开启：
 * <pre>
 * mvn -o -B test -Dtest=DynamicImgSmokeTest -Dbili.smoke=true
 * </pre>
 * 想换被测动态，追加 {@code -Dbili.imgId=&lt;动态id&gt;}。
 *
 * <p>产物写在 {@code target/smoke/} 下（target 已 gitignore），不会污染仓库根目录
 * —— 这是对旧 {@code DynamicTest#getDynamicImg}（往根目录写 {@code dynamic.png}）的修正。
 *
 * <p>本用例守的是 P2 要改的那条链路（Selenium → Java2D 自绘）：<b>改造前后都必须出图</b>，
 * 它是 P2 的行为基线。
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("联网冒烟：动态长截图（默认跳过）")
class DynamicImgSmokeTest {

    /** 默认被测动态（新格式 19 位 id，取自动态 feed 的 items[].id_str） */
    private static final String DEFAULT_ID = "1247481497478234118";

    @Test
    @DisplayName("真实动态能截出长图，且尺寸合理")
    void 截图() throws Exception {
        String id = System.getProperty("bili.imgId", DEFAULT_ID);

        BufferedImage img = new Dynamic().getDynamicImg(id);

        assertNotNull(img, "截图不能为 null");
        assertTrue(img.getWidth() > 200, "宽度异常：" + img.getWidth());
        assertTrue(img.getHeight() > 200, "高度异常：" + img.getHeight());

        Path out = Path.of("target", "smoke", "dynamic-" + id + ".png");
        Files.createDirectories(out.getParent());
        assertTrue(ImageIO.write(img, "png", out.toFile()), "PNG 写盘失败");

        System.out.printf("截图成功：id=%s 尺寸=%dx%d 文件=%s (%d bytes)%n",
                id, img.getWidth(), img.getHeight(),
                out.toAbsolutePath(), Files.size(out));
    }
}

package com.esdllm.bilibiliApi.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QrImages} 回归：<b>锁住"渲染出的图真的能被扫码器读回"</b>。
 *
 * <p><b>为什么值得单独测</b>：二维码这块所有错误都只表现为"扫不出来"，不会抛异常 ——
 * 尺寸太小／静默区被吃掉／纠错级别配错／颜色反相，任一处出问题，扫码登录冒烟都会卡在
 * "用户扫了半天没反应"，而日志里一切正常。所以这里用解码器把图读回来做闭环断言，
 * 让这类问题在<b>不联网、不扫码</b>的情况下就能被 CI 抓住。
 *
 * <p>不依赖网络，因此<b>不</b>受 {@code -Dbili.smoke=true} 门控，默认就会执行。
 *
 * @author 饿死的流浪猫
 */
@DisplayName("测试工具：二维码渲染（编码 → 解码闭环）")
class QrImagesTest {

    /**
     * 与 B 站登录二维码<b>同形状</b>的内容（131 字符，{@code qrcode_key} 换成占位值）。
     *
     * <p>取自 2026-09-16 对真实端点的实测返回：域名是 {@code account.bilibili.com}、
     * 路径 {@code /h5/account-h5/auth/scan-web} —— 与"想当然"的
     * {@code passport.bilibili.com/h5-app/...} 并不一样。用真实形状做夹具，
     * 长度与字符集才不会偏离实际（长度直接决定二维码版本，版本决定单模块像素）。
     */
    private static final String BILI_LIKE_URL =
            "https://account.bilibili.com/h5/account-h5/auth/scan-web"
                    + "?navhide=1&callback=close&qrcode_key=0123456789abcdef0123456789abcdef&from=";

    @Test
    @DisplayName("渲染的 PNG 能被解码器读回同一内容（读不回 = 用户扫不出来）")
    void encodeDecodeRoundTrip() throws IOException {
        Path png = QrImages.writePng(BILI_LIKE_URL, Path.of("target", "qr-roundtrip-test.png"));

        assertTrue(Files.exists(png), "应该生成图片文件");
        assertTrue(Files.size(png) > 0L, "图片不能是空文件");
        assertEquals(BILI_LIKE_URL, QrImages.decode(png), "解码结果必须与原始内容逐字一致");
    }

    @Test
    @DisplayName("长内容（256 字符，接近二维码容量上限附近）也能闭环")
    void longContentRoundTrip() throws IOException {
        String longUrl = "https://passport.bilibili.com/h5-app/passport/login/scan?navhide=1&from=2222222"
                + "&qrcode_key=0123456789abcdef0123456789abcdef"
                + "&extra=" + "abcdefghij".repeat(10);

        assertEquals(longUrl, QrImages.decode(QrImages.writePng(longUrl, Path.of("target", "qr-roundtrip-long.png"))));
    }

    @Test
    @DisplayName("四角为纯白（静默区没被吃掉，否则部分扫码器找不到定位图案）")
    void quietZonePresent() throws IOException {
        BufferedImage image = QrImages.render(BILI_LIKE_URL, QrImages.DEFAULT_SIZE);
        int width = image.getWidth();
        int height = image.getHeight();

        assertTrue(width > 0 && height > 0, "图不能是空的");
        int white = 0xFFFFFFFF;
        assertEquals(white, image.getRGB(0, 0), "左上角应是静默区（白）");
        assertEquals(white, image.getRGB(width - 1, 0), "右上角应是静默区（白）");
        assertEquals(white, image.getRGB(0, height - 1), "左下角应是静默区（白）");
        assertEquals(white, image.getRGB(width - 1, height - 1), "右下角应是静默区（白）");
    }

    @Test
    @DisplayName("图里只有纯黑与纯白（灰度抖动会让低端扫码器识别率下降）")
    void onlyBlackAndWhite() throws IOException {
        BufferedImage image = QrImages.render(BILI_LIKE_URL, QrImages.DEFAULT_SIZE);
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;

        // 抽样即可：整图逐像素扫一遍对断言没有额外价值，还会拖慢测试
        for (int y = 0; y < image.getHeight(); y += 7) {
            for (int x = 0; x < image.getWidth(); x += 7) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                assertTrue(rgb == (black & 0xFFFFFF) || rgb == (white & 0xFFFFFF),
                        "像素 (" + x + "," + y + ") 既非纯黑也非纯白：" + Integer.toHexString(rgb));
            }
        }
    }
}

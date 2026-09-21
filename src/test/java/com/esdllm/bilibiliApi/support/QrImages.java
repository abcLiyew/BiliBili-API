package com.esdllm.bilibiliApi.support;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 测试期二维码渲染工具（<b>仅测试代码使用</b>）。
 *
 * <p><b>为什么不放进主源码</b>：{@code Login} 门面的职责止于"拿到二维码内容"，把它画成图是
 * 调用方的事（服务端场景可能画到网页上、可能直接用手机打开，未必需要本地 PNG）。
 * 为了不让一个"只服务于联调"的能力进入下游依赖树，zxing 依赖限定在 {@code scope=test}。
 *
 * <p><b>为什么引 zxing</b>：JDK 没有二维码能力，自写编码器需要 Reed-Solomon 纠错 + 掩码评估
 * 几百行，且写错了只表现为"扫不出来"（不报错）—— 典型的负收益。zxing core 是成熟实现，
 * 且本机能离线解析到 jar。
 *
 * <p><b>为什么 {@link #render} 不设 {@code CHARACTER_SET}</b>：B 站登录二维码内容是纯 ASCII 的
 * 登录页地址，默认的 ISO-8859-1 编码兼容性最好（显式设成 UTF-8 时，部分扫码器会因 ECI 头
 * 处理差异而识别失败）。
 *
 * @author 饿死的流浪猫
 */
public final class QrImages {

    /**
     * 默认图片边长（像素）。
     *
     * <p>600 是"手机上能稳定对焦"与"屏幕上一眼看得全"的折中：B 站登录二维码内容约 100 字符，
     * M 级纠错下版本约 7（45 个模块），加静默区后单模块仍有 12px 左右，远高于识别阈值。
     */
    public static final int DEFAULT_SIZE = 600;

    /**
     * 静默区宽度（模块数）。
     *
     * <p>QR 规范推荐 4，这里取 2：图片本身是独立的白色背景，屏幕上已有足够留白。
     * 再少就会让部分扫码器"找不到定位图案"（识别率骤降，且表现为随机失败）。
     */
    public static final int QUIET_ZONE_MODULES = 2;

    private QrImages() {
    }

    /**
     * 把内容渲染成 PNG 落盘。
     *
     * @param content 二维码内容
     * @param target  目标文件路径（父目录不存在会自动创建）
     * @return {@code target}
     * @throws IOException 编码或写盘失败
     */
    public static Path writePng(String content, Path target) throws IOException {
        return writePng(content, target, DEFAULT_SIZE);
    }

    /**
     * 把内容渲染成指定边长的 PNG 落盘。
     *
     * @param content 二维码内容
     * @param target  目标文件路径
     * @param size    图片边长（像素）
     * @return {@code target}
     * @throws IOException 编码或写盘失败
     */
    public static Path writePng(String content, Path target, int size) throws IOException {
        BufferedImage image = render(content, size);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    /**
     * 把内容渲染成黑白位图（不落盘）。
     *
     * @param content 二维码内容
     * @param size    图片边长（像素）
     * @return 仅含纯黑（{@code 0xFF000000}）与纯白（{@code 0xFFFFFFFF}）的 RGB 图
     * @throws IOException 编码失败
     */
    public static BufferedImage render(String content, int size) throws IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // M 级纠错：屏幕扫码足够稳，比 H 级少占容量 —— 版本更低 = 单模块更大 = 更好扫
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);

        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException e) {
            throw new IOException("二维码生成失败：" + e.getMessage(), e);
        }

        // 不用 zxing 的 MatrixToImageWriter（那在 javase 包里）：自己写 20 行就够，
        // 少引一个 jar 就少一处离线解析风险。刻意用纯黑白两色，避免灰度抖动影响识别。
        BufferedImage image = new BufferedImage(
                matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }

    /**
     * 把 PNG 读回并解码，用于<b>验证渲染结果真的可扫</b>。
     *
     * <p>"图生成了"与"图能扫"是两件事：尺寸、静默区、纠错级别、颜色任一处理错，
     * 都只会表现为扫不出来（不报错）。所以用解码器回读一遍，把这件事变成可断言的。
     *
     * @param png 图片路径
     * @return 解码出的内容
     * @throws IOException 图片不可读，或无法被解码（即"扫不出来"）
     */
    public static String decode(Path png) throws IOException {
        BufferedImage image = ImageIO.read(png.toFile());
        if (image == null) {
            throw new IOException("不是可识别的图片格式：" + png);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return result.getText();
        } catch (NotFoundException e) {
            throw new IOException("二维码无法被解码器读回（图片存在但扫不出来）：" + png, e);
        }
    }
}

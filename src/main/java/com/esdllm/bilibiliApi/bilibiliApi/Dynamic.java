package com.esdllm.bilibiliApi.bilibiliApi;



import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.adapter.DynamicSchemaAdapter;
import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.*;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;

@Slf4j
public class Dynamic {
    /**
     * 动态列表信息
     */
    @Data
    public static class DynamicInfo {
        /**
         * 动态ID，如果为null，则该条动态为转发动态
         */
        private String dynamicId;
        /**
         * 标签，只有置顶动态有值，并且值为“置顶"
         */
        private String tag;
        /**
         * 发布时间+动作，如“04月20日 · 发布了动态视频”，“04月20日 · 投稿了视频”，如果是直播动态则值为“直播了”
         */
        private String time;
        /**
         * 标题
         */
        private String title;
        /**
         * 内容
         */
        private String desc;
        /**
         * 图片链接，如果为空数组，则该条动态不是图文投稿
         */
        private List<String> imageUrl;
        /**
         * 视频BV号，如果为null，则该条动态不是视频投稿
         */
        private String bvid;
        /**
         * 转发动态ID，如果为null，则该条动态不是转发动态
         */
        private String shareDynamicId;
    }

    /**
     * 获取动态详情。
     *
     * <p>端点：{@code x/polymer/web-dynamic/v1/detail?id={dynamicId}}（实测匿名可用）。
     * 旧端点所在的 {@code api.vc.bilibili.com/dynamic_svr} 已整站下线（HTTP 404），
     * 换源前本方法在真实调用下必然失败。
     *
     * <p><b>异常约定</b>：所有失败在门面边界统一转成签名里声明的 {@link IOException}。
     * 原因是下游 XatiiBot 的两个调用点（{@code BilibiliAnalysisImpl.java:49}、
     * {@code ShortChain.getDynamicCard()}）都<b>只有 {@code catch (IOException)}</b> ——
     * 若抛 RuntimeException（含 {@code BilibiliException}），异常会直接穿透出去把消息处理器打挂，
     * 连日志都留不下。库内部仍然统一用 {@code BilibiliException}，只在这一层做转换。
     *
     * @param dynamicId 动态ID（opus id / dynamic id 均可，新旧格式都支持）
     * @return 动态卡片详情
     * @throws IOException IO异常，或取数失败（消息里含 B 站 code 与语义化说明）
     */
    public BilibiliDynamicResp.Data.Card getDynamicDetail(String dynamicId) throws IOException {
        if (dynamicId == null || dynamicId.isEmpty()) {
            // 参数校验属于调用方编程错误，保持原有的 BilibiliException（不受 IOException 影响）
            throw new BilibiliException("动态ID不能为空");
        }
        try {
            JSONObject item = requestDynamicItem(dynamicId);
            // 两套 schema（LEGACY / DESKTOP）在这里收敛成冻结模型
            return DynamicSchemaAdapter.toCard(item);
        } catch (BilibiliException e) {
            // 门面边界：库内统一的 BilibiliException → 契约声明的 IOException
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 请求详情接口并取出 {@code data.item}。
     *
     * @param dynamicId 动态ID
     * @return 响应里的 item 节点
     * @throws IOException 网络层失败
     */
    private JSONObject requestDynamicItem(String dynamicId) throws IOException {
        String url = BilibiliConfig.dynamicDetailUrl + dynamicId;

        HttpResponse<String> response;
        try {
            response = ApiBase.getCloseableHttpResponse(url);
        } catch (Exception e) {
            throw new IOException("获取动态详情失败：请求发送异常：" + e.getMessage(), e);
        }
        if (response == null) {
            throw new IOException("获取动态详情失败：请求无响应");
        }

        // HTTP 层先拦一道（412 风控必须有可读提示，且明确"不可重试"）
        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), "获取动态详情");
        if (httpError != null) {
            throw new IOException(httpError.getMessage(), httpError);
        }

        ApiResponse<JSONObject> resp;
        try {
            resp = JSON.parseObject(response.getBody(), new TypeReference<ApiResponse<JSONObject>>() {
            });
        } catch (Exception e) {
            throw new IOException("获取动态详情失败：响应不是合法 JSON（前 120 字："
                    + brief(response.getBody()) + "）", e);
        }

        // code 校验：4101139（参数名错）/ 4101105（id 不存在）等都会在这里被语义化
        JSONObject data = ResponseParserSupport.unwrap(resp, "获取动态详情");

        JSONObject item = data.getJSONObject("item");
        if (item == null) {
            throw new IOException("获取动态详情失败：data 里没有 item");
        }
        return item;
    }

    /** 截断响应体，避免把整页 HTML/JS 塞进异常消息 */
    private static String brief(String body) {
        if (body == null) {
            return "null";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    /**
     * 获取动态图片
     * @param dynamicId 动态ID
     * @return BufferedImage 动态图片
     */

    public BufferedImage getDynamicImg(String dynamicId) throws InterruptedException {
        // 配置 ChromeOptions
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless"); // 无头模式，不打开浏览器窗口
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1280,10000"); // 增加窗口宽度和高度
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        // 创建 WebDriver
        WebDriver driver = new ChromeDriver(options);
        log.info("正在加载页面...");
        try {
            driver.get(BilibiliConfig.dynamicInfoUrl + dynamicId);

            // 等待页面加载完成
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".bili-opus-view")));
            } catch (Exception e) {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".bili-dyn-item")));
            }


            // 使用JavaScript移除评论区和其他不需要的元素，并展开所有折叠内容
            ((JavascriptExecutor) driver).executeScript(
                    "var style = document.createElement('style'); " +
                            "style.innerHTML = 'body { font-family: \"WenQuanYi Zen Hei\", \"DejaVu Sans\", sans-serif !important; }'; " +
                            "document.head.appendChild(style);"+
                    "var comments = document.querySelector('.opus-module-section.comment-container'); " +
                            "if(comments) comments.remove(); " +
                            "var header = document.querySelector('.z-top-container'); " +
                            "if(header) header.remove(); " +
                            "var footer = document.querySelector('.opus-detail-app'); " +
                            "if(footer) footer.remove(); " +
                            "var rightPanel = document.querySelector('.bili-tabs.opus-tabs'); " +
                            "if(rightPanel) rightPanel.remove(); " +
                            // 尝试展开所有可能的折叠内容
                            "var expandButtons = document.querySelectorAll('.expand-btn'); " +
                            "expandButtons.forEach(function(btn) { btn.click(); });" +
                            // 移除可能影响截图的浮动元素
                            "var floatElements = document.querySelectorAll('.float-panel, .fixed-panel, .popup-panel'); " +
                            "floatElements.forEach(function(el) { if(el) el.remove(); });" );

            log.info("页面加载完成，正在截图...");
            // 定位动态内容区域
            WebElement dynamicContent = driver.findElement(By.cssSelector(".bili-opus-view"));

            // 获取动态内容的实际高度
            Long scrollHeight = (Long) ((JavascriptExecutor) driver).executeScript(
                    "return arguments[0].scrollHeight", dynamicContent);

            // 使用JavaScript调整内容区域的样式，确保文字不重叠
            ((JavascriptExecutor) driver).executeScript(
                    "var textElements = document.querySelectorAll('.opus-module-content p, .opus-module-content span, .opus-module-content div');" +
                            "for(var i=0; i<textElements.length; i++) {" +
                            "  var el = textElements[i];" +
                            "  el.style.lineHeight = '1.5';" +
                            "  el.style.letterSpacing = '0.5px';" +
                            "  el.style.position = 'static';" +
                            "}");
            // 获取动态内容的宽度和位置
            int contentWidth = dynamicContent.getRect().width;
            int contentX = dynamicContent.getRect().x;


            // 使用新方法：直接设置窗口大小为内容大小，然后一次性截图
            Dimension originalSize = driver.manage().window().getSize();
            // 增加额外的高度余量，宽度也增加以容纳右移的内容
            if (scrollHeight != null) {
                driver.manage().window().setSize(new Dimension(contentX + contentWidth-10, scrollHeight.intValue() + 300));
            }


            // 滚动到顶部
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");

            // 尝试使用分段截图方法
            BufferedImage fullImg;

            // 方法1：如果内容不是特别长，尝试一次性截图
            if (scrollHeight!=null&&scrollHeight < 15000) {
                File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                fullImg = ImageIO.read(screenshot);
            } else {
                // 方法2：内容太长，使用分段截图并拼接
                int viewportHeight = ((Long) Objects.requireNonNull(((JavascriptExecutor) driver).executeScript(
                        "return window.innerHeight"))).intValue();
                int totalHeight = 0;
                if (scrollHeight != null) {
                    totalHeight = scrollHeight.intValue();
                }

                // 创建一个足够大的图像来存储完整页面
                fullImg = new BufferedImage(contentX + contentWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = fullImg.createGraphics();

                int yPosition = 0;
                while (yPosition < totalHeight) {
                    // 滚动到指定位置
                    ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, " + yPosition + ");");

                    // 截取当前可见区域
                    File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    BufferedImage partImg = ImageIO.read(screenshot);

                    // 将部分图像复制到完整图像
                    graphics.drawImage(partImg, 0, yPosition, null);

                    // 移动到下一部分
                    yPosition += viewportHeight - 100; // 减去100像素以确保重叠，避免遗漏内容
                }

                graphics.dispose();
            }

            // 计算裁剪的起始位置，确保包含右移后的内容
            int cropX = Math.max(0, contentX-100); // 左边界留一些余量
            int cropWidth = Math.min(contentWidth+800, fullImg.getWidth() - cropX); // 宽度加一些余量
            int cropHeight = 0;
            if (scrollHeight != null) {
                cropHeight = Math.max(scrollHeight.intValue(), fullImg.getHeight());
            }

            // 确保裁剪区域不超出图像边界，否则 getSubimage 会抛 RasterFormatException
            cropX = Math.max(0, Math.min(cropX, fullImg.getWidth() - 1));
            cropWidth = Math.max(1, Math.min(cropWidth, fullImg.getWidth() - cropX));
            cropHeight = Math.max(1, Math.min(cropHeight - 100, fullImg.getHeight()));
            log.info("Full image size: {} x {}", fullImg.getWidth(), fullImg.getHeight());
            log.info("Crop region: x={}, y=0, width={}, height={}", cropX, cropWidth, cropHeight);
            BufferedImage croppedImg = fullImg.getSubimage(cropX, 0, cropWidth, cropHeight);



            // 恢复原始窗口大小
            driver.manage().window().setSize(originalSize);
            return croppedImg;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            driver.quit();
        }
    }

    /**
     * 获取动态列表信息
     * @param uid 用户UID
     * @return List<DynamicInfo> 动态列表信息
     */
    public List<DynamicInfo> getDynamicInfoList(String uid) throws InterruptedException {
        List<DynamicInfo> dynamicInfoList = new ArrayList<>();
        String url = String.format(BilibiliConfig.dynamicListUrl, uid);
        log.info("正在获取动态列表:{}", url);
        // 配置 ChromeOptions
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless"); // 无头模式，不打开浏览器窗口
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=2080,1920");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        // 创建 ChromeDriver
        WebDriver driver = new ChromeDriver(options);
        String pageSource;
        try {
            driver.get(url);
            // 等待页面加载完成
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".bili-dyn-list")));
            } catch (Exception e) {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".bili-dyn-list__items")));

            }  // 获取页面源代码
            pageSource = driver.getPageSource();

            log.info("正在解析页面...");

            // 使用 Jsoup 解析页面
            Document doc = null;
            if (pageSource != null) {
                doc = Jsoup.parse(pageSource, url);
            }
            Elements dynamicElements = null;
            if (doc != null) {
                dynamicElements = doc.getElementsByClass("bili-dyn-item");
            }
            if (dynamicElements != null) {
                for (Element dynamicElement : dynamicElements) {
                    DynamicInfo dynamicInfo = new DynamicInfo();
                    if (!dynamicElement.getElementsByClass("bili-dyn-content__orig__author").isEmpty()){
                        String shareDynamicId = dynamicElement.getElementsByClass("bili-dyn-content__orig__major")
                                .get(0).getElementsByClass("dyn-card-opus")
                                .get(0).attr("dyn-id");
                        dynamicInfo.setShareDynamicId(shareDynamicId);
                    }
                    if (!dynamicElement.getElementsByClass("bili-dyn-tag__text").isEmpty()){
                        String tag = dynamicElement.getElementsByClass("bili-dyn-tag__text").get(0).text();
                        dynamicInfo.setTag(tag);
                    }
                    if (!dynamicElement.getElementsByClass("dyn-card-opus").isEmpty()&&dynamicInfo.getShareDynamicId()==null) {
                        String dyn_id = dynamicElement.getElementsByClass("dyn-card-opus").get(0).attr("dyn-id");
                        dynamicInfo.setDynamicId(dyn_id);
                    }
                    if (!dynamicElement.getElementsByClass("bili-dyn-time fs-small bili-ellipsis").isEmpty()){
                        String time = dynamicElement.getElementsByClass("bili-dyn-time fs-small bili-ellipsis").get(0).text();
                        dynamicInfo.setTime(time);
                    }
                    if (!dynamicElement.getElementsByClass("dyn-card-opus__title").isEmpty()&&dynamicInfo.getDynamicId()!=null){
                        String title = dynamicElement.getElementsByClass("dyn-card-opus__title").get(0).text();
                        dynamicInfo.setTitle(title);
                    }
                    if (!dynamicElement.getElementsByClass("bili-rich-text__content").isEmpty()){
                        String desc = dynamicElement.getElementsByClass("bili-rich-text__content").get(0).text();
                        dynamicInfo.setDesc(desc);
                    }
                    if(!dynamicElement.getElementsByTag("img").isEmpty()){
                        List<String> imageUrl = new ArrayList<>();
                        for (Element img : dynamicElement.getElementsByTag("img")) {
                            String src = img.attr("src");
                            if (src.startsWith("//")) {
                                src = "https:" + src;
                            }
                            if (src.indexOf('@')>0){
                                src = src.substring(0, src.indexOf('@'));
                            }
                            if (src.isEmpty()){
                                continue;
                            }
                            if (imageUrl.contains(src)){
                                continue;
                            }
                            if (!src.contains("i0.hdslb.com/bfs/")){
                                continue;
                            }
                            if (src.contains("emote")||src.contains("face")){
                                continue;
                            }
                            imageUrl.add(src);
                        }
                        dynamicInfo.setImageUrl(imageUrl);
                    }
                    if (!dynamicElement.getElementsByTag("a").isEmpty()){
                        for (Element a : dynamicElement.getElementsByTag("a")) {
                            if (!a.attr("href").isEmpty()){
                                String videoUrl = a.attr("href");
                                if (videoUrl.startsWith("//")){
                                    videoUrl = videoUrl.substring(2);
                                }
                                String[] split = videoUrl.split("/");
                                for (String s : split){
                                    if (s.startsWith("BV")){
                                        dynamicInfo.setBvid(s.substring(0, 12));
                                    }
                                }
                            }
                        }
                    }
                    dynamicInfoList.add(dynamicInfo);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            //关闭 ChromeDriver
            driver.quit();
        }
        return dynamicInfoList;
    }
}

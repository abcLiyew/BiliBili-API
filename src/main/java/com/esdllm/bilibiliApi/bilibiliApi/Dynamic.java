package com.esdllm.bilibiliApi.bilibiliApi;



import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
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
         * 标签
         */
        private String tag;
        /**
         * 发布时间
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
         * 图片链接，如果为null，则该条动态不是图文投稿
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
     * 获取动态详情
     * @param dynamicId 动态ID
     * @return 动态卡片详情
     * @throws IOException IO异常
     */
    public BilibiliDynamicResp.Data.Card getDynamicDetail(String dynamicId) throws IOException {
        if(dynamicId == null || dynamicId.isEmpty()){
            throw new BilibiliException("动态ID不能为空");
        }
        BilibiliDynamicResp resp;
        String baseUrl = BilibiliConfig.dynamicBaseUrl;
        String url = baseUrl + dynamicId;

        try  {
            HttpResponse<String> response = ApiBase.getCloseableHttpResponse(url);
            resp = JSON.parseObject(response.getBody(), BilibiliDynamicResp.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (Objects.isNull(resp) ||resp.getCode() != 0){
            throw new BilibiliException("获取动态详情失败");
        }
        return resp.getData().getCard();
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
        Thread.sleep(0);

        // 创建 WebDriver
        WebDriver driver = new ChromeDriver(options);
        Thread.sleep(0);
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

            // 获取动态内容的宽度和位置
            int contentWidth = dynamicContent.getRect().width;
            int contentX = dynamicContent.getRect().x;
            dynamicContent.getRect();
            // 使用JavaScript调整内容区域的样式，确保文字不重叠
            ((JavascriptExecutor) driver).executeScript(
                    "var textElements = document.querySelectorAll('.opus-module-content p, .opus-module-content span, .opus-module-content div');" +
                            "for(var i=0; i<textElements.length; i++) {" +
                            "  var el = textElements[i];" +
                            "  el.style.lineHeight = '1.5';" +
                            "  el.style.letterSpacing = '0.5px';" +
                            "  el.style.position = 'static';" +
                            "}");


            // 使用新方法：直接设置窗口大小为内容大小，然后一次性截图
            Dimension originalSize = driver.manage().window().getSize();
            // 增加额外的高度余量，宽度也增加以容纳右移的内容
            if (scrollHeight != null) {
                driver.manage().window().setSize(new Dimension(contentX + contentWidth + 150, scrollHeight.intValue() + 400));
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
            int cropX = Math.max(0, contentX-50); // 左边界留一些余量
            int cropWidth = Math.min(contentWidth+800, fullImg.getWidth() - cropX); // 宽度加一些余量
            int cropHeight = 0;
            if (scrollHeight != null) {
                cropHeight = Math.max(scrollHeight.intValue(), fullImg.getHeight());
            }

            // 确保裁剪区域不超出图像边界
            if (cropX + cropWidth > fullImg.getWidth()) {
                cropWidth = fullImg.getWidth() - cropX;
            }

            BufferedImage croppedImg = fullImg.getSubimage(cropX+125, 0, cropWidth, cropHeight-500);



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
        DynamicInfo dynamicInfo = new DynamicInfo();
        String url = String.format(BilibiliConfig.dynamicListUrl, uid);
        log.info("正在获取动态列表:{}", url);
        // 配置 ChromeOptions
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless"); // 无头模式，不打开浏览器窗口
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=2080,1920");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        Thread.sleep(0);

        // 创建 ChromeDriver
        WebDriver driver = new ChromeDriver(options);
        try {
            driver.get(url);
            // 等待页面加载完成
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".bili-dyn-item")));

            // 获取页面源代码
            String pageSource = driver.getPageSource();

            //关闭 ChromeDriver
            driver.quit();
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
                    if (!dynamicElement.getElementsByClass("dyn-card-opus").isEmpty()&&dynamicInfo.shareDynamicId==null) {
                        String dyn_id = dynamicElement.getElementsByClass("dyn-card-opus").get(0).attr("dyn-id");
                        dynamicInfo.setDynamicId(dyn_id);
                    }
                    if (!dynamicElement.getElementsByClass("bili-dyn-time fs-small bili-ellipsis").isEmpty()){
                        String time = dynamicElement.getElementsByClass("bili-dyn-time fs-small bili-ellipsis").get(0).text();
                        dynamicInfo.setTime(time);
                    }
                    if (!dynamicElement.getElementsByClass("dyn-card-opus__title").isEmpty()&&dynamicInfo.dynamicId!=null){
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
                            if (src.contains("emote")){
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
                    dynamicInfo = new DynamicInfo();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return dynamicInfoList;
    }
}

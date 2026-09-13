package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import com.esdllm.bilibiliApi.model.data.pojo.Card;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
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
import java.util.Objects;

@Slf4j
@Disabled("联网手测用例：依赖 B 站线上接口与本地 Chrome，无断言、会往项目根目录写截图，不参与自动构建（见 REFACTOR_PLAN.md P3）")
class CardInfoTest {
    private CardInfo cardInfo = new CardInfo();
    private long uid = 3546774476163227L;

    @Test
    void getBilibiliLiveResp() {
        try {
            BilibiliCardResp resp = cardInfo.getBilibiliLiveResp(uid);
            System.out.println(resp);
        } catch (BilibiliException | IOException e) {
           log.error(e.getMessage());
        }
    }

    @Test
    void getArchiveCount() {
            Integer archiveCount = cardInfo.getArchiveCount(uid);
            System.out.println(archiveCount);
    }

    @Test
    void getUserName() {
        String userName = cardInfo.getUserName(uid);
        System.out.println(userName);
    }

    @Test
    void getFace() {
        String face = cardInfo.getFace(uid);
        System.out.println(face);
    }

    @Test
    void getLevel() {
        Integer level = cardInfo.getLevel(uid);
        System.out.println(level);
    }

    @Test
    void getSign() {
        String sign = cardInfo.getSign(uid);
        System.out.println(sign);
    }
    @Test
    void getFollower() {
        Integer follower = cardInfo.getFollower(uid);
        System.out.println(follower);
    }
    @Test
    void getLikeNum() {
        Integer likeNum = cardInfo.getLikeNum(uid);
        System.out.println(likeNum);
    }
    @Test
    void getCardTest() {
        Card card = cardInfo.getCard(uid);
        System.out.println(card);
    }
    @Test
    void testJsoup() throws IOException {
        // 设置 ChromeDriver 路径（如果未添加到系统环境变量）
        // System.setProperty("webdriver.chrome.driver", "path/to/chromedriver");

        String url = "https://space.bilibili.com/3546774476163227/dynamic";
        System.out.println("Loading page now-----------------------------------------------: " + url);

        // 配置 ChromeOptions
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless"); // 无头模式，不打开浏览器窗口
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=2080,1920");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");

        WebDriver driver = new ChromeDriver(options);

        String id = "1055444954124386328";

        /*try {
            driver.get(url);

            // 等待页面加载完成
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("bili-dyn-item")));

            // 获取页面源代码
            String pageSource = driver.getPageSource();

            // 使用 Jsoup 解析页面
            Document doc = null;
            if (pageSource != null) {
                doc = Jsoup.parse(pageSource, url);
            }
            if (doc != null) {
                Elements pngs = doc.select("img[src$=.png]"); // 获取所有图片元素集
            }
            Elements elements = null;
            if (doc != null) {
                elements = doc.getElementsByClass("bili-dyn-item");
            }
            if (elements != null) {
                if (!elements.isEmpty() &&elements.get(0).getElementsByClass("bili-dyn-tag__text").get(0).text().equals("置顶")){
                    Element opsid = elements.get(2).getElementsByClass("dyn-card-opus").get(0);
                    System.out.println(opsid);
                    id = opsid.attr("dyn-id");
                }
            }
            if (elements != null) {
                System.out.println("elements.size() = " + elements.size());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }*/
        try {
            driver.get("https://www.bilibili.com/opus/" + id);
            Thread.sleep(5000);
            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            FileUtils.copyFile(screenshot, new File("screenshot.png"));

        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            driver.quit();
        }

    }
/*@Test
void test111() throws IOException, InterruptedException {
    // 配置 ChromeOptions
    ChromeOptions options = new ChromeOptions();
    options.addArguments("--headless"); // 无头模式，不打开浏览器窗口
    options.addArguments("--disable-gpu");
    options.addArguments("--window-size=1280,10000"); // 增加窗口宽度和高度
    options.addArguments("--no-sandbox");
    options.addArguments("--disable-dev-shm-usage");
    
    // 设置日志级别
    System.setProperty("webdriver.chrome.silentOutput", "true");
    java.util.logging.Logger.getLogger("org.openqa.selenium").setLevel(java.util.logging.Level.OFF);

    WebDriver driver = new ChromeDriver(options);

    String id = "1020454757493375033";

    try {
        driver.get("https://www.bilibili.com/opus/" + id);

        // 等待页面加载完成
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".bili-opus-view")));
        
        // 等待内容完全加载，增加等待时间
        Thread.sleep(5000);
        
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
        
        // 再等待一下，确保DOM更新
        Thread.sleep(2000);
        
        // 定位动态内容区域
        WebElement dynamicContent = driver.findElement(By.cssSelector(".opus-module-content"));
        
        // 获取动态内容的实际高度
        Long scrollHeight = (Long) ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].scrollHeight", dynamicContent);
        
        // 获取动态内容的宽度和位置
        int contentWidth = dynamicContent.getRect().width;
        int contentX = dynamicContent.getRect().x;
        int contentY = dynamicContent.getRect().y;
        
        System.out.println("内容区域: 宽=" + contentWidth + ", X=" + contentX + ", Y=" + contentY + ", 高=" + scrollHeight+300);
        
        // 使用JavaScript调整内容区域的样式，确保文字不重叠
        ((JavascriptExecutor) driver).executeScript(
                "var textElements = document.querySelectorAll('.opus-module-content p, .opus-module-content span, .opus-module-content div');" +
                "for(var i=0; i<textElements.length; i++) {" +
                "  var el = textElements[i];" +
                "  el.style.lineHeight = '1.5';" +
                "  el.style.letterSpacing = '0.5px';" +
                "  el.style.position = 'static';" +
                "}");
        
        // 再次等待DOM更新
        Thread.sleep(2000);
        
        // 使用新方法：直接设置窗口大小为内容大小，然后一次性截图
        Dimension originalSize = driver.manage().window().getSize();
        // 增加额外的高度余量，宽度也增加以容纳右移的内容
        if (scrollHeight != null) {
            driver.manage().window().setSize(new Dimension(contentX + contentWidth + 150, scrollHeight.intValue() + 400));
        }

        // 等待窗口大小调整完成
        Thread.sleep(2000);
        
        // 滚动到顶部
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        Thread.sleep(1000);
        
        // 尝试使用分段截图方法
        BufferedImage fullImg;
        
        // 方法1：如果内容不是特别长，尝试一次性截图
        if (scrollHeight < 15000) {
            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            fullImg = ImageIO.read(screenshot);
        } else {
            // 方法2：内容太长，使用分段截图并拼接
            int viewportHeight = ((Long) Objects.requireNonNull(((JavascriptExecutor) driver).executeScript(
                    "return window.innerHeight"))).intValue();
            int totalHeight = scrollHeight.intValue();
            
            // 创建一个足够大的图像来存储完整页面
            fullImg = new BufferedImage(contentX + contentWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = fullImg.createGraphics();
            
            int yPosition = 0;
            while (yPosition < totalHeight) {
                // 滚动到指定位置
                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, " + yPosition + ");");
                Thread.sleep(500); // 等待滚动和渲染
                
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
        
        // 保存原始截图用于调试
        File fullScreenshot = new File("full_screenshot_debug.png");
        ImageIO.write(fullImg, "png", fullScreenshot);
        
        // 裁剪掉可能的多余部分，只保留动态内容区域
        // 计算裁剪的起始位置，确保包含右移后的内容
        int cropX = Math.max(0, contentX-50); // 左边界留一些余量
        int cropWidth = Math.min(contentWidth+800, fullImg.getWidth() - cropX); // 宽度加一些余量
        int cropHeight = Math.max(scrollHeight.intValue(), fullImg.getHeight());

        // 确保裁剪区域不超出图像边界
        if (cropX + cropWidth > fullImg.getWidth()) {
            cropWidth = fullImg.getWidth() - cropX;
        }
        
        BufferedImage croppedImg = fullImg.getSubimage(cropX+125, 0, cropWidth, cropHeight-100);
        
        // 保存裁剪后的截图
        File dynamicScreenshot = new File("dynamic_content_full.png");
        ImageIO.write(croppedImg, "png", dynamicScreenshot);
        
        System.out.println("截图已保存: " + dynamicScreenshot.getAbsolutePath());
        
        // 恢复原始窗口大小
        driver.manage().window().setSize(originalSize);

    } catch (Exception e) {
        e.printStackTrace();
    } finally {
        driver.quit();
    }
}*/

    @Test
    void testHtmlUnit() throws IOException {
        System.out.println(File.separator);
    }
}
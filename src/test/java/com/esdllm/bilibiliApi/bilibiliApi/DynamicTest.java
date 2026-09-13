package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("联网手测用例：依赖 B 站线上接口、无断言，且会往项目根目录写截图，不参与自动构建（见 REFACTOR_PLAN.md P3）")
class DynamicTest {
    @Test
    void getDynamicDetail() {
        Dynamic dynamic = new Dynamic();
        try {
            BilibiliDynamicResp.Data.Card card = dynamic.getDynamicDetail("1055444954124386328");
            System.out.println(card);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @Test
    void getDynamicImg() throws IOException, InterruptedException {
        Dynamic dynamic = new Dynamic();
        BufferedImage dynamicImg = dynamic.getDynamicImg("1020454757493375033");
        System.out.println("dynamicImg = " + dynamicImg);
        File file = new File("dynamic.png");
        boolean write = ImageIO.write(dynamicImg, "png", file);
        assertTrue(write);
    }

    @Test
    void testGetInfo() throws IOException, InterruptedException {
        Dynamic dynamic = new Dynamic();
        List<Dynamic.DynamicInfo> dynamicInfo = dynamic.getDynamicInfoList("3546774476163227");
        for (Dynamic.DynamicInfo info : dynamicInfo) {
            System.out.println("id = " + info.getDynamicId());
            System.out.println("title = " + info.getTitle());
            System.out.println("desc = " + info.getDesc());
            System.out.println("time = " + info.getTime());
            System.out.println("tag = " + info.getTag());
            System.out.println("Bvid = " + info.getBvid());
            System.out.println("ImgUrl = "+ info.getImageUrl());
            System.out.println("shareDynamicId = " + info.getShareDynamicId());
            System.out.println("------------------------------------------------------");
        }
    }
    @Test
    void testGetInfoAsync(){
        Thread thread = new Thread(() -> {
            try {
                testGetInfo();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread thread1 = new Thread(() -> {
            try {
                getDynamicImg();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
        thread1.start();
        try {
            thread.join();
            thread1.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
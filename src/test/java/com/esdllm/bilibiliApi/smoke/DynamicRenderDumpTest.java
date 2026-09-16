package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.render.RenderModel;
import com.esdllm.bilibiliApi.render.RenderModelLoader;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 诊断工具：把一条动态的渲染链路"摊开"给人看 —— 打印 {@link RenderModel}、
 * 导出 opus/detail 两端点的原始 JSON、并把长图落盘成 PNG。
 *
 * <p><b>为什么值得长期留着</b>：排查"图不对/缺标题/空白卡片"这类问题时，
 * 光看日志只能知道"渲染成功了"，看不出"图里到底有什么"。而渲染器的输出<b>完全由 RenderModel 决定</b>，
 * 所以打印模型等价于"这张图里有什么"。2026-09-14 就是靠它定位出
 * 「opus 的 MODULE_TYPE_TITLE 被忽略」与「LIVE_RCMD 标题读错路径」两个 bug。
 *
 * <p>默认跳过（不联网）。跑法：
 * <pre>
 * mvn -o -B test "-Dtest=DynamicRenderDumpTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true -Dbili.cookieFile=&lt;cookie文件&gt; -Dbili.outDir=&lt;输出目录&gt; \
 *              [-Dbili.renderId=&lt;动态id&gt;] -Djdk.net.URLClassPath.disableClassPathURLCheck=true"
 * </pre>
 * 不给 {@code -Dbili.renderId} 时会从关注流自动挑一条（优先 uid=497078180）。
 *
 * <p>⚠️ 提示：若 UP 会"发完就删"（实测 uid 497078180 如此），历史动态的 detail 端点会返回
 * {@code code=4101152 动态不可见} —— 排查时请挑**当前仍可见**的动态。
 */
@DisplayName("诊断：动态长图落盘 + RenderModel 打印")
class DynamicRenderDumpTest {

    @Test
    @DisplayName("渲染一条可见动态为 PNG，并导出原始 JSON")
    void dump() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过");

        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(), "未提供 Cookie，跳过");
        HttpPolicy.setCookie(cookie);

        String outDir = System.getProperty("bili.outDir", ".");
        String id = System.getProperty("bili.renderId");

        if (id == null || id.isBlank()) {
            id = pickVisibleId();
        }
        assumeTrue(id != null && !id.isBlank(), "关注流里没找到可用动态 id");
        System.out.printf("%n§ 选用动态 id = %s%n", id);

        dumpJson(BilibiliEndpoint.opusDetailUrl + id, outDir + "/opus_" + id + ".json");
        dumpJson(BilibiliEndpoint.dynamicDetailUrl + id, outDir + "/detail_" + id + ".json");

        dumpModel(id);

        BufferedImage img = new Dynamic().getDynamicImg(id);
        if (img == null) {
            System.out.println("§ 渲染返回 null");
            return;
        }
        File out = new File(outDir + "/render_" + id + ".png");
        ImageIO.write(img, "png", out);
        System.out.printf("§ 长图已落盘：%s（%dx%d，%d 字节）%n",
                out.getAbsolutePath(), img.getWidth(), img.getHeight(), out.length());
    }

    /** 把渲染模型逐项打印 —— 渲染器只画模型里的东西，所以这等价于"这张图里有什么" */
    private static void dumpModel(String id) {
        try {
            RenderModel m = RenderModelLoader.load(id);
            System.out.printf("%n§ ---- RenderModel ----%n");
            System.out.printf("   dynamicId=%s type=%s top=%s%n", m.getDynamicId(), m.getType(), m.isTop());
            System.out.printf("   author.name=%s%n", m.getAuthor().getName());
            System.out.printf("   author.pubTime=%s%n", m.getAuthor().getPubTime());
            System.out.printf("   author.badge=%s%n", m.getAuthor().getBadge());
            System.out.printf("   author.faceUrl=%s%n", m.getAuthor().getFaceUrl());
            System.out.printf("   stat forward=%s comment=%s like=%s%n",
                    m.getStat().getForward(), m.getStat().getComment(), m.getStat().getLike());
            System.out.printf("   blocks=%d%n", m.getBlocks().size());
            int i = 0;
            for (RenderModel.Block b : m.getBlocks()) {
                if (b instanceof RenderModel.TextBlock) {
                    StringBuilder sb = new StringBuilder();
                    for (RenderModel.Span s : ((RenderModel.TextBlock) b).getSpans()) {
                        sb.append('[').append(s.getKind()).append(']').append(s.getText());
                    }
                    System.out.printf("   #%d TEXT  : %s%n", i, sb);
                } else if (b instanceof RenderModel.ImageBlock) {
                    System.out.printf("   #%d IMAGE : %d 张%n", i, ((RenderModel.ImageBlock) b).getPics().size());
                } else {
                    System.out.printf("   #%d %s%n", i, b.getClass().getSimpleName());
                }
                i++;
            }
        } catch (Exception e) {
            System.out.println("§ dumpModel 失败：" + e);
        }
    }

    /** 从关注流里挑一条图文/视频类（非转发）的动态 id，优先挑 uid=497078180 */
    private static String pickVisibleId() {
        try {
            List<Dynamic.DynamicInfo> feed = new Dynamic().getFollowFeed();
            List<String> all = new ArrayList<>();
            String preferred = null;
            for (Dynamic.DynamicInfo info : feed) {
                if (info.getDynamicId() == null) {
                    continue;
                }
                all.add(info.getDynamicId());
                if (preferred == null && "497078180".equals(info.getUid())) {
                    preferred = info.getDynamicId();
                }
                System.out.printf("   候选 uid=%s name=%s time=%s id=%s%n",
                        info.getUid(), info.getUserName(), info.getTime(), info.getDynamicId());
            }
            return preferred != null ? preferred : (all.isEmpty() ? null : all.get(0));
        } catch (Exception e) {
            System.out.println("§ 拉关注流失败：" + e);
            return null;
        }
    }

    private static void dumpJson(String url, String path) {
        try {
            HttpResponse<String> resp = BilibiliHttp.get(url);
            String body = resp.getBody();
            JSONObject o = JSON.parseObject(body);
            String code = o == null ? "?" : String.valueOf(o.get("code"));
            Files.writeString(Path.of(path), body);
            System.out.printf("§ %s%n   HTTP=%s code=%s bytes=%d -> %s%n",
                    url, resp.getStatus(), code, body == null ? 0 : body.length(), path);
        } catch (Exception e) {
            System.out.printf("§ %s%n   抓取失败：%s%n", url, e);
        }
    }

    private static String cookie() throws Exception {
        String file = System.getProperty("bili.cookieFile");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file)).trim();
        }
        return System.getProperty("bili.cookie");
    }
}

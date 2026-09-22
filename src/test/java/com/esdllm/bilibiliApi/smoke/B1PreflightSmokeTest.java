package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B1 批的<b>开工前 / 交付前预检</b>（联网，默认跳过）。
 *
 * <p>与 {@code B35PreflightSmokeTest} 同一套路，但<b>解决的问题不一样</b>：
 * B3.5 那次要分的是"端点被封 vs 探测姿势不对"（所以要带凭据 ×签名跑满格子）；
 * B1 这 8 项<b>全是匿名可用</b>，所以本类要分的是另外两件事：
 *
 * <ol>
 *   <li>🔴 <b>{@code ranking/v2} 到底能吃哪种 Referer。</b>这是本批唯一的"请求头决定生死"，
 *       而且是<b>一次真实的排期事故</b>的源头：用站根 Referer 时预检里它回 {@code -352 风控校验失败}，
 *       后续几条因为这个 {@code -352} 拿不到 bootstrap id，整表跟着变成 {@code -400}，
 *       看起来像"B1 全批都挂了"。所以本类<b>把同一个端点在两种 Referer 下各打一次</b>并排显示 ——
 *       这是唯一能把"端点坏了"与"Referer 写错了"分开的证据形态。</li>
 *   <li>✅ <b>八项是不是都不需要签名、不需要凭据。</b>每一项都断言 {@code code=0}，
 *       因为冻结契约测试只能证明方法存在，证明不了"这条路今天还能走"。</li>
 * </ol>
 *
 * <p><b>阳性对照先跑</b>（{@code x/web-interface/popular} 用站根 Referer）：
 * 它拿不到 {@code code=0} 的话，整表结论作废 —— 这是 2026-09-21 两次翻案换来的纪律。
 *
 * <p>⚠️ <b>bvid / aid / cid 全部自举</b>（从 {@code popular} 的第一条里取），
 * 不硬编码任何 id —— 硬编码的样本一旦失效，会伪装成"端点挂了"。
 *
 * <p>跑法：
 * <pre>
 * mvn -o -B test "-Dtest=B1PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true"
 * </pre>
 * 本批全部匿名，所以<b>不需要</b> {@code -Dbili.cookieFile}。
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：B1 八端点（默认跳过）")
class B1PreflightSmokeTest {

    /** 直播自举样本：uid=2（碧诗）的直播间实测是 1024 —— 这两个是稳定常量，不是内容 id */
    private static final long BOOTSTRAP_UID = 2L;
    private static final long BOOTSTRAP_ROOM_ID = 1024L;

    @Test
    @DisplayName("先跑阳性对照，再逐条预检八端点；ranking 额外做 Referer A/B 对照")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        StringBuilder report = new StringBuilder();
        report.append("B1 preflight（全部匿名：本批 8 项都不需要签名与凭据）\n");

        // ---------- 1. 阳性对照（必须先跑） ----------
        // 用 popular 而不是 view：view 与 view/detail 同前缀，用它当对照会让结果含糊。
        HttpPolicy.clearCookie();
        HttpResponse<String> control = BilibiliHttp.get(
                BilibiliEndpoint.popularUrl + "?ps=5&pn=1",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("control-popular", control);
        int controlCode = codeOf(control.getBody());
        report.append(String.format("control popular            http=%d code=%d%n",
                control.getStatus(), controlCode));
        assertEquals(0, controlCode,
                "阳性对照失败：连 x/web-interface/popular 都拿不到 code=0，说明出口 IP 被封，"
                        + "本次预检的所有 -352 / 412 都不可作为'端点不可用'的证据");

        // ---------- 2. 自举：从热门第一条取真实 id ----------
        JSONObject first = firstItem(control.getBody());
        assumeTrue(first != null, "热门列表为空，无法自举样本 id");
        String bvid = first.getString("bvid");
        String cid = String.valueOf(first.getLongValue("cid"));
        String aid = String.valueOf(first.getLongValue("aid"));
        JSONObject owner = first.getJSONObject("owner");
        String upMid = owner == null ? null : String.valueOf(owner.getLongValue("mid"));
        report.append(String.format("bootstrap from popular     bvid=%s aid=%s cid=%s up=%s%n",
                bvid, aid, cid, upMid));
        assertTrue(bvid != null && !bvid.isBlank(), "自举到的 bvid 不能为空");

        String videoReferer = BilibiliEndpoint.videoReferer.formatted(bvid);

        // ---------- 3. 视频域四项 ----------
        report.append(plain("view/detail (一站式详情)",
                BilibiliEndpoint.viewDetailUrl + "?bvid=" + bvid, videoReferer));
        report.append(plain("online/total (在线观看数)",
                BilibiliEndpoint.onlineTotalUrl + "?bvid=" + bvid + "&cid=" + cid, videoReferer));
        report.append(plain("v2/reply (评论，oid=aid)",
                BilibiliEndpoint.replyUrl + "?type=1&oid=" + aid + "&pn=1&ps=5&sort=2",
                BilibiliEndpoint.referer));

        // ---------- 4. ranking 的 Referer A/B（本类存在的主要理由） ----------
        report.append(plain("ranking[v2] Referer=站根",
                BilibiliEndpoint.rankingUrl + "?rid=0&type=all", BilibiliEndpoint.referer));
        report.append(plain("ranking[v2] Referer=排行榜页",
                BilibiliEndpoint.rankingUrl + "?rid=0&type=all", BilibiliEndpoint.rankingReferer));

        // ---------- 5. 榜单域另一项 + 用户关系数 ----------
        report.append(plain("popular (热门，站根 Referer 即可)",
                BilibiliEndpoint.popularUrl + "?ps=20&pn=1", BilibiliEndpoint.referer));
        if (upMid != null) {
            report.append(plain("relation/stat (关系数，任意用户)",
                    BilibiliEndpoint.relationStatUrl + "?vmid=" + upMid,
                    BilibiliEndpoint.spaceReferer.formatted(upMid)));
        }

        // ---------- 6. 直播域两项 ----------
        report.append(plain("live Room/playUrl (拉流地址)",
                BilibiliEndpoint.liveStreamUrl + "?cid=" + BOOTSTRAP_ROOM_ID + "&qn=10000&platform=web",
                BilibiliEndpoint.liveReferer));
        report.append(plain("live Master/info (主播信息)",
                BilibiliEndpoint.liveMasterInfoUrl + "?uid=" + BOOTSTRAP_UID,
                BilibiliEndpoint.liveReferer));

        Files.writeString(Path.of(".workbuddy/_b1_preflight.txt"), report.toString(),
                StandardCharsets.UTF_8);
        System.out.printf("%n§ B1 preflight%n%s", report);
    }

    /** 打一次普通 GET 并落盘 */
    private static String plain(String tag, String url, String referer) {
        HttpResponse<String> resp = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept, referer);
        dump(tag.replace(' ', '-').replace('/', '-'), resp);
        return String.format("%-42s http=%d code=%d%n", tag, resp.getStatus(), codeOf(resp.getBody()));
    }

    /** 取 {@code data.list[0]}；拿不到时返回 {@code null} */
    private static JSONObject firstItem(String body) {
        try {
            JSONObject root = JSON.parseObject(body);
            JSONArray list = root.getJSONObject("data").getJSONArray("list");
            return list == null || list.isEmpty() ? null : list.getJSONObject(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取业务码；不是 JSON 时返回 {@code Integer.MIN_VALUE} */
    private static int codeOf(String body) {
        if (body == null || body.isBlank() || body.charAt(0) != '{') {
            return Integer.MIN_VALUE;
        }
        ApiResponse<Object> parsed = JSON.parseObject(body,
                new com.alibaba.fastjson2.TypeReference<>() {
                });
        return parsed == null ? Integer.MIN_VALUE : parsed.getCode();
    }

    private static void dump(String name, HttpResponse<String> resp) {
        try {
            Path dir = Path.of(".workbuddy");
            Files.createDirectories(dir);
            String body = resp.getBody() == null ? "" : resp.getBody();
            if (body.length() > 200_000) {
                body = body.substring(0, 200_000);
            }
            Files.writeString(dir.resolve("_b1_" + name + ".json"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
        }
    }
}

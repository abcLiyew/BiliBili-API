package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.bilibiliApi.Comment;
import com.esdllm.bilibiliApi.bilibiliApi.Ranking;
import com.esdllm.bilibiliApi.bilibiliApi.VideoExtra;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.comment.MainReplyPage;
import com.esdllm.bilibiliApi.model.data.pojo.video.Pages;
import com.esdllm.bilibiliApi.model.data.pojo.video.PreciousList;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoShot;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C1 批（候选池换源 · 第一批）的<b>交付前预检 + 能力面复查</b>（联网，默认跳过）。
 *
 * <p>本批 5 个端点<b>全部匿名可用</b>，所以本类的重点不是"要不要凭据"，
 * 而是把三处<b>只有真机才看得见</b>的行为变成可重复执行的检查：
 *
 * <ol>
 *   <li>🔴 <b>{@code reply/main} 匿名档的"静默截断 + 假终止信号"</b>：
 *       只给 3 条却报 {@code is_end=true}（而 {@code all_count} 上万），
 *       且回传 {@code next} 会拿到 {@code replies=null}。
 *       本类用 {@code mode=4 → -400 invalid mode} 这条<b>协议级</b>事实做断言，
 *       而"3 条 / 20 条"这类<b>上游状态</b>只打印 —— 理由见下面第 4 点。</li>
 *   <li>🔴 <b>{@code videoshot} 的 {@code index} 陷阱</b>：传 2 会<b>静默</b>拿到 P1 的雪碧图。
 *       库内因此把 {@code index} 钉死成 1 且不暴露参数。</li>
 *   <li><b>{@code precious} 完全不吃分页</b>：五格实测同结果，库内因此不发任何参数。</li>
 *   <li>⚠️ <b>关于"哪些下断言、哪些只打印"</b>（本库在 B4 踩过、在 B5 定下的纪律）：
 *       依赖<b>协议</b>的（{@code -400} 这种参数校验、{@code aid/bvid} 自洽）写断言；
 *       依赖<b>上游状态</b>的（风控、条数、{@code is_end} 的真假）<b>只打印</b>。
 *       因为把"某个端点今天死/今天只给 3 条"写进断言，只会得到一条
 *       <b>服务端一改就无故变红</b>的检查 —— 而"变红"会训练人忽略红色。
 *       第 2、3 点的"陷阱还在不在"属于"哪天修好了是<b>好消息</b>"，所以本类
 *       把它们做成 <b>WARNING 打印</b>而不是失败。</li>
 * </ol>
 *
 * <p>跑法（全匿名即可；补上 {@code -Dbili.cookieFile} 会多跑一列凭据对照）：
 * <pre>
 * mvn -o -B test "-Dtest=C1PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true -Dbili.cookieFile=.workbuddy/bili-cookie.txt"
 * </pre>
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：C1 批 5 端点 + 三处陷阱复查（默认跳过）")
class C1PreflightSmokeTest {

    /** 与夹具同源的样本：4 分P 视频（pagelist / videoshot 都靠它） */
    private static final String MULTI_BVID = "BV1BqhB6nEdN";

    /** 与两个夹具同源的评论样本（{@code reply/main} 与 {@code reply/count} 共用它） */
    private static final long SAMPLE_AID = 117308542555694L;

    @Test
    @DisplayName("阳性对照 → 5 端点 → 两处协议级反证 → 三处陷阱复查，并落盘报告")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        StringBuilder report = new StringBuilder();
        report.append("C1 preflight（5 端点，全部匿名可用）\n");

        // ---------- 0. 阳性对照（必须先跑） ----------
        HttpPolicy.clearCookie();
        HttpResponse<String> control = BilibiliHttp.get(
                BilibiliEndpoint.popularUrl + "?ps=20&pn=1",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("control-popular", control);
        int controlCode = codeOf(control.getBody());
        report.append(String.format("%-46s http=%d code=%d%n",
                "control popular", control.getStatus(), controlCode));
        assertEquals(0, controlCode,
                "阳性对照失败：连 x/web-interface/popular 都拿不到 code=0，说明出口被封，"
                        + "本次预检里所有负面结果都不可作为『端点不可用』的证据");
        report.append(String.format("%-46s %s%n", "  -> 出站身份", HttpPolicy.describe()));
        // 先落一次盘：后面任何断言红掉，诊断也不会跟着丢
        flush(report);

        // ---------- 1. 分P列表：与 view 的 cid 必须自洽 ----------
        HttpResponse<String> viewResp = BilibiliHttp.get(
                BilibiliEndpoint.videoBaseUrl + MULTI_BVID,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("view-multi", viewResp);
        assertEquals(0, codeOf(viewResp.getBody()), "样本视频的 view 拿不到 code=0，本样本作废");
        JSONObject viewData = dataOf(viewResp.getBody());
        assertNotNull(viewData, "view 没有 data");
        long viewCid = viewData.getLongValue("cid");
        int viewVideos = viewData.getIntValue("videos");

        List<Pages> parts = new VideoExtra().getParts(MULTI_BVID);
        assertFalse(parts.isEmpty(), "pagelist 一个分P都没给 —— 该端点本该匿名可读");
        assertEquals(viewVideos, parts.size(),
                "★ 两条路给的『有几个分P』必须一致（view.videos vs pagelist 长度）—— 不一致说明样本或端点变了");
        assertEquals(viewCid, parts.get(0).getCid(),
                "★ pagelist 的 P1 cid 必须等于 view.cid —— 这是两个端点互相自洽的硬性质");
        report.append(String.format("%-46s parts=%d p1.cid=%d (view.cid=%d) first_frame=%s%n",
                "C1 pagelist " + MULTI_BVID, parts.size(), parts.get(0).getCid(), viewCid,
                parts.get(0).getFirst_frame() != null));

        // ---------- 2. 缩略图：index=1 必须给图 ----------
        VideoShot shot = new VideoExtra().getVideoShot(MULTI_BVID);
        assertFalse(shot.getImage().isEmpty(), "videoshot 没给 image —— 该端点本该匿名可读");
        report.append(String.format("%-46s grid=%sx%s cell=%sx%s image=%d index[]=%d%n",
                "C1 videoshot index=1", shot.getImg_x_len(), shot.getImg_y_len(),
                shot.getImg_x_size(), shot.getImg_y_size(), shot.getImage().size(),
                shot.getIndex() == null ? 0 : shot.getIndex().size()));

        // 🔴 陷阱复查（打印 + WARNING，不失败）：index=2 是否仍然静默给 P1 的图？
        HttpResponse<String> shotTwo = BilibiliHttp.get(
                BilibiliEndpoint.playerVideoShotUrl + "?bvid=" + MULTI_BVID + "&index=2",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.videoReferer.formatted(MULTI_BVID));
        dump("videoshot-index2", shotTwo);
        JSONObject shotTwoData = dataOf(shotTwo.getBody());
        if (shotTwoData != null && codeOf(shotTwo.getBody()) == 0) {
            JSONArray twoImage = shotTwoData.getJSONArray("image");
            String oneFirst = shot.getImage().isEmpty() ? null : shot.getImage().get(0);
            String twoFirst = twoImage == null || twoImage.isEmpty() ? null : twoImage.getString(0);
            boolean same = oneFirst != null && oneFirst.equals(twoFirst);
            int twoIndexLen = shotTwoData.getJSONArray("index") == null
                    ? 0 : shotTwoData.getJSONArray("index").size();
            report.append(String.format("%-46s code=0 image=%s index[]=%d%n",
                    "  TRAP videoshot index=2", twoImage == null ? "-" : twoImage.size(), twoIndexLen));
            if (same) {
                report.append("  WARNING: index=2 仍然返回 P1 的雪碧图"
                        + " —— 陷阱还在，库内固定 index=1 的决定继续有效\n");
            } else {
                report.append("  WARNING: index=2 的结果与 index=1 不同了 —— 这是【好消息】："
                        + "服务端可能修好了该参数，请重新评估「不暴露 index」这个决定，"
                        + "并在 BilibiliEndpoint#playerVideoShotUrl 更新实测表\n");
            }
        } else {
            report.append(String.format("  TRAP videoshot index=2 -> http=%d code=%d%n",
                    shotTwo.getStatus(), codeOf(shotTwo.getBody())));
        }

        // ---------- 3. 入站必刷：分页参数是否仍然无效 ----------
        PreciousList precious = new Ranking().getPrecious();
        assertFalse(precious.getList().isEmpty(), "入站必刷拿不到内容 —— 该端点本该匿名可读");
        HttpResponse<String> preciousPaged = BilibiliHttp.get(
                BilibiliEndpoint.popularPreciousUrl + "?page=2&page_size=1",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("precious-paged", preciousPaged);
        JSONObject pagedData = dataOf(preciousPaged.getBody());
        int pagedLen = pagedData == null || pagedData.getJSONArray("list") == null
                ? -1 : pagedData.getJSONArray("list").size();
        long firstAid = precious.getList().get(0).getAid();
        long pagedFirstAid = pagedData == null || pagedData.getJSONArray("list") == null
                || pagedData.getJSONArray("list").isEmpty() ? -1
                : pagedData.getJSONArray("list").getJSONObject(0).getLongValue("aid");
        report.append(String.format("%-46s list=%d first_aid=%d%n",
                "C1 precious (无参数)", precious.getList().size(), firstAid));
        report.append(String.format("%-46s list=%d first_aid=%d%n",
                "  TRAP precious ?page=2&page_size=1", pagedLen, pagedFirstAid));
        if (pagedLen == precious.getList().size() && pagedFirstAid == firstAid) {
            report.append("  WARNING: 分页参数仍然无效（两格完全一致）"
                    + " —— 库内『不发任何分页参数』的决定继续有效\n");
        } else {
            report.append("  WARNING: 分页参数开始生效了 —— 这是【好消息】："
                    + "请重新评估 Ranking#getPrecious 是否要加 ps/pn，"
                    + "并更新 BilibiliEndpoint#popularPreciousUrl 的实测表\n");
        }

        // ---------- 4. 评论：协议级反证 + 匿名/凭据两列（只打印） ----------
        HttpResponse<String> countResp = BilibiliHttp.get(
                BilibiliEndpoint.replyCountUrl + "?type=1&oid=" + SAMPLE_AID,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("reply-count", countResp);
        int countCode = codeOf(countResp.getBody());
        assertEquals(0, countCode, "reply/count 拿不到 code=0 —— 该端点本该匿名可读");
        JSONObject countData = dataOf(countResp.getBody());
        long total = countData == null ? -1 : countData.getLongValue("count");
        report.append(String.format("%-46s code=0 count=%d%n", "C1 reply/count (匿名)", total));

        // 🔴 mode=4 是**协议级**的参数校验（服务端明确说 invalid mode），可以下断言
        HttpResponse<String> badMode = BilibiliHttp.get(
                BilibiliEndpoint.replyMainUrl + "?type=1&oid=" + SAMPLE_AID + "&mode=4&next=0&ps=20",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("reply-main-badmode", badMode);
        int badModeCode = codeOf(badMode.getBody());
        report.append(String.format("%-46s http=%d code=%d (expect -400)%n",
                "NEG reply/main mode=4", badMode.getStatus(), badModeCode));
        assertEquals(-400, badModeCode,
                "★ mode 的合法集变了（本该只有 1/2/3）—— 要么服务端扩了取值范围，"
                        + "要么它的校验被放开；两种情况都要更新 CommentService.MAIN_MODE_* 与 javadoc");

        // 匿名列（只打印：条数与 is_end 都是上游状态）
        report.append(replyMainColumn("reply/main 匿名", null));
        // 凭据列（可选）
        String cookie = cookie();
        if (cookie != null && !cookie.isBlank()) {
            report.append(replyMainColumn("reply/main 凭据", cookie));
            report.append(replyMainPaging("reply/main 凭据 第2页", cookie));
            report.append(String.format("%-46s code=0 count=%d (应与匿名列相同)%n",
                    "C1 reply/count (凭据)", countOfReplyCount()));
        } else {
            report.append("  （未提供 -Dbili.cookieFile，跳过凭据列对照）\n");
        }

        // ---------- 5. 走门面再验一次：证明『解析 + 门槛』整条链路通 ----------
        MainReplyPage viaFacade = new Comment().getMainReplies(SAMPLE_AID);
        assertNotNull(viaFacade, "门面返回 null —— 与上面的 code=0 矛盾");
        assertNotNull(viaFacade.getCursor(), "★ 游标必须被映射出来，否则调用方无法翻页");
        report.append(String.format("%-46s replies=%d all_count=%d is_end=%s%n",
                "门面 getMainReplies", viaFacade.getReplies() == null ? -1 : viaFacade.getReplies().size(),
                viaFacade.getCursor().getAll_count(), viaFacade.getCursor().getIs_end()));
        assertTrue(viaFacade.getCursor().getAll_count() >= 0, "all_count 应可读");

        flush(report);
    }

    // ------------------------------------------------------------------ helpers

    /** 评论总数（匿名），只打印用 */
    private static long countOfReplyCount() {
        HttpResponse<String> resp = BilibiliHttp.get(
                BilibiliEndpoint.replyCountUrl + "?type=1&oid=" + SAMPLE_AID,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        JSONObject d = dataOf(resp.getBody());
        return d == null ? -1 : d.getLongValue("count");
    }

    /** reply/main 的一列：{@code cookie} 为 null 表示匿名 */
    private static String replyMainColumn(String label, String cookie) {
        if (cookie == null) {
            HttpPolicy.clearCookie();
        } else {
            HttpPolicy.setCookie(cookie);
        }
        HttpResponse<String> resp = BilibiliHttp.get(
                BilibiliEndpoint.replyMainUrl + "?type=1&oid=" + SAMPLE_AID + "&mode=3&next=0&ps=20",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        JSONObject d = dataOf(resp.getBody());
        JSONArray replies = d == null ? null : d.getJSONArray("replies");
        JSONObject cursor = d == null ? null : d.getJSONObject("cursor");
        return String.format("%-46s replies=%s is_end=%s all_count=%s next=%s%n",
                label + " (mode=3)",
                replies == null ? "null" : String.valueOf(replies.size()),
                cursor == null ? "-" : cursor.get("is_end"),
                cursor == null ? "-" : cursor.get("all_count"),
                cursor == null ? "-" : cursor.get("next"));
    }

    /** reply/main 翻页格（靠 cursor.next），只打印 */
    private static String replyMainPaging(String label, String cookie) {
        HttpPolicy.setCookie(cookie);
        HttpResponse<String> first = BilibiliHttp.get(
                BilibiliEndpoint.replyMainUrl + "?type=1&oid=" + SAMPLE_AID + "&mode=3&next=0&ps=20",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        JSONObject c1 = dataOf(first.getBody()) == null ? null
                : dataOf(first.getBody()).getJSONObject("cursor");
        if (c1 == null || c1.get("next") == null) {
            return String.format("%-46s 拿不到 next，跳过%n", label);
        }
        HttpResponse<String> second = BilibiliHttp.get(
                BilibiliEndpoint.replyMainUrl + "?type=1&oid=" + SAMPLE_AID
                        + "&mode=3&next=" + c1.get("next") + "&ps=20",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        JSONObject d2 = dataOf(second.getBody());
        JSONArray replies = d2 == null ? null : d2.getJSONArray("replies");
        return String.format("%-46s next=%s -> replies=%s%n", label, c1.get("next"),
                replies == null ? "null" : String.valueOf(replies.size()));
    }

    private static void flush(StringBuilder report) {
        try {
            Files.createDirectories(Path.of(".workbuddy"));
            Files.writeString(Path.of(".workbuddy/_c1_preflight_smoke.txt"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("flush failed: " + e);
        }
        System.out.printf("%n§ C1 preflight%n%s", report);
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

    /** 取 {@code data} 对象；拿不到返回 {@code null} */
    private static JSONObject dataOf(String body) {
        try {
            return JSON.parseObject(body).getJSONObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    private static void dump(String name, HttpResponse<String> resp) {
        try {
            Path dir = Path.of(".workbuddy");
            Files.createDirectories(dir);
            String body = resp.getBody() == null ? "" : resp.getBody();
            if (body.length() > 400_000) {
                body = body.substring(0, 400_000);
            }
            Files.writeString(dir.resolve("_c1_" + name + ".txt"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
        }
    }

    private static String cookie() throws Exception {
        String file = System.getProperty("bili.cookieFile");
        if (file != null && !file.isBlank() && Files.exists(Path.of(file))) {
            return Files.readString(Path.of(file)).trim();
        }
        return System.getProperty("bili.cookie");
    }
}

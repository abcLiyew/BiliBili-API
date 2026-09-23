package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.bilibiliApi.UserSpace;
import com.esdllm.bilibiliApi.bilibiliApi.VideoExtra;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoBrief;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.BvCode;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B5 批的<b>交付前预检 + 能力面复查</b>（联网，默认跳过）。
 *
 * <p>本类与 {@code B4PreflightSmokeTest} 的任务不同：B4 的重点是"记住哪些做不了"，
 * 而 B5 交付的两项能力<b>都做得了</b>（{@code x/space/top/arc} 与 {@code x/note/is_forbid}），
 * 第三项（{@code bvid ⇄ aid}）甚至不发请求。所以这里的重点是
 * <b>把"这两条路今天还能走 + 这个算法与真机一致"变成可重复执行的断言</b>，
 * 而不是留下一份一次性的探针结论。
 *
 * <p><b>本类的三块断言各自防守什么</b>：
 * <ol>
 *   <li><b>阳性对照先跑</b>（{@code x/web-interface/view}）。它拿不到 {@code code=0} 时，
 *       后面的 {@code -352} / {@code -400} 都不能作为"端点不可用"的证据 —— 与 B1/B2/B4 同一条纪律。</li>
 *   <li>🔴 <b>算法与真机的交叉校验（本类最重要的一条）</b>：{@code parse.BvCode} 是纯算法，
 *       算错了不会抛异常，只会让下游拿到一个"格式合法但内容错误"的 aid 去换空列表。
 *       光靠夹具自洽证明不了它对，所以这里<b>把本地算出的 aid 与服务端 {@code view} 返回的 aid 逐一对齐</b>，
 *       并断言 {@code toBvid(真实 aid) == 真实 bvid}。这一步是真机版 {@code BvCodeTest}。</li>
 *   <li><b>两条"反证格"必须仍然是错的</b>：{@code top/arc} 的参数名写成 {@code mid} 要回 {@code -400}；
 *       {@code is_forbid} 不传 {@code aid} 也要回 {@code -400}。
 *       这两格是 B5 预检里真正救过场的东西 —— 09-22 那次盘点正是把 {@code vmid} 写成 {@code mid}
 *       才差点把可用端点判死。把它们写成断言，是为了让"参数名"这件事不许悄悄变。</li>
 * </ol>
 *
 * <p>⚠️ <b>只打印不断言</b>的两项（与 B4 的"边界表"同一理由）：
 * 置顶视频属于某个具体 UP 主（会换），{@code top/arc} 的 {@code data} 键数也会随服务端增删而变 ——
 * 把"碧诗的置顶视频是哪一个"写成断言，只会得到一条哪天就会无故变红的检查。
 * 真正稳定的性质（{@code aid} 与 {@code bvid} 互相自洽）已经在上面的第 2 块断言过了。
 *
 * <p>跑法（本批全匿名，<b>不需要</b> {@code -Dbili.cookieFile}）：
 * <pre>
 * mvn -o -B test "-Dtest=B5PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true"
 * </pre>
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：B5 置顶视频 / 笔记入口 / bvid⇄aid 真机交叉校验（默认跳过）")
class B5PreflightSmokeTest {

    /** 交叉校验用的样本：{@code bvid → aid} 的真值已由 2026-09-23 实测 view 确认 */
    private static final String[] SAMPLE_BVIDS = {
            "BV1GJ411x7h7", "BV17x411w7KC", "BV1xx411c7DS"};

    /** 用来取 {@code is_forbid} 的样本 aid（与 {@code fixtures/note-isforbid.json} 同一个） */
    private static final long SAMPLE_AID = 80433022L;

    /** 用于置顶视频的样本 UP 主：{@code vmid=2}（2026-09-23 实测其 data 为 38 键） */
    private static final long SAMPLE_VMID = 2L;

    @Test
    @DisplayName("阳性对照 → 三项能力 → 两格反证 → 算法×真机交叉校验，并落盘报告")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        StringBuilder report = new StringBuilder();
        report.append("B5 preflight（全匿名：2 个新端点 + 1 个纯算法）\n");

        // ---------- 0. 阳性对照（必须先跑） ----------
        HttpPolicy.clearCookie();
        String controlBvid = SAMPLE_BVIDS[0];
        HttpResponse<String> control = BilibiliHttp.get(
                BilibiliEndpoint.videoBaseUrl + controlBvid,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("control-view", control);
        int controlCode = codeOf(control.getBody());
        report.append(String.format("%-44s http=%d code=%d%n",
                "control view (" + controlBvid + ")", control.getStatus(), controlCode));
        assertEquals(0, controlCode,
                "阳性对照失败：连 x/web-interface/view 都拿不到 code=0，说明出口被封，"
                        + "本次预检里所有 -400 / -352 都不可作为'端点不可用'的证据");
        report.append(String.format("%-44s %s%n", "  -> 出站身份", HttpPolicy.describe()));
        // 先落一次盘：后面任何断言红掉，诊断也不会跟着丢（B4 踩过这个坑）
        flush(report);

        // ---------- 1. 置顶视频 x/space/top/arc ----------
        HttpResponse<String> topArc = BilibiliHttp.get(
                BilibiliEndpoint.spaceTopArcUrl + "?vmid=" + SAMPLE_VMID,
                BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.spaceReferer.formatted(String.valueOf(SAMPLE_VMID)));
        dump("top-arc", topArc);
        int topArcCode = codeOf(topArc.getBody());
        assertEquals(0, topArcCode, "置顶视频拿不到 code=0 —— 该端点本该匿名可读");

        JSONObject topData = dataOf(topArc.getBody());
        assertNotNull(topData, "code=0 但拿不到 data 对象，夹具需要重抓");
        long pinnedAid = topData.getLongValue("aid");
        String pinnedBvid = topData.getString("bvid");

        // 🔴 稳定性质：aid 与 bvid 必须互相自洽（这才是"这条数据是好的"的判据）
        assertEquals(pinnedBvid, BvCode.toBvid(pinnedAid),
                "服务端给的 aid/bvid 配对，本地算法算不出同一个 bvid —— 算法漂了");
        assertEquals(pinnedAid, BvCode.toAid(pinnedBvid),
                "反方向也要成立");
        report.append(String.format("%-44s http=%d code=0 keys=%d%n",
                "B5 space/top/arc vmid=" + SAMPLE_VMID, topArc.getStatus(), topData.size()));
        report.append(String.format("%-44s pinned=%s aid=%d vt_display=%s%n",
                "  -> 置顶视频（打印，不断言）", pinnedBvid, pinnedAid,
                topData.getString("vt_display")));
        report.append(String.format("%-44s attribute=%s pay_free_watch=%s%n",
                "  -> 列表那一代的两个键",
                topData.get("attribute"),
                topData.getJSONObject("rights") == null
                        ? null : topData.getJSONObject("rights").get("pay_free_watch")));

        // 走门面再验一次：证明"解析 + 门槛"这条链路整体是通的（而不只是裸 HTTP 通）
        VideoBrief viaFacade = new UserSpace().getTopArchive(SAMPLE_VMID);
        assertNotNull(viaFacade, "门面竟然返回 null —— 与上面那次 code=0 矛盾，看看 53016 的特判有没有误伤");
        assertEquals(pinnedBvid, viaFacade.getBvid(), "门面解析出的 bvid 与裸响应不一致");

        // 反证：参数名写成 mid
        HttpResponse<String> wrongParam = BilibiliHttp.get(
                BilibiliEndpoint.spaceTopArcUrl + "?mid=" + SAMPLE_VMID,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("top-arc-wrongparam", wrongParam);
        int wrongParamCode = codeOf(wrongParam.getBody());
        report.append(String.format("%-44s http=%d code=%d (expect -400)%n",
                "NEG top/arc?mid= (wrong param)", wrongParam.getStatus(), wrongParamCode));
        assertEquals(-400, wrongParamCode,
                "★ 参数名 vmid 写错竟然不再回 -400 了 —— 要么服务端改了参数名（本库要跟着改），"
                        + "要么它开始兼容 mid（那也要把这个事实记下来）。09-22 那次盘点就是栽在这一格");

        // ---------- 2. 笔记入口 x/note/is_forbid ----------
        HttpResponse<String> forbid = BilibiliHttp.get(
                BilibiliEndpoint.noteIsForbidUrl + "?aid=" + SAMPLE_AID,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("note-isforbid", forbid);
        assertEquals(0, codeOf(forbid.getBody()), "笔记入口查询拿不到 code=0 —— 该端点本该匿名可读");

        JSONObject forbidData = dataOf(forbid.getBody());
        assertNotNull(forbidData, "code=0 但拿不到 data 对象");
        assertNotNull(forbidData.get("forbid_note_entrance"),
                "★ 该字段必须给值（服务端给的是布尔，不给 null）—— 为 null 说明形状变了，POJO 要重审");
        report.append(String.format("%-44s http=%d code=0 forbid_note_entrance=%s%n",
                "B5 note/is_forbid aid=" + SAMPLE_AID, forbid.getStatus(),
                forbidData.get("forbid_note_entrance")));

        // 走门面再验一次
        boolean viaFacadeForbid = new VideoExtra().isNoteForbidden(SAMPLE_AID);
        assertEquals(forbidData.getBooleanValue("forbid_note_entrance"), viaFacadeForbid,
                "门面读出的布尔与裸响应不一致 —— 单布尔端点最容易写成取反");

        // 🔴 本端点头号坑：它**不校验 aid 是否存在**
        HttpResponse<String> ghost = BilibiliHttp.get(
                BilibiliEndpoint.noteIsForbidUrl + "?aid=1",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("note-isforbid-ghost", ghost);
        int ghostCode = codeOf(ghost.getBody());
        report.append(String.format("%-44s http=%d code=%d (expect 0)%n",
                "TRAP is_forbid?aid=1 (ghost aid)", ghost.getStatus(), ghostCode));
        assertEquals(0, ghostCode,
                "★ 这一格是本端点最该被记住的性质：不存在的 aid 也返回 code=0 "
                        + "⇒ 调用方【不能】拿它的成功当'稿件存在'的证明。哪天它开始回 -404/-400 了，"
                        + "说明服务端收紧了校验，本方法与它的 javadoc 都要更新");

        // 反证：不传 aid
        HttpResponse<String> noParam = BilibiliHttp.get(
                BilibiliEndpoint.noteIsForbidUrl,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("note-isforbid-noparam", noParam);
        int noParamCode = codeOf(noParam.getBody());
        report.append(String.format("%-44s http=%d code=%d (expect -400)%n",
                "NEG is_forbid (no aid)", noParam.getStatus(), noParamCode));
        assertEquals(-400, noParamCode, "★ aid 是必填参数 —— 这一格变了说明端点签名改了");

        // ---------- 3. 🔴 纯算法 × 真机 view：逐样本交叉校验 ----------
        report.append("\n--- BvCode vs real view (每一条都必须对上) ---\n");
        for (String bvid : SAMPLE_BVIDS) {
            HttpResponse<String> view = BilibiliHttp.get(
                    BilibiliEndpoint.videoBaseUrl + bvid,
                    BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
            dump("view-" + bvid, view);
            assertEquals(0, codeOf(view.getBody()), bvid + " 的 view 拿不到 code=0，本样本作废");

            JSONObject vd = dataOf(view.getBody());
            assertNotNull(vd, bvid + " 的 view 没有 data");
            long realAid = vd.getLongValue("aid");

            assertEquals(realAid, BvCode.toAid(bvid),
                    "★ 本地算出的 aid 与真机不一致（" + bvid + "）—— 这正是「静默算错」的形态："
                            + "不会抛异常，只会让下游换到空列表");
            assertEquals(bvid, BvCode.toBvid(realAid),
                    "★ 反方向也要对上（aid=" + realAid + "）");
            report.append(String.format("%-44s %s <-> %d  OK%n", "  cross-check", bvid, realAid));
        }

        // 门面暴露的纯函数必须是同一份实现（不是"看起来一样"）
        assertEquals(BvCode.toAid(SAMPLE_BVIDS[0]), new VideoExtra().toAid(SAMPLE_BVIDS[0]),
                "门面 toAid 与 parse.BvCode 不一致 —— 有人在门面里抄了一份");

        flush(report);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 把当前报告落盘 + 打到 stdout。
     *
     * <p>⚠️ 在"可能失败的断言之前"也要调用一次 —— 否则断言一红，报告文件就不会生成，
     * 那正好在<b>最需要诊断信息的时候</b>把它丢掉（B4 第一版踩过）。
     */
    private static void flush(StringBuilder report) {
        try {
            Files.createDirectories(Path.of(".workbuddy"));
            Files.writeString(Path.of(".workbuddy/_b5_preflight_smoke.txt"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("flush failed: " + e);
        }
        System.out.printf("%n§ B5 preflight%n%s", report);
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
            Files.writeString(dir.resolve("_b5_" + name + ".txt"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
        }
    }
}

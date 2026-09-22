package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson2.JSON;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B4 批的<b>交付前预检 + 能力边界复查</b>（联网，默认跳过）。
 *
 * <p>本类与 {@code B1PreflightSmokeTest} / {@code B2PreflightSmokeTest} 的**最大不同**：
 * 那两批的存在理由是"证明新端点今天还能走"，而 B4 的交付物只有 **1 个**端点
 * （专栏信息 {@code x/article/viewinfo}）。B4 真正该被记住的是
 * <b>另外 8 个候选为什么不做</b> —— 而"不做"这个结论<b>会过期</b>（B 站随时可能把某条路修好）。
 *
 * <p>所以本类做三件事：
 * <ol>
 *   <li><b>阳性对照先跑</b>（{@code popular} 用站根 Referer）。它拿不到 {@code code=0} 时整表作废 ——
 *       否则表里的 {@code -352} 会被误读成"端点挂了"。</li>
 *   <li><b>断言专栏信息可用</b>，并把它的"三层含义"里**稳定的那两条**钉死：
 *       {@code stats.like} 是全局赞数（必须 {@code > 0}）、顶层 {@code like} 匿名恒 {@code 0}
 *       —— 这正是调用方最容易读错的地方。
 *       🔴 第三层（{@code is_author} / {@code in_list}）**只打印、不断言**：2026-09-22 真机跑第一版时
 *       同一个 cv、同样没带凭据，{@code in_list} 拿到了 {@code true}，而同期的夹具与
 *       纯 urllib 采样 6 次都是 {@code false} ⇒ 这两个字段**不足以当"是否已登录"的判据**，
 *       拿它们做断言只会得到一条时红时绿的检查。</li>
 *   <li>🔴 <b>打印"边界表"（刻意不 assert）</b>：把 6 个"确认做不动"的端点各打一次，
 *       只记 http + code 并落盘。原因是这些端点的"死"是**待观察状态而不是契约** ——
 *       哪天某一条活了（比如 B 站放开了 {@code pgc/web/timeline}），**应该由人看到表格后决定要不要开工，
 *       而不是让冒烟测试变红**。硬 assert 只会把"上游变好"报成"我们坏了"。</li>
 * </ol>
 *
 * <p>唯一的**阴性对照**是旧路径 {@code x/article/view}：它必须不是 {@code code=0}
 * （实测匿名 {@code -352}）。这条断言有两个作用 —— ① 记住"本库只交付专栏**信息**、不交付**正文**"；
 * ② 和阳性对照一起，证明"表中的 {@code code=0} 是有信息量的"，而不是全都是 0。
 *
 * <p>跑法（本批全匿名，<b>不需要</b> {@code -Dbili.cookieFile}）：
 * <pre>
 * mvn -o -B test "-Dtest=B4PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true"
 * </pre>
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：B4 专栏信息 + 能力边界复查（默认跳过）")
class B4PreflightSmokeTest {

    /** 用来验证专栏信息的样本号：{@code cv4538122}。取自夹具 {@code fixtures/article-viewinfo.json}。 */
    private static final long SAMPLE_CV = 4538122L;

    /** 夹具里记录的 {@code data} 键数；对不上说明服务端改了字段，夹具该重抓了。 */
    private static final int EXPECTED_KEYS = 23;

    @Test
    @DisplayName("先跑阳性对照，断言专栏信息可用，再打印 6 项能力边界的当前状态")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        StringBuilder report = new StringBuilder();
        report.append("B4 preflight（全部匿名：本批 1 个可做端点 + 8 个不做端点）\n");

        // ---------- 1. 阳性对照（必须先跑） ----------
        HttpPolicy.clearCookie();
        HttpResponse<String> control = BilibiliHttp.get(
                BilibiliEndpoint.popularUrl + "?ps=5&pn=1",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("control-popular", control);
        int controlCode = codeOf(control.getBody());
        report.append(String.format("control popular              http=%d code=%d%n",
                control.getStatus(), controlCode));
        assertEquals(0, controlCode,
                "阳性对照失败：连 x/web-interface/popular 都拿不到 code=0，说明出口 IP 被封，"
                        + "本次预检的所有 -352 / 412 都不可作为'端点不可用'的证据");

        // ---------- 2. 本批唯一交付项：专栏信息 ----------
        HttpResponse<String> info = BilibiliHttp.get(
                BilibiliEndpoint.articleViewInfoUrl + "?id=" + SAMPLE_CV,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("article-viewinfo", info);
        assertEquals(0, codeOf(info.getBody()),
                "专栏信息拿不到 code=0 —— 该端点本该匿名可读（本批唯一免凭据的 Content 方法）");

        // 🔴 先记出站身份：这一步是为了回答"这次的 is_author / in_list 是在什么环境下取的"。
        // 2026-09-22 真机第一次跑本类时，同一个 cv、同样"匿名"，in_list 拿到 true，
        // 而夹具（也是匿名抓的）与纯 urllib 采样 6 次都是 false ⇒ 必须先看库到底带了什么。
        report.append(String.format("%-42s %s%n", "  -> 出站身份", HttpPolicy.describe()));
        // 先落一次盘：后面任何一条断言红掉，本次的诊断也不会跟着丢
        // （本类第一版就吃过这个亏 —— 断言失败时报告文件根本没生成）。
        flush(report);

        JSONObject data = dataOf(info.getBody());
        assertNotNull(data, "code=0 但拿不到 data 对象，夹具需要重抓");

        int keys = data.size();
        report.append(String.format("%-42s http=%d code=0 keys=%d%n",
                "B4 article/viewinfo (cv" + SAMPLE_CV + ")", info.getStatus(), keys));
        assertEquals(EXPECTED_KEYS, keys,
                "data 键数变了（夹具记录 " + EXPECTED_KEYS + "）—— 服务端加了/删了字段，夹具与 POJO 都要重审");

        // 三层含义：全局统计 / 我视角 / 两个布尔（归因各不相同）
        JSONObject stats = data.getJSONObject("stats");
        assertNotNull(stats, "stats 必须存在 —— 全局统计只在这里，不在顶层");
        long globalLike = stats.getLongValue("like");
        long myLike = data.getLongValue("like");
        assertTrue(globalLike > 0,
                "样本专栏的全局赞数应大于 0（实际 " + globalLike + "）");
        assertEquals(0, myLike,
                "匿名时顶层 like 必须是 0 —— 它是【我有没有赞过】，不是文章的赞数。"
                        + "这一条挂掉通常意味着'我视角'字段的语义被改了，调用方会静默读错数");

        // 🔴 is_author / in_list 在这里**只打印、不断言**（2026-09-22 订正）。
        // 本类第一版曾断言"匿名下两者都是 false"，真机一跑就红了：同一个 cv、同样没带凭据，
        // 这次 in_list 拿到 true。⇒ 两个布尔**归因并不相同**（2×2 实测：is_author 跟凭据走、
        // in_list 只跟"有没有会话标识"走），拿任一个当"登录与否"的判据都只会得到一条时红时绿的检查。
        // 它们仍然值得被打印出来，供人对比"这次环境与上次有什么不同"。
        boolean isAuthor = data.getBooleanValue("is_author");
        boolean inList = data.getBooleanValue("in_list");
        report.append(String.format("%-42s stats.like=%d (global) vs data.like=%d (mine)%n",
                "  -> 三层含义自检", globalLike, myLike));
        report.append(String.format("%-42s is_author=%s in_list=%s  (printed only, NOT asserted)%n",
                "  -> 两个布尔（归因不同）", isAuthor, inList));

        // ---------- 3. 阴性对照：旧路径必须不通 ----------
        HttpResponse<String> oldPath = BilibiliHttp.get(
                "https://api.bilibili.com/x/article/view?id=" + SAMPLE_CV,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("article-view-oldpath", oldPath);
        int oldCode = codeOf(oldPath.getBody());
        report.append(String.format("%-42s http=%d code=%d (expect != 0)%n",
                "NEG article/view (old path)", oldPath.getStatus(), oldCode));
        assertNotEquals(0, oldCode, "旧路径 x/article/view 竟然通了 —— 意味着【专栏正文】可以做了，"
                + "那是本库明确不交付的一项，请重新评估（见 INTERFACE_PLAN.md §4-B4）");

        // ---------- 4. 边界表：6 个"确认做不动"的候选（只看不判） ----------
        // 待观察端点的 URL 刻意写成字面量、**不**放进 BilibiliEndpoint：
        // 常量进了主源（`src/main`）就会让人以为"本库已经支持这些端点"。
        report.append("\n--- boundary (printed only, deliberately NOT asserted) ---\n");
        report.append(probe("edge stein/edgeinfo_v2 (no graph_version)",
                "https://api.bilibili.com/x/stein/edgeinfo_v2"));
        report.append(probe("edge stein/edgeinfo_v2 (gv=0)",
                "https://api.bilibili.com/x/stein/edgeinfo_v2?graph_version=0"));
        report.append(probe("edge pgc/web/timeline",
                "https://api.bilibili.com/pgc/web/timeline?types=1&before=6&after=6"));
        report.append(probe("edge pgc/review/user (no season_id)",
                "https://api.bilibili.com/pgc/review/user"));
        report.append(probe("edge audio song/info (no sid)",
                "https://api.bilibili.com/audio/music-service-c/web/song/info"));
        report.append(probe("edge x/note/info (cvid=1, expect -400/-101)",
                "https://api.bilibili.com/x/note/info?cvid=1"));
        report.append("edge 弹幕 WebSocket / 写操作：按设计未探（需独立立项，见 §4-B4）\n");

        // 边界表只落盘，供日后比对；不写进 .workbuddy 之外的任何地方
        flush(report);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 把当前报告落盘 + 打到 stdout。
     *
     * <p>⚠️ 在"可能失败的断言之前"也要调用一次 —— 否则断言一红，报告文件就不会生成，
     * 那正好在**最需要诊断信息的时候**把它丢掉（本类第一版踩过）。
     */
    private static void flush(StringBuilder report) {
        try {
            Files.writeString(Path.of(".workbuddy/_b4_preflight.txt"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("flush failed: " + e);
        }
        System.out.printf("%n§ B4 preflight%n%s", report);
    }

    /**
     * 打一次**待观察**端点，只返回一行报告。
     *
     * <p>刻意<b>不</b>在这里 assert：这些端点当前"不通"是**观察结果**，不是契约。
     * 上游哪天修好了，本行会从 {@code -400} 变成 {@code 0} —— 那是好消息，
     * 应由人看到表格后决定要不要开工，不该让冒烟测试变红。
     */
    private static String probe(String tag, String url) {
        try {
            HttpResponse<String> resp = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                    BilibiliEndpoint.referer);
            String body = resp.getBody() == null ? "" : resp.getBody();
            // "连 data 键都没有"是 B4 学到的一种独立形态 —— 单独报出来，别混进"空列表"
            boolean hasDataKey = body.startsWith("{") && body.contains("\"data\"");
            return String.format("%-44s http=%d code=%d dataKey=%s%n",
                    tag, resp.getStatus(), codeOf(body), hasDataKey);
        } catch (Exception e) {
            return String.format("%-44s [exception] %s%n", tag, e);
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
            Files.writeString(dir.resolve("_b4_" + name + ".txt"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
        }
    }
}

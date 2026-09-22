package com.esdllm.bilibiliApi.smoke;

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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B3.5 批的<b>开工前预检</b>（联网，默认跳过）。
 *
 * <p><b>它存在的理由是一次真实的翻案。</b> 定这一批之前，先用一个<b>纯 Python</b> 探测脚本
 * 把 6 个端点跑了一遍，结论是 {@code playurl} 四格（720P/1080P × 凭据/匿名）
 * <b>全部 HTTP 412 {@code request was banned}</b> —— 与 {@code INTERFACE_PLAN.md} 里
 * "09-21 匿名未签名即 {@code code=0}、412 未复现"的记载直接冲突。
 *
 * <p>但那个脚本有个致命短处：<b>它没有带 {@code buvid3} 设备指纹</b>（裸 urllib，不跑
 * {@code AnonymousSession}）。而本库既有的实测结论恰恰是"风控钥匙是 {@code buvid3}，不是 UA"。
 * ⇒ 于是"端点被封"与"探测姿势不对"两种解释无法区分。
 *
 * <p>本类把同一批请求放到<b>库自己的 HTTP 栈</b>（{@link BilibiliHttp}，含指纹/限流/重试/
 * 身份轮换）上重跑一遍，并<b>先打一个阳性对照</b>：
 * {@code x/web-interface/view} 匿名必须 {@code code=0}，否则整表结论作废
 * （红线条目：没有阳性对照时，满屏 {@code -352}/{@code -412} 可能只是出口被封）。
 *
 * <p>跑法：
 * <pre>
 * mvn -o -B test "-Dtest=B35PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true -Dbili.cookieFile=.workbuddy/bili-cookie.txt"
 * </pre>
 *
 * <p>它同时把每一条的<b>原始响应体</b>落到 {@code .workbuddy/_j35_*.json} ——
 * 因为 POJO 的字段名必须与真实 JSON 逐字对应，凭记忆写一定会错。
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：B3.5 六端点（默认跳过）")
class B35PreflightSmokeTest {

    /** 真实 bvid/cid：来自同一次探测里 {@code history/cursor} 的返回，且匿名 {@code view} 验证可达 */
    private static final String BVID = "BV1Lfhq6jEGp";
    private static final long CID = 42086696070L;

    /** 与 {@code x/web-interface/view} 的 JSON 形状一致的 bvid 片段 */
    private static final String VIDEO_REFERER = "https://www.bilibili.com/video/" + BVID;

    @Test
    @DisplayName("六端点逐条预检：先跑阳性对照，再带凭据 / 匿名各一次")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");
        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(),
                "未提供 -Dbili.cookie / -Dbili.cookieFile，跳过");

        StringBuilder report = new StringBuilder();
        report.append("B3.5 preflight\n");

        // ---------- 1. 阳性对照（必须先跑） ----------
        HttpPolicy.clearCookie();
        HttpResponse<String> control = BilibiliHttp.get(
                BilibiliEndpoint.videoBaseUrl + BVID, BilibiliEndpoint.jsonAccept, VIDEO_REFERER);
        dump("control-view", control);
        int controlCode = codeOf(control.getBody());
        report.append(String.format("control view        http=%d code=%d%n",
                control.getStatus(), controlCode));
        assertEquals(0, controlCode,
                "阳性对照失败：连 x/web-interface/view 都拿不到 code=0，说明出口 IP 被封，"
                        + "本次预检的所有 412/-352 都不可作为'端点被封'的证据");

        // ---------- 2. playurl：凭据 vs 匿名，MP4 与 DASH ----------
        HttpPolicy.setCookie(cookie);
        report.append(playUrl("playurl cred fnval=1 qn=64", 64, 1, true));
        report.append(playUrl("playurl cred fnval=1 qn=80", 80, 1, true));
        report.append(playUrl("playurl cred fnval=16(DASH)", 80, 16, true));
        HttpPolicy.clearCookie();
        report.append(playUrl("playurl anon fnval=1 qn=64", 64, 1, false));

        // ---------- 2b. playurl：补上"签名"这一格 ----------
        // 上面四格全是"不签名"。路径里带 /wbi/，规划也判"DASH 必须签名"，
        // 所以"是否只要签上名就能通"是决定该项能否交付的最后一格 —— 不跑它就无法定案。
        HttpPolicy.setCookie(cookie);
        report.append(playUrlSigned("playurl SIGNED fnval=1 qn=64", 64, 1));
        report.append(playUrlSigned("playurl SIGNED fnval=16(DASH)", 80, 16));
        HttpPolicy.clearCookie();
        report.append(playUrlSigned("playurl SIGNED anon fnval=1 qn=64", 64, 1));

        // ---------- 2c. 换门：非 /wbi/ 路径 ----------
        //   /wbi/ 那条七格全 412（凭据、签名、指纹都已确认带上），但同名的<b>非</b> /wbi/ 路径
        //   回了 code=0。这不只是"另一个参数"，而是"这扇门还开不开"的分水岭 ——
        //   它决定 playurl 这一项是"交付"还是"退回环境挂起"，所以四种组合都留证。
        HttpPolicy.setCookie(cookie);
        report.append(plain("playurl(plain) fnval=1 qn=64 cred", playUrlPlain(64, 1, false), VIDEO_REFERER));
        report.append(plain("playurl(plain) fnval=1 qn=80 cred", playUrlPlain(80, 1, false), VIDEO_REFERER));
        report.append(plain("playurl(plain) fnval=16(DASH) qn=80 cred", playUrlPlain(80, 16, false), VIDEO_REFERER));
        report.append(plain("playurl(plain) html5 qn=64 cred", playUrlPlain(64, 1, true), VIDEO_REFERER));
        HttpPolicy.clearCookie();
        report.append(plain("playurl(plain) fnval=1 qn=64 anon", playUrlPlain(64, 1, false), VIDEO_REFERER));

        // ---------- 3. 其余五个（凭据） ----------
        HttpPolicy.setCookie(cookie);
        report.append(plain("upstat cred", BilibiliEndpoint.upstatUrl + "497078180",
                "https://space.bilibili.com/497078180"));
        report.append(plain("toview cred", BilibiliEndpoint.historyToViewUrl, BilibiliEndpoint.watchLaterReferer));
        report.append(plain("followers cred",
                BilibiliEndpoint.relationFollowersUrl + "?vmid=497078180&pn=1&ps=5",
                "https://space.bilibili.com/497078180/fans"));
        report.append(plain("followings cred",
                BilibiliEndpoint.relationFollowingsUrl + "?vmid=497078180&pn=1&ps=5",
                "https://space.bilibili.com/497078180/follow"));
        report.append(plain("history cred", BilibiliEndpoint.historyCursorUrl + "?ps=5",
                BilibiliEndpoint.historyReferer));
        report.append(plain("fav-folders cred", BilibiliEndpoint.favFolderListAllUrl + "?up_mid=497078180",
                "https://space.bilibili.com/497078180/favlist"));

        // ---------- 4. 五个端点的匿名对照 ----------
        HttpPolicy.clearCookie();
        report.append(plain("upstat anon", BilibiliEndpoint.upstatUrl + "497078180",
                "https://space.bilibili.com/497078180"));
        report.append(plain("toview anon", BilibiliEndpoint.historyToViewUrl, BilibiliEndpoint.watchLaterReferer));
        report.append(plain("followers anon",
                BilibiliEndpoint.relationFollowersUrl + "?vmid=497078180&pn=1&ps=5",
                "https://space.bilibili.com/497078180/fans"));
        report.append(plain("history anon", BilibiliEndpoint.historyCursorUrl + "?ps=5",
                BilibiliEndpoint.historyReferer));
        report.append(plain("fav-folders anon", BilibiliEndpoint.favFolderListAllUrl + "?up_mid=497078180",
                "https://space.bilibili.com/497078180/favlist"));

        Files.writeString(Path.of(".workbuddy/_j35_preflight.txt"), report.toString(),
                StandardCharsets.UTF_8);
        System.out.printf("%n§ B3.5 preflight%n%s", report);
    }

    /** 打一次 playurl 并落盘 */
    private static String playUrl(String tag, int qn, int fnval, boolean withCred) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", BVID);
        params.put("cid", String.valueOf(CID));
        params.put("qn", String.valueOf(qn));
        params.put("fnval", String.valueOf(fnval));
        params.put("fourk", "1");
        String url = BilibiliEndpoint.playUrlUrl + "?"
                + params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b).orElse("");
        HttpResponse<String> resp = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept, VIDEO_REFERER);
        dump("playurl-qn" + qn + "-fnval" + fnval + (withCred ? "-cred" : "-anon"), resp);
        return String.format("%-32s http=%d code=%d%n", tag, resp.getStatus(), codeOf(resp.getBody()));
    }

    /** 打一次<b>带 WBI 签名</b>的 playurl 并落盘。签名取不到密钥时把异常记进报告而不是让整个预检炸掉。 */
    private static String playUrlSigned(String tag, int qn, int fnval) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", BVID);
        params.put("cid", String.valueOf(CID));
        params.put("qn", String.valueOf(qn));
        params.put("fnval", String.valueOf(fnval));
        params.put("fourk", "1");
        try {
            HttpResponse<String> resp = BilibiliHttp.getSigned(BilibiliEndpoint.playUrlUrl, params,
                    BilibiliEndpoint.jsonAccept, VIDEO_REFERER);
            dump("playurl-signed-qn" + qn + "-fnval" + fnval, resp);
            return String.format("%-32s http=%d code=%d%n", tag, resp.getStatus(), codeOf(resp.getBody()));
        } catch (RuntimeException e) {
            return String.format("%-32s EXC %s%n", tag, e.getMessage());
        }
    }

    /** 拼一条<b>非 /wbi/</b> 的 playurl（备用门。是否可用见类注释与预检报告） */
    private static String playUrlPlain(int qn, int fnval, boolean html5) {
        String url = BilibiliEndpoint.playUrlPlainUrl + "?bvid=" + BVID + "&cid=" + CID
                + "&qn=" + qn + "&fnval=" + fnval;
        return html5 ? url + "&platform=html5&high_quality=1" : url;
    }

    /** 打一次普通 GET 并落盘 */
    private static String plain(String tag, String url, String referer) {
        HttpResponse<String> resp = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept, referer);
        String shortTag = tag.replace(' ', '-');
        dump(shortTag, resp);
        return String.format("%-32s http=%d code=%d%n", tag, resp.getStatus(), codeOf(resp.getBody()));
    }

    /** 取业务码；不是 JSON 时返回 {@code Integer.MIN_VALUE} */
    private static int codeOf(String body) {
        if (body == null || body.isBlank() || body.charAt(0) != '{') {
            return Integer.MIN_VALUE;
        }
        ApiResponse<Object> parsed = com.alibaba.fastjson.JSON.parseObject(body,
                new com.alibaba.fastjson.TypeReference<ApiResponse<Object>>() {
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
            Files.writeString(dir.resolve("_j35_" + name + ".json"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
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

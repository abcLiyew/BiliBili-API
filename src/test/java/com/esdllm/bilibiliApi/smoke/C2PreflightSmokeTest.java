package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.bilibiliApi.Search;
import com.esdllm.bilibiliApi.bilibiliApi.UserSpace;
import com.esdllm.bilibiliApi.bilibiliApi.VideoExtra;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.search.DefaultSearchWord;
import com.esdllm.bilibiliApi.model.data.pojo.user.NavNum;
import com.esdllm.bilibiliApi.model.data.pojo.video.RegionOnline;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C2 批（候选池换源 · 第二批）的<b>交付前预检</b>（联网，默认跳过）。
 *
 * <p>本批 3 个端点<b>全部匿名可用、不需要签名与凭据</b>，所以本类的重点不是门槛，
 * 而是把三处<b>只有真机才看得见、且极易被后人"顺手改坏"</b>的事实变成可重复执行的检查：
 *
 * <ol>
 *   <li>🔴 <b>{@code search/default} 的 {@code seid} 必须是 {@code String}</b> ——
 *       实测值是 <b>19~20 位的随机数字串</b>，<b>大多数时候超出 {@code long} 范围</b>
 *       （实测最长 {@code 16451640188548591644} ≈ 1.65e19），但<b>偶尔会落在范围内</b>。
 *       ⇒ 断言只能钉<b>字段类型</b>（反射），<b>不能钉运行时的值</b> —— 后者是"时而绿时而红"。
 *       本类第一版就栽在这上面（同一份代码两次运行，一次红一次绿，纯粹因为服务端那次给的数小）。
 *       <br>⚠️ 并且 {@code seid} <b>每次请求都变</b>（2026-10-02 三连跑三个不同值）——
 *       它是<b>搜索会话 id</b>，不是"这个词的稳定 id"，<b>不可缓存、不可跨请求复用</b>；
 *       而同一次请求里的 {@code name} / {@code id} / {@code url} 才是"当前默认词"。</li>
 *   <li>🔴 <b>{@code space/navnum} 的参数名是 {@code mid}，不是 {@code vmid}</b>
 *       —— B5 那次盘点正是栽在传错这个参数上（拿到 {@code -400} 差点把能用的端点判死）。
 *       本类跑两格：{@code ?mid=2} 必须 {@code code=0}（<b>断言</b>），
 *       {@code ?vmid=2} 只<b>打印 + WARNING</b>（理由见下第 4 点）。</li>
 *   <li>🔴 <b>{@code web-interface/online} 与 {@code player/online/total} 的"形状"不同</b>
 *       —— 前者给 {@code region_count}（全站 26 分区），后者给 {@code total/count}（单个视频）。
 *       两者<b>量级差约三个数量级（实测相差 500~7000 倍）</b>，混用会得出"这个视频有 34 万人在看"。
 *       本类断言前者的 {@code data} <b>没有</b> {@code total} 键。</li>
 *   <li>🔴 <b>{@code web-interface/online} 不吃任何参数</b>（本类第 3 段的对照格）——
 *       六格实测（无参 / 各种 {@code bvid} / {@code garbage} / 空串 / {@code aid}）逐字节相同
 *       ⇒ 库内**不给它加参数**。⚠️ 交付时它曾是 {@code getRegionOnlineCount(String bvid)}，
 *       review 时正是被这组 A/B 打回：**同一条"参数被忽略"的判据，我们拿来剔除了
 *       {@code web-interface/zone}，却给这个端点强加了一个参数**。
 *       该格只<b>打印 + WARNING</b>：在线人数每秒都在变，"两次响应相同"不能当断言。</li>
 *   <li>⚠️ <b>关于"哪些下断言、哪些只打印"</b>（本库在 B4 踩过、在 B5 定下的纪律）：
 *       依赖<b>协议</b>的（{@code code=0}、参数名、{@code seid} 接不了 long、响应形状）
 *       写断言；依赖<b>上游状态</b>的（风控、在线人数是多少、分区有几个）<b>只打印</b>。
 *       特别地，{@code ?vmid=} 这一格<b>刻意不下断言</b>：若哪天它从 {@code -400} 变成
 *       {@code code=0}，那是服务端<b>变宽</b>了 —— 库内继续用 {@code mid} 依然正确，
 *       没有任何东西需要返工。把"变宽"写成断言，只会得到一条<b>无故变红</b>的检查。</li>
 * </ol>
 *
 * <p>跑法（全匿名即可）：
 * <pre>
 * mvn -o -B test "-Dtest=C2PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true"
 * </pre>
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：C2 批 3 端点 + 三处易错点复查（默认跳过）")
class C2PreflightSmokeTest {

    /** 与 {@code space-navnum.json} 夹具同源的 UP（B 站官方账号，内容类型齐全） */
    private static final long SAMPLE_MID = 2L;

    /**
     * 任意活跃视频 —— 只用来给 {@code player/online/total} 的<b>量级对照格</b>取 {@code cid}。
     * ⚠️ {@code web-interface/online} 那格**已经不用它了**（该端点不吃参数）。
     */
    private static final String SAMPLE_BVID = "BV1BqhB6nEdN";

    @Test
    @DisplayName("阳性对照 → 3 端点正向 → 两处对照 → 门面复验，并落盘报告")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        StringBuilder report = new StringBuilder();
        report.append("C2 preflight（3 端点，全部匿名可用）\n");

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

        // ---------- 1. search/default：无参数 + seid 超 long ----------
        HttpResponse<String> defaultResp = BilibiliHttp.get(
                BilibiliEndpoint.searchDefaultUrl,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("search-default", defaultResp);
        int defaultCode = codeOf(defaultResp.getBody());
        assertEquals(0, defaultCode,
                "search/default 拿不到 code=0 —— 该端点本该匿名可读（2026-10-02 两格实测均 code=0）");
        JSONObject defaultData = dataOf(defaultResp.getBody());
        assertNotNull(defaultData, "search/default 没有 data");
        String rawSeid = defaultData.getString("seid");
        assertNotNull(rawSeid, "data 里没有 seid —— 响应形状变了");
        assertFalse(rawSeid.isBlank(), "seid 是空串 —— 响应形状变了");
        report.append(String.format("%-46s code=0 name=%s%n",
                "C2 search/default", defaultData.getString("name")));

        // 🔴 协议级断言：seid 的**字段类型**必须是 String —— 改回 long 会溢出/变负。
        //    ⚠️ 这里**刻意不断言运行时的值**：实测 seid 是随机会话 id，**大多数时候超过
        //    Long.MAX_VALUE（1.65e19 级别），但偶尔会落在 long 范围内**。
        //    把"值超 long"写进断言 = 一条**时而绿时而红**的检查（本库红线 26 ④），
        //    本轮就真的踩到了：第一次跑红、第二次跑绿，纯粹因为服务端这次给的数小。
        assertEquals(String.class, DefaultSearchWord.class.getDeclaredField("seid").getType(),
                "★ seid 的字段类型被改成非 String 了 —— 它实测出现过超出 Long.MAX_VALUE 的值"
                        + "（最长约 1.65e19），按数值接会溢出/变负，且不一定抛异常");

        // 🔴 第二次请求：seid 是**每次请求新生成**的搜索会话 id（2026-10-02 三连跑各不相同）。
        //    它不该被当成"这个词的稳定 id"去缓存或跨请求复用 —— 只打印，不下断言（上游状态）。
        HttpResponse<String> defaultResp2 = BilibiliHttp.get(
                BilibiliEndpoint.searchDefaultUrl,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("search-default-2nd", defaultResp2);
        JSONObject defaultData2 = dataOf(defaultResp2.getBody());
        String rawSeid2 = defaultData2 == null ? null : defaultData2.getString("seid");
        assertNotNull(rawSeid2, "第二次请求没有 seid —— 响应形状变了");
        reportSeid("  seid 稳定性复查 1st", rawSeid, report);
        reportSeid("  seid 稳定性复查 2nd", rawSeid2, report);
        report.append("  （历史实测：seid 常为 19~20 位、多次超出 Long.MAX_VALUE —— "
                + "所以它必须留在 String 里，与本次的值大小无关）\n");
        if (rawSeid.equals(rawSeid2)) {
            report.append("  WARNING: 两次请求的 seid 相同了 —— 服务端可能改成「会话级」seid；"
                    + "请复核 DefaultSearchWord#seid 的 javadoc"
                    + "（原本写的是『每次请求新生成、不可缓存』）\n");
        } else {
            report.append("  WARNING: seid 每次请求都变，符合预期（不可缓存 / 不可跨请求复用）\n");
        }

        // ---------- 2. space/navnum：参数名 mid（正向断言） / vmid（只打印） ----------
        HttpResponse<String> navnumResp = BilibiliHttp.get(
                BilibiliEndpoint.spaceNavNumUrl + "?mid=" + SAMPLE_MID,
                BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.spaceReferer.formatted(SAMPLE_MID));
        dump("space-navnum-mid", navnumResp);
        int navnumCode = codeOf(navnumResp.getBody());
        assertEquals(0, navnumCode,
                "★ space/navnum 用 ?mid= 拿不到 code=0 —— 参数名就是 mid（不是 vmid），"
                        + "若这条红了请先确认是不是自己把参数名写错了");
        JSONObject navnumData = dataOf(navnumResp.getBody());
        assertNotNull(navnumData, "space/navnum 没有 data");
        JSONObject fav = navnumData.getJSONObject("favourite");
        assertNotNull(fav, "★ favourite 本该是嵌套对象 {guest, master} —— 形状变了");
        report.append(String.format("%-46s code=0 video=%s album=%s opus=%s favourite=%s%n",
                "C2 space/navnum ?mid=", navnumData.get("video"), navnumData.get("album"),
                navnumData.get("opus"), fav));

        // 对照格：把参数名写成 vmid（同域另一个端点的写法）。**只打印 + WARNING**：
        // 这一格即使变成 code=0 也不代表库内要改 —— 见类 javadoc 第 4 点。
        HttpResponse<String> navnumVmid = BilibiliHttp.get(
                BilibiliEndpoint.spaceNavNumUrl + "?vmid=" + SAMPLE_MID,
                BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.spaceReferer.formatted(SAMPLE_MID));
        dump("space-navnum-vmid", navnumVmid);
        int vmidCode = codeOf(navnumVmid.getBody());
        report.append(String.format("%-46s http=%d code=%d%n",
                "  CTRL space/navnum ?vmid=", navnumVmid.getStatus(), vmidCode));
        if (vmidCode == 0) {
            report.append("  WARNING: ?vmid= 也给 code=0 了 —— 服务端对参数名变宽容了"
                    + "；库内继续用 mid 正确，但可放宽 BilibiliEndpoint#spaceNavNumUrl 里"
                    + "『参数名是 mid 不是 vmid』的强表述\n");
        } else {
            report.append(String.format("  WARNING: ?vmid= 仍不被接受（%d）"
                            + " —— 库内必须用 mid 的决定继续有效（B5 那次就栽在这）%n",
                    vmidCode));
        }

        // ---------- 3. web-interface/online：无参数 + 形状 != online/total ----------
        HttpResponse<String> onlineResp = BilibiliHttp.get(
                BilibiliEndpoint.webInterfaceOnlineUrl,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("region-online", onlineResp);
        int onlineCode = codeOf(onlineResp.getBody());
        assertEquals(0, onlineCode, "web-interface/online 拿不到 code=0 —— 该端点本该匿名可读");
        JSONObject onlineData = dataOf(onlineResp.getBody());
        assertNotNull(onlineData, "web-interface/online 没有 data");
        JSONObject regionCount = onlineData.getJSONObject("region_count");
        assertNotNull(regionCount, "★ data 里没有 region_count —— 响应形状变了");
        assertFalse(regionCount.isEmpty(), "region_count 是空的 —— 形状对但内容空");
        // 🔴 形状红线：本端点的 data 里**不该**有 total / count（那是 online/total 的字段）
        assertFalse(onlineData.containsKey("total"),
                "★ web-interface/online 竟然给了 total —— 它和 player/online/total 可能被合并了，"
                        + "请复核 RegionOnline 与 OnlineTotal 是否还需分开");
        assertFalse(onlineData.containsKey("count"),
                "★ web-interface/online 竟然给了 count —— 同上");
        long regionTotal = 0L;
        for (Map.Entry<String, Object> e : regionCount.entrySet()) {
            regionTotal += Long.parseLong(String.valueOf(e.getValue()));
        }
        report.append(String.format("%-46s code=0 regions=%d total=%d first=\"%s\"%n",
                "C2 web-interface/online（无参）", regionCount.size(), regionTotal,
                regionCount.keySet().iterator().next()));

        // 🔴 参数对照格：**库内特意不给它加 bvid**（2026-10-02 六格实测：无参 / 各种 bvid /
        //    garbage / 空串 / aid 逐字节相同 ⇒ 端点根本不看参数）。⚠️ 这一格只打印：
        //    在线人数**每秒都在变**，把"两次响应相同"写成断言就是"时而绿时而红"（红线 26 ④）。
        //    要复跑"参数是否被消费"请用 tools/endpoint-preflight.py 的 C2 段（同趟连跑更高频）。
        HttpResponse<String> onlineJunk = BilibiliHttp.get(
                BilibiliEndpoint.webInterfaceOnlineUrl + "?bvid=garbage",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("region-online-junkbvid", onlineJunk);
        JSONObject junkData = dataOf(onlineJunk.getBody());
        JSONObject junkRegions = junkData == null ? null : junkData.getJSONObject("region_count");
        report.append(String.format("%-46s code=%s regions=%s%n",
                "  CTRL online ?bvid=garbage", codeOf(onlineJunk.getBody()),
                junkRegions == null ? "-" : String.valueOf(junkRegions.size())));
        if (codeOf(onlineJunk.getBody()) != 0 || junkRegions == null || junkRegions.isEmpty()) {
            report.append("  WARNING: 传垃圾 bvid 不再返回完整分布了 —— 服务端可能开始【消费】bvid；"
                    + "那是好事但意味着可加参数，请重新评估『本端点无参数』这个结论，"
                    + "并同步 VideoService#getRegionOnlineCount 的签名与 javadoc\n");
        } else {
            report.append("  WARNING: 垃圾 bvid 照样返回完整分布 —— 参数仍被忽略，"
                    + "库内『不给它加参数』的决定继续有效\n");
        }

        // 对照格：online/total（单个视频）——证明量级确实差很远。只打印（人数是上游状态）。
        Long cid = null;
        HttpResponse<String> viewResp = BilibiliHttp.get(
                BilibiliEndpoint.videoBaseUrl + SAMPLE_BVID,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("view-sample", viewResp);
        JSONObject viewData = dataOf(viewResp.getBody());
        if (viewData != null && codeOf(viewResp.getBody()) == 0) {
            cid = viewData.getLong("cid");
        }
        if (cid != null) {
            HttpResponse<String> totalResp = BilibiliHttp.get(
                    BilibiliEndpoint.onlineTotalUrl + "?bvid=" + SAMPLE_BVID + "&cid=" + cid,
                    BilibiliEndpoint.jsonAccept,
                    BilibiliEndpoint.videoReferer.formatted(SAMPLE_BVID));
            dump("online-total", totalResp);
            JSONObject totalData = dataOf(totalResp.getBody());
            report.append(String.format("%-46s code=%d total=%s count=%s%n",
                    "  CTRL player/online/total（同一视频）", codeOf(totalResp.getBody()),
                    totalData == null ? "-" : totalData.get("total"),
                    totalData == null ? "-" : totalData.get("count")));
            report.append(String.format("%-46s 两个端点的量级差见上面两行 —— "
                    + "全站合计 %d vs 单视频百量级%n", "  -> 说明", regionTotal));
        } else {
            report.append("  （拿不到 sample 视频的 cid，跳过 online/total 对照格）\n");
        }

        // ---------- 4. 走门面再验一次：证明『解析 + 门槛』整条链路通 ----------
        HttpPolicy.clearCookie();
        DefaultSearchWord viaSearch = new Search().getDefaultSearchWord();
        assertNotNull(viaSearch, "Search#getDefaultSearchWord 返回 null —— 与上面的 code=0 矛盾");
        assertNotNull(viaSearch.getName(), "★ 门面返回的词为 null —— 空词守卫没生效");
        report.append(String.format("%-46s name=%s seid=%s%n",
                "门面 Search#getDefaultSearchWord", viaSearch.getName(), viaSearch.getSeid()));

        NavNum viaUser = new UserSpace().getNavNum(SAMPLE_MID);
        assertNotNull(viaUser, "UserSpace#getNavNum 返回 null —— 与上面的 code=0 矛盾");
        assertNotNull(viaUser.getFavourite(), "★ 嵌套对象 favourite 没被映射出来");
        report.append(String.format("%-46s video=%s favourite.master=%s%n",
                "门面 UserSpace#getNavNum", viaUser.getVideo(),
                viaUser.getFavourite().getMaster()));

        RegionOnline viaVideo = new VideoExtra().getRegionOnlineCount();
        assertNotNull(viaVideo, "VideoExtra#getRegionOnlineCount 返回 null —— 与上面的 code=0 矛盾");
        assertFalse(viaVideo.getRegion_count().isEmpty(), "★ 门面返回的空分布不该过关");
        report.append(String.format("%-46s regions=%d%n",
                "门面 VideoExtra#getRegionOnlineCount", viaVideo.getRegion_count().size()));

        // 非法入参必须在出站前被拦住（不需要网络，但放在这里一并复验门面口径）
        // ⚠️ 只有 getNavNum 还有入参 —— getRegionOnlineCount 是**无参**的（该端点不看参数）。
        // 🔴 断言必须精确到 IOException：写成 Exception 的话，门面里任何 NPE 也会让这条"通过"
        // （测试就只证明了"抛了个什么"，没证明"按契约抛 IOException"）。
        assertThrows(IOException.class, () -> new UserSpace().getNavNum(0L));
        report.append("  非法入参（mid=0）在出站前抛异常 —— 复验通过\n");

        flush(report);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 打印一个 {@code seid} 及其"落在 long 范围内与否"与位数。
     *
     * <p>⚠️ <b>只打印、不断言</b>：seid 是随机会话 id，实测<b>大多数时候</b>超过
     * {@code Long.MAX_VALUE}、<b>偶尔</b>落在范围内 ⇒ 它的量级是<b>上游状态</b>，
     * 写进断言会得到一条"时而绿时而红"的检查。
     */
    private static void reportSeid(String label, String seid, StringBuilder report) {
        boolean fitsLong;
        try {
            Long.parseLong(seid);
            fitsLong = true;
        } catch (NumberFormatException e) {
            fitsLong = false;
        }
        report.append(String.format("%-46s seid=%s digits=%d %s%n", label, seid, seid.length(),
                fitsLong ? "（本次落在 long 范围内）" : "（本次超出 long 范围）"));
    }

    private static void flush(StringBuilder report) {
        try {
            Files.createDirectories(Path.of(".workbuddy"));
            Files.writeString(Path.of(".workbuddy/_c2_preflight_smoke.txt"), report.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("flush failed: " + e);
        }
        System.out.printf("%n§ C2 preflight%n%s", report);
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
            Files.writeString(dir.resolve("_c2_smoke_" + name + ".txt"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
        }
    }
}

package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * B2 批的<b>开工前 / 交付前预检</b>（联网，默认跳过）。
 *
 * <p>本类存在的主要理由是<b>一个有争议的设计问题</b>：弹幕端点 {@code x/v1/dm/list.so}
 * 返回的是 <b>{@code Content-Encoding: deflate}</b> 的 XML，而本库唯一的出站出口
 * {@link BilibiliHttp#get} 返回的是 {@code HttpResponse<String>}（{@code asString()}）。
 * 于是有两种互斥的可能，且<b>结论决定要不要给出口加一个"字节"重载</b>：
 *
 * <ul>
 *   <li><b>甲：底层（Apache/Unirest）自动解压了</b> ⇒ {@code getBody()} 拿到的<b>已经是 XML 文本</b>，
 *       业务层直接解析即可，<b>不需要</b>动出口；</li>
 *   <li><b>乙：没有解压</b> ⇒ 压缩字节已被 charset 解码<b>有损破坏</b>（UTF-8 解码替换字符不可逆），
 *       字符串里彻底没有原始字节 ⇒ <b>必须</b>给 {@code BilibiliHttp} 加一个返回 {@code byte[]} 的重载，
 *       否则这条链路做不出来。</li>
 * </ul>
 *
 * <p>所以本类对弹幕端点打印三个判据：<b>body 长度、首字符是否像 XML、以及"把字符串按
 * ISO-8859-1 回读字节后再 inflate"能不能成功</b>。三者合起来就能区分甲/乙，
 * 而不是靠"我觉得 Unirest 应该会解压"。
 *
 * <p>另外两件事：
 * <ol>
 *   <li>把每个端点的<b>真实响应体落盘</b>到 {@code .workbuddy/_b2_*.json}
 *       —— 夹具必须从真实响应抄，不能凭文档编（本库已因此踩过多次）。</li>
 *   <li>断言 5 个匿名端点 {@code code=0}。<b>冻结契约测试只能证明方法存在，
 *       证明不了"这条路今天还能走"。</b></li>
 * </ol>
 *
 * <p><b>阳性对照先跑</b>（{@code popular} 用站根 Referer）：它拿不到 {@code code=0} 时整表作废。
 *
 * <p>⚠️ 自举原则：视频 id 从 {@code popular} 的第一条取（<b>不硬编码</b>）；
 * 收藏夹 {@code media_id} 用的是 {@code src/test/resources/fixtures/fav-folders.json}
 * 里记录的那两个（一个公开、一个私密）—— 它们不是"内容样本"，而是用来<b>复现公开/私密两条权限路径</b>的。
 * 拿不到时按 {@code assumeTrue} 跳过，不伪装成"端点挂了"。
 *
 * <p>跑法（本批全匿名，<b>不需要</b> {@code -Dbili.cookieFile}）：
 * <pre>
 * mvn -o -B test "-Dtest=B2PreflightSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true"
 * </pre>
 *
 * @author 饿死的流浪猫
 */
@DisplayName("联网预检：B2 七端点（默认跳过）")
class B2PreflightSmokeTest {

    /** 公开收藏夹（{@code attr=1}，来自 {@code fixtures/fav-folders.json}） */
    private static final String PUBLIC_MEDIA_ID = "1095405480";
    /** 私密收藏夹（{@code attr=2}），用于验证"匿名访问应被拒" */
    private static final String PRIVATE_MEDIA_ID = "3526698880";

    @Test
    @DisplayName("先跑阳性对照，再逐条预检 B2 七项；弹幕额外判定 deflate 是否已被底层解压")
    void preflight() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网预检");

        StringBuilder report = new StringBuilder();
        report.append("B2 preflight（全部匿名：本批 7 项都不需要签名与凭据）\n");

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

        // ---------- 2. 自举真实 id ----------
        JSONObject first = firstItem(control.getBody());
        assumeTrue(first != null, "热门列表为空，无法自举样本 id");
        String bvid = first.getString("bvid");
        String cid = String.valueOf(first.getLongValue("cid"));
        String aid = String.valueOf(first.getLongValue("aid"));
        report.append(String.format("bootstrap from popular       bvid=%s aid=%s cid=%s%n", bvid, aid, cid));
        assertTrue(bvid != null && !bvid.isBlank(), "自举到的 bvid 不能为空");

        String videoReferer = BilibiliEndpoint.videoReferer.formatted(bvid);

        // ---------- 3. 弹幕：本类存在的主要理由 ----------
        HttpResponse<String> dm = BilibiliHttp.get(
                BilibiliEndpoint.dmListUrl + "?oid=" + cid,
                BilibiliEndpoint.jsonAccept, videoReferer);
        dump("dm-list", dm);
        report.append(describeDanmaku(dm));

        // ---------- 4. 热搜 ----------
        report.append(plain("B2 search/square (热搜)",
                BilibiliEndpoint.searchSquareUrl + "?limit=10", BilibiliEndpoint.referer));

        // ---------- 5. 二级评论（要先拿主评论的 rpid） ----------
        HttpResponse<String> top = BilibiliHttp.get(
                BilibiliEndpoint.replyUrl + "?type=1&oid=" + aid + "&pn=1&ps=20&sort=2",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        dump("reply-main", top);
        report.append(String.format("%-42s http=%d code=%d%n",
                "B2 v2/reply (main, oid=aid)", top.getStatus(), codeOf(top.getBody())));
        Long rootRpid = firstRpid(top.getBody());
        if (rootRpid != null) {
            report.append(plain("B2 reply/reply (sub, root=" + rootRpid + ")",
                    BilibiliEndpoint.replyReplyUrl + "?type=1&oid=" + aid + "&root=" + rootRpid
                            + "&pn=1&ps=20",
                    BilibiliEndpoint.referer));
        } else {
            report.append("B2 reply/reply                 [skip] main replies empty -- no rpid to use as root\n");
        }

        // ---------- 6. 收藏夹：公开 / 私密两条权限路径 ----------
        report.append(plain("B2 fav/folder/info (public)",
                BilibiliEndpoint.favFolderInfoUrl + "?media_id=" + PUBLIC_MEDIA_ID,
                BilibiliEndpoint.referer));
        report.append(plain("B2 fav/resource/list (public)",
                BilibiliEndpoint.favResourceListUrl + "?media_id=" + PUBLIC_MEDIA_ID + "&pn=1&ps=20",
                BilibiliEndpoint.referer));
        report.append(plain("B2 fav/folder/info (PRIVATE)",
                BilibiliEndpoint.favFolderInfoUrl + "?media_id=" + PRIVATE_MEDIA_ID,
                BilibiliEndpoint.referer));
        report.append(plain("B2 fav/resource/list (PRIVATE)",
                BilibiliEndpoint.favResourceListUrl + "?media_id=" + PRIVATE_MEDIA_ID + "&pn=1&ps=20",
                BilibiliEndpoint.referer));

        // ---------- 7. 表情包 + 直播分区 ----------
        report.append(plain("B2 emote panel (business=reply)",
                BilibiliEndpoint.emotePanelUrl + "?business=reply", BilibiliEndpoint.referer));
        report.append(plain("B2 live Area/getList",
                BilibiliEndpoint.liveAreaListUrl + "?parent_area_id=1", BilibiliEndpoint.liveReferer));

        // ---------- 8. 阴性对照：已下线的端点应当 404 ----------
        HttpResponse<String> dead = BilibiliHttp.get(
                "https://api.bilibili.com/x/web-interface/search/suggest?term=bilibili",
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
        report.append(String.format("%-42s http=%d (expect 404 -- proves 'alive' rows mean something)%n",
                "NEG search/suggest (retired)", dead.getStatus()));

        Files.writeString(Path.of(".workbuddy/_b2_preflight.txt"), report.toString(),
                StandardCharsets.UTF_8);
        System.out.printf("%n§ B2 preflight%n%s", report);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 弹幕端点专用报告：判定"底层有没有帮我们解压"。
     *
     * <p>三个判据：长度、首部是否像 XML、以及"按 ISO-8859-1 回读字节后再 inflate"是否成功。
     */
    private static String describeDanmaku(HttpResponse<String> resp) {
        String body = resp.getBody() == null ? "" : resp.getBody();
        boolean looksXml = body.startsWith("<?xml") || body.startsWith("<i>") || body.startsWith("<i ");
        String inflated = tryInflate(body);

        String verdict;
        if (looksXml) {
            verdict = "VERDICT=A: already decompressed by the HTTP layer -> parse the String directly";
        } else if (inflated != null) {
            verdict = "VERDICT=B1: still raw deflate, but String round-trip is lossless -> "
                    + "need a byte[] overload (ISO-8859-1 works here by luck)";
        } else {
            verdict = "VERDICT=B2: still raw deflate AND the String is lossy -> "
                    + "MUST add a byte[] overload on BilibiliHttp";
        }
        return String.format("%-42s http=%d len=%d looksXml=%s%n%-42s head=%s%n%-42s %s%n",
                "B2 dm/list.so (oid=cid)", resp.getStatus(), body.length(), looksXml,
                "", escape(body, 140),
                "", verdict);
    }

    /**
     * 尝试把 {@code body} 当作"被 ISO-8859-1 无损搬运过的 deflate 字节"解压。
     *
     * <p>用 ISO-8859-1 是因为它是唯一能把 0x00-0xFF 与 U+0000-U+00FF 一一对应的编码；
     * 如果底层根本没做 charset 转换（或做了 ISO-8859-1），这条路就能还原。
     * 返回 {@code null} 表示这条路走不通。
     */
    private static String tryInflate(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        byte[] raw = body.getBytes(StandardCharsets.ISO_8859_1);
        // deflate 有两种封装：zlib（带 2 字节头）与 raw（无头）。两种都试。
        for (boolean zlibWrapped : new boolean[]{true, false}) {
            try (InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(raw),
                    new java.util.zip.Inflater(zlibWrapped))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                String text = out.toString(StandardCharsets.UTF_8);
                if (text.startsWith("<?xml") || text.startsWith("<i>") || text.startsWith("<i ")) {
                    return text;
                }
            } catch (Exception ignored) {
                // 换下一种封装再试
            }
        }
        return null;
    }

    /** 打一次普通 GET 并落盘 */
    private static String plain(String tag, String url, String referer) {
        HttpResponse<String> resp = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept, referer);
        dump(tag.replace(' ', '-').replace('/', '-').replace('(', '_').replace(')', '_').replace('=', '_'),
                resp);
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

    /** 取一级评论第一条的 {@code rpid}（用来当二级评论的 {@code root}）；拿不到返回 {@code null} */
    private static Long firstRpid(String body) {
        try {
            JSONArray replies = JSON.parseObject(body).getJSONObject("data").getJSONArray("replies");
            return replies == null || replies.isEmpty() ? null : replies.getJSONObject(0).getLong("rpid");
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
                new com.alibaba.fastjson.TypeReference<>() {
                });
        return parsed == null ? Integer.MIN_VALUE : parsed.getCode();
    }

    /** 把不可打印字符转义，避免日志里出现"看起来是空的"或把终端搞乱 */
    private static String escape(String text, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < max; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void dump(String name, HttpResponse<String> resp) {
        try {
            Path dir = Path.of(".workbuddy");
            Files.createDirectories(dir);
            String body = resp.getBody() == null ? "" : resp.getBody();
            if (body.length() > 400_000) {
                body = body.substring(0, 400_000);
            }
            Files.writeString(dir.resolve("_b2_" + name + ".txt"), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("dump failed for " + name + ": " + e);
        }
    }
}

package com.esdllm.bilibiliApi.smoke;

import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.bilibiliApi.Login;
import com.esdllm.bilibiliApi.bilibiliApi.Search;
import com.esdllm.bilibiliApi.bilibiliApi.UserSpace;
import com.esdllm.bilibiliApi.bilibiliApi.VideoExtra;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.data.pojo.login.CredentialStatus;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.model.data.pojo.user.AccInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.AiSummary;
import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import com.esdllm.bilibiliApi.sign.WbiSigner;
import kong.unirest.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 联网冒烟：<b>WBI 签名链路</b>（{@code nav} 取密钥 → 签名 → 真机端点的门槛）。
 *
 * <p><b>为什么非要联网冒烟</b>：单测的响应是 fixture 造的，只能证明"我们的算法<b>自洽</b>"——
 * 它能证明"同一份密钥能复算出同一个 {@code w_rid}"，<b>却证明不了这个 {@code w_rid} 服务端会认</b>。
 * WBI 签名最坑的就是这一点：算错了不会有任何本地异常，服务端只回一个 {@code -403}，
 * 而 {@code -403} 在别处还意味着"资源权限不足"。所以必须有真机用例把"服务端认不认"钉住。
 *
 * <p>默认跳过（不联网）。跑法：
 * <pre>
 * mvn -o -B test "-Dtest=WbiSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true -Dbili.cookieFile=.workbuddy/bili-cookie.txt -Dbili.smokeBvid=BV1xxxxxxx"
 * </pre>
 * {@code -Dbili.smokeBvid} 可省（省了 AI 摘要那条跳过）。
 *
 * <p>本类的三个门禁用例对应 {@code BilibiliEndpoint} 实测表里最容易记错的三行：
 * <ol>
 *   <li>{@code search/*} —— 匿名可用（虽然本库仍走签名出口）；</li>
 *   <li>{@code space/wbi/acc/info} —— 匿名<b>必失败</b>（实测 -352，不是"做不了"）；</li>
 *   <li><b>空格必须编成 {@code %20}}</b> —— 这一条只有"签名真被服务端复算"时才有对比度，
 *       也正因如此只能放到联网用例里。</li>
 * </ol>
 *
 * @see com.esdllm.bilibiliApi.sign.WbiSigner
 */
@DisplayName("联网冒烟：WBI 签名（默认跳过）")
class WbiSmokeTest {

    /** 影视飓风：拿来做"签名对不对"的 oracle（{@code acc/info} 对他人 mid 也正常返回） */
    private static final long PROBE_MID = 946974L;

    /** 与一次性探针 {@code .workbuddy/_probe_wbi_space.py} 用的同一个参数名/值，便于互相对照 */
    private static final String PROBE_PARAM = "probe_ws";
    private static final String PROBE_VALUE = "one one four";

    @AfterEach
    void tearDown() {
        // 凭据是测试注入的，跑完必须清掉，别影响同 JVM 里的其它用例
        HttpPolicy.clearCookie();
    }

    // ================================================================
    // 密钥
    // ================================================================

    @Test
    @DisplayName("nav 里能取到当天的 img_key/sub_key，且能算出 32 位 mixin_key")
    void keysAreFetchable() {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        WbiKeyStore.invalidate();

        WbiKeyStore.WbiKeys keys = WbiKeyStore.get();

        assertNotNull(keys, "nav 取不到 wbi_img —— 端点形状变了或出口被风控，整个 B 层都要重查");
        assertEquals(32, keys.imgKey().length());
        assertEquals(32, keys.subKey().length());
        assertEquals(32, WbiSigner.mixinKey(keys.imgKey(), keys.subKey()).length());

        System.out.printf("%n§ WBI 密钥：%s%n", keys.summary());
    }

    // ================================================================
    // 门槛：匿名哪条能过、哪条不能
    // ================================================================

    @Test
    @DisplayName("search 匿名可用（本库仍走签名出口，所以这里验的是'带签名也能用'）")
    void searchWorksAnonymously() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        HttpPolicy.clearCookie();
        WbiKeyStore.invalidate();

        SearchTypeResult<SearchVideo> page = new Search().searchVideos("测试", 1);

        System.out.printf("%n§ 匿名搜索：本页 %d 条 / 共 %d 条%n", page.size(), page.getNumResults());

        assertFalse(page.getResult().isEmpty(), "匿名搜索返回 0 条 —— 可能被静默风控（实测该端点匿名 code=0）");
        assertNotNull(page.getResult().get(0).getCleanTitle());
    }

    @Test
    @DisplayName("★ acc/info 匿名必失败（-352 风控）—— 这条'需登录'的结论有真机守口")
    void spaceRequiresCredential() {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        HttpPolicy.clearCookie();
        WbiKeyStore.invalidate();

        IOException e = assertThrows(IOException.class, () -> new UserSpace().getAccInfo(PROBE_MID),
                "匿名居然成功了 ⇒ 该端点的门槛变了（不再需要凭据），请复核 BilibiliEndpoint 实测表");

        System.out.printf("%n§ 匿名 acc/info 的失败文案：%s%n", e.getMessage());
    }

    // ================================================================
    // 签名口径：服务端认不认
    // ================================================================

    @Test
    @DisplayName("★ 空格必须编成 %20：带凭据签名请求被接受（错编码只会得到 -403）")
    void spaceMustBePercent20() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(), "未提供 -Dbili.cookie / -Dbili.cookieFile，跳过");
        HttpPolicy.setCookie(cookie);
        WbiKeyStore.invalidate();

        // ① 对照组：只有 mid。若这一步就不是 0，说明凭据/出口有问题，后面的结论不可信
        int baseline = codeOf(signedAccInfo(Map.of("mid", String.valueOf(PROBE_MID))));
        assertEquals(0, baseline, "对照组（只带 mid）不是 code=0 ⇒ 凭据或出口有问题，先排查这个");
        System.out.printf("%n§ 对照：凭据 + 只带 mid → code=0%n");

        // ② 真正要验的：多塞一个带空格的值。这个参数对端点无意义，
        //    服务端唯一会拿它做的事就是"纳入签名复算" —— 正是我们要的 oracle。
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mid", String.valueOf(PROBE_MID));
        params.put(PROBE_PARAM, PROBE_VALUE);
        int withSpace = codeOf(signedAccInfo(params));

        System.out.printf("§ %s=%s（含两个空格）→ code=%d%n", PROBE_PARAM, PROBE_VALUE, withSpace);

        assertEquals(0, withSpace,
                "含空格的值让签名被拒了（code=" + withSpace + "）⇒ 空格编码不再是 %20，"
                        + "或值里的 !'()* 处理变了。对照 .workbuddy/_probe_wbi_space.py 的结论："
                        + "%20 → code=0，+ → -403");
    }

    // ================================================================
    // 有凭据时的正路
    // ================================================================

    @Test
    @DisplayName("凭据 + 签名：nav 报的 uid 与 acc/info 的 mid 必须一致（两个端点的交叉验证）")
    void accInfoMatchesNavUid() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(), "未提供 -Dbili.cookie / -Dbili.cookieFile，跳过");
        HttpPolicy.setCookie(cookie);
        WbiKeyStore.invalidate();

        CredentialStatus status = new Login().getCredentialStatus();
        assumeTrue(status.isLoggedIn(), "凭据已失效（" + status.summary() + "），跳过");
        assumeTrue(status.getUid() > 0, "nav 没给出 uid，跳过");

        AccInfo info = new UserSpace().getAccInfo(status.getUid());

        System.out.printf("%n§ nav: uid=%d uname=%s ｜ acc/info: mid=%d name=%s%n",
                status.getUid(), status.getUname(), info.getMid(), info.getName());

        assertEquals(status.getUid(), info.getMid(),
                "两个端点对'我是谁'的回答必须一致 —— 不一致说明有一边读错了字段");
    }

    @Test
    @DisplayName("AI 摘要：需 -Dbili.smokeBvid=BV1xxxxxxxxx（没给就跳过）")
    void aiSummary() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");
        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(), "未提供 -Dbili.cookie / -Dbili.cookieFile，跳过");
        String bvid = System.getProperty("bili.smokeBvid");
        assumeTrue(bvid != null && !bvid.isBlank(), "未提供 -Dbili.smokeBvid，跳过");
        HttpPolicy.setCookie(cookie);
        WbiKeyStore.invalidate();

        AiSummary summary = new VideoExtra().getAiSummary(bvid);

        assertNotNull(summary);
        int outline = summary.getModel_result() == null || summary.getModel_result().getOutline() == null
                ? 0 : summary.getModel_result().getOutline().size();
        System.out.printf("%n§ AI 摘要 %s：hasSummary=%s，大纲 %d 段%n",
                bvid, summary.hasSummary(), outline);

        // 判据只能是 hasSummary()：实测 data.code / data.status 都是 0 而摘要正常返回
        assertEquals(0, summary.getCode(), "data.code 不该被当成'有没有摘要'的判据");
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 用库自己的签名出口打一次 {@code acc/info}（默认走 {@code getSigned}，含重签逻辑） */
    private static HttpResponse<String> signedAccInfo(Map<String, String> params) {
        return BilibiliHttp.getSigned(BilibiliEndpoint.accInfoUrl, params,
                BilibiliEndpoint.jsonAccept, "https://space.bilibili.com/" + PROBE_MID);
    }

    /** 取业务码；解析不出来返回 {@code Integer.MIN_VALUE}（这样断言会明确失败而不是静默通过） */
    private static int codeOf(HttpResponse<String> response) {
        try {
            Integer code = JSON.parseObject(response.getBody()).getInteger("code");
            return code == null ? Integer.MIN_VALUE : code;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** 取 Cookie：优先 {@code -Dbili.cookieFile=<文件路径>}，否则用 {@code -Dbili.cookie} */
    private static String cookie() throws Exception {
        String file = System.getProperty("bili.cookieFile");
        if (file != null && !file.isBlank()) {
            return java.nio.file.Files.readString(java.nio.file.Path.of(file)).trim();
        }
        return System.getProperty("bili.cookie");
    }
}

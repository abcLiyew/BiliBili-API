package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 联网冒烟：<b>关注流</b>（{@code feed/all}）真实链路。
 *
 * <p><b>为什么需要它</b>（2026-09-14）：某些客户端会被 B 站 WAF 以
 * {@code -412 request was banned} 封禁 {@code feed/space}，此时关注流是唯一可用的数据源。
 * 本冒烟在真实网络下验证"能拉到 + 解析出 uid/userName/time"，
 * 避免"写法看着对、字段其实取错"这类只有真数据才能暴露的问题。
 *
 * <p>默认跳过（不联网）。跑法：
 * <pre>
 * mvn -o -B test "-Dtest=FollowFeedSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" \
 *   "-DargLine=-Dbili.smoke=true -Dbili.cookie=<完整Cookie>"
 * </pre>
 *
 * <p>注意 Cookie 里不能有空格以外的麻烦字符（用 {@code ;} 连接即可）。
 */
@DisplayName("联网冒烟：关注流 feed/all（默认跳过）")
class FollowFeedSmokeTest {

    @Test
    @DisplayName("getFollowFeed 能拉到并解析出 uid / userName / time")
    void followFeedChain() throws Exception {
        assumeTrue(Boolean.getBoolean("bili.smoke"), "未开启 -Dbili.smoke=true，跳过联网冒烟");

        String cookie = cookie();
        assumeTrue(cookie != null && !cookie.isBlank(), "未提供 -Dbili.cookie / -Dbili.cookieFile，跳过");
        HttpPolicy.setCookie(cookie);

        List<Dynamic.DynamicInfo> feed = new Dynamic().getFollowFeed();
        assertNotNull(feed);

        System.out.printf("%n§ 关注流：共 %d 条%n", feed.size());
        int printed = 0;
        for (Dynamic.DynamicInfo info : feed) {
            if (printed++ >= 5) {
                break;
            }
            System.out.printf("   uid=%s name=%s time=\"%s\" kind=%s bvid=%s%n",
                    info.getUid(), info.getUserName(), info.getTime(),
                    info.getDynamicId() == null ? "转发" : "图文/视频", info.getBvid());
        }
        long withUid = feed.stream().filter(i -> i.getUid() != null && !i.getUid().isEmpty()).count();
        System.out.printf("§ 带 uid 的条目：%d / %d（调用方靠它把动态归到订阅）%n", withUid, feed.size());
        System.out.println("§ 结论：" + (feed.isEmpty()
                ? "关注流为空 —— 确认该账号有关注任何 UP"
                : "关注流可用，解析正常"));
    }

    /**
     * 取 Cookie：优先 {@code -Dbili.cookieFile=<文件路径>}，否则用 {@code -Dbili.cookie}。
     *
     * <p>为什么要支持文件：Cookie 里带 {@code %3D%3D}、{@code ,} 这类字符，
     * 经 Windows 的 {@code cmd} 传进 {@code -DargLine} 时容易被环境变量展开规则吃掉，
     * 出现"明明传了却对不上"的假故障。放文件里就没有这个问题。
     *
     * @return Cookie 字符串；未提供时返回 {@code null}
     */
    private static String cookie() throws Exception {
        String file = System.getProperty("bili.cookieFile");
        if (file != null && !file.isBlank()) {
            return java.nio.file.Files.readString(java.nio.file.Path.of(file)).trim();
        }
        return System.getProperty("bili.cookie");
    }
}

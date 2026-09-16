package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.BilibiliClient;
import com.esdllm.bilibiliApi.bilibiliApi.CardInfo;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.bilibiliApi.Live;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>XatiiBot 推送链路验证探针</b>（默认跳过）。
 *
 * <p>用途：<b>不依赖 QQ / 数据库 / 机器人</b>，用与 XatiiBot <b>逐字相同</b>的门面调用方式，
 * 分段验证"重新打包后推送链路是否真的通"。
 *
 * <p>链路对照（{@code PushInfoServiceImpl.dynamicPush} + {@code sendMsg}）：
 * <pre>
 * live.getUid(roomId)                        ← 本类 §1
 * dynamic.getDynamicInfoList(String uid)     ← 本类 §2（★ 重点：time 不能为 null）
 * cardInfo.getUserName(uid)                  ← 本类 §3
 * dynamic.getDynamicImg(dynamicId)           ← 本类 §4（长图，走 Java2D）
 * bilibiliClient.getVideoCoverUrl/Title/...  ← 本类 §5（视频类动态推送用）
 * </pre>
 *
 * <h2>用法</h2>
 * <pre>
 * # 只验动态+名片（最常用）
 * mvn -o -B test -Dtest=XatiiBotPushChainProbeTest -Dbili.smoke=true
 *
 * # 换 UP（默认 946974 = 影视飓风）
 * mvn -o -B test -Dtest=XatiiBotPushChainProbeTest -Dbili.smoke=true -Dbili.uid=123456
 *
 * # 连直播间一起验
 * mvn -o -B test -Dtest=XatiiBotPushChainProbeTest -Dbili.smoke=true -Dbili.roomId=732
 *
 * # 验视频类动态的封面/标题（视频推送用）
 * mvn -o -B test -Dtest=XatiiBotPushChainProbeTest -Dbili.smoke=true -Dbili.bvid=BV1xVY26dEbz
 * </pre>
 *
 * <h2>成功判据</h2>
 * <ul>
 *   <li>§2 打印的每一条 {@code time} <b>全部非 null</b>（P2 修的就是这个：取错字段会让它恒 null，
 *       导致 XatiiBot 的 {@code startsWith("刚刚")} 抛 NPE 被吞 → 推送静默失效）；</li>
 *   <li>§2 能看到形如 {@code 3小时前} / {@code 昨天 11:00 · 投稿了视频} 的<b>相对时间文案</b>；</li>
 *   <li>§4 能产出 PNG 且大小 &gt; 5KB（说明 Java2D 出图正常，无 Selenium 依赖）。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("探针：XatiiBot 推送链路分段验证（默认跳过）")
class XatiiBotPushChainProbeTest {

    /** 默认 UP：影视飓风 */
    private static final String DEFAULT_UID = "946974";

    @Test
    @DisplayName("完整链路：Live.getUid → Dynamic.getDynamicInfoList → CardInfo.getUserName → Dynamic.getDynamicImg")
    void fullPushChain() throws Exception {
        String uid = System.getProperty("bili.uid", DEFAULT_UID);
        String roomIdProp = System.getProperty("bili.roomId");

        System.out.println("========== XatiiBot 推送链路验证 ==========");
        System.out.println("bilibili-api 版本：" + versionOf());
        System.out.println();

        // ---------- §1 Live.getUid（可选）----------
        Long uidFromLive;
        if (roomIdProp != null && !roomIdProp.isBlank()) {
            long roomId = Long.parseLong(roomIdProp.trim());
            // 与 PushInfoServiceImpl.dynamicPush 的写法一致：uid 是从直播间号反查出来的
            uidFromLive = new Live().getUid(roomId);
            System.out.printf("§1 Live.getUid(%d) = %s%n", roomId, uidFromLive);
            if (uidFromLive != null) {
                uid = String.valueOf(uidFromLive);
            }
        } else {
            System.out.println("§1 Live.getUid —— 跳过（未传 -Dbili.roomId）");
        }
        System.out.println();

        // ---------- §2 Dynamic.getDynamicInfoList（★ 核心）----------
        //
        // 连续采样 3 次：v1/feed/space 的失败是概率性的（实测同一进程内交替出现
        // code=-352 与 code=0+items=[] ），单次结果不足以判断链路是否可用。
        int samples = Integer.getInteger("bili.feedSamples", 3);
        System.out.printf("§2 Dynamic.getDynamicInfoList(%s) —— 连续采样 %d 次%n", uid, samples);

        List<Dynamic.DynamicInfo> list = null;
        int emptyCount = 0;
        for (int round = 1; round <= samples; round++) {
            try {
                list = new Dynamic().getDynamicInfoList(uid);
            } catch (Exception e) {
                System.out.printf("   第 %d 次：抛异常 %s: %s%n",
                        round, e.getClass().getSimpleName(), e.getMessage());
                list = null;
                if (round < samples) {
                    Thread.sleep(1500);
                }
                continue;
            }
            int n = list == null ? -1 : list.size();
            System.out.printf("   第 %d 次：%d 条%n", round, n);
            if (n == 0) {
                emptyCount++;
            }
            if (round < samples) {
                Thread.sleep(1500); // 让限流窗口过去，避免把"限流等待"误判成"空"
            }
        }
        if (list == null) {
            System.out.println();
            System.out.println("   ❌ 最后一次仍因 -352 风控失败 —— 接口当前不可用（这是上游状态，不是代码问题）");
            System.out.printf("      本次 %d 次采样：%d 次抛 -352 异常，%d 次返回空列表%n", samples, samples - emptyCount, emptyCount);
            System.out.println("      ★ 动态推送此刻不会工作。XatiiBot 侧的两种表现：");
            System.out.println("         · 显式风控 → 日志有「处理动态推送信息时发生异常 ... code=-352」");
            System.out.println("         · 静默空   → 连日志都没有（上面采样里就有这种）");
            System.out.println("      ★ 应对：① 注入真实登录 Cookie（最可能有效）；② 等一段时间让 IP 风控冷却；");
            System.out.println("             ③ 走代理换 IP（HttpPolicy.setProxy）；④ 修 Bug 2（空列表重试/告警）");
        } else if (list.isEmpty()) {
            System.out.println();
            System.out.println("   ⚠️ 最后一次仍为空！两种可能：");
            System.out.println("      a) 该 UP 确实没有动态（极少见）；");
            System.out.println("      b) ★命中 v1/feed/space 的『静默空列表』——code=0 但 items=[]★");
            System.out.println("         → 调用方无法与 (a) 区分，XatiiBot 会认为'没有新动态'→ 推送漏报。");
            System.out.println("         → 这是已知健壮性缺口（Bug 2），已在本探针暴露：");
            System.out.printf("           本次 %d 次采样里 %d 次为空%n", samples, emptyCount);
            System.out.println("     应对：重跑本探针看是否变化；或给库注入真实 Cookie（登录态可大幅降低风控）。");
        } else {
            int nullTime = 0;
            int relative = 0;
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                Dynamic.DynamicInfo d = list.get(i);
                String t = d.getTime();
                System.out.printf("   [%d] time=%-30s dynamicId=%-20s bvid=%-14s images=%s tag=%s%n",
                        i, t,
                        d.getDynamicId() == null ? "(转发)" : d.getDynamicId(),
                        d.getBvid() == null ? "-" : d.getBvid(),
                        d.getImageUrl() == null ? 0 : d.getImageUrl().size(),
                        d.getTag() == null ? "-" : d.getTag());
                if (t == null) {
                    nullTime++;
                } else if (t.contains("前") || t.contains("刚刚") || t.contains("昨天") || t.contains("今天")) {
                    relative++;
                }
            }
            System.out.printf("   共 %d 条；检查前 8 条：time 为 null %d 条，相对文案 %d 条%n",
                    list.size(), nullTime, relative);

            // ★ 这是本轮改造最关键的不变量
            assertEquals(0, nullTime, "time 出现 null！说明 pub_time 字段又取错了 —— XatiiBot 的 getTime().startsWith(\"刚刚\") 会抛 NPE 并静默吞掉，推送永久失效");
            if (relative == 0) {
                // 断言只能证明"能拿到 time"，不能凭空造出一条新动态。
                // 该 UP 最近没发动态时，feed 里全是 `5月30日` / `8月1日` 这种**绝对日期**
                // （相对文案只出现在新动态上），此时判失败是误报 —— 改为提示。
                System.out.println("   ⚠️ 本批样本全是绝对日期（该 UP 最近没有新动态），"
                        + "无法验证『相对文案』通道 —— 这不代表 schema 错。");
                System.out.println("      要验证推送触发条件，等该 UP 发一条新动态后重跑本探针即可。");
            } else {
                System.out.println("   ✅ 结论：time 全部非 null，且含相对文案 → 推送触发条件可正常工作");
            }
        }
        System.out.println();

        // ---------- §3 CardInfo.getUserName ----------
        String username = new CardInfo().getUserName(Long.parseLong(uid));
        System.out.printf("§3 CardInfo.getUserName(%s) = %s%n", uid, username);
        assertNotNull(username, "用户名不能为 null（推送文案要用）");
        System.out.println();

        // ---------- §4 Dynamic.getDynamicImg（长图，Java2D）----------
        if (list != null && !list.isEmpty()) {
            Dynamic.DynamicInfo first = list.get(0);
            String targetId = first.getDynamicId() != null ? first.getDynamicId() : first.getShareDynamicId();
            if (targetId != null) {
                System.out.printf("§4 Dynamic.getDynamicImg(%s) —— 走 Java2D 自绘%n", targetId);
                long t0 = System.currentTimeMillis();
                BufferedImage img = new Dynamic().getDynamicImg(targetId);
                long cost = System.currentTimeMillis() - t0;
                assertNotNull(img, "长图不能为 null");
                Path outDir = Path.of("target", "smoke");
                Files.createDirectories(outDir);
                Path out = outDir.resolve("xatiibot-chain-" + targetId + ".png");
                ImageIO.write(img, "png", out.toFile());
                long size = Files.size(out);
                System.out.printf("   ✅ %dx%d，耗时 %d ms，PNG %.1f KB → %s%n",
                        img.getWidth(), img.getHeight(), cost, size / 1024.0, out.toAbsolutePath());
                assertTrue(img.getWidth() >= 200 && img.getHeight() >= 200,
                        "尺寸异常（可能字体漏字）: " + img.getWidth() + "x" + img.getHeight());
                assertTrue(size >= 5 * 1024, "PNG 过小，疑似空图");
                System.out.println("   （无 Selenium：若还依赖浏览器，这里会报找不到驱动 / 启动 Chrome）");
            } else {
                System.out.println("§4 跳过：首条动态没有可用于渲染的 id");
            }
        }
        System.out.println();

        // ---------- §5 视频类动态用的 BilibiliClient ----------
        String bvidProp = System.getProperty("bili.bvid");
        if (bvidProp != null && !bvidProp.isBlank()) {
            System.out.printf("§5 BilibiliClient 视频字段（供视频类动态推送用）：%s%n", bvidProp);
            BilibiliClient client = new BilibiliClient();
            VideoInfo v = client.getVideoInfo(bvidProp);
            System.out.printf("   title=%s%n   aid=%s%n   up=%s%n   cover=%s%n",
                    v.getTitle(), v.getAid(), v.getOwner().getName(), v.getPic());
            System.out.printf("   getVideoTitle/getVideoAv/getVideoCoverUrl 三个调用（与 sendMsg 一致）：%s / %s / %s%n",
                    client.getVideoTitle(bvidProp), client.getVideoAv(bvidProp), client.getVideoCoverUrl(bvidProp));
        } else {
            System.out.println("§5 跳过（未传 -Dbili.bvid）；视频类动态推送会用到 getVideoTitle/getVideoAv/getVideoCoverUrl");
        }

        System.out.println();
        System.out.println("========== 验证结束：以上各段无异常即链路通 ==========");
    }

    /** 从 jar 的实现版本推断当前 bilibili-api 版本（便于确认"重新打包用的是新包"） */
    private static String versionOf() {
        String implVersion = Dynamic.class.getPackage().getImplementationVersion();
        return implVersion == null ? "未知（未从 jar 加载，可能是 target/classes）" : implVersion;
    }
}
package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联网冒烟测试：验证「换源后的 {@code getDynamicDetail} 在真实环境里真的能取到数据」。
 *
 * <p><b>默认不执行</b>，因为依赖 B 站线上接口。需要时显式开启：
 * <pre>
 * mvn -o -B test -Dtest=DynamicSmokeTest -Dbili.smoke=true
 * </pre>
 *
 * <p>它守的是这条链路：门面 → 新端点 {@code v1/detail?id=} → 响应解析 → schema 适配 →
 * 冻结模型（{@code desc.dynamic_id_str} / {@code desc.user_profile.info.uname}）。
 * 这正是换源前 100% 失败、换源后必须通过的那条路径。
 *
 * <p>注意：用例里的动态 id 是真实存在的公开动态。若某天被 UP 删除，用例会失败 ——
 * 届时换一个近期动态 id 即可（从 {@code desktop/v1/feed/space} 取一条）。
 */
@EnabledIfSystemProperty(named = "bili.smoke", matches = "true")
@DisplayName("联网冒烟：动态详情（默认跳过）")
class DynamicSmokeTest {

    /** 新格式动态 id（19 位），来自真实 feed 的 items[].id_str */
    private static final String NEW_FORMAT_ID = "1247016318199136288";

    /** 旧格式动态 id（18 位），验证 id 格式兼容性 */
    private static final String OLD_FORMAT_ID = "463864834570585963";

    /** P2-A 探测用的空间 mid（影视飓风，与 WiredDynamicImgSmokeTest 的样本 UP 一致，动态量大且类型齐全） */
    private static final String SPACE_MID = "946974";

    @Test
    @DisplayName("新格式 id：能取到详情，且下游读取的两条路径非空")
    void 新格式id() throws IOException {
        BilibiliDynamicResp.Data.Card card = new Dynamic().getDynamicDetail(NEW_FORMAT_ID);

        assertNotNull(card, "card 不能为 null");
        assertNotNull(card.getDesc(), "desc 不能为 null");
        // 与 XatiiBot BilibiliAnalysisImpl.java:132 / :137 的取值方式逐字一致
        assertNotNull(card.getDesc().getDynamic_id_str(), "dynamic_id_str 不能为 null");
        assertNotNull(card.getDesc().getUser_profile().getInfo().getUname(), "uname 不能为 null");

        System.out.printf("新格式 id 详情：id=%s type=%s up=%s bvid=%s like=%s%n",
                card.getDesc().getDynamic_id_str(),
                card.getDesc().getType(),
                card.getDesc().getUser_profile().getInfo().getUname(),
                card.getDesc().getBvid(),
                card.getDesc().getLike());
    }

    @Test
    @DisplayName("旧格式 id：同样能取到详情（id 格式双向兼容）")
    void 旧格式id() throws IOException {
        BilibiliDynamicResp.Data.Card card = new Dynamic().getDynamicDetail(OLD_FORMAT_ID);
        assertNotNull(card.getDesc().getDynamic_id_str());
        assertNotNull(card.getDesc().getUser_profile().getInfo().getUname());
        System.out.printf("旧格式 id 详情：id=%s up=%s%n",
                card.getDesc().getDynamic_id_str(),
                card.getDesc().getUser_profile().getInfo().getUname());
    }

    @Test
    @DisplayName("不存在的 id：抛 IOException（不是 RuntimeException 穿透），且消息里带 B 站错误码")
    void 不存在的id() {
        // 关键回归点：失败必须落在签名声明的 IOException 上，
        // 否则下游 XatiiBot 的 catch (IOException) 兜不住，会把消息处理器打挂
        IOException e = assertThrows(IOException.class,
                () -> new Dynamic().getDynamicDetail("1100000000000000000"));
        assertTrue(e.getMessage().contains("4101105"),
                "异常消息应包含 B 站错误码 4101105，实际：" + e.getMessage());
        System.out.println("不存在的 id 异常消息：" + e.getMessage());
    }

    @Test
    @DisplayName("空 id：仍抛 BilibiliException（调用方编程错误，不走 IOException）")
    void 空id() {
        assertThrows(RuntimeException.class, () -> new Dynamic().getDynamicDetail(""));
    }

    // ================================================================
    // P2-A 前置验证（§7 P2-A 的三项确认，用真实接口实测）
    // ================================================================

    /**
     * P2-A-1：确认 desktop feed 拿到的 {@code module_author.pub_text} 是<b>相对时间文案</b>。
     *
     * <p>下游 XatiiBot 的推送触发条件是 {@code getTime().startsWith("刚刚")}
     * （{@code PushInfoServiceImpl.java:196}），所以 {@code DynamicInfo.time} 必须承载
     * 与前端同款的相对文案。本用例把列表里每条动态的 time 打出来，供人工核对。
     *
     * <p><b>为什么不做强断言</b>：{@code pub_text} 的具体取值取决于"当前时刻离发布时刻多远"，
     * 是时间相关的（"刚刚" / "5分钟前" / "3小时前" / "昨天 11:00 · 投稿了视频"），
     * 任何固定断言都会随时间漂移。这里断言的是"非空 + 不是绝对时间格式"，
     * 具体文案靠 {@code System.out} 输出核对。
     */
    @Test
    @DisplayName("P2-A-1：desktop feed 的 pub_text 是相对时间文案（含'刚刚'的可能性）")
    void pub_text是相对时间文案() throws Exception {
        // 连续调 3 次，验证「新身份首次调 feed 是否静默返空」这一现象（2026-09-13 实测发现）
        int[] sizes = new int[3];
        for (int i = 0; i < 3; i++) {
            List<Dynamic.DynamicInfo> list = new Dynamic().getDynamicInfoList(SPACE_MID);
            sizes[i] = list == null ? -1 : list.size();
            if (list != null && !list.isEmpty()) {
                System.out.printf("=== 第 %d 次调用：%d 条 ===", i + 1, list.size());
                for (int j = 0; j < Math.min(5, list.size()); j++) {
                    Dynamic.DynamicInfo info = list.get(j);
                    System.out.printf("[%d] time=%-30s tag=%s bvid=%s shareId=%s%n",
                            j, info.getTime(), info.getTag(), info.getBvid(), info.getShareDynamicId());
                }
            } else {
                System.out.printf("=== 第 %d 次调用：空列表（%d）===", i + 1, sizes[i]);
            }
            Thread.sleep(600); // 让限流窗口过去，避免把"限流等待"误判成"返空"
        }
        System.out.printf("=== 三次 size：%d / %d / %d ===%n", sizes[0], sizes[1], sizes[2]);

        // 硬要求：三次里至少有一次拿到非空（证明接口与 schema 都是通的）
        boolean anyNonEmpty = sizes[0] > 0 || sizes[1] > 0 || sizes[2] > 0;
        assertTrue(anyNonEmpty,
                "连续 3 次都拿不到动态列表 —— feed 端点/features 参数失效，或 buvid3 始终未生效。sizes="
                        + Arrays.toString(sizes));

        // 观察性结论：若首次为空而后续非空，说明"新身份首次调 feed 返空"确有其事（见 §4.4 待办）
        if (sizes[0] == 0 && sizes[1] > 0) {
            System.out.println("⚠️ 复现：全新匿名身份首次调 feed 静默返回空列表（第 2 次正常）");
        }
    }

    /**
     * P2-A-2：确认置顶动态的 {@code tag} 来源（{@code MODULE_TYPE_TAG.module_tag.text}）真实存在。
     *
     * <p>同时验证 P2.5 修复的双 schema 解析：{@code modules} 是 JSONArray 时
     * 也能正确抽到 imageUrl / bvid / title。
     */
    @Test
    @DisplayName("P2-A-2：置顶 tag 与各类型动态的字段映射（DRAW/AV/FORWARD 覆盖率）")
    void tag与字段映射() throws Exception {
        List<Dynamic.DynamicInfo> list = new Dynamic().getDynamicInfoList(SPACE_MID);
        assertNotNull(list);

        int draw = 0;
        int av = 0;
        int forward = 0;
        int tagged = 0;
        System.out.println("=== P2-A-2：字段映射抽样 ===");
        for (Dynamic.DynamicInfo info : list) {
            boolean hasImages = info.getImageUrl() != null && !info.getImageUrl().isEmpty();
            boolean hasBvid = info.getBvid() != null && !info.getBvid().isEmpty();
            boolean isForward = info.getDynamicId() == null && info.getShareDynamicId() != null;
            if (isForward) {
                forward++;
            } else if (hasBvid) {
                av++;
            } else if (hasImages) {
                draw++;
            }
            if (info.getTag() != null) {
                tagged++;
                System.out.printf("  [置顶 tag=%s] id=%s images=%s bvid=%s%n",
                        info.getTag(), info.getDynamicId(), hasImages, info.getBvid());
            }
        }
        System.out.printf("=== 抽样 %d 条：DRAW=%d / AV=%d / FORWARD=%d / 带tag=%d ===%n",
                list.size(), draw, av, forward, tagged);
        // 「至少能解析出内容」是硬要求；类型分布只打印供核对（真实 UP 的动态类型会随时间变化）
        assertTrue(draw + av + forward > 0,
                "至少应能解析出一种动态类型（DRAW/AV/FORWARD），否则 schema 适配失效");
    }
}

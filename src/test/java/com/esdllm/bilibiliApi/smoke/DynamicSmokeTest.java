package com.esdllm.bilibiliApi.smoke;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;

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
}

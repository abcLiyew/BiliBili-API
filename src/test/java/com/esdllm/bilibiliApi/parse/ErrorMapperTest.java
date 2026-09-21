package com.esdllm.bilibiliApi.parse;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ErrorMapper} 单测 —— 抗压层的地基。
 *
 * <p>这个类决定"哪些错误值得重试"，判错任何一条都会实质损害可用性：
 * <ul>
 *   <li>把风控码（412/-352/-509/-412）判成"可重试" → 退避重试会加重指纹标记 → 进小黑屋；</li>
 *   <li>把瞬态错误（网关 5xx）判成"不可重试" → 本该自愈的抖动变成用户可见失败。</li>
 * </ul>
 *
 * <p>此前该类只被 {@code BilibiliHttpTest} 间接覆盖，本测试类直接锁定它的四组契约。
 */
class ErrorMapperTest {

    @Nested
    @DisplayName("isRiskControl：风控码识别")
    class RiskControl {

        @Test
        @DisplayName("四个风控码全部命中")
        void riskControlCode() {
            assertTrue(ErrorMapper.isRiskControl(412), "HTTP 412 是风控页");
            assertTrue(ErrorMapper.isRiskControl(-352), "-352 是动态接口最常见的风控码");
            assertTrue(ErrorMapper.isRiskControl(-509), "-509 是请求过于频繁");
            assertTrue(ErrorMapper.isRiskControl(-412), "-412 是风控变体");
        }

        @Test
        @DisplayName("非风控码不得误判")
        void nonRiskControlCode() {
            assertFalse(ErrorMapper.isRiskControl(0), "成功码不是风控");
            assertFalse(ErrorMapper.isRiskControl(4101139), "参数名错误不是风控（重试无意义但也不加重风控）");
            assertFalse(ErrorMapper.isRiskControl(4101105), "id 不存在不是风控");
            assertFalse(ErrorMapper.isRiskControl(-101), "未登录不是风控");
            assertFalse(ErrorMapper.isRiskControl(500), "服务端异常不是风控");
            assertFalse(ErrorMapper.isRiskControl(503), "网关 5xx 不是风控，它是瞬态");
        }
    }

    @Nested
    @DisplayName("retryable：可否退避重试")
    class Retryable {

        @Test
        @DisplayName("code=0 不算可重试（它本来就不是失败）")
        void successCode() {
            assertFalse(ErrorMapper.retryable(0),
                    "retryable 的语义是'失败后是否值得重试'；code=0 根本没失败");
        }

        @Test
        @DisplayName("明确不可重试的都要 false")
        void noRetrySet() {
            // 风控类
            assertFalse(ErrorMapper.retryable(412));
            assertFalse(ErrorMapper.retryable(-352));
            assertFalse(ErrorMapper.retryable(-509));
            assertFalse(ErrorMapper.retryable(-412));
            // 鉴权类
            assertFalse(ErrorMapper.retryable(-101), "账号未登录，重试还是没登录");
            assertFalse(ErrorMapper.retryable(-403), "权限不足，重试还是不足");
            // 请求本身有问题
            assertFalse(ErrorMapper.retryable(-400));
            assertFalse(ErrorMapper.retryable(-404));
            assertFalse(ErrorMapper.retryable(4101139), "参数名写错，重试一万次都一样");
            assertFalse(ErrorMapper.retryable(4101105), "id 不存在，重试不会让它存在");
            // 服务端确定性异常
            assertFalse(ErrorMapper.retryable(500), "实测 500 来自确定性 JS 异常");
        }

        @Test
        @DisplayName("名单外的码默认可重试（宁可多试一次）")
        void notInList() {
            assertTrue(ErrorMapper.retryable(-1), "未知业务码按瞬态处理");
            assertTrue(ErrorMapper.retryable(-500), "未知负数码");
            assertTrue(ErrorMapper.retryable(502), "网关错误是瞬态");
            assertTrue(ErrorMapper.retryable(503));
            assertTrue(ErrorMapper.retryable(504));
            assertTrue(ErrorMapper.retryable(408), "请求超时是瞬态");
            assertTrue(ErrorMapper.retryable(429), "被限流，退避后应该能过");
            assertTrue(ErrorMapper.retryable(9999));
        }
    }

    @Nested
    @DisplayName("toException：错误码 → 语义化异常")
    class ToException {

        @Test
        @DisplayName("code/message/description 三者映射正确")
        void fieldMapping() {
            BilibiliException e = ErrorMapper.toException(-352, "风控了", "获取动态详情");
            assertEquals(-352, e.getCode());
            assertTrue(e.getMessage().contains("获取动态详情"), "message 应含动作前缀");
            assertTrue(e.getMessage().contains("code=-352"), "message 应含业务码");
            assertTrue(e.getMessage().contains("风控了"), "message 应含 B 站原文");
            assertEquals("风控了", e.getDescription(), "description 是 B 站原文");
        }

        @Test
        @DisplayName("不可重试的错误在 message 里带提示")
        void noRetryHint() {
            BilibiliException e = ErrorMapper.toException(4101105, "id 不存在", "获取动态详情");
            assertTrue(e.getMessage().contains("该错误不可重试"),
                    "让调用方一眼看出'别重试'，实际：" + e.getMessage());
        }

        @Test
        @DisplayName("可重试的错误不带'不可重试'提示")
        void retryableWithoutHint() {
            BilibiliException e = ErrorMapper.toException(503, "网关错误", "获取动态详情");
            assertFalse(e.getMessage().contains("不可重试"),
                    "可重试的不该劝退调用方，实际：" + e.getMessage());
        }

        @Test
        @DisplayName("message 为 null / 空白时给出'无错误信息'占位")
        void blankMessage() {
            BilibiliException nullMsg = ErrorMapper.toException(1, null, "拉取数据");
            assertTrue(nullMsg.getMessage().contains("无错误信息"));

            BilibiliException blankMsg = ErrorMapper.toException(1, "   ", "拉取数据");
            assertTrue(blankMsg.getMessage().contains("无错误信息"));
        }

        @Test
        @DisplayName("message 两侧空白被去掉")
        void trimMessage() {
            BilibiliException e = ErrorMapper.toException(1, "  有空格  ", "拉取数据");
            assertEquals("有空格", e.getDescription());
        }
    }

    @Nested
    @DisplayName("forHttpStatus：HTTP 层早期拦截")
    class ForHttpStatus {

        @Test
        @DisplayName("2xx 返回 null（表示'无需拦截'）")
        void successStatus() {
            assertNull(ErrorMapper.forHttpStatus(200, "获取动态详情"));
            assertNull(ErrorMapper.forHttpStatus(201, "获取动态详情"));
            assertNull(ErrorMapper.forHttpStatus(204, "获取动态详情"));
            assertNull(ErrorMapper.forHttpStatus(299, "获取动态详情"));
        }

        @Test
        @DisplayName("412 有专门的、带'请勿自动重试'的文案")
        void riskControl412() {
            BilibiliException e = ErrorMapper.forHttpStatus(412, "获取动态详情");
            assertNotNull(e);
            assertEquals(412, e.getCode());
            assertTrue(e.getMessage().contains("风控"), "应点明风控，实际：" + e.getMessage());
            assertTrue(e.getMessage().contains("请勿自动重试"), "必须劝住调用方别重试");
            assertEquals("HTTP 412 风控", e.getDescription());
        }

        @Test
        @DisplayName("其它非 2xx 给出通用文案，code 即 HTTP 状态码")
        void otherErrors() {
            BilibiliException e404 = ErrorMapper.forHttpStatus(404, "获取动态详情");
            assertNotNull(e404);
            assertEquals(404, e404.getCode());
            assertTrue(e404.getMessage().contains("HTTP 404"));

            BilibiliException e500 = ErrorMapper.forHttpStatus(500, "获取动态详情");
            assertNotNull(e500);
            assertEquals(500, e500.getCode());

            BilibiliException e302 = ErrorMapper.forHttpStatus(302, "获取动态详情");
            assertNotNull(e302, "3xx 也不算成功，应该被拦截");
            assertEquals(302, e302.getCode());
        }
    }
}
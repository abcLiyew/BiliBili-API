package com.esdllm.bilibiliApi.parse;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ResponseParserSupport} 单测 —— 解析流水线的统一收口。
 *
 * <p>它守住一条关键约束：<b>失败一律抛 {@link BilibiliException}（RuntimeException 子类）</b>，
 * 绝不再抛裸 {@code RuntimeException}。因为门面方法的 {@code throws IOException} 声明
 * 只覆盖受检异常，裸 RuntimeException 会"逃出"下游的 {@code catch (IOException)}。
 */
class ResponseParserSupportTest {

    /** 造一个响应壳 */
    private static <T> ApiResponse<T> resp(int code, String message, String msg, T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }

    @Test
    @DisplayName("正常路径：code=0 且 data 非空 → 原样返回 data")
    void happyPath() {
        String payload = "hello";
        ApiResponse<String> r = resp(0, "0", "", payload);
        String got = ResponseParserSupport.unwrap(r, "拉取数据");
        assertSame(payload, got, "应返回同一个 data 实例，而不是拷贝");
    }

    @Test
    @DisplayName("resp 为 null → 抛 BilibiliException，且消息含动作名")
    void nullResponse() {
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(null, "拉取数据"));
        assertTrue(e.getMessage().contains("拉取数据"), "消息应含动作名，实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("响应为空"), "消息应点明原因是响应为空");
    }

    @Test
    @DisplayName("code != 0 → 抛带业务码的 BilibiliException（经 ErrorMapper 语义化）")
    void nonZeroCode() {
        ApiResponse<String> r = resp(4101105, "id 不存在", "", null);
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r, "获取动态详情"));
        assertEquals(4101105, e.getCode(), "业务码必须透传到异常的 code 字段");
        assertTrue(e.getMessage().contains("获取动态详情"));
        assertTrue(e.getMessage().contains("该错误不可重试"), "4101105 属不可重试，应带提示");
    }

    @Test
    @DisplayName("风控码 -352 → 抛异常且码正确（这条决定上层会不会盲重试）")
    void riskControlCode() {
        ApiResponse<String> r = resp(-352, "风控", "", null);
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r, "获取动态列表"));
        assertEquals(-352, e.getCode());
        assertTrue(e.getMessage().contains("不可重试"), "风控码必须带'不可重试'提示");
    }

    @Test
    @DisplayName("code=0 但 data 为 null → 抛 BilibiliException（不是 NPE）")
    void nullData() {
        ApiResponse<String> r = resp(0, "0", "", null);
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r, "拉取数据"));
        assertTrue(e.getMessage().contains("data 为空"), "实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("拉取数据"));
    }

    @Test
    @DisplayName("message 为空时回退用 msg 字段")
    void fallsBackToMsg() {
        // message 为 null、msg 有值
        ApiResponse<String> r1 = resp(-1, null, "来自msg的错误", null);
        BilibiliException e1 = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r1, "拉取数据"));
        assertTrue(e1.getMessage().contains("来自msg的错误"),
                "message 缺失时应回退 msg，实际：" + e1.getMessage());

        // message 为空白、msg 有值
        ApiResponse<String> r2 = resp(-1, "   ", "来自msg的错误", null);
        BilibiliException e2 = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r2, "拉取数据"));
        assertTrue(e2.getMessage().contains("来自msg的错误"),
                "message 是空白时应回退 msg，实际：" + e2.getMessage());
    }

    @Test
    @DisplayName("message 与 msg 都有值时优先 message")
    void prefersMessage() {
        ApiResponse<String> r = resp(-1, "来自message", "来自msg", null);
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r, "拉取数据"));
        assertTrue(e.getMessage().contains("来自message"));
    }

    @Test
    @DisplayName("两者都空时给'无错误信息'占位，不出现 null 字面量")
    void bothBlank() {
        ApiResponse<String> r = resp(-1, null, null, null);
        BilibiliException e = assertThrows(BilibiliException.class,
                () -> ResponseParserSupport.unwrap(r, "拉取数据"));
        assertTrue(e.getMessage().contains("无错误信息"));
        assertFalse(e.getMessage().contains("null"), "不该把 Java 的 null 字面量拼进用户可见文案");
    }
}
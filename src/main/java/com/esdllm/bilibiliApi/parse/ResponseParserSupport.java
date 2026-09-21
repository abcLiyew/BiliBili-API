package com.esdllm.bilibiliApi.parse;

import com.esdllm.bilibiliApi.exception.BilibiliException;

/**
 * 解析流水线的统一收口：反序列化 → code 校验 → 取 data。
 *
 * <p>原先这四步散落在每个门面方法里，各写一遍 {@code code != 0} 与 {@code data == null} 判断，
 * 而且抛出的异常类型五花八门（{@code RuntimeException} / {@code BilibiliException} / 字符串拼异常）。
 * 收敛到这里之后，门面只需要关心"我要的数据在哪"。
 */
public final class ResponseParserSupport {

    private ResponseParserSupport() {
    }

    /**
     * 校验响应并取出 data。
     *
     * <p><b>失败一律抛 {@link BilibiliException}（RuntimeException 子类）</b>，
     * 绝不再抛裸 {@code RuntimeException} —— 后者会"逃出"门面方法的
     * {@code throws IOException} 声明，导致下游的 {@code catch (IOException)} 兜不住。
     *
     * @param resp 已反序列化的响应壳，可为 null
     * @param what 正在做的事，用于拼失败描述
     * @param <T>  data 类型
     * @return 非 null 的 data
     */
    public static <T> T unwrap(ApiResponse<T> resp, String what) {
        if (resp == null) {
            throw new BilibiliException(what + "失败：响应为空或无法解析");
        }
        if (resp.getCode() != 0) {
            throw ErrorMapper.toException(resp.getCode(), firstNonBlank(resp.getMessage(), resp.getMsg()), what);
        }
        if (resp.getData() == null) {
            throw new BilibiliException(resp.getCode(), what + "失败：data 为空", "data 为空");
        }
        return resp.getData();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}

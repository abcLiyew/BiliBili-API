package com.esdllm.bilibiliApi.parse;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import kong.unirest.HttpResponse;

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
     * <b>一次请求一份结果的完整流水线</b>：HTTP 状态 → 反序列化 → {@link #unwrap}。
     *
     * <p>原先这整段（连同 {@code brief}）在每个 Service 里各存一份同名私有实现，内容完全一致，
     * 属于复制粘贴级别的重复。它们本就共用 {@link #unwrap} 与 {@code ErrorMapper}，
     * 抽到这里并没有引入新的耦合，只是把最后一层壳也收进来。
     *
     * <p>失败时一定带上<b>能定位的证据</b>（HTTP 状态或响应原文片段）：
     * 调用方看到的失败形态高度相似（"取不到东西"），不给证据就只能靠猜。
     *
     * <p>⚠️ 少数旧实现（{@code VideoService#doGet}、{@code UserService#getCard}、
     * {@code LiveService#load}）<b>刻意不走这里</b> —— 它们的异常文案与
     * {@code code != 0} 的判定顺序是下游契约的一部分，迁移没有收益。
     *
     * @param response 原始响应
     * @param type     目标类型（fastjson 的 {@code TypeReference}，用于保留泛型）
     * @param action   正在做的事（拼失败文案）
     * @param <T>      data 类型
     * @return 非 null 的 data
     * @throws BilibiliException HTTP 非 2xx、响应无法解析、业务码非 0、或 data 为空
     */
    public static <T> T requireData(HttpResponse<String> response, TypeReference<ApiResponse<T>> type,
                                    String action) {
        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), action);
        if (httpError != null) {
            throw httpError;
        }
        ApiResponse<T> parsed;
        try {
            parsed = JSON.parseObject(response.getBody(), type);
        } catch (Exception e) {
            throw new BilibiliException(0, action + "失败：HTTP " + response.getStatus()
                    + " 的响应无法解析（前 120 字：" + brief(response.getBody()) + "）", "响应形状不符");
        }
        return unwrap(parsed, action);
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

    /** 截断响应体，避免把整页 HTML/JS 塞进异常消息 */
    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}

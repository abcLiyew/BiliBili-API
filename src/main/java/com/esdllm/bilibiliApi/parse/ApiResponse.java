package com.esdllm.bilibiliApi.parse;

import lombok.Data;

/**
 * B 站接口的通用响应外壳。
 *
 * <p>B 站所有 JSON 接口都返回同一套最外层结构：
 * <pre>
 * { "code": 0, "message": "0", "ttl": 1, "msg": "", "data": { ... } }
 * </pre>
 * 本库原先为每种接口各写了一份字段完全同构的壳（{@code BilibiliVideoResp} /
 * {@code BilibiliCardResp} / {@code BilibiliLiveResp} / {@code BilibiliDynamicResp}），
 * 每个类解析时还要各写一遍 {@code code != 0} 校验。这里收敛成一份泛型壳。
 *
 * <p>注意：{@code BilibiliDynamicResp} <b>不能删除</b> —— 它是门面
 * {@code Dynamic#getDynamicDetail} 的返回类型的一部分，属于下游冻结契约。
 *
 * @param <T> data 字段的目标类型
 */
@Data
public class ApiResponse<T> {

    /** 业务状态码，0 表示成功 */
    private int code;

    /** 状态信息（部分接口为 null） */
    private String message;

    /** 状态信息（部分接口用这个字段而非 message） */
    private String msg;

    /** 缓存有效期（秒） */
    private int ttl;

    /** 业务数据 */
    private T data;
}

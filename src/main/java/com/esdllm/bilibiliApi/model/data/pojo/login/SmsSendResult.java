package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「发送短信验证码」的响应体（{@code x/passport-login/web/sms/send} 的 {@code data}）。
 *
 * <p>整个响应只有一个有用的字段，但它<b>必须活着传到第二步</b> ——
 * 丢了就要重新发一次码（同号 60 秒内不能重发，等于让用户干等）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SmsSendResult {

    /**
     * 短信登录令牌，第二步 {@code web/login/sms} 的必填参数。
     *
     * <p>与验证码同寿命：<b>5 分钟</b>内有效（过期登录返回 {@code 1007}）。
     * 字段名保留 snake_case —— fastjson 按字段名直接映射 JSON，
     * 改成驼峰会解析不到（与 {@code QrCodeLogin.qrcode_key} 同一约定）。
     */
    private String captcha_key;
}

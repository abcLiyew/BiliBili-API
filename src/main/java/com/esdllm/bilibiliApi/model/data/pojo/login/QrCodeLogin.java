package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「申请登录二维码」的响应体
 * （{@code x/passport-login/web/qrcode/generate} 的 {@code data}）。
 *
 * <p>结构极简，但有一处容易误解：{@link #url} 是<b>二维码的内容</b>（一个登录页地址），
 * <b>不是</b>二维码图片地址。要得到可扫的图，调用方需自行把它渲染成二维码 ——
 * 本库的 {@code render} 包只管长图，不含二维码能力。
 *
 * @author 饿死的流浪猫
 */
@Data
public class QrCodeLogin {

    /** 二维码内容（登录页 URL），交给二维码生成器渲染即可 */
    private String url;

    /**
     * 扫码登录密钥，恒为 32 字符，有效期 180 秒。
     *
     * <p>字段名刻意保留 snake_case：fastjson 按字段名直接映射 JSON，改成驼峰会解析不到
     * （与 {@code LiveRoom.room_id} 等既有 POJO 同一约定）。
     */
    private String qrcode_key;
}

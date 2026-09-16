package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 一次扫码状态查询的结果：状态 + （成功时）凭据。
 *
 * <p><b>为什么不让 {@code poll} 直接返回凭据 / null</b>：调用方需要区分
 * "还没扫"（继续等）、"已扫未确认"（继续等）、"失效"（重新申请）三种非成功状态，
 * 只给一个 null 会让这三分支塌成一句"再试试"，失效的二维码会被无限轮询下去。
 *
 * @author 饿死的流浪猫
 */
@Data
public class QrLoginStatus {

    /** 扫描状态，永不为 null */
    private QrLoginState state;

    /** B 站原始状态码（{@code data.code}），排障用 */
    private int rawCode;

    /** B 站状态文案，如「未扫码」 */
    private String message;

    /** 登录凭据，<b>仅 {@link QrLoginState#SUCCESS} 时非 null</b> */
    private LoginCredential credential;

    /** 是否已经登录成功且拿到了凭据 */
    public boolean hasCredential() {
        return credential != null;
    }
}

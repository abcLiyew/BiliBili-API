package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 扫码登录成功后拿到的<b>登录凭据</b>（即"token"在本库语境下的实体）。
 *
 * <p>用法只有一步：把 {@link #cookieHeader} 交给
 * {@code HttpPolicy.setCookie(...)}，之后所有出站请求都会自动带上登录态：
 * <pre>{@code
 * LoginCredential c = new Login().waitForLogin(qrKey, 180_000L);
 * HttpPolicy.setCookie(c.getCookieHeader());
 * }</pre>
 *
 * <p><b>为什么不自动注入</b>：注入会改变<b>全局</b>出站身份（静态状态），
 * 库内不宜有这种隐式副作用。由调用方显式调一次，出问题也一眼能看出是谁改的身份。
 *
 * <p><b>安全约定</b>：{@link #toString()} 刻意手写并打码 —— Lombok 默认生成的那份
 * 会把 {@code SESSDATA} 原文打进日志，而 {@code SESSDATA} 等价于账号密码
 * （拿到即可读写该账号的全部接口，含写操作）。本库对凭据的一贯态度是"值一律不出"
 * （参见 {@code HttpPolicy.maskCookie}）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class LoginCredential {

    /** 会话凭据，<b>最关键的字段</b>，等价于账号密码 */
    private String sessdata;

    /** CSRF 令牌。只读接口用不到，写操作（点赞/发动态/收藏…）必须带 {@code csrf}={@code bili_jct} */
    private String biliJct;

    /** 登录账号的 uid */
    private String dedeUserId;

    /** {@code DedeUserID} 的校验值，B 站会一并下发，带上无害、缺了可能被判凭据不完整 */
    private String dedeUserIdCkMd5;

    /**
     * 凭据到期时间（<b>秒</b>级 Unix 时间戳，取自跨域登录地址里的 {@code Expires}）。
     *
     * <p>解析不出时为 0，语义是"未知"而<b>不是</b>"已过期"（{@link #isExpired()} 据此刻意返回 false）。
     * web 端凭据实测有效期约 30 天。
     */
    private long expiresAt;

    /** 刷新令牌，可用于换新 Cookie（本库暂未实现刷新，先原样透出） */
    private String refreshToken;

    /** 登录时刻（毫秒时间戳，取自 B 站返回的 {@code timestamp}） */
    private long loginTime;

    /**
     * 组装好的 {@code Cookie} 头（{@code DedeUserID; DedeUserID__ckMd5; SESSDATA; bili_jct}）。
     *
     * <p>已排除跨域地址里的 {@code gourl} —— 它是跳转控制参数，不是 Cookie。
     * 值均已 URL 解码（未解码的值带 {@code %2C}，服务端不认）。
     */
    private String cookieHeader;

    /**
     * 凭据是否已过期。
     *
     * @return {@code expiresAt} 为 0（未知）时返回 false，避免把"没解析出来"当成"已过期"
     */
    public boolean isExpired() {
        return expiresAt > 0L && System.currentTimeMillis() / 1000L >= expiresAt;
    }

    /**
     * {@link #cookieHeader} 的<b>键名</b>列表（值一律不出），供日志与排障使用。
     *
     * @return 形如 {@code DedeUserID,DedeUserID__ckMd5,SESSDATA,bili_jct}；无凭据时为空串
     */
    public String cookieKeys() {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String key = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(key);
        }
        return sb.toString();
    }

    /**
     * 手写的脱敏 {@code toString}（Lombok 不会覆盖已存在的方法）。
     *
     * <p>保留 uid / 到期时间 / 键名这些"能拿来排障、又不足以冒用账号"的信息，
     * 凭据值一律只露长度。
     */
    @Override
    public String toString() {
        return "LoginCredential{dedeUserId=" + dedeUserId
                + ", sessdata=" + mask(sessdata)
                + ", biliJct=" + mask(biliJct)
                + ", expiresAt=" + expiresAt
                + ", refreshToken=" + mask(refreshToken)
                + ", cookieKeys=" + cookieKeys()
                + "}";
    }

    /** 只露前 4 位与长度：足够确认"值确实存在、确实变了"，又不足以复原 */
    private static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "<空>";
        }
        if (secret.length() <= 8) {
            return "***(" + secret.length() + "字符)";
        }
        return secret.substring(0, 4) + "***(" + secret.length() + "字符)";
    }
}

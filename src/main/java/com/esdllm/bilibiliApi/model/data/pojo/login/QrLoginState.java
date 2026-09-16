package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Getter;

/**
 * 扫码登录的四种状态。
 *
 * <p><b>取值来自轮询响应的 {@code data.code}，不是最外层 {@code code}</b>
 * （外层在轮询期间恒为 0，详见 {@link QrCodePoll}）。这是本模块最容易踩错的一处，
 * 也是唯一一处"错了还不报错"的地方 —— 判错的表现是流程以为登录成功、后续请求全部匿名。
 *
 * @author 饿死的流浪猫
 */
@Getter
public enum QrLoginState {

    /** 用户已在手机上确认，凭据可取 */
    SUCCESS(0, "登录成功"),

    /** 已扫码，等待用户在手机上点确认 —— 继续轮询即可 */
    SCANNED_NOT_CONFIRMED(86090, "二维码已扫码未确认"),

    /** 还没有人扫 —— 继续轮询即可 */
    NOT_SCANNED(86101, "未扫码"),

    /** 二维码失效（时效 180 秒，或被新申请的二维码顶掉）—— <b>必须重新申请</b>，继续轮询没有意义 */
    EXPIRED(86038, "二维码已失效"),

    /** B 站回了未收录的状态码，按"继续轮询"处理并留日志 */
    UNKNOWN(-1, "未知状态");

    /**
     * -- GETTER --
     * B 站原始状态码，便于排障时对照
     */
    private final int code;
    /**
     * -- GETTER --
     * 状态的中文文案（取自 B 站原文的固定部分）
     */
    private final String text;

    QrLoginState(int code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * 把 B 站的状态码映射成枚举。
     *
     * @param code 轮询响应里的 {@code data.code}
     * @return 对应状态；未收录的码返回 {@link #UNKNOWN}
     */
    public static QrLoginState fromCode(int code) {
        for (QrLoginState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        return UNKNOWN;
    }

    /**
     * 本状态是否已经结束整个流程。
     *
     * <p>{@link #SUCCESS} 与 {@link #EXPIRED} 是终态：前者拿到凭据，后者必须重新申请二维码。
     * 其余状态都应当继续轮询（对 {@link #UNKNOWN} 宽容处理，避免 B 站新增状态码直接把登录打死）。
     *
     * @return true 表示不必再轮询
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == EXPIRED;
    }

    /** 是否登录成功（唯一可以取凭据的状态） */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

}

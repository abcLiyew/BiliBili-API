package com.esdllm.bilibiliApi.model.data.pojo.login;

import lombok.Data;

/**
 * 「这枚凭据现在什么状态」的答案 —— <b>由服务端确认，不是本地猜的</b>。
 *
 * <p><b>为什么需要它</b>：本库的凭据可以来自任何地方（扫码、短信、密码，或调用方自己
 * 从别处拿来的一串 Cookie），而 {@link LoginCredential#isExpired()} 只能做<b>本地时钟</b>
 * 判断 —— 它依赖解析出的 {@code Expires}，而一串"裸 Cookie"根本没有这个字段
 * （{@code expiresAt} 为 0 时 {@code isExpired()} 刻意返回 false，即"未知"）。
 * 于是唯一的真判据只能是<b>问服务端</b>。
 *
 * <p><b>它治的是什么病</b>：凭据失效在本库是<b>静默</b>的 —— 关注流会返回 {@code -412}、
 * 空间动态会返回 {@code code=0} 加空列表，长驻进程（如推送机器人）表现为"突然什么都不推了"，
 * 日志里一行错误都没有。有了本状态对象，"该不该重新登录"就从一个猜测变成了一个布尔值。
 *
 * <p><b>典型用法</b>（长驻进程启动时校验一次，或定时校验）：
 * <pre>{@code
 * CredentialStatus st = new Login().getCredentialStatus();
 * if (!st.isLoggedIn()) {
 *     // 凭据没了 —— 走扫码/短信重新登录，而不是继续发注定失败的请求
 * } else if (st.isRefreshNeeded()) {
 *     // 服务端说该刷新了（见 refreshTimestamp 的说明）
 * }
 * }</pre>
 *
 * @author 饿死的流浪猫
 */
@Data
public class CredentialStatus {

    /**
     * 服务端是否认这枚凭据（{@code nav} 的 {@code data.isLogin}）。
     *
     * <p>这是<b>唯一</b>该用的判据 —— 不要用 {@link #code} 之外的任何本地信息去猜
     * （凭据看起来再正常，服务端不认就是不认）。
     */
    private boolean loggedIn;

    /** 登录账号 uid（{@code nav} 的 {@code data.mid}）；未登录时为 {@code 0} */
    private long uid;

    /** 登录账号昵称（{@code nav} 的 {@code data.uname}）；未登录时为 {@code null} */
    private String uname;

    /**
     * {@code nav} 的外层业务码：{@code 0} = 已登录，{@code -101} = 账号未登录。
     *
     * <p>⚠️ 未登录是<b>正常结果而非异常</b>，所以本字段为 {@code -101} 时不会抛异常 ——
     * 它正是调用方问的那个问题本身。
     */
    private int code;

    /** 服务端原话（如「账号未登录」）。失败排查时最有用的一句话，故原样透出 */
    private String message;

    // ------------------------------------------------------------------
    // 以下三项来自 cookie/info，只在"服务端确认已登录"时才去问（未登录时问它没有意义）
    // ------------------------------------------------------------------

    /**
     * 是否真的查过 {@code cookie/info}。
     *
     * <p>{@code false} 时下面两项<b>没有意义</b>，调用方不应据此做任何判断 ——
     * 典型来源：凭据本身就没登录（提前返回），或该次查询失败（不阻塞主结果）。
     */
    private boolean refreshChecked;

    /**
     * 服务端是否认为该凭据<b>该刷新了</b>（{@code cookie/info} 的 {@code data.refresh}）。
     *
     * <p>⚠️ <b>本库目前没有实现"刷新"这一步</b>，所以这个 {@code true} 现在只意味着
     * "该换新凭据了，请重新登录"：换新 Cookie 的 {@code cookie/refresh} 端点还需要一个
     * {@code refresh_token}，而 web 端实测下发的是<b>空串</b>
     * （详见 {@link LoginCredential#getRefreshToken()}）。
     */
    private boolean refreshNeeded;

    /**
     * 刷新流程的输入时间戳（{@code cookie/info} 的 {@code data.timestamp}，毫秒）。
     *
     * <p>⚠️ 公开文档写的 {@code hex(RSA-OAEP("refresh_" + timestamp))} 与 {@code /correspond/1/}
     * <b>都已过期</b>。2026-09-16 从线上前端包（异步 chunk）取到现行实现：明文
     * {@code hexAscii("set_" + timestamp)}（Safari 为 {@code "set_" + timestamp + "_" + refresh_token}）
     * 经 RSA/SHA-256 加密后拼成 {@code www.bilibili.com/correspond/0/<密文>}；另一支变体用
     * {@code data.hash + timestamp}。而且这个地址是<b>隐藏 iframe 的 src</b>，
     * {@code refresh_token} 由父页面在 {@code onload} 里用 {@code postMessage} 递进去（不在 URL 上）
     * ⇒ 换 Cookie 是**浏览器内的流程**，本库不实现。本字段仅原样透出，未查过时为 {@code 0}。
     *
     * <p>🆕 <b>实测语义（2026-09-16）</b>：它是<b>服务端当前时间</b>，不是"上次刷新时间" ——
     * 两次相隔约 8 分钟的请求分别得到 {@code 1789537994849} 与 {@code 1789538447003}，
     * 差值恰好等于请求间隔。所以实现刷新时要取<b>本次响应的</b> timestamp，
     * 不能复用登录时（或上一次）拿到的那个。
     */
    private long refreshTimestamp;

    /**
     * 一句话摘要，供日志使用。
     *
     * <p>不含任何凭据值（本类里也没有）—— uid 与昵称是"能拿来排障、又不足以冒用账号"的信息，
     * 与 {@link LoginCredential#toString()} 的口径一致。
     *
     * @return 形如 {@code 已登录(uid=497078180, uname=…, 无需刷新)} 或 {@code 未登录(code=-101, message=账号未登录)}
     */
    public String summary() {
        if (!loggedIn) {
            return "未登录(code=" + code + ", message=" + message + ")";
        }
        String refresh = refreshChecked
                ? (refreshNeeded ? "需刷新" : "无需刷新")
                : "刷新状态未查";
        return "已登录(uid=" + uid + ", uname=" + uname + ", " + refresh + ")";
    }

    /**
     * 手写 toString，复用 {@link #summary()}（Lombok 不会覆盖已存在的方法）。
     *
     * <p>刻意不打 {@code refreshTimestamp} —— 它与正确性无关，出现在日志里只是噪音。
     */
    @Override
    public String toString() {
        return "CredentialStatus{" + summary() + "}";
    }
}

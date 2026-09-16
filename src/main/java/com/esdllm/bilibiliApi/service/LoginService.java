package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 登录服务（{@code Login} 门面的后端）：<b>扫码 / 密码 / 短信三条链路 → 换取登录凭据</b>。
 *
 * <p>三条链路最终产出同一个东西（{@link LoginCredential}），差别只在"人工门槛"是什么：
 * <table border="1">
 *   <caption>三条链路的取舍</caption>
 *   <tr><th>链路</th><th>入口</th><th>人工门槛</th><th>适合场景</th></tr>
 *   <tr><td><b>扫码</b></td><td>{@link #requestQrCode()} → {@link #waitForLogin}</td>
 *       <td>App 扫一次，<b>不过极验</b></td><td>首选：长驻进程首次启动</td></tr>
 *   <tr><td><b>密码</b></td><td>{@link #requestCaptcha()} → {@link #passwordLogin}</td>
 *       <td>过极验（浏览器）</td><td>没有手机 App 可扫、但有账号密码时</td></tr>
 *   <tr><td><b>短信</b></td><td>{@link #requestCaptcha()} → {@link #sendSmsCode} → {@link #smsLogin}</td>
 *       <td>过极验 + 手机收码</td><td>密码登录被风控要求二次验证时兜底</td></tr>
 * </table>
 *
 * <p><b>扫码为什么仍是首选</b>：它<b>不经过极验</b> —— 三条链路里只有它不需要浏览器。
 * 密码/短信都要先过极验 v3（背景图 + 滑动/点选，且提交时必须带本地 JS 生成的 {@code w}：
 * 操作轨迹 + 浏览器指纹），这一步本地伪造不出来，因此本库<b>不含任何浏览器或打码逻辑</b>，
 * 只负责"把 {@code gt}/{@code challenge} 交给你、把你过验的结果送出去"。
 *
 * <p><b>本模块的失败几乎全是"静默"的</b>（这也是它比其它 service 啰嗦得多的原因）：
 * {@code data.code} 误读、{@code %2C} 没解码、响应形状与预期不同、{@code Set-Cookie} 与
 * {@code data.url} 哪个才带凭据 —— 这些全都会表现为
 * "HTTP 一切正常、日志一行没有、凭据却没拿到"。对付这类失败只有两个手段：
 * <b>留下原始证据</b>（见 {@link #trace}）与<b>别把陌生形状直接当失败</b>
 * （见 {@link #credentialFrom} 的三级回退）。二者都是 2026-09-16 真机扫码失败后补上的。
 *
 * <p><b>异常语义</b>：本服务<b>只抛</b> {@link BilibiliException}（runtime），
 * 由门面边界决定是否转成 {@code IOException}（沿用库内既有分层约定）。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class LoginService {

    /** 单例入口，无状态（轮询循环的中间状态全在栈上）。 */
    public static final LoginService INSTANCE = new LoginService();

    /**
     * 默认轮询间隔（毫秒）。
     *
     * <p>2 秒是"灵敏"与"别把轮询打成风控形态"的折中：二维码只有 180 秒寿命，
     * 间隔太长会让用户确认后的等待变明显；太短则一百多次连发请求，形态上很像脚本。
     */
    public static final long DEFAULT_POLL_INTERVAL_MS = 2_000L;

    /**
     * 默认总超时（毫秒）。
     *
     * <p>取 180 秒（与二维码寿命一致）：让"二维码已失效"这个<b>明确状态</b>先于"超时"出现，
     * 调用方拿到的失败原因更具体。
     */
    public static final long DEFAULT_TIMEOUT_MS = 180_000L;

    /** 凭据参数名。跨域地址的 query 与 {@code Set-Cookie} 用的是同一批键，故两处共用 */
    private static final Set<String> CREDENTIAL_KEYS =
            Set.of("DedeUserID", "DedeUserID__ckMd5", "SESSDATA", "bili_jct", "Expires");

    /** 日志与异常里必须打码的键（凭据值一律不出，{@code SESSDATA} 等价于账号密码） */
    private static final List<String> SECRET_KEYS =
            List.of("SESSDATA", "bili_jct", "DedeUserID__ckMd5", "refresh_token");

    /**
     * 从任意文本里挖凭据参数 —— 兜底路径用（HTML 页面、非预期 JSON 里也可能藏着同一个跨域地址）。
     *
     * <p>键名长的写在前面（{@code DedeUserID__ckMd5} 在 {@code DedeUserID} 之前），
     * 否则会被短键抢先匹配掉一半。值里排除 {@code & " ' 空白 < > \} 这些明显的边界字符。
     */
    private static final Pattern CREDENTIAL_PARAM = Pattern.compile(
            "(?:^|[?&\"'\\s<;,])(DedeUserID__ckMd5|DedeUserID|SESSDATA|bili_jct|Expires)"
                    + "=([^&\"'\\s<>\\\\]+)");

    /**
     * HTTP 日期解析器（{@code Set-Cookie} 的 {@code Expires} 属性）。
     *
     * <p>模式是归一化之后的固定形状 —— 星期几已在 {@code parseHttpDate} 里被丢掉、
     * {@code -} 已被换成空格，因此这里只需要认一种写法。
     *
     * <p><b>{@code Locale.ENGLISH} 不能省</b>：本机默认 locale 是 {@code zh_CN}，
     * 用默认 locale 解析 {@code "15 Oct 2026"} 会因为月名不认识而直接失败
     * （中文环境下 {@code Oct} 不是任何月份的缩写）。
     *
     * <p>{@code parseLenient()} 让单位数日期（{@code 5 Oct 2026}）也能过。
     */
    private static final DateTimeFormatter HTTP_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .parseLenient()
            .appendPattern("d MMM yyyy HH:mm:ss")
            .toFormatter(Locale.ENGLISH);

    /**
     * 尾部时区令牌：{@code GMT} / {@code UTC} / {@code UT}，或 {@code ±HHMM} / {@code ±HH:MM}。
     *
     * <p>HTTP 日期规范规定时区就是 GMT，所以<b>缺失时按 UTC 解释</b>，不按本机时区 ——
     * 后者会让"到期时间"随部署机器漂移，是个很难察觉的坑。
     */
    private static final Pattern TRAILING_ZONE =
            Pattern.compile("\\s+(GMT|UTC|UT|[+-]\\d{2}:?\\d{2})$", Pattern.CASE_INSENSITIVE);

    private LoginService() {
    }

    /**
     * 申请一个登录二维码。
     *
     * @return 二维码内容 + {@code qrcode_key}，不可为 null
     * @throws BilibiliException 网络失败、响应无法解析、业务码非 0、或缺 {@code qrcode_key}
     */
    public QrCodeLogin requestQrCode() {
        HttpResponse<String> response = BilibiliHttp.get(
                BilibiliEndpoint.passportQrCodeUrl,
                BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.passportReferer);
        QrCodeLogin data = ResponseParserSupport.unwrap(parseQrLogin(response), "申请登录二维码");
        if (isBlank(data.getQrcode_key())) {
            // code=0 但没有 key：B 站偶发的半残响应。此时"拿到二维码"是假的，必须当失败
            throw new BilibiliException("申请登录二维码失败：响应里没有 qrcode_key");
        }
        log.info("已申请登录二维码（qrcode_key={}…，有效期 180 秒），请用哔哩哔哩 App 扫码",
                head(data.getQrcode_key(), 8));
        return data;
    }

    /**
     * 查询一次扫码状态。
     *
     * <p>只查一次、立刻返回 —— 轮询节奏由调用方掌握。要"一直等到出结果"用
     * {@link #waitForLogin(String, long, long)}。
     *
     * @param qrcodeKey {@link #requestQrCode()} 返回的密钥
     * @return 状态 + （成功时）凭据；非成功状态不抛异常，因为"还没扫"是正常流程而非错误
     * @throws BilibiliException {@code qrcodeKey} 为空、网络失败、外层业务码非 0，
     *                           或响应里确实没有可用凭据
     */
    public QrLoginStatus poll(String qrcodeKey) {
        if (isBlank(qrcodeKey)) {
            throw new BilibiliException("查询扫码状态失败：qrcode_key 不能为空");
        }
        String url = BilibiliEndpoint.passportQrCodePollUrl + encode(qrcodeKey);
        HttpResponse<String> response = BilibiliHttp.get(
                url, BilibiliEndpoint.jsonAccept, BilibiliEndpoint.passportReferer);
        return interpret(response);
    }

    /**
     * 解读一次轮询响应。
     *
     * <p><b>为什么这段要写得这么啰嗦</b>（2026-09-16 真机扫码踩坑）：本端点的失败形态是
     * <b>静默</b>的 —— 用户扫码确认后，HTTP 层一切正常、没有任何异常与告警，只得到一句
     * "查询扫码状态失败"，连"服务端到底回了什么"都看不到，只能靠猜。
     * 因此这里固定做三件事，缺一件就会再踩一次：
     * <ol>
     *   <li><b>原始响应一律留痕</b>：HTTP 状态 + {@code Set-Cookie} 键名 + 打码后的 body；</li>
     *   <li><b>不把"形状陌生"直接判成失败</b>：只要响应里确实带了 {@code SESSDATA}，
     *       那就是登录成功，形状不同只是我们没预料到（见 {@link #salvage}）；</li>
     *   <li>失败时<b>把服务端原话带进异常</b>（HTTP 状态 + 原文片段 + 内层业务码），
     *       而不是丢一句无信息量的"失败了"。</li>
     * </ol>
     *
     * @param response 轮询的原始响应
     * @return 状态 + （成功时）凭据
     * @throws BilibiliException 外层业务码非 0、HTTP 非 2xx，或响应里确实没有凭据
     */
    private QrLoginStatus interpret(HttpResponse<String> response) {
        int httpStatus = response.getStatus();
        String body = response.getBody();
        // Set-Cookie 读的是原始响应头：BilibiliHttp 关掉的是 Unirest 的 cookie *罐*
        // （它会把 Set-Cookie 回放到后续请求，实测能稳定诱发 412），响应头本身照旧可读
        Map<String, String> cookies = setCookiesOf(response);
        log.debug("轮询响应：HTTP {}，Set-Cookie 键=[{}]，body={}",
                httpStatus, String.join(",", cookies.keySet()), brief(mask(body), 300));

        ApiResponse<QrCodePoll> parsed =
                parseOrNull(body, new TypeReference<>() {
                });
        if (parsed != null && parsed.getCode() != 0) {
            // 外层码非 0 才是"这次调用失败"；正常轮询期间它恒为 0（状态在 data.code）
            throw ErrorMapper.toException(parsed.getCode(),
                    firstNonBlank(parsed.getMessage(), parsed.getMsg()), "查询扫码状态");
        }
        QrCodePoll data = parsed == null ? null : parsed.getData();

        if (data == null) {
            // 不是熟悉的外壳。此时唯一还能证明"登录成功"的证据，是响应里已经带了 SESSDATA
            LoginCredential salvaged = salvage(body, cookies, null, 0L);
            if (salvaged != null) {
                log.warn("轮询响应不是预期的 JSON 外壳（HTTP {}），但其中已带 SESSDATA —— 按登录成功处理",
                        httpStatus);
                return successStatus(salvaged, "响应形状与预期不同，凭据来源见日志");
            }
            // 非 2xx 且没有凭据：HTTP 层语义（412 = 风控）比"形状不符"更有用
            BilibiliException httpError = ErrorMapper.forHttpStatus(httpStatus, "查询扫码状态");
            if (httpError != null) {
                throw httpError;
            }
            throw new BilibiliException(0, "查询扫码状态失败：HTTP " + httpStatus
                    + " 的响应不是预期外壳，且其中没有可用凭据（前 120 字：" + brief(mask(body)) + "）",
                    "响应形状不符");
        }

        // ★ 只能看 data.code：外层 code 在轮询期间恒为 0（含义是"接口调通了"）。
        //   误用外层码的后果是所有状态都判成"成功"，包括二维码早已失效的时候。
        QrLoginState state = QrLoginState.fromCode(data.getCode());
        QrLoginStatus status = new QrLoginStatus();
        status.setState(state);
        status.setRawCode(data.getCode());
        status.setMessage(data.getMessage());
        if (state.isSuccess()) {
            status.setCredential(credentialOf(data, body, cookies));
        } else if (state == QrLoginState.UNKNOWN) {
            log.warn("扫码状态返回了未收录的状态码 {}（{}），按'继续轮询'处理", data.getCode(), data.getMessage());
        }
        return status;
    }

    /**
     * 阻塞式扫码登录：反复轮询直到成功、二维码失效或超时。
     *
     * <p>适合"启动时人工扫一次，之后长期无人值守"的场景。调用方拿到二维码后先用
     * {@link #requestQrCode()} 的 {@code url} 渲染出图片给用户扫，再调本方法等待。
     *
     * @param qrcodeKey     {@link #requestQrCode()} 返回的密钥
     * @param timeoutMs     总超时（毫秒），小于等于 0 表示只轮询一次
     * @param pollIntervalMs 两次轮询之间的等待（毫秒），小于 0 按 0 处理
     * @return 登录凭据，不可为 null
     * @throws BilibiliException 二维码失效、超时、或任一底层失败
     */
    public LoginCredential waitForLogin(String qrcodeKey, long timeoutMs, long pollIntervalMs) {
        if (isBlank(qrcodeKey)) {
            throw new BilibiliException("扫码登录失败：qrcode_key 不能为空");
        }
        long interval = Math.max(0L, pollIntervalMs);
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        int polls = 0;

        while (true) {
            polls++;
            QrLoginStatus status = poll(qrcodeKey);
            if (status.getState().isSuccess()) {
                log.info("扫码登录成功（共轮询 {} 次）", polls);
                return status.getCredential();
            }
            if (status.getState() == QrLoginState.EXPIRED) {
                // 继续轮询没有意义：失效的 key 不会自己复活，必须重新申请
                throw new BilibiliException(86038,
                        "扫码登录失败：二维码已失效（有效期 180 秒，或被后申请的二维码顶掉），请重新申请",
                        "二维码已失效");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new BilibiliException("扫码登录超时：等待 " + Math.max(0L, timeoutMs)
                        + "ms 仍未确认登录（最后一次状态：" + status.getState().getText()
                        + "，已轮询 " + polls + " 次）");
            }
            sleep(interval);
        }
    }

    // ------------------------------------------------------------------ 密码 / 短信登录

    /**
     * 申请验证码前置信息（密码登录与短信登录<b>共用</b>的第一步）。
     *
     * <p>⚠️ 返回的<b>不是一张可渲染成图的验证码</b> —— {@code type} 实测恒为 {@code geetest}，
     * 是极验 v3 交互式验证。调用方拿 {@code gt} + {@code challenge} 在自己的页面里
     * 用极验官方 JS 过验，再把 {@code validate}/{@code seccode} 打包成
     * {@link GeeTestValidation} 传回给 {@link #passwordLogin} 或 {@link #sendSmsCode}。
     *
     * <p>本次申请（{@code token} + {@code challenge}）<b>只能用于一次登录</b>：
     * 重试、或改用另一条链路（密码 ↔ 短信）都必须<b>重新申请 + 重新过验</b>。
     *
     * @return 验证码前置信息（type / token / geetest），不可为 null
     * @throws BilibiliException 网络失败、业务码非 0、响应缺字段、或返回的不是极验类型
     */
    public LoginCaptcha requestCaptcha() {
        HttpResponse<String> response = BilibiliHttp.get(
                BilibiliEndpoint.passportCaptchaUrl,
                BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.passportReferer);
        trace("申请验证码", response);

        ApiResponse<LoginCaptcha> parsed =
                parseOrNull(response.getBody(), new TypeReference<>() {
                });
        requireSuccess(parsed, response, "申请验证码");

        LoginCaptcha data = parsed.getData();
        if (data == null || isBlank(data.getType())) {
            throw new BilibiliException(0, "申请验证码失败：响应里没有 type"
                    + "（前 120 字：" + brief(mask(response.getBody())) + "）", "响应缺 type");
        }
        if (!"geetest".equalsIgnoreCase(data.getType())) {
            // 换验证码供应商意味着整条交互流程都要重写，绝不能悄悄降级成"没有验证码"
            throw new BilibiliException(0, "申请验证码失败：验证码类型是 " + data.getType()
                    + "，本库只支持 geetest（原始响应：" + brief(mask(response.getBody())) + "）",
                    "验证码类型不支持");
        }
        if (data.getGeetest() == null || isBlank(data.getGeetest().getGt())
                || isBlank(data.getGeetest().getChallenge())) {
            throw new BilibiliException(0, "申请验证码失败：geetest 缺 gt/challenge"
                    + "（原始响应：" + brief(mask(response.getBody())) + "）", "响应缺 gt");
        }
        log.info("已申请登录验证码：type=geetest，gt={}…，challenge={}…（一次申请只能用于一次登录）",
                head(data.getGeetest().getGt(), 8), head(data.getGeetest().getChallenge(), 8));
        return data;
    }

    /**
     * 取 RSA 公钥与盐（密码登录专用，{@link #passwordLogin} 内部会自己调）。
     *
     * <p>单独公开是为了可观测性：{@code hash} 只有 20 秒寿命，把它单独调出来打日志，
     * 能一眼看出"是不是取了 key 之后拖太久才提交"（那会返回 2400 登录秘钥错误）。
     *
     * @return 公钥与盐，不可为 null
     * @throws BilibiliException 网络失败、业务码非 0、或响应缺字段
     */
    public RsaKeyInfo rsaKey() {
        HttpResponse<String> response = BilibiliHttp.get(
                BilibiliEndpoint.passportWebKeyUrl,
                BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.passportReferer);
        trace("获取 RSA 公钥", response);

        ApiResponse<RsaKeyInfo> parsed =
                parseOrNull(response.getBody(), new TypeReference<>() {
                });
        requireSuccess(parsed, response, "获取 RSA 公钥");

        RsaKeyInfo data = parsed.getData();
        if (data == null || isBlank(data.getHash()) || isBlank(data.getKey())) {
            throw new BilibiliException(0, "获取 RSA 公钥失败：响应缺 hash/key"
                    + "（前 120 字：" + brief(mask(response.getBody())) + "）", "响应缺 hash/key");
        }
        return data;
    }

    /**
     * <b>账号密码登录</b>。
     *
     * <p>调用顺序（<b>顺序不能变</b>，因为 {@code hash} 只有 20 秒寿命）：
     * <ol>
     *   <li>{@link #requestCaptcha()} 拿 {@code token}/{@code gt}/{@code challenge}；</li>
     *   <li>调用方在浏览器里过极验，得到 {@link GeeTestValidation}；</li>
     *   <li>本方法内部取 key → 加密 → 立刻提交（第 1、2 步的等待发生在取 key <b>之前</b>，这才是对的）。</li>
     * </ol>
     *
     * <p><b>密码不会被记录</b>：明文与密文都不进日志、不进异常消息，只打长度。
     *
     * @param username 手机号或邮箱
     * @param password 明文密码（本方法内部完成 {@code base64(RSA_PKCS1(hash + 明文))}）
     * @param captcha  {@link #requestCaptcha()} 的结果（提供 {@code token}，以及未显式指定时的 {@code challenge}）
     * @param gee      调用方在浏览器里过验后的结果
     * @return 登录凭据，不可为 null
     * @throws BilibiliException 参数为空、加密失败、网络失败、或服务端拒绝
     *                           （含 {@code -629} 账号或密码错误、{@code 2400} 登录秘钥错误=hash 过期、
     *                           {@code 2406} 极验结果失效、{@code -2100} 需手机号二次验证）
     */
    public LoginCredential passwordLogin(String username, String password,
                                        LoginCaptcha captcha, GeeTestValidation gee) {
        if (isBlank(username) || isBlank(password)) {
            throw new BilibiliException("密码登录失败：账号与密码都不能为空");
        }
        if (captcha == null || isBlank(captcha.getToken())) {
            throw new BilibiliException("密码登录失败：缺少验证码前置信息（token），请先调 requestCaptcha()");
        }
        if (gee == null) {
            throw new BilibiliException("密码登录失败：缺少极验结果，请先在浏览器里完成极验");
        }

        RsaKeyInfo key = rsaKey();
        String encrypted = encryptPassword(key.getHash(), key.getKey(), password);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("password", encrypted);
        form.put("keep", "0");
        form.put("source", BilibiliEndpoint.passportLoginSource);
        putCaptchaParams(form, captcha, gee);

        HttpResponse<String> response = BilibiliHttp.postForm(
                BilibiliEndpoint.passportWebLoginUrl, form,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.passportReferer);
        // 只打字段名：password 是密文也不该进日志（日志常被长期留存、随工单外发）
        trace("密码登录", response);

        return credentialFrom(response, "密码登录");
    }

    /**
     * <b>发送短信验证码</b>（短信登录第一步）。
     *
     * <p>极验 {@code validate} 是一次性的：本方法与 {@link #passwordLogin}
     * <b>必须各过各的极验</b>，共用一份会返回 {@code 2406 验证极验服务出错}。
     *
     * <p>两条时效约束：同一手机号 <b>60 秒</b>内不能重发（{@code 1003}），
     * 验证码 <b>5 分钟</b>过期（{@code 1007}）。所以 {@code captcha_key} 拿到后要立刻往下走。
     *
     * @param tel     手机号（不含国际冠字码）
     * @param cid     国际冠字码，中国大陆用 {@link BilibiliEndpoint#passportCidChina}（{@code 86}）
     * @param captcha {@link #requestCaptcha()} 的结果
     * @param gee     调用方在浏览器里过验后的结果
     * @return {@code captcha_key} 等，不可为 null
     * @throws BilibiliException 参数为空、网络失败、或服务端拒绝
     *                           （含 {@code 1002} 手机号格式错、{@code 1003} 已发送过、
     *                           {@code 86203} 次数达上限、{@code 2406} 极验结果失效）
     */
    public SmsSendResult sendSmsCode(String tel, String cid,
                                     LoginCaptcha captcha, GeeTestValidation gee) {
        if (isBlank(tel)) {
            throw new BilibiliException("发送短信验证码失败：手机号不能为空");
        }
        if (captcha == null || isBlank(captcha.getToken())) {
            throw new BilibiliException("发送短信验证码失败：缺少验证码前置信息（token），请先调 requestCaptcha()");
        }
        if (gee == null) {
            throw new BilibiliException("发送短信验证码失败：缺少极验结果，请先在浏览器里完成极验");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("cid", isBlank(cid) ? BilibiliEndpoint.passportCidChina : cid);
        form.put("tel", tel);
        form.put("source", BilibiliEndpoint.passportLoginSource);
        putCaptchaParams(form, captcha, gee);

        HttpResponse<String> response = BilibiliHttp.postForm(
                BilibiliEndpoint.passportSmsSendUrl, form,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.passportReferer);
        trace("发送短信验证码", response);

        ApiResponse<SmsSendResult> parsed =
                parseOrNull(response.getBody(), new TypeReference<>() {
                });
        requireSuccess(parsed, response, "发送短信验证码");

        SmsSendResult data = parsed.getData();
        if (data == null || isBlank(data.getCaptcha_key())) {
            throw new BilibiliException(0, "发送短信验证码失败：响应里没有 captcha_key"
                    + "（前 120 字：" + brief(mask(response.getBody())) + "）", "响应缺 captcha_key");
        }
        log.info("短信验证码已发送（tel={}，captcha_key={}…，验证码 5 分钟内有效）",
                maskPhone(tel), head(data.getCaptcha_key(), 8));
        return data;
    }

    /**
     * <b>短信验证码登录</b>（默认中国大陆 86 冠字码）。
     *
     * @param tel        手机号
     * @param code       用户收到的短信验证码
     * @param captchaKey {@link #sendSmsCode} 返回的 {@code captcha_key}
     * @return 登录凭据
     * @throws BilibiliException 同上，以及 {@code 1006} 验证码错、{@code 1007} 验证码过期
     */
    public LoginCredential smsLogin(String tel, String code, String captchaKey) {
        return smsLogin(tel, BilibiliEndpoint.passportCidChina, code, captchaKey);
    }

    /**
     * <b>短信验证码登录</b>。
     *
     * <p>本步<b>不需要极验</b> —— 三条链路里人工门槛最低的一步，也是密码登录被风控
     * 要求二次验证时的兜底路径。
     *
     * @param tel        手机号
     * @param cid        国际冠字码
     * @param code       短信验证码
     * @param captchaKey {@link #sendSmsCode} 返回的 {@code captcha_key}
     * @return 登录凭据，不可为 null
     * @throws BilibiliException 参数为空、网络失败、或服务端拒绝
     */
    public LoginCredential smsLogin(String tel, String cid, String code, String captchaKey) {
        if (isBlank(tel) || isBlank(code)) {
            throw new BilibiliException("短信登录失败：手机号与验证码都不能为空");
        }
        if (isBlank(captchaKey)) {
            throw new BilibiliException("短信登录失败：缺少 captcha_key（请先调 sendSmsCode）");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("cid", isBlank(cid) ? BilibiliEndpoint.passportCidChina : cid);
        form.put("tel", tel);
        form.put("code", code);
        form.put("source", BilibiliEndpoint.passportLoginSource);
        form.put("captcha_key", captchaKey);

        HttpResponse<String> response = BilibiliHttp.postForm(
                BilibiliEndpoint.passportSmsLoginUrl, form,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.passportReferer);
        trace("短信验证码登录", response);

        return credentialFrom(response, "短信验证码登录");
    }

    /**
     * 把极验四件套写进表单。
     *
     * <p>{@code challenge} 的取值优先用调用方显式给的（JS 过验后回显的那个，实测更可靠），
     * 没有则用 {@link #requestCaptcha()} 申请到的原始值（官方文档口径）。
     *
     * @param form    目标表单
     * @param captcha 申请到的验证码信息
     * @param gee     过验结果
     */
    private static void putCaptchaParams(Map<String, String> form, LoginCaptcha captcha,
                                        GeeTestValidation gee) {
        form.put("token", captcha.getToken());
        form.put("challenge", gee.hasChallenge()
                ? gee.getChallenge()
                : captcha.getGeetest().getChallenge());
        form.put("validate", gee.getValidate());
        form.put("seccode", gee.getSeccode());
    }

    /**
     * 按 B 站口径加密密码：{@code base64(RSA_PKCS1(hash + 明文))}。
     *
     * <p><b>盐拼在明文前面</b>、与明文一并加密（不是分别加密再拼）；
     * 输出 <b>base64</b> 而非 hex。填充方式 {@code RSA/ECB/PKCS1Padding}，
     * 与官方示例里 Python 的 {@code rsa.encrypt}（PKCS#1 v1.5）一致。
     *
     * <p>用 JDK 自带 JCE，<b>零新增依赖</b>。
     *
     * @param hash     {@code RsaKeyInfo.hash}（16 字符盐）
     * @param pem      {@code RsaKeyInfo.key}（PEM 格式公钥）
     * @param password 明文密码
     * @return base64 密文
     * @throws BilibiliException 公钥无法解析、或明文超长（RSA 单块上限 = 密钥字节数 - 11）
     */
    static String encryptPassword(String hash, String pem, String password) {
        try {
            PublicKey publicKey = parsePublicKey(pem);
            int keyBytes = ((RSAPublicKey) publicKey).getModulus().bitLength() / 8;
            byte[] plain = (hash + password).getBytes(StandardCharsets.UTF_8);
            int maxPlain = keyBytes - 11;
            if (plain.length > maxPlain) {
                throw new BilibiliException("加密登录密码失败：RSA " + (keyBytes * 8)
                        + " 位单块最多加密 " + maxPlain + " 字节（含 " + hash.length() + " 字符盐），"
                        + "当前 " + plain.length + " 字节");
            }
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain));
        } catch (BilibiliException e) {
            throw e;
        } catch (Exception e) {
            // 不把底层异常全文拼进消息（与库内既有约定一致），但保留类型与原因
            throw new BilibiliException("加密登录密码失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "（" + brief(e.getMessage(), 80) + "）"));
        }
    }

    /**
     * 解析 PEM 格式的 RSA 公钥（{@code -----BEGIN PUBLIC KEY-----} … {@code -----END PUBLIC KEY-----}）。
     *
     * <p>PEM 里的换行是 {@code \n} 字面量，必须先全部去掉再 base64 解码；
     * 解出来的是 X.509 {@code SubjectPublicKeyInfo}，所以用 {@link X509EncodedKeySpec}。
     *
     * @param pem PEM 公钥
     * @return 公钥
     * @throws Exception 格式非法（由调用方统一包装）
     */
    private static PublicKey parsePublicKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    // ------------------------------------------------------------------ 凭据解析

    /**
     * 从"已确认登录"的响应里取凭据：{@code Set-Cookie} → {@code data.url} → 响应原文。
     *
     * <p><b>为什么 {@code Set-Cookie} 是主来源</b>（2026-09-16 真机扫码实测，官方文档已过时）：
     * web 端扫码成功后 {@code data.url} 只是
     * <pre>
     * <a href="https://passport.biligame.com/x/passport-login/web/crossDomain?ticket=">...</a>…&amp;gourl=…&amp;first_domain=.bilibili.com
     * </pre>
     * ——<b>只有一枚 ticket，不含任何凭据</b>；四项 Cookie 走 {@code Set-Cookie} 响应头下发。
     * 沿用老文档"从 {@code data.url} 的 query 里取凭据"的写法会<b>必然失败，且失败得毫无痕迹</b>
     * （HTTP 200、无异常、日志空白），这正是"扫了码却提示登录失败"的成因。
     *
     * <p>{@code data.url} 保留为兼容来源：旧格式与游戏站跨域会把凭据直接写在 query 里。
     *
     * @return 凭据，不可为 null
     * @throws BilibiliException 三个来源都没有可用的 SESSDATA
     */
    private LoginCredential credentialOf(QrCodePoll data, String body, Map<String, String> cookies) {
        return extractCredential(body, cookies, data.getUrl(),
                data.getRefresh_token(), data.getTimestamp(), "扫码登录", null);
    }

    /**
     * 从"提交登录"的响应里取凭据 —— 密码登录与短信登录共用。
     *
     * <p>与扫码链路的差别只有两点：错误码在<b>根对象 {@code code}</b> 上（扫码那些状态码在
     * {@code data.code} 上），以及 {@code data.message} 可能是
     * "本次登录环境存在风险…"这类<b>风控提示</b> —— 它不是网络错误，
     * 要原样带进异常，否则调用方只会看到"没拿到 SESSDATA"而不知道被要求二次验证。
     *
     * @param response 登录接口的原始响应
     * @param action   正在做的事（拼日志与异常文案）
     * @return 登录凭据，不可为 null
     * @throws BilibiliException 根业务码非 0、HTTP 非 2xx 且无凭据、或响应里确实没有凭据
     */
    private LoginCredential credentialFrom(HttpResponse<String> response, String action) {
        String body = response.getBody();
        Map<String, String> cookies = setCookiesOf(response);
        ApiResponse<LoginSubmitData> parsed =
                parseOrNull(body, new TypeReference<>() {
                });
        if (parsed != null && parsed.getCode() != 0) {
            // 登录类错误码全部收进了 ErrorMapper.NO_RETRY，异常里会带"不可重试"提示 ——
            // 这是有意的：用同一份已失效的 validate 或错误的密码自动重试，形态就是撞库。
            throw ErrorMapper.toException(parsed.getCode(),
                    firstNonBlank(parsed.getMessage(), parsed.getMsg()), action);
        }

        LoginSubmitData data = parsed == null ? null : parsed.getData();
        String serverHint = null;
        if (data == null) {
            // 形状陌生：HTTP 层语义（412 = 风控）比"形状不符"更有用
            BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), action);
            if (httpError != null) {
                throw httpError;
            }
        } else {
            if (data.getStatus() != null && data.getStatus() != 0) {
                // 文档把 status 标注为"成功时 0"且未给取值表，所以只告警不判死 ——
                // 真正的判据是"有没有拿到 SESSDATA"，避免误信单一字段（扫码链路栽过一次）。
                // 🆕 2026-09-16 实测（本机真机密码登录，两次独立运行复现）：status=2 伴随
                //    message="本次登录环境存在风险, 需使用手机号进行验证或绑定"、url 指向
                //    h5-app/passport/risk/verify，响应里无任何 SESSDATA ⇒ 2 = 服务端要求风控二次验证。
                //    它不代表请求有问题：同一次请求里 RSA 加密密码与极验四件套都已被接受（code=0）。
                log.warn("{}响应的 data.status={}（非 0；实测 2=需风控二次验证），本次仍以'是否拿到 SESSDATA'为准",
                        action, data.getStatus());
            }
            if (!isBlank(data.getMessage())) {
                serverHint = data.getMessage();
                log.warn("{}响应带服务端提示：{}", action, serverHint);
            }
        }

        return extractCredential(body, cookies,
                data == null ? null : data.getUrl(),
                data == null ? null : data.getRefresh_token(),
                (data == null || data.getTimestamp() == null) ? 0L : data.getTimestamp(),
                action, serverHint);
    }

    /**
     * 三级回退取凭据：{@code Set-Cookie}（主）→ {@code data.url}（兼容旧格式）→ 响应原文（兜底）。
     *
     * <p>扫码、密码、短信三条链路共用这一份 —— 三者的凭据下发方式完全一致
     * （都是 {@code Set-Cookie}，{@code data.url} 只是"有时带、有时不带"的兼容来源）。
     *
     * @param body         响应原文（兜底用）
     * @param cookies      响应里的 {@code Set-Cookie}
     * @param dataUrl      {@code data.url}，可为 null
     * @param refreshToken {@code data.refresh_token}，可为 null
     * @param timestamp    登录时间（毫秒），为 0 时取本机时间
     * @param action       正在做的事（拼异常文案）
     * @param serverHint   服务端原话（如风控提示），会带进异常消息，可为 null
     * @return 凭据，不可为 null
     * @throws BilibiliException 三个来源都没有可用的 SESSDATA
     */
    private LoginCredential extractCredential(String body, Map<String, String> cookies, String dataUrl,
                                              String refreshToken, long timestamp, String action,
                                              String serverHint) {
        LoginCredential credential = credentialFromCookies(cookies, refreshToken, timestamp);
        if (credential != null) {
            return credential;
        }
        if (!isBlank(parseQuery(dataUrl).get("SESSDATA"))) {
            return parseCredential(dataUrl, refreshToken, timestamp);
        }
        credential = credentialFromBody(body, refreshToken, timestamp);
        if (credential != null) {
            return credential;
        }
        throw new BilibiliException(0, action + "失败：服务端没有下发 SESSDATA"
                + "（data.url=" + brief(mask(dataUrl))
                + "，Set-Cookie 键=[" + String.join(",", cookies.keySet()) + "]"
                + (isBlank(serverHint) ? "" : "，服务端提示：" + serverHint) + "）",
                isBlank(serverHint) ? "响应里没有凭据" : serverHint);
    }

    /**
     * 兜底取凭据：{@code Set-Cookie} → 响应原文。
     *
     * <p>用在"响应压根不是预期外壳"的分支上（见 {@link #interpret}）：那时没有 {@code data} 可读，
     * 但响应里若确实带着 SESSDATA，它仍然是"登录成功"的证据 —— 形状不同不代表失败。
     *
     * @param body         响应原文
     * @param cookies      响应里的 {@code Set-Cookie}
     * @param refreshToken {@code data.refresh_token}，可能为 null
     * @param timestamp    {@code data.timestamp}，为 0 时取本机时间
     * @return 凭据；两个来源都没挖到合法值时返回 {@code null}
     */
    private LoginCredential salvage(String body, Map<String, String> cookies,
                                    String refreshToken, long timestamp) {
        LoginCredential credential = credentialFromCookies(cookies, refreshToken, timestamp);
        return credential != null ? credential : credentialFromBody(body, refreshToken, timestamp);
    }

    /**
     * 从 {@code Set-Cookie} 取凭据（web 端扫码的<b>主来源</b>）。
     *
     * <p>读到的是<b>原始响应头</b> —— 本库关掉的是 Unirest 的 cookie <i>罐</i>（它会回放 Cookie
     * 并与显式拼装的头叠加，实测能稳定诱发 412），而不是"不读响应头"，两者互不影响。
     *
     * @param cookies      响应里的 {@code Set-Cookie}（键 → 值）
     * @param refreshToken 刷新令牌
     * @param timestamp    登录时间
     * @return 凭据；没带 SESSDATA 或形状不可信时返回 {@code null}
     */
    private LoginCredential credentialFromCookies(Map<String, String> cookies,
                                                 String refreshToken, long timestamp) {
        if (!looksLikeSessdata(normalize(cookies.get("SESSDATA")))) {
            return null;
        }
        return assemble(cookies, refreshToken, timestamp, "Set-Cookie");
    }

    /**
     * 从响应原文里挖凭据（最后的兜底：响应是 HTML、或形状陌生的 JSON 时用）。
     *
     * <p>必须过形状自检（见 {@link #looksLikeSessdata}）—— 兜底本来就是模糊匹配，
     * 宁可放弃，也不能塞进一枚被截断的残值：那样服务端会拒收，而调用方以为登录成功。
     *
     * @param body         响应原文
     * @param refreshToken 刷新令牌
     * @param timestamp    登录时间
     * @return 凭据；原文里没有合法 SESSDATA 时返回 {@code null}
     */
    private LoginCredential credentialFromBody(String body, String refreshToken, long timestamp) {
        Map<String, String> fromBody = scanParams(body);
        if (!looksLikeSessdata(normalize(fromBody.get("SESSDATA")))) {
            return null;
        }
        log.warn("常规凭据来源（Set-Cookie / data.url）里都没有 SESSDATA，回退到响应原文取凭据");
        return assemble(fromBody, refreshToken, timestamp, "响应原文");
    }

    /**
     * 从轮询响应的<b>跨域登录地址</b>里解出凭据。
     *
     * <p><b>为什么优先解析 URL 而不是读 {@code Set-Cookie}</b>：Unirest 的 cookie 罐在本库被
     * 刻意关掉了（它会把 Cookie 头和显式拼装的那串叠加成同名重复键，实测能稳定诱发 412，
     * 见 {@code BilibiliHttp} 静态块），所以取凭据的主路径是 {@code data.url} ——
     * B 站把同一批 Cookie 值（URL 编码后）也放进了这个跳转地址。
     * （{@code Set-Cookie} 仍可从原始响应头读到，作为 {@link #salvage} 的兜底来源。）
     *
     * <p><b>必须 URL 解码</b>：{@code SESSDATA} 形如 {@code a1b2c3d4%2C1690000000%2C7f8e*41}，
     * 其中的 {@code %2C} 是逗号。不解码就把整串塞进 Cookie 头，服务端拿到的是一个
     * 含 {@code %2C} 的假值 —— <b>不报错，只是所有需要登录的接口继续按匿名处理</b>，
     * 是这一块最容易"看起来对了但根本没用"的陷阱。
     *
     * @param crossDomainUrl {@code data.url}
     * @param refreshToken   {@code data.refresh_token}
     * @param timestamp      {@code data.timestamp}（毫秒），为 0 时取本机当前时间
     * @return 凭据
     * @throws BilibiliException 地址里没有 {@code SESSDATA}（响应为空或格式已变）
     */
    LoginCredential parseCredential(String crossDomainUrl, String refreshToken, long timestamp) {
        Map<String, String> params = parseQuery(crossDomainUrl);
        if (isBlank(params.get("SESSDATA"))) {
            throw new BilibiliException("扫码登录失败：已确认登录，但未能从响应里解析出 SESSDATA"
                    + "（url=" + brief(mask(crossDomainUrl)) + "）");
        }
        return assemble(params, refreshToken, timestamp, "data.url");
    }

    /**
     * 把参数表组装成凭据，并留一行"凭据从哪来的"日志。
     *
     * @param params       参数表（值可能带 percent-encoding）
     * @param refreshToken 刷新令牌，可能为 null
     * @param timestamp    登录时间（毫秒），为 0 时取本机时间
     * @param source       来源标签，只进日志（{@code data.url} / {@code Set-Cookie} / 响应原文）
     * @return 凭据
     */
    private LoginCredential assemble(Map<String, String> params, String refreshToken,
                                     long timestamp, String source) {
        LoginCredential credential = new LoginCredential();
        credential.setDedeUserId(normalize(params.get("DedeUserID")));
        credential.setDedeUserIdCkMd5(normalize(params.get("DedeUserID__ckMd5")));
        credential.setSessdata(normalize(params.get("SESSDATA")));
        credential.setBiliJct(normalize(params.get("bili_jct")));
        credential.setExpiresAt(parseExpiry(normalize(params.get("Expires"))));
        credential.setRefreshToken(refreshToken);
        credential.setLoginTime(timestamp > 0L ? timestamp : System.currentTimeMillis());
        credential.setCookieHeader(buildCookieHeader(credential));
        log.info("已取得登录凭据：DedeUserID={}，Cookie 键=[{}]，Expires={}，来源={}",
                credential.getDedeUserId(), credential.cookieKeys(), credential.getExpiresAt(), source);
        if (!looksLikeSessdata(credential.getSessdata())) {
            // 不在这里拒绝（形状可能随 B 站调整），但必须显眼：半截凭据的表现就是"登录了却仍匿名"
            log.warn("SESSDATA 的形状不像完整凭据（长度 {}）—— 若后续请求被判未登录，先查这里",
                    credential.getSessdata() == null ? 0 : credential.getSessdata().length());
        }
        return credential;
    }

    /**
     * 解析凭据到期时间（秒级 Unix 时间戳）。
     *
     * <p>同一个参数名、<b>两种格式</b>，两个来源各用一种：
     * <ul>
     *   <li>{@code data.url} 的 query 参数 {@code Expires} → Unix 秒（如 {@code 1692594809}）；</li>
     *   <li>{@code Set-Cookie} 的属性 {@code Expires} → <b>HTTP 日期</b>
     *       （如 {@code Thu, 15 Oct 2026 07:08:09 GMT}）。</li>
     * </ul>
     * 两种都要认，否则从 {@code Set-Cookie} 取凭据时到期时间恒为 0
     * （实测踩过：真机拿到的凭据显示"未知"，等于无法判断还剩几天该重扫）。
     *
     * <p>注意 {@code Set-Cookie} 里 {@code Expires} 是"属性"，理论上属于同一响应里最近的那个
     * Cookie；B 站的四项 Cookie 到期时间一致，取到哪个都一样。
     *
     * @param raw 原始值，可为 null
     * @return 秒级时间戳；解析不出时返回 0（含义是"未知"，见 {@code LoginCredential#isExpired()}）
     */
    private static long parseExpiry(String raw) {
        if (isBlank(raw)) {
            return 0L;
        }
        String trimmed = raw.trim();
        long unixSeconds = parseLong(trimmed);
        if (unixSeconds > 0L) {
            return unixSeconds;
        }
        long epoch = parseHttpDate(trimmed);
        if (epoch > 0L) {
            return epoch;
        }
        log.debug("无法解析 Expires（{}），按'未知'处理", brief(trimmed, 40));
        return 0L;
    }

    /**
     * 解析 HTTP 日期（{@code Set-Cookie} 的 {@code Expires} 属性）。
     *
     * <p><b>为什么不用 {@code DateTimeFormatter.RFC_1123_DATE_TIME}</b>
     * （2026-09-16 实测，用 JDK 17 逐例跑过）：它<b>同时踩两个坑</b>，而两个坑真实存在：
     * <ol>
     *   <li><b>星期几与日期不符就抛异常</b>：
     *       {@code "Thu, 15 Oct 2026 07:08:09 GMT"} 能过，把 Thu 换成 Sat/Wed 就
     *       {@code Conflict found: Field DayOfWeek 4 differs from DayOfWeek 6} ——
     *       服务端偶尔会下发星期写错的时间，我们没有任何理由去校验它；</li>
     *   <li><b>不认 {@code d-MMM-yyyy} 与单位数日期</b>：官方抓包里就是
     *       {@code Sat, 18-Jul-2020 09:57:57 GMT}，RFC 严格版直接
     *       {@code could not be parsed at index 7}。</li>
     * </ol>
     * 两者都表现为"静默返回 0（未知）"，而 0 与"没解析出来"在调用方看来一模一样 ——
     * 这正是真机上凭据到期时间显示"未知"的原因。
     *
     * <p>因此这里改成：<b>先归一化，再按固定模式解析</b> ——
     * 丢掉星期几、把 {@code -} 统一成空格、折叠空白，最后处理时区后缀
     * （{@code GMT}/{@code UTC}/{@code UT} 或 {@code ±HHMM}；缺失则按 UTC，HTTP 日期规范就是 GMT）。
     *
     * @param raw HTTP 日期串
     * @return 秒级时间戳；解析失败返回 0
     */
    private static long parseHttpDate(String raw) {
        String text = raw
                // 星期几一律丢掉：它可能写错，而它对"到什么时候"没有任何信息量
                .replaceAll("^[A-Za-z]{3,}\\s*,\\s*", "")
                // 18-Jul-2020 与 18 Jul 2020 统一成后者
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        ZoneOffset offset = ZoneOffset.UTC;
        Matcher zone = TRAILING_ZONE.matcher(text);
        if (zone.find()) {
            String token = zone.group(1);
            text = text.substring(0, zone.start()).trim();
            if (!"GMT".equalsIgnoreCase(token) && !"UTC".equalsIgnoreCase(token)
                    && !"UT".equalsIgnoreCase(token)) {
                String digits = token.replace(":", "").replace("+", "").replace("-", "");
                int seconds = Integer.parseInt(digits.substring(0, 2)) * 3600
                        + Integer.parseInt(digits.substring(2)) * 60;
                offset = ZoneOffset.ofTotalSeconds(token.startsWith("-") ? -seconds : seconds);
            }
        }
        try {
            return LocalDateTime.parse(text, HTTP_DATE).toEpochSecond(offset);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 把跨域地址的 query 解析成 map（键值都做 URL 解码，{@code gourl} 一并保留但后续不使用）。
     *
     * @param url 形如 {@code https://passport.biligame.com/crossDomain?a=1&b=2}
     * @return 保持出现顺序的参数表；无 query 时为空表
     */
    private static Map<String, String> parseQuery(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        if (url == null || url.isEmpty()) {
            return params;
        }
        int question = url.indexOf('?');
        if (question < 0) {
            return params;
        }
        for (String pair : url.substring(question + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    /**
     * 从任意文本里挖凭据参数（兜底用）。
     *
     * <p>同一个键只取第一次出现的值：跨域地址在响应里可能出现多次，取第一次就够，
     * 而且顺序稳定、可复现。
     *
     * @param text 响应原文，可为 null
     * @return 挖到的参数表；挖不到时为空表
     */
    private static Map<String, String> scanParams(String text) {
        Map<String, String> params = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return params;
        }
        Matcher matcher = CREDENTIAL_PARAM.matcher(text);
        while (matcher.find()) {
            params.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return params;
    }

    /**
     * 取响应里的 {@code Set-Cookie}（原始响应头，与 Unirest 的 cookie 罐开关无关）。
     *
     * <p>只收凭据键，并顺手处理"多个 {@code Set-Cookie} 被并成一行"的情形
     * （那时最后一个逗号之后才是本头的 Cookie 键值对）。
     *
     * @param response 轮询响应
     * @return 键 → 值（保持出现顺序）；无 {@code Set-Cookie} 时为空表
     */
    private static Map<String, String> setCookiesOf(HttpResponse<String> response) {
        Map<String, String> cookies = new LinkedHashMap<>();
        try {
            List<String> values = response.getHeaders().get("Set-Cookie");
            if (values == null || values.isEmpty()) {
                String first = response.getHeaders().getFirst("Set-Cookie");
                values = first == null ? List.of() : List.of(first);
            }
            for (String value : values) {
                collectCookies(cookies, value);
            }
        } catch (Exception e) {
            // 读不到响应头只影响兜底路径，不影响主流程（data.url）
            log.debug("读取 Set-Cookie 失败，兜底取凭据不可用：{}", e.toString());
        }
        return cookies;
    }

    /** 按 {@code ;} 切一个 {@code Set-Cookie} 头 —— 因此 {@code Expires} 属性里的逗号不会被误切 */
    private static void collectCookies(Map<String, String> target, String header) {
        if (header == null || header.isBlank()) {
            return;
        }
        for (String fragment : header.split(";")) {
            String pair = fragment.trim();
            putCredentialPair(target, pair);
            int comma = pair.lastIndexOf(',');
            if (comma >= 0) {
                putCredentialPair(target, pair.substring(comma + 1).trim());
            }
        }
    }

    /** 只收凭据键：{@code Path} / {@code Domain} / {@code HttpOnly} 等属性一律不要 */
    private static void putCredentialPair(Map<String, String> target, String pair) {
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = pair.substring(0, eq).trim();
        if (!CREDENTIAL_KEYS.contains(key)) {
            return;
        }
        target.putIfAbsent(key, pair.substring(eq + 1).trim());
    }

    /** 组装 Cookie 头。顺序对齐 B 站真实的四项下发顺序，便于与浏览器里的值逐项比对 */
    private static String buildCookieHeader(LoginCredential credential) {
        StringBuilder header = new StringBuilder();
        appendCookie(header, "DedeUserID", credential.getDedeUserId());
        appendCookie(header, "DedeUserID__ckMd5", credential.getDedeUserIdCkMd5());
        appendCookie(header, "SESSDATA", credential.getSessdata());
        appendCookie(header, "bili_jct", credential.getBiliJct());
        return header.toString();
    }

    private static void appendCookie(StringBuilder header, String key, String value) {
        if (isBlank(value)) {
            return;
        }
        if (!header.isEmpty()) {
            header.append("; ");
        }
        header.append(key).append('=').append(value);
    }

    // ------------------------------------------------------------------ 解析与工具

    private static ApiResponse<QrCodeLogin> parseQrLogin(HttpResponse<String> response) {
        try {
            return JSON.parseObject(response.getBody(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new BilibiliException("申请登录二维码失败：响应无法解析（前 120 字：" + brief(response.getBody()) + "）");
        }
    }

    /**
     * 反序列化响应的外壳，<b>解析不了返回 null 而不是抛异常</b>。
     *
     * <p>这里刻意宽容：body 为空、是 HTML 风控页、或形状陌生时都返回 null，
     * 交给调用方去判断"这到底算失败，还是'形状不同但凭据其实在'"。
     * 直接抛会把后者误判成失败 —— 而那正是"扫了码却登录不了"的形态之一。
     *
     * @param body 响应原文，可为 null
     * @param type 目标类型（fastjson 的 {@code TypeReference}，泛型信息靠它保留）
     * @return 解析出的外壳；不是 JSON 对象时返回 {@code null}
     */
    private static <T> ApiResponse<T> parseOrNull(String body, TypeReference<ApiResponse<T>> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(body, type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 断言"这次调用确实成功"：外壳可解析且根业务码为 0。
     *
     * <p>与 {@link #parseOrNull} 的分工：先宽容地解析，再在这里明确地报错 ——
     * 报错时一定带上 HTTP 状态、业务码与服务端原话，因为这一块的失败几乎都是
     * "形状/参数"问题，没有原文就只能靠猜（2026-09-16 排障教训）。
     *
     * @param parsed   解析结果，可为 null（形状不符）
     * @param response 原始响应（提供 HTTP 状态与原文）
     * @param action   正在做的事
     * @throws BilibiliException 形状不符（非 2xx 时报 HTTP 语义）或业务码非 0
     */
    private static void requireSuccess(ApiResponse<?> parsed, HttpResponse<String> response, String action) {
        if (parsed != null && parsed.getCode() != 0) {
            throw ErrorMapper.toException(parsed.getCode(),
                    firstNonBlank(parsed.getMessage(), parsed.getMsg()), action);
        }
        if (parsed == null) {
            BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), action);
            if (httpError != null) {
                throw httpError;
            }
            throw new BilibiliException(0, action + "失败：HTTP " + response.getStatus()
                    + " 的响应无法解析（前 120 字：" + brief(mask(response.getBody())) + "）",
                    "响应形状不符");
        }
    }

    /**
     * 把一次出站的原始响应留痕（HTTP 状态 + {@code Set-Cookie} <b>键名</b> + 打码后的 body）。
     *
     * <p><b>为什么值得每个接口都打这一行</b>：登录这一块的失败几乎全是静默的 ——
     * 2016 年那次真机扫码失败，日志里连"服务端回了什么"都没有，
     * 只能靠猜是风控、是参数、还是形状变了。这一行把三者一次性区分开：
     * <ul>
     *   <li>{@code HTTP 412} → 出口风控，与凭据/参数无关；</li>
     *   <li>{@code code} 非 0 → 服务端明确拒绝，看业务码；</li>
     *   <li>{@code Set-Cookie 键=[]} → 没下发凭据，问题在服务端判定而非解析；</li>
     *   <li>形状陌生 → 对照 body 就能看出换了什么。</li>
     * </ul>
     * 打的是 DEBUG 级别（正常流程不刷屏），且凭据值一律先打码（见 {@link #mask}）。
     *
     * @param action   正在做的事
     * @param response 原始响应
     */
    private static void trace(String action, HttpResponse<String> response) {
        log.debug("{}响应：HTTP {}，Set-Cookie 键=[{}]，body={}",
                action, response.getStatus(), String.join(",", setCookiesOf(response).keySet()),
                brief(mask(response.getBody()), 300));
    }

    /** 手机号打码：保留前 3 后 2 位，中间用 {@code *} 顶替（日志里不该出现完整手机号） */
    private static String maskPhone(String tel) {
        if (tel == null || tel.length() < 6) {
            return "***";
        }
        return tel.substring(0, 3) + "****" + tel.substring(tel.length() - 2);
    }

    /** 组装一个"登录成功"的结果（兜底路径用，{@code rawCode} 记 0） */
    private static QrLoginStatus successStatus(LoginCredential credential, String message) {
        QrLoginStatus status = new QrLoginStatus();
        status.setState(QrLoginState.SUCCESS);
        status.setRawCode(0);
        status.setMessage(message);
        status.setCredential(credential);
        return status;
    }

    /**
     * 把凭据值还原成规范形态：<b>只有含 {@code %} 时才做 URL 解码</b>。
     *
     * <p>刻意不用"无条件 decode"：值可能本来就是明文（{@code Set-Cookie} 里就常是明文），
     * 而无谓的解码会把合法的 {@code +} 变成空格。
     *
     * @param raw 原始值，可为 null
     * @return 规范值；{@code raw} 为 null 时返回 null
     */
    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('%') < 0) {
            return raw;
        }
        return decode(raw);
    }

    /**
     * SESSDATA 的形状自检：只要"长度够 + 含逗号 + 含 {@code *} 校验位"。
     *
     * <p>判据刻意宽松（B 站的格式可能调整），它要拦的不是"格式变了"，而是
     * <b>被截断的半截值</b> —— 兜底路径靠正则挖值，不设闸门就可能把半截串当凭据发出去，
     * 而半截串的表现是"服务端静默拒收、调用方以为登录成功"。
     *
     * @param value 规范化后的值，可为 null
     * @return true 表示形状足以当真
     */
    private static boolean looksLikeSessdata(String value) {
        return value != null && value.length() >= 20
                && value.indexOf(',') > 0 && value.indexOf('*') > 0;
    }

    /**
     * 打码：凭据值一律不出。日志与异常消息都必须先过这里。
     *
     * <p>覆盖两种形态：{@code key=value}（URL / Cookie 形态）与 {@code "key":"value"}（JSON 形态）。
     * 只打码 4 字符以上的值 —— 短值不可能是凭据，全打掉反而看不清结构。
     *
     * @param text 原始文本，可为 null
     * @return 打码后的文本；输入为 null 时返回空串
     */
    private static String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = text;
        for (String key : SECRET_KEYS) {
            masked = masked.replaceAll("(" + key + "=)([^&\"'\\s<;]{4,})", "$1<打码>");
            masked = masked.replaceAll("(\"" + key + "\"\\s*:\\s*\")([^\"]{4,})", "$1<打码>");
        }
        return masked;
    }

    private static String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    /**
     * URL 解码，失败则原样返回。
     *
     * <p>刻意"宽容"：一个畸形参数不该让整串凭据作废 —— 宁可让服务端去拒，
     * 也好过在这里抛异常把凭据直接丢掉。
     */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static long parseLong(String raw) {
        if (isBlank(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 截断长文本，避免把完整凭据或整页 HTML 写进日志/异常 */
    private static String brief(String text) {
        return brief(text, 120);
    }

    private static String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 只留前 {@code count} 个字符（用于打印 {@code qrcode_key} 这类"够定位、不足以复现"的标识） */
    private static String head(String text, int count) {
        if (text == null) {
            return "";
        }
        return text.length() <= count ? text : text.substring(0, count);
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BilibiliException("扫码登录被中断");
        }
    }
}

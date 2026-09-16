package com.esdllm.bilibiliApi.endpoint;

/**
 * 端点（endpoint）常量集中点：所有外网 URL、UA、Referer、Accept 等请求相关常量只此一份。
 *
 * <p>v3 起承担"URL 单一来源"职责，{@link com.esdllm.bilibiliApi.config.BilibiliConfig} 保留为
 * {@code @Deprecated} 转调以保证向后兼容（任何历史依赖此类的代码无需改动即可继续工作）。
 *
 * <p><b>红线</b>：本类的常量名与 {@code BilibiliConfig} <b>逐字一致</b>，否则两个类的兼容性
 * 都会破。允许后续整段改动，但改名必须先 grep 全库 + 通知 XatiiBot 维护者（实际未引用，见 §9）。
 *
 * @author 饿死的流浪猫
 */
public class BilibiliEndpoint {

    /**
     * 默认 User-Agent。
     *
     * <p>注意：实测结论是动态类端点的风控<b>主要看设备指纹 buvid3，不看 UA</b>。
     * 这个常量现在是 {@code UserAgentPool} 的第 0 个元素（即"不轮换时的默认面孔"），
     * 保留原样以保证改造前后行为一致。
     */
    public static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    public static final String accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";

    /**
     * JSON 接口用的 {@code Accept}。
     *
     * <p><b>为什么需要单独一份</b>（2026-09-13 真机 412 排查）：上面那份 {@link #accept} 是
     * <b>文档型</b>的（{@code text/html,...}），它是"浏览器打开一个页面"时才发的形状；
     * 而 B 站 web 前端请求 {@code /x/...} 这类 JSON 接口时发的是
     * {@code application/json, text/plain, *\/\*}。
     *
     * <p>把文档型 Accept 发到 JSON 接口上，属于"与真实客户端形状不一致"的请求 ——
     * 在高信誉的住宅 IP 上通常无碍，但在低信誉出口（机房 IP）上就是 WAF 的加分项。
     * 这类差异不会报错，只会让请求看起来"不像浏览器发的"。
     */
    public static final String jsonAccept = "application/json, text/plain, */*";

    /**
     * 统一 Referer。
     *
     * <p>缺失时部分接口会额外收紧风控（实测），因此所有出站请求都带上。
     * 取站根而非具体页面：具体页面路径对 API 请求反而不自然。
     *
     * <p>例外见 {@link #spaceDynamicReferer}：动态 feed 属于"空间页里发起的请求"，
     * 真正的前端会带具体空间页地址。
     */
    public static final String referer = "https://www.bilibili.com/";

    /**
     * 动态 feed 专用的 Referer 模板（{@code %s} = uid）。
     *
     * <p>B 站空间页请求动态列表时，Referer 是当前空间页
     * （{@code https://space.bilibili.com/<uid>/dynamic}），而不是站根。
     * 实测（python 对照）用这一组合 + {@link #jsonAccept} 请求
     * {@code v1/feed/space} 能稳定拿到 {@code code=0}。
     */
    public static final String spaceDynamicReferer = "https://space.bilibili.com/%s/dynamic";

    /** {@code x/web-interface/view?bvid=} —— 视频投稿主查（按 bvid）。 */
    public static final String videoBaseUrl = "https://api.bilibili.com/x/web-interface/view?bvid=";

    /** {@code x/web-interface/view?aid=} —— 视频投稿主查（按 aid）。 */
    public static final String videoAvBaseUrl = "https://api.bilibili.com/x/web-interface/view?aid=";

    /** {@code x/web-interface/card?mid=} —— 用户名片主查。 */
    public static final String cardBaseUrl = "https://api.bilibili.com/x/web-interface/card?mid=";

    /**
     * {@code x/web-interface/nav} —— <b>查询当前登录态</b>（数据域，判据端点）。
     *
     * <p>GET，无参数、<b>不需要 WBI 签名</b>。已登录返回：
     * <pre>
     * {@code {"code":0,"data":{"isLogin":true,"mid":497078180,"uname":"…", …}}}
     * </pre>
     * 未登录返回 <b>{@code code=-101}</b>（{@code message="账号未登录"}）—— 注意这是
     * <b>正常结果而非异常</b>：它的含义是"这枚凭据没登录"，正是调用方要问的问题本身。
     *
     * <p>🔴 <b>两个细节，都很容易踩</b>：
     * <ol>
     *   <li>判据是 <b>{@code data.isLogin}</b>（布尔）或外层 {@code code}（0 / -101），
     *       <b>不是 HTTP 状态</b> —— 未登录时 HTTP 同样是 200，只看状态码会永远得到"成功"。</li>
     *   <li>未登录时 <b>{@code data.wbi_img} 照样有值</b>（img/sub key 与登录态无关），
     *       所以 Web 端做 WBI 签名时<b>不能靠 {@code code} 判断能否取 key</b>，要判 {@code wbi_img} 是否存在。</li>
     * </ol>
     *
     * <p>⚠️ 响应里的 {@code data.money}（B 币余额）等字段属于账户隐私，本库只取
     * {@code isLogin} / {@code mid} / {@code uname} 三项，其余一概不落日志。
     */
    public static final String navUrl = "https://api.bilibili.com/x/web-interface/nav";

    /**
     * 动态详情地址（v1 起复用）。
     *
     * <p><b>参数名必须是 {@code id}</b> —— 传 {@code rid} 或 {@code dynamic_id} 都会返回
     * {@code code:4101139}，该错误码文案是"请求数据发生错误"，极易被误判成"需要登录"。
     *
     * <p>该端点实测<b>匿名可用</b>（无需 Cookie），返回 LEGACY schema，
     * 由 {@code adapter.DynamicSchemaAdapter} 转成冻结模型。
     */
    public static final String dynamicDetailUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=";

    /**
     * opus 图文详情地址（动态长图渲染专用）。
     *
     * <p>与 {@link #dynamicDetailUrl} 的区别（均为实测结论）：
     * <ul>
     *   <li>{@code v1/detail} 走 LEGACY schema，<b>对图文类动态返回的 desc 是 null</b>
     *       —— 整条响应里没有正文文本，且图与文分属不同 module、<b>丢失原始先后顺序</b>；</li>
     *   <li>{@code v1/opus/detail} 走 OPUS schema，返回
     *       {@code MODULE_TYPE_CONTENT.module_content.paragraphs}，段落自带
     *       {@code para_type}（1=文本、2=图片）且<b>顺序即原文顺序</b>，还带 emoji 贴图地址。</li>
     * </ul>
     * 因此"取数据"用 {@code v1/detail}，"画长图"用本端点。
     *
     * <p>覆盖范围有限：视频动态与转发动态在本端点返回空 {@code modules}（实测），由
     * {@code render.RenderModelLoader} 自动回退 {@link #dynamicDetailUrl}。
     */
    public static final String opusDetailUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id=";

    /** {@code live.bilibili.com/room/v1/Room/get_info?room_id=} —— 直播间主查。 */
    public static final String liveBaseUrl = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=";

    /**
     * <b>桌面端动态空间 feed</b>（{@code getDynamicInfoList} 专用）。
     *
     * <p>{@code host_mid} 形参与 {@code features} 都是<b>必填</b>：
     * <ul>
     *   <li>{@code features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote} —
     *       <b>缺了这条 {@code features} 时响应里的 {@code items} 永远是空数组</b>，
     *       接口本身返回 200 但拿不到任何数据（极易被误判为接口失效）。</li>
     *   <li>{@code platform=web} + {@code build=735002902680334849} 是桌面端默认 UA 习惯，
     *       跟移动端拿到的不一样（移动端不放 {@code features} 不就空）。</li>
     *   <li>{@code specials=1} 表示"只要置顶"，不传则拿到按时间倒序的全部。</li>
     * </ul>
     *
     * <p>实测<b>匿名可用</b>（无需 Cookie），但带 {@code buvid3} 更稳。
     */
    public static final String dynamicFeedUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space"
            + "?host_mid=%s&features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote"
            + "&platform=web&build=735002902680334849";

    /**
     * <b>关注流</b>（{@code feed/all}）—— 登录账号所关注 UP 的最新动态，一次请求覆盖全部。
     *
     * <p><b>实测（2026-09-14）它比 {@link #dynamicFeedUrl} 更"耐封"</b>：在被
     * {@code -412 request was banned} 拒掉 feed/space 的同一台机器、同一枚 Cookie 下，
     * 本端点返回 {@code 200 / code=0}（15 万字节真实数据）。
     *
     * <p>参数说明：{@code type=all} 取全部类型（投稿/图文/转发/直播推荐…）；
     * {@code page=1} 只要第一页（约 20 条）；{@code platform=web} + {@code build=…} 与桌面端一致。
     *
     * <p>需要登录 Cookie；且返回里含推荐内容（{@code DYNAMIC_TYPE_LIVE_RCMD} 等），
     * 调用方必须按 {@code module_author.mid} 过滤。
     */
    public static final String followFeedUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all"
            + "?type=all&page=1&platform=web&build=735002902680334849";

    /** 关注流页面地址，用作该端点的 Referer（与真实网页一致）。 */
    public static final String followFeedReferer = "https://t.bilibili.com/";

    // ---------------------------------------------------------------- 登录（passport 域）

    /**
     * {@code x/passport-login/web/qrcode/generate} —— <b>申请登录二维码</b>。
     *
     * <p>GET、<b>无参数、无鉴权</b>，返回二维码内容（一个登录页 URL）与 32 字符的
     * {@code qrcode_key}。密钥<b>有效期 180 秒</b>，超时后必须重新申请（旧 key 再轮询只会拿到 86038）。
     *
     * <p>注意域名是 {@code passport.bilibili.com}，<b>不是</b>数据域 {@code api.bilibili.com} ——
     * 登录域与数据域是两套，拼相对路径时会踩空。
     */
    public static final String passportQrCodeUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate";

    /**
     * {@code x/passport-login/web/qrcode/poll?qrcode_key=} —— <b>轮询扫码状态</b>。
     *
     * <p>⚠️ <b>本端点最大的坑</b>：外层 {@code code} <b>恒为 0</b>（含义是"这个接口调用成功了"），
     * 真正的扫码状态在 {@code data.code} 里（{@code 0} / 86038 / 86090 / 86101）。
     * 只读外层码会把"还没扫码"当成"登录成功"，然后拿着空凭据继续跑 —— 且全程不报错。
     * 判状态一律用 {@code data.code}，见 {@link com.esdllm.bilibiliApi.model.data.pojo.login.QrLoginState}。
     *
     * <p>⚠️ <b>第二个坑（2026-09-16 真机实测，文档已过时）</b>：登录成功后下发的
     * {@code data.url} <b>只有一枚 ticket，不含任何凭据</b>：
     * <pre>
     * <a href="https://passport.biligame.com/x/passport-login/web/crossDomain?ticket=">...</a>…&amp;gourl=…&amp;first_domain=.bilibili.com
     * </pre>
     * 四项 Cookie（{@code SESSDATA} / {@code bili_jct} / {@code DedeUserID} /
     * {@code DedeUserID__ckMd5}）是随响应的 {@code Set-Cookie} <b>响应头</b>下发的。
     * 沿用老文档"从 {@code data.url} 解析凭据"的写法<b>必然失败且毫无痕迹</b>
     * （HTTP 200、无异常、日志一行没有）—— 真机第一次扫码就是栽在这里。
     *
     * <p>本库确实<b>刻意关掉了</b> Unirest 的 cookie 罐（见 {@code BilibiliHttp} 静态块），
     * 但那只关掉"回放"，原始响应头照旧可读。取凭据因此是三级：
     * {@code Set-Cookie}（主）→ {@code data.url}（兼容旧格式）→ 响应原文（兜底），
     * 见 {@code LoginService#credentialOf}。任一来源都要<b>先 URL 解码</b> ——
     * 带 {@code %2C} 的原始串不是可用凭据。
     */
    public static final String passportQrCodePollUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=";

    /**
     * passport 域的 Referer（登录页）。
     *
     * <p>登录类端点若发数据域的站根 Referer（{@link #referer}），与真实浏览器形状不符：
     * 用户是"在登录页扫码"的，Referer 应当是登录页本身。
     */
    public static final String passportReferer = "https://passport.bilibili.com/login";

    /**
     * {@code x/passport-login/captcha?source=main_web} —— <b>申请验证码前置信息</b>。
     *
     * <p>GET、无参数（{@code source} 除外）、无鉴权。密码登录与短信登录都要先过它，
     * 拿到三件套：{@code token}（B 站侧的登录令牌）、{@code geetest.gt}、
     * {@code geetest.challenge}（极验侧参数）。
     *
     * <p>⚠️ <b>返回的不是"一张图"</b>（2026-09-16 实测）：{@code type} 恒为 {@code geetest}，
     * 是<b>极验 v3 全屏版</b>（背景图 + 滑动/点选，且提交时必须带一个本地 JS 生成的 {@code w} 参数
     * —— 操作轨迹 + 浏览器指纹）。因此它<b>无法</b>像图形验证码那样"渲染成图让用户输入"，
     * 只能由调用方在浏览器里用极验官方 JS 过验，再把 {@code validate} / {@code seccode} 回传。
     * 本库因此<b>不含任何浏览器/打码逻辑</b>，只负责"把参数交给你、把你过验的结果送出去"。
     *
     * <p>响应里另有 {@code tencent:{appid:""}} 字段（实测为空）。若哪天 {@code type} 变成腾讯系，
     * 说明验证码换了供应商 —— 届时本库的 {@code GeeTestValidation} 参数会直接被拒，
     * 且异常里会带上原始响应，不会静默失败。
     */
    public static final String passportCaptchaUrl =
            "https://passport.bilibili.com/x/passport-login/captcha?source=main_web";

    /**
     * {@code x/passport-login/web/key} —— <b>取 RSA 公钥与盐</b>（密码登录专用）。
     *
     * <p>GET、无参数、无鉴权。响应 {@code data} 含两项：{@code hash}（16 字符盐，
     * <b>有效期仅 20 秒</b>）与 {@code key}（PEM 格式 RSA 公钥）。
     *
     * <p>密码的加密口径：{@code base64(RSA_PKCS1(hash + 明文密码))} ——
     * 盐拼在明文<b>前面</b>、一并加密，输出 base64（不是 hex）。
     * 因为它与 {@code /login} 之间有 20 秒窗口，两者必须<b>紧挨着</b>调用，
     * 不要在中间插入其它请求或让用户交互。
     */
    public static final String passportWebKeyUrl = "https://passport.bilibili.com/x/passport-login/web/key";

    /**
     * {@code x/passport-login/web/login} —— <b>账号密码登录</b>。
     *
     * <p>POST {@code application/x-www-form-urlencoded}。参数：
     * {@code username}（手机号或邮箱）、{@code password}（上一步的 base64 密文）、
     * {@code keep=0}、{@code source=main_web}，以及极验四件套
     * {@code token} / {@code challenge} / {@code validate} / {@code seccode}。
     *
     * <p><b>凭据同样走 {@code Set-Cookie}</b>（与扫码登录一致，见
     * {@link #passportQrCodePollUrl} 的说明）；{@code data.url} 是游戏分站跨域地址，
     * 有时带凭据、有时只有 ticket，因此取凭据仍是三级回退。
     *
     * <p>⚠️ {@code data.message} 可能是"本次登录环境存在风险, 需使用手机号进行验证或绑定" ——
     * 这是<b>风控要求二次验证</b>，不是网络错误。本库会把它原样带进异常，交由调用方决定是否走短信链路。
     */
    public static final String passportWebLoginUrl = "https://passport.bilibili.com/x/passport-login/web/login";

    /**
     * {@code x/passport-login/web/sms/send} —— <b>发送短信验证码</b>（短信登录第一步）。
     *
     * <p>POST form。参数：{@code cid}（国际冠字码，中国大陆为 {@code 86}）、{@code tel}、
     * {@code source=main_web}，以及极验四件套。
     *
     * <p>响应 {@code data.captcha_key} 是第二步的凭据。<b>两条时效约束</b>：
     * 同一手机号 <b>60 秒</b>内只能发一次（重复发返回 {@code 1003}），
     * 验证码本身 <b>5 分钟</b>过期（过期返回 {@code 1007}）。
     *
     * <p>⚠️ 极验的 {@code validate} 是<b>一次性</b>的：用过一次就失效，再次提交返回 {@code 2406}。
     * 所以"发短信"与"密码登录"<b>不能共用同一份过验结果</b>，必须各过各的。
     */
    public static final String passportSmsSendUrl = "https://passport.bilibili.com/x/passport-login/web/sms/send";

    /**
     * {@code x/passport-login/web/login/sms} —— <b>用短信验证码登录</b>（短信登录第二步）。
     *
     * <p>POST form。参数：{@code cid}、{@code tel}、{@code code}（用户收到的 6 位数字）、
     * {@code source=main_web}、{@code captcha_key}（上一步返回）。
     *
     * <p><b>本步不需要极验</b> —— 人工门槛只有"手机收码"这一个，这也是短信登录相对密码登录
     * 的唯一优势（代价是每次登录都要收码，不可能是无人值守的形态）。
     */
    public static final String passportSmsLoginUrl = "https://passport.bilibili.com/x/passport-login/web/login/sms";

    /**
     * {@code x/passport-login/web/cookie/info} —— <b>查询凭据是否需要刷新</b>。
     *
     * <p>GET，<b>不需要参数</b>（凭据走 Cookie）。响应：
     * <pre>
     * {@code {"code":0,"message":"OK","ttl":1,"data":{"refresh":false,"timestamp":1789537994849}}}
     * </pre>
     * {@code data.refresh} 为 {@code true} 表示服务端认为该凭据<b>该刷新了</b>；
     * {@code data.timestamp} 是刷新流程的输入 —— 现行明文是 {@code "set_" + timestamp}
     * （公开文档写的 {@code "refresh_" + timestamp} 已过期），由
     * {@link com.esdllm.bilibiliApi.model.data.pojo.login.CredentialStatus#getRefreshTimestamp()}
     * 原样透出。未登录时返回 {@code code=-101}（HTTP 仍是 200）。
     *
     * <p>⚠️ <b>路径里必须有 {@code /web/} 这一段</b> —— 2026-09-16 实测：漏掉它
     * （{@code /x/passport-login/cookie/info}）会返回 <b>HTTP 404</b> 的 HTML 错误页，
     * 而不是一个带业务码的 JSON，很容易被误判成"端点已下线"。
     *
     * <p>⚠️ 本端点<b>不返回 {@code refresh_token}</b>，别把它和"刷新"混为一谈：
     * 它只回答"现在要不要刷"。真正换新 Cookie 的
     * {@code x/passport-login/web/cookie/refresh} 另需 {@code refresh_token}，
     * 而该值在 web 端实测为空串（详见 {@code LoginCredential#refreshToken}）。
     */
    public static final String passportCookieInfoUrl =
            "https://passport.bilibili.com/x/passport-login/web/cookie/info";

    /** 登录来源：独立登录页（网页版默认）。{@code main_mini} 是小窗登录，本库不用 */
    public static final String passportLoginSource = "main_web";

    /** 中国大陆国际冠字码。短信登录默认用它，境外号码需另行指定 */
    public static final String passportCidChina = "86";

    // 旧端点：保留为 @Deprecated 常量供历史引用方继续可解析
    /**
     * @deprecated 旧端点所在的 {@code api.vc.bilibili.com/dynamic_svr} 已整站下线
     *         （实测 HTTP 404，带 Cookie 也救不回来）。改用 {@link #dynamicDetailUrl}。
     */
    @Deprecated
    public static final String dynamicBaseUrl = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail?dynamic_id=";

    /** 工具类，禁止实例化。 */
    private BilibiliEndpoint() {
        throw new AssertionError("endpoint constants holder; do not instantiate");
    }
}

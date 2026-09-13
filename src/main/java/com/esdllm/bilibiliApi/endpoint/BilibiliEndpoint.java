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

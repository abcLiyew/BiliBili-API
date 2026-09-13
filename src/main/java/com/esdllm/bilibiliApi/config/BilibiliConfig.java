package com.esdllm.bilibiliApi.config;

/**
 * 请求配置相关信息
 */
public class BilibiliConfig {
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
     * 统一 Referer。
     *
     * <p>缺失时部分接口会额外收紧风控（实测），因此所有出站请求都带上。
     * 取站根而非具体页面：具体页面路径对 API 请求反而不自然。
     */
    public static final String referer = "https://www.bilibili.com/";
    public static final String videoBaseUrl = "https://api.bilibili.com/x/web-interface/view?bvid=";
    public static final String videoAvBaseUrl = "https://api.bilibili.com/x/web-interface/view?aid=";
    public static final String cardBaseUrl = "https://api.bilibili.com/x/web-interface/card?mid=";
    /**
     * 动态详情地址。
     *
     * @deprecated 旧端点所在的 {@code api.vc.bilibili.com/dynamic_svr} 已整站下线
     *         （实测 HTTP 404，带 Cookie 也救不回来）。改用 {@link #dynamicDetailUrl}。
     */
    @Deprecated
    public static final String dynamicBaseUrl = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail?dynamic_id=";
    /**
     * 动态详情地址（现行端点）。
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
     * <p>覆盖范围有限：视频动态与转发动态在本端点返回空 {@code modules}（实测）。
     */
    public static final String opusDetailUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/opus/detail?id=";
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
     * 之所以必须走这条而<b>不是</b> {@code api.vc.bilibili.com/dynamic_svr/space_history}
     * —— 后者既已 404（参见 {@link #dynamicBaseUrl} 的弃用说明）。
     */
    public static final String dynamicFeedUrl = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space"
            + "?host_mid=%s&features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote"
            + "&platform=web&build=735002902680334849";

}

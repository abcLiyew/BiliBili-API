package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson2.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.comment.CommentPage;
import com.esdllm.bilibiliApi.model.data.pojo.comment.EmotePanel;
import com.esdllm.bilibiliApi.model.data.pojo.comment.MainReplyPage;
import com.esdllm.bilibiliApi.model.data.pojo.comment.ReplyCount;
import com.esdllm.bilibiliApi.model.data.pojo.comment.SubReplyPage;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>评论数据服务</b>（{@code Comment} 门面的后端，B1 批 #6）。
 *
 * <p>本批只做"评论列表"这一件事。评论区还有一整族端点（楼中楼的 {@code x/v2/reply/reply}、
 * 表情包的 {@code x/emote/user/panel/web}）—— <b>它们不在本批</b>：楼中楼在 B2，
 * 表情包也在 B2（计划里并入同一个门面）。所以本服务<b>刻意只有一个公开方法</b>。
 *
 * <p>🆕 <b>B2 批（2026-09-22）已补齐上面两项</b>：{@link #getSubReplies} 与
 * {@link #getEmotePanel}。两者的门槛<b>不同</b>，别一起记：
 * <table border="1">
 *   <caption>评论域三个端点的门槛</caption>
 *   <tr><th>端点</th><th>门槛</th><th>实测证据</th></tr>
 *   <tr><td>{@code x/v2/reply}（本类原有）</td><td>匿名可用</td><td>09-22 实测 {@code code=0}</td></tr>
 *   <tr><td>{@code x/v2/reply/reply}</td><td>匿名可用</td><td>09-22 实测 {@code code=0}，{@code replies} 16 条</td></tr>
 *   <tr><td>{@code x/emote/user/panel/web}</td><td>🔴 <b>需凭据</b></td>
 *       <td>匿名 {@code code=0} 但 {@code packages=null}；带凭据 {@code list} × 68</td></tr>
 * </table>
 * ⇒ <b>表情包虽在本类（"评论表情"语义最近），但它不是匿名能力</b> ——
 * 这是本批与"B2 = 匿名中频"这个批次名的一处出入，已记在计划文档里。
 *
 * <p>🔴 <b>参数是 {@code aid} 不是 {@code bvid}</b> —— 这是本服务与门面之间唯一需要解释的边界：
 * 端点要的是 {@code oid=aid}，而 {@code bvid} 是调用方手里更常见的东西，
 * 所以<b>换算放在门面层</b>（门面多打一次 {@code view} 换 aid），服务层保持"要什么给什么"。
 * 这样做的好处：服务层不偷偷发第二个请求，调用方从方法签名就能看出成本。
 *
 * <p>📌 文档把本端点标成 {@code Wbi}（需签名），<b>2026-09-22 实测匿名即 {@code code=0}</b>
 * —— 又一处分歧（同族还有 {@code search/all/v2}、{@code search/type}）。本服务因此走普通 GET。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常），
 * 由门面边界按既有约定包装成 {@code IOException}。
 *
 * <p><b>无状态</b>：不缓存任何数据。
 *
 * <p>🆕 <b>C1 批（2026-09-24）补第三条评论链路：新版 {@code x/v2/reply/main}</b>
 * （现行网页端用的那条）+ {@code x/v2/reply/count}。三条链路的分工：
 * <table border="1">
 *   <caption>本服务现在有三条评论路径，别混用</caption>
 *   <tr><th>方法</th><th>端点</th><th>翻页</th><th>门槛</th></tr>
 *   <tr><td>{@link #getReplies}</td><td>旧版 {@code x/v2/reply}</td><td>{@code pn} 页码</td>
 *       <td>✅ 匿名即完整</td></tr>
 *   <tr><td>{@link #getSubReplies}</td><td>{@code x/v2/reply/reply}</td><td>{@code pn} 页码</td>
 *       <td>✅ 匿名即完整</td></tr>
     *   <tr><td>{@link #getMainReplies}</td><td>新版 {@code x/v2/reply/main}</td>
     *       <td>{@code cursor.next} <b>游标</b></td>
     *       <td>🔴 <b>带匿名指纹只给 3 条且谎报 {@code is_end}</b>（零 Cookie 反而完整）</td></tr>
 * </table>
 * ⇒ <b>只要"匿名拿到完整评论"就够了，用 {@link #getReplies}；要用现行网页端那条链路
 * （游标翻页、{@code all_count}），必须注入凭据。</b>
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class CommentService {

    /** 单例入口，无状态。 */
    public static final CommentService INSTANCE = new CommentService();

    /** 排序：按热度（B 站网页端默认）。{@code sort} 参数的合法值之一 */
    public static final int SORT_HOT = 2;

    /** 排序：按点赞数 */
    public static final int SORT_LIKE = 1;

    /** 排序：按发布时间 */
    public static final int SORT_TIME = 0;

    /**
     * <b>取评论列表</b>（按热度排序）。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>）
     * @param pn  页码（从 1 开始）
     * @param ps  每页条数
     * @return 评论页，不可为 null
     * @throws BilibiliException {@code aid} ≤ 0、网络失败、业务码非 0、或 {@code data} 为空
     */
    public CommentPage getReplies(long aid, int pn, int ps) {
        return getReplies(aid, pn, ps, SORT_HOT);
    }

    /**
     * <b>取评论列表</b>（可指定排序）。
     *
     * <p>🔴 <b>{@code oid} 必须是 {@code aid}</b>：把 {@code BV…} 丢进去不会报错，
     * 只会得到一个空 {@code replies} —— 这是最容易被误读成"这个视频没有评论"的形态。
     * 门面提供了收 {@code bvid} 的重载，它内部会先换算。
     *
     * <p>⚠️ <b>返回的 {@code replies} 与 {@code top_replies} / {@code upper.top} 会重叠</b>：
     * 同一条置顶评论可能同时出现在三处。要"评论列表"请只读 {@code replies}，
     * 否则会看到重复项（这不是 bug，是端点的形状）。
     *
     * <p>⚠️ 每条评论的 {@code replies} 是楼中楼<b>预览</b>（实测 3 条），不是全部。
     * 完整楼中楼需要 {@code x/v2/reply/reply}（B2 批）。
     *
     * @param aid  稿件 avid（<b>不是 bvid</b>）
     * @param pn   页码（从 1 开始）；{@code ≤0} 时按 1
     * @param ps   每页条数；{@code ≤0} 时按 20
     * @param sort 排序：{@link #SORT_TIME} / {@link #SORT_LIKE} / {@link #SORT_HOT}；
     *             不在合法集内时按热度（不把脏值原样交给服务端去猜）
     * @return 评论页，不可为 null
     * @throws BilibiliException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public CommentPage getReplies(long aid, int pn, int ps, int sort) {
        if (aid <= 0) {
            throw new BilibiliException("aid不能小于0");
        }
        int sortValue = (sort == SORT_TIME || sort == SORT_LIKE || sort == SORT_HOT) ? sort : SORT_HOT;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "1");
        params.put("oid", String.valueOf(aid));
        params.put("pn", String.valueOf(Math.max(1, pn)));
        params.put("ps", String.valueOf(ps <= 0 ? 20 : ps));
        params.put("sort", String.valueOf(sortValue));

        String url = BilibiliEndpoint.replyUrl + "?" + queryOf(params);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        CommentPage data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取评论列表");
        log.info("评论 aid={} 第 {} 页：本页 {} 条 / 共 {} 条（置顶 {} 条，sort={}）",
                aid, pn,
                data.getReplies() == null ? 0 : data.getReplies().size(),
                data.getPage() == null ? null : data.getPage().getCount(),
                data.getTop_replies() == null ? 0 : data.getTop_replies().size(),
                sortValue);
        return data;
    }

    // ------------------------------------------------------------------ B2 评论域扩（2026-09-22 新增）

    /** {@code business} 取值：评论表情（实测与 {@code dynamic} 返回完全一致）。 */
    public static final String EMOTE_BUSINESS_REPLY = "reply";

    /** {@code business} 取值：动态表情。 */
    public static final String EMOTE_BUSINESS_DYNAMIC = "dynamic";

    /**
     * <b>取楼中楼（二级评论）</b>（{@code x/v2/reply/reply}，B2 批 #4）。
     *
     * <p>✅ <b>匿名可用</b>（2026-09-22 实测 {@code code=0}，两次分别拿到 9 / 16 条）。
     *
     * <p>🔴 <b>{@code root} 是"被回复的那条一级评论的 {@code rpid}"，不是 aid、不是 bvid</b>。
     * 拿它的路径：{@link #getReplies} 返回的 {@code replies[i].getRpid()}。
     *
     * <p>🔴 <b>楼中楼只有一层</b>：实测每条 {@code replies[].replies} 都是 {@code null}，
     * <b>不要写递归</b>去"再往下取一层"，那永远是空的。
     *
     * <p>⚠️ 返回的 {@code page} 是复用一级评论的 {@code CommentPage.Page}，
     * 但<b>本端点没有 {@code acount}</b>（恒 {@code null}）；{@code upper} 也<b>只有 {@code mid}</b>。
     * 详见 {@code SubReplyPage} 的类注释。
     *
     * <p>⚠️ <b>空楼中楼是合法结果</b>（这条评论确实没人回），所以本方法<b>不</b>因为
     * {@code replies} 为空而抛异常 —— 与本类 {@link #getEmotePanel} 的处理相反，
     * 区别在于这里的空<b>无法</b>由"缺凭据"造成（本端点匿名就通）。
     *
     * @param aid  稿件 avid（<b>不是 bvid</b>）
     * @param root 一级评论的 {@code rpid}
     * @param pn   页码（从 1 开始）；{@code ≤0} 时按 1
     * @param ps   每页条数；{@code ≤0} 时按 20
     * @return 楼中楼页，不可为 null
     * @throws BilibiliException {@code aid}/{@code root} ≤ 0、网络失败、HTTP 非 2xx、
     *                           业务码非 0、或 {@code data} 为空
     */
    public SubReplyPage getSubReplies(long aid, long root, int pn, int ps) {
        if (aid <= 0) {
            throw new BilibiliException("aid不能小于0");
        }
        if (root <= 0) {
            throw new BilibiliException("root不能小于0（它是一级评论的 rpid）");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "1");
        params.put("oid", String.valueOf(aid));
        params.put("root", String.valueOf(root));
        params.put("pn", String.valueOf(Math.max(1, pn)));
        params.put("ps", String.valueOf(ps <= 0 ? 20 : ps));

        String url = BilibiliEndpoint.replyReplyUrl + "?" + queryOf(params);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        SubReplyPage data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取楼中楼");
        log.info("楼中楼 aid={} root={} 第 {} 页：本页 {} 条 / 共 {} 条",
                aid, root, pn,
                data.getReplies() == null ? 0 : data.getReplies().size(),
                data.getPage() == null ? null : data.getPage().getCount());
        return data;
    }

    /**
     * <b>取表情包面板</b>（{@code x/emote/user/panel/web}，B2 批 #7）。
     *
     * <p>🔴 <b>本端点需要凭据 —— 匿名拿不到任何表情包</b>（2026-09-22 双向实测）：
     * <table border="1">
     *   <caption>同一分钟、只换凭据</caption>
     *   <tr><th>请求</th><th>{@code code}</th><th>{@code data.packages}</th></tr>
     *   <tr><td>匿名</td><td>{@code 0}</td><td><b>{@code null}</b></td></tr>
     *   <tr><td>带凭据</td><td>{@code 0}</td><td><b>{@code list} × 68</b></td></tr>
     * </table>
     * ⇒ 这是红线里的 <b>"B 形态"（{@code code=0} + 空数据）</b>，本方法因此把空
     * {@code packages} 当<b>失败</b>抛出并说明"该先注入凭据" ——
     * 若安静返回空列表，"我没带凭据"会伪装成"这个用户没有表情包"。
     *
     * <p>⚠️ <b>{@code business} 实测不影响结果</b>：传 {@code reply} 与 {@code dynamic}
     * 返回的是同一批 68 个包。<b>本库不声称两者不同</b>，参数只为将来保留
     * （因此 {@link #EMOTE_BUSINESS_REPLY} / {@link #EMOTE_BUSINESS_DYNAMIC} 两个常量
     * 只是"填什么都可以"的说明，不是两种模式）。
     *
     * <p>⚠️ <b>响应很大</b>：实测 425912 字符（68 包 / 约 1555 个表情）。
     * 想要"某个包的表情"请在本类结果上自己筛 —— <b>端点没有按包查询的参数</b>。
     *
     * @param business 业务位；{@code null} 或空白时按 {@link #EMOTE_BUSINESS_REPLY}
     * @return 表情包面板，不可为 null
     * @throws BilibiliException 网络失败、HTTP 非 2xx、业务码非 0、{@code data} 为空，
     *                           或<b>{@code code=0} 但 {@code packages} 为空</b>（几乎总是"没注入凭据"）
     */
    public EmotePanel getEmotePanel(String business) {
        String biz = (business == null || business.isBlank()) ? EMOTE_BUSINESS_REPLY : business;
        String url = BilibiliEndpoint.emotePanelUrl + "?business=" + biz;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        EmotePanel data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取表情包面板");
        if (data.getPackages() == null || data.getPackages().isEmpty()) {
            throw new BilibiliException(0,
                    "获取表情包面板失败：服务端返回 code=0，但 packages 为空 —— "
                            + "这不是'这个用户没有表情包'，而是'没有给出数据'",
                    "该端点匿名时正是这种形态（code=0 但不给 packages，实测 data.packages=null），"
                            + "请先 Login#getCredentialStatus() 确认已注入有效凭据");
        }
        int emotes = 0;
        for (EmotePanel.Package pkg : data.getPackages()) {
            emotes += pkg.getEmote() == null ? 0 : pkg.getEmote().size();
        }
        log.info("表情包面板 business={}：{} 个包 / {} 个表情", biz, data.getPackages().size(), emotes);
        return data;
    }

    // ------------------------------------------------------------------ C1 批（2026-09-24 新增）

    /** {@code mode} 取值：<b>仅按热度</b>（默认；也是服务端把 {@code 0} 归一后的值）。 */
    public static final int MAIN_MODE_HOT = 3;

    /** {@code mode} 取值：按热度 + 按时间。 */
    public static final int MAIN_MODE_HOT_AND_TIME = 1;

    /** {@code mode} 取值：仅按时间。 */
    public static final int MAIN_MODE_TIME = 2;

    /**
     * <b>取新版主评论列表</b>（{@code x/v2/reply/main}，默认按热度取第一页，C1 批）。
     *
     * <p>等价于 {@link #getMainReplies(long, int, int, int) getMainReplies(aid, MAIN_MODE_HOT, 0, 20)}。
     *
     * <p>🔴 <b>本方法是全库"匿名档最会骗人"的一个</b>：带匿名指纹时它只回 <b>3</b> 条，
     * 却把 {@code cursor.is_end} 报成 {@code true}（而同一时刻<b>零 Cookie 反而能拿到完整的
     * 一页 20 条</b> —— 截断的触发器是指纹，不是"缺凭据"）。要判"到底了没有"必须拿
     * {@code cursor.all_count} 与结果条数对照。详见 {@link #getMainReplies(long, int, int, int)}。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>；只有 bvid 时用
     *            {@code VideoExtra#toAid(String)} 换算，零出站）
     * @return 评论页，不可为 null
     * @throws BilibiliException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、
     *                           {@code data} 为空，或<b>服务端自相矛盾</b>
     *                           （{@code all_count} 说有评论却一条不给）——见下述方法
     */
    public MainReplyPage getMainReplies(long aid) {
        return getMainReplies(aid, MAIN_MODE_HOT, 0, 20);
    }

    /**
     * <b>取新版主评论列表</b>（可指定排序与游标，C1 批）。
     *
     * <p>它<b>不是</b> {@link #getReplies} 的替代品，而是"另一条评论链路"：
     * 旧版用 {@code pn} 页码、新版用 {@code next} <b>游标</b>。
     * 🔴 <b>游标在响应里，所以翻页必须回传上一页的 {@code cursor.next}</b>
     * （首页传 {@code 0}）。只映射 {@code replies} 而丢掉 {@code cursor} 的话，调用方<b>无法翻页</b>。
     *
     * <p>🔴🔴 <b>"带匿名指纹"这档 = "静默截断 + 假终止信号"，本方法最该记住的一条</b>
     * （2026-09-24 实测，三轮独立复现 + 同日下午变量分离）：
     * <table border="1">
     *   <caption>同一 aid、同一 UA，只换 Cookie（{@code mode=3&next=0&ps=20}）</caption>
     *   <tr><th>请求</th><th>{@code replies}</th><th>{@code cursor.is_end}</th><th>能不能翻页</th></tr>
     *   <tr><td>带匿名指纹（buvid3/4）第 1 页</td><td><b>3</b></td><td><b>{@code true}</b>（谎报）</td>
     *       <td>回传 {@code next} 会拿到 {@code replies=null}（{@code all_count} 也一并消失）</td></tr>
     *   <tr><td><b>零 Cookie</b> 第 1 页</td><td><b>20</b></td><td>{@code false}</td>
     *       <td>正常 —— ⚠️ 截断的触发器是<b>指纹</b>，不是"缺凭据"</td></tr>
     *   <tr><td>凭据 第 1 页</td><td><b>20</b></td><td>{@code false}</td><td>正常（第 2 页 20 条）</td></tr>
     * </table>
     * ⇒ 三个症状要一起记：① 条数被静默截到 3（<b>"打长度"这条防线在这里无效</b>，
     * 3 条看着很正常）；② {@code is_end} 直接被写成 {@code true} —— 按它决定"要不要翻页"的调用方
     * 会得出"这条稿件只有 3 条评论"；③ 真去翻第二页会得到 {@code replies=null}
     * （<b>且 {@code all_count} 可能同时消失</b>）。
     * <b>{@code all_count} 是第 1 页里唯一没被动过的字段</b>。
     * ⇒ <b>想要完整的评论区，本端点实质上是"需要凭据（或零 Cookie 身份）"的</b>
     * （尽管带指纹的匿名档也能拿到 3 条）。注意本库的匿名出站<b>默认自动领取指纹</b>
     * （{@code AnonymousSession}），所以"库的匿名"恰好就是被截的那一档。
     *
     * <p>⚠️ <b>服务端自相矛盾时本方法抛异常</b>：若 {@code replies} 为 {@code null} 而
     * {@code cursor.all_count > 0}（"说有 N 条，一条都不给"），<b>或 {@code all_count} 干脆消失</b>
     * （带指纹翻页的实测形态），都抛 {@link BilibiliException} 并说明"匿名指纹翻页正是这种形态"。
     * 这样 {@code null} 不会被静默读成"没有评论"。
     * 反过来，{@code all_count} 为 0 时<b>不抛</b> —— 那种情况"确实没人评论"是合法结果。
     *
     * <p>⚠️ {@code ps} 上游定义域是 <b>1–30</b>（本库只把 ≤0 兜成 20，不夹上限）；
     * {@code next} 小于 0 时按 0。
     *
     * @param aid  稿件 avid（<b>不是 bvid</b>）
     * @param mode 排序：{@link #MAIN_MODE_HOT} / {@link #MAIN_MODE_HOT_AND_TIME} /
     *             {@link #MAIN_MODE_TIME}；<b>非法值按热度</b>。
     *             ⚠️ <b>不要传 0</b> —— 服务端会把它归一成 3（本库因此也不接受 0）
     * @param next 游标：第一页传 {@code 0}，其后回传上一页的 {@code cursor.next}
     * @param ps   每页条数（定义域 1–30）；{@code ≤0} 时按 20
     * @return 评论页，不可为 null
     * @throws BilibiliException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、
     *                           {@code data} 为空，或 {@code replies} 为 {@code null}
     *                           且 {@code all_count} 不明确为 0（匿名指纹翻页的固定形态）
     */
    public MainReplyPage getMainReplies(long aid, int mode, int next, int ps) {
        if (aid <= 0) {
            throw new BilibiliException("aid不能小于0");
        }
        int modeValue = (mode == MAIN_MODE_HOT || mode == MAIN_MODE_HOT_AND_TIME || mode == MAIN_MODE_TIME)
                ? mode : MAIN_MODE_HOT;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "1");
        params.put("oid", String.valueOf(aid));
        params.put("mode", String.valueOf(modeValue));
        params.put("next", String.valueOf(Math.max(0, next)));
        params.put("ps", String.valueOf(ps <= 0 ? 20 : ps));

        String url = BilibiliEndpoint.replyMainUrl + "?" + queryOf(params);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        MainReplyPage data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取新版评论列表");

        Integer allCount = (data.getCursor() == null) ? null : data.getCursor().getAll_count();
        // replies=null 在这里只可能是"被截断"：零评论的稿件服务端给的是 all_count=0。
        // 🔴 2026-09-24 变量分离实测：带匿名指纹翻页时，服务端连 all_count 都一起抹掉
        // （replies=null 且 all_count=null）⇒ 判据不能只靠 allCount>0，否则那一格会漏过去、
        // null 被静默读成"没有评论"。
        if (data.getReplies() == null && (allCount == null || allCount > 0)) {
            // 提示写进 message 而不是只放 description：门面边界只保留 getMessage()
            // （见 BilibiliException 与各门面的 IOException 包装），把可操作的建议留在
            // description 里，调用方从门面拿到的异常就只剩一句"没有数据"。
            throw new BilibiliException(0,
                    "获取新版评论列表失败：服务端返回 code=0，却一条评论都不给（replies=null）"
                            + (allCount == null
                                    ? "，cursor.all_count 也一并消失"
                                    : "，cursor.all_count=" + allCount + " 明明说有评论")
                            + " —— 这正是【匿名指纹 + 翻页】的固定形态：请注入凭据，"
                            + "或换不带指纹的身份（实测同一时刻零 Cookie 能拿到完整一页）",
                    "带匿名指纹时本端点只给第 1 页的前 3 条，回传 cursor.next 会拿到 replies=null"
                            + "（all_count 可能同时被抹掉）；只想看前几条就不要翻第二页");
        }
        log.info("新版评论 aid={} mode={} next={}：本页 {} 条 / 共 {} 条，is_end={}（⚠️ 匿名档的 is_end 不可信）",
                aid, modeValue, next,
                data.getReplies() == null ? 0 : data.getReplies().size(), allCount,
                data.getCursor() == null ? null : data.getCursor().getIs_end());
        return data;
    }

    /**
     * <b>取评论总数</b>（{@code x/v2/reply/count}，C1 批）。
     *
     * <p><b>门槛</b>：✅ 匿名可用、不需签名；且<b>匿名与带凭据取值相同</b>
     * （2026-09-24 实测：两格都是 {@code code=0}、同一个 count）。
     *
     * <p>📌 <b>它是"看穿 {@link #getMainReplies} 静默截断"的那把尺子</b>：
     * 匿名调 {@code reply/main} 只给 3 条并谎报 {@code is_end=true}，而本方法给出的总数
     * <b>不受匿名影响</b>。所以"这个视频到底有多少条评论"要问这里，不要数 {@code replies}。
     *
     * <p>⚠️ {@code oid} 是 <b>{@code aid}</b>（与 {@link #getReplies} 同口径）。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>）
     * @return 评论总数，不可为 null
     * @throws BilibiliException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public ReplyCount getReplyCount(long aid) {
        if (aid <= 0) {
            throw new BilibiliException("aid不能小于0");
        }
        String url = BilibiliEndpoint.replyCountUrl + "?type=1&oid=" + aid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        ReplyCount data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取评论总数");
        log.info("评论总数 aid={}：{}", aid, data.getCount());
        return data;
    }

    /** 按插入顺序拼 query（值全是数字与短枚举串，无需 URL 编码） */
    private static String queryOf(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private CommentService() {}
}

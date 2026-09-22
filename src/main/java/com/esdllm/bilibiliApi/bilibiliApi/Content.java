package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.content.ArticleInfo;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderInfo;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavResourceList;
import com.esdllm.bilibiliApi.model.data.pojo.content.HistoryCursor;
import com.esdllm.bilibiliApi.model.data.pojo.content.ToViewList;
import com.esdllm.bilibiliApi.service.ArticleService;
import com.esdllm.bilibiliApi.service.FavoriteService;
import com.esdllm.bilibiliApi.service.HistoryService;

import java.io.IOException;

/**
 * 内容管理门面：<b>观看历史 / 稍后再看 / 收藏夹目录 / 收藏夹详情与内容 / 专栏信息</b>
 * （库内第 11 个门面，2026-09-22 B3.5 批新增，B2 与 B4 批各补过方法）。
 *
 * <p><b>为什么把这三件事装进一个门面</b>：这是 {@code INTERFACE_PLAN.md} §7-Q1 决策
 * <b>(d)「混合：高频域独立 + 低频域合并」</b>的产物 —— 高频域（视频 / 评论 / 直播 / 用户 / 搜索）
 * 各自独立成类，而这三项<b>彼此无关、频率低、又都是"我自己的内容"</b>，
 * 给每个建一个门面只会增加调用方的认知成本。
 * <b>门面数量 = 调用方认知成本</b>，这个门面就是按这一条原则合并出来的。
 *
 * <p>⚠️ <b>它不是"内容"的万能入口</b>：<b>专栏正文</b>、弹幕、番剧、音频都不在这里
 * （弹幕属 {@code Danmaku} 门面；番剧时间表与音频在 B4 里实测<b>做不动</b>，见 §4-B4）。
 * 🆕 B4 批补进来的 {@link #getArticleInfo(long)} 是专栏<b>信息</b>（元数据 + 统计），
 * <b>不含正文</b> —— 正文那条旧路径 {@code x/article/view} 实测两次都不是 {@code code=0}
 * （先 {@code -352}、后 {@code -509}，码值会变），本库不用它。
 * 名字宽泛是合并的代价，边界靠这句话与每个方法的 javadoc 划清。
 *
 * <p>🆕 <b>B2 批（2026-09-22）补了两项</b>：{@link #getFolderInfo(long)} 与
 * {@link #getResources(long, int, int)}（夹内内容）。注意<b>这两项的门槛与上面三项不同</b> ——
 * 上面三项<b>全部真需登录</b>，而夹详情/夹内容<b>取决于夹的可见性</b>：
 * 公开夹匿名就能读，含 {@code attr=1} 的夹（如"默认收藏夹"）匿名会拿到
 * {@code -403 访问权限不足}。详见下面那张对照表（{@link #getFolderInfo(long)}）。
 *
 * <p><b>🔴 本门面所有方法都依赖调用方提供的有效凭据</b>（三者实测全部<b>真需登录</b>）：
 * 未注入凭据时会拿到 {@code -101 账号未登录}（{@link #getWatchHistory} / {@link #getToView}），
 * 或 {@code code=0} 但<b>空列表</b>（{@link #getFavoriteFolders} —— 这种最阴，
 * 外层码骗人，只有"list 为空"能暴露它）。
 * ⇒ 建议<b>先问一次 {@code Login#getCredentialStatus()}</b>，别拿异常当唯一判据
 * （{@code -101} 该重新登录，{@code -352} 是风控/环境，两者处置完全不同）。
 *
 * <p>🔴 <b>本门面全是 GET 只读</b>（这是它与"写操作"的边界，也是它敢做进库里的原因）：
 * 往"稍后再看"里增删、清空历史、收藏/取消收藏<b>都不在这里，也不在本库任何地方</b> ——
 * 那些需要 {@code csrf=bili_jct} 且会改动账号，若哪天要做必须另立门面与只读面严格隔离。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code UserSpace} / {@code VideoExtra} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class Content {

    /**
     * <b>取观看历史第一页</b>（{@code x/web-interface/history/cursor}）。
     *
     * <p>🔴 <b>真需登录</b>：匿名 {@code -101 账号未登录}（实测），无降级。
     *
     * <p>要翻页请用 {@link #getWatchHistory(Integer, Long, Long, String)} —— 本条只给第一页，
     * 且它返回的 {@code HistoryCursor#getCursor()} 就是下一页的输入。
     *
     * @param ps 每页条数（实测只吃<b>很小的</b>合法区间：传超大值会被服务端直接拒掉，
     *           而不是"尽量多给"）
     * @return 观看历史，不可为 null
     * @throws IOException {@code ps} ≤ 0、网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public HistoryCursor getWatchHistory(int ps) throws IOException {
        try {
            return HistoryService.INSTANCE.getWatchHistory(ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取观看历史（游标翻页）</b>。
     *
     * <p>🔴 <b>翻页是游标式的，不是页码式的 —— 这是本方法唯一需要读懂的地方</b>：
     * 把上一条结果里的 {@code cursor.max} / {@code cursor.view_at} / {@code cursor.business}
     * 原样传进来，就是下一页。三个值<b>缺一不可</b>（只带 {@code max} 在跨业务时跳错位置）。
     * <pre>{@code
     * HistoryCursor first = content.getWatchHistory(20);
     * HistoryCursor.CursorPos c = first.getCursor();
     * HistoryCursor next  = content.getWatchHistory(20, c.getMax(), c.getView_at(), c.getBusiness());
     * }</pre>
     * 本库<b>刻意不把这个游标存在内部</b>：服务无状态，翻页的"位置"属于调用方，
     * 藏在库里会让"翻到哪了"变得不可见，也会让并发调用互相踩。
     *
     * @param ps       每页条数；{@code null} 或 ≤ 0 时不传该参数
     * @param max      游标主键；首页传 {@code null}
     * @param viewAt   游标时间；首页传 {@code null}
     * @param business 业务类型（{@code archive}/{@code live}/{@code article}）；首页传 {@code null}
     * @return 观看历史，不可为 null
     * @throws IOException 参数非法、网络失败、业务码非 0（含 {@code -101} 未登录）、或 {@code data} 为空
     */
    public HistoryCursor getWatchHistory(Integer ps, Long max, Long viewAt, String business)
            throws IOException {
        try {
            return HistoryService.INSTANCE.getWatchHistory(ps, max, viewAt, business);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取"稍后再看"整个列表</b>（{@code x/v2/history/toview}）。
     *
     * <p>🔴 <b>它不分页</b>：一次把整个列表给完（实测 37 条），响应只有 {@code count} 与 {@code list}
     * —— 与 {@link #getWatchHistory(int)} 的游标翻页形态完全不同，别按同一种方式用。
     *
     * <p>🔴 <b>真需登录</b>：匿名 {@code -101}（实测）。
     *
     * <p>📌 这个端点曾被 {@code INTERFACE_PLAN.md} 误归进 B4"写操作"，已纠正：它是 <b>GET 只读</b>。
     * 往列表里<b>加/删</b>才是写操作（另有端点 + {@code csrf}），本库不做。
     *
     * @return 稍后再看列表，不可为 null
     * @throws IOException 网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public ToViewList getToView() throws IOException {
        try {
            return HistoryService.INSTANCE.getToView();
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取"我创建的"收藏夹目录</b>（{@code x/v3/fav/folder/created/list-all}）。
     *
     * <p>🔴 <b>这里缺凭据的形态是本门面最阴的一种，值得单独记</b>：实测<b>匿名也返回 {@code code=0}</b>，
     * 但<b>不给列表</b> —— 与 {@code x/space/upstat} 同属"外层码骗人"那一类。
     * ⇒ 判据不是 {@code code}，而是"list 有没有内容"。本方法因此把空列表当<b>失败</b>抛出，
     * 而不是安静地返回一个空目录（那会被读成"此人没有收藏夹"，与"我没带凭据"无法区分）。
     *
     * <p>⚠️ 取到的 {@code list[].id} 才是夹内内容要用的 {@code media_id}；{@code fid} 是另一套短 id，
     * 混用会查不到。
     *
     * <p>⚠️ <b>{@code attr} 不能用来提前判断公开性</b>（B2 批已订正，B3.5 曾写反）：实测含
     * {@code attr=1} 的夹（"默认收藏夹"）匿名访问夹详情/夹内容会 {@code -403}，
     * {@code attr=2} 的那个却能匿名读。判据是响应码，不是这个字段。
     * 夹内内容见 {@link #getResources(long, int, int)}。
     *
     * @param upMid 目标用户 mid（实测只有本人的 mid 能给到有效数据）
     * @return 收藏夹目录，不可为 null
     * @throws IOException {@code upMid} ≤ 0、网络失败、业务码非 0，
     *                     或服务端回 {@code code=0} 但列表为空（几乎总是"没注入凭据"）
     */
    public FavFolderList getFavoriteFolders(long upMid) throws IOException {
        try {
            return FavoriteService.INSTANCE.getCreatedFolders(upMid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取收藏夹详情</b>（{@code x/v3/fav/folder/info}，B2 批 #5）。
     *
     * <p>⚠️ <b>门槛取决于夹本身 —— 与本门面另外三个方法不同，它不是"一律需登录"</b>
     * （2026-09-22 同一分钟实测，全匿名）：
     * <table border="1">
     *   <caption>同一端点、只换 media_id</caption>
     *   <tr><th>{@code media_id}</th><th>标题</th><th>{@code attr}</th><th>结果</th></tr>
     *   <tr><td>3526698880</td><td>小雨绒Candy</td><td>2</td><td>{@code code=0}</td></tr>
     *   <tr><td>1095405480</td><td>默认收藏夹</td><td>1</td><td><b>{@code -403 访问权限不足}</b></td></tr>
     * </table>
     * ⇒ 🔴 <b>别用 {@code attr} 反推公开性</b>（这与 {@link #getFavoriteFolders} 上的旧注相反，
     * B2 批已订正）：只含低位 {@code 1} 的夹匿名读不到，但"不含 {@code 1}"<b>不等于</b>公开。
     * 判据是响应码本身。
     *
     * <p>🔴 <b>{@code -403} 在本库是两义码</b>：既可能是"缺 WBI 签名"，也可能是
     * <b>"资源权限不足"</b>。这个端点<b>根本不需要签名</b>，所以这里的 {@code -403}
     * 几乎一定是后者 —— 异常消息里会把两种成因都写出来，不用再去翻文档。
     *
     * @param mediaId 夹 id（{@link #getFavoriteFolders} 里那条的 {@code id}，<b>不是</b> {@code fid}）
     * @return 夹详情，不可为 null
     * @throws IOException {@code mediaId} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0
     *                     （含<b>私密夹的 {@code -403}</b>，消息里会说明两种成因）
     */
    public FavFolderInfo getFolderInfo(long mediaId) throws IOException {
        try {
            return FavoriteService.INSTANCE.getFolderInfo(mediaId);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取收藏夹内容（一页）</b>（{@code x/v3/fav/resource/list}，B2 批 #6）。
     *
     * <p>门槛与 {@link #getFolderInfo(long)} <b>完全一致</b>（实测两个端点对同一个夹的裁决相同）：
     * 公开夹匿名可读，含 {@code attr=1} 的夹匿名 {@code -403}。
     *
     * <p>🔴 <b>"本页 0 条"与"夹是空的"不是一回事</b>：本方法会拿 {@code info.media_count}
     * 做交叉校验 —— 夹里明明有内容却一条都没给会<b>抛异常</b>（多半是 {@code pn} 越界或形状变了）；
     * 而 {@code media_count=0} 的空夹返回空列表，<b>不抛</b>。
     *
     * <p>⚠️ 翻页用 {@code pn}（每页默认 20 条，实测正好给 20 条），是否还有下一页看
     * {@code result.getHas_more()}。
     *
     * <p>⚠️ 条目里 {@code season} / {@code ogv} 实测为 {@code null}（视频内容用不到），
     * 其形状<b>未验证</b>；{@code id} 是 {@code aid}、{@code bv_id} 与 {@code bvid} 同值，
     * 详见 {@code FavResourceList.Media}。
     *
     * @param mediaId 夹 id
     * @param pn      页码（从 1 开始）；{@code ≤0} 时按 1
     * @param ps      每页条数；{@code ≤0} 时按 20
     * @return 一页内容（含夹信息 {@code info}），不可为 null
     * @throws IOException {@code mediaId} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0
     *                     （含私密夹的 {@code -403}），或<b>夹里明明有内容却一条都没给</b>
     */
    public FavResourceList getResources(long mediaId, int pn, int ps) throws IOException {
        try {
            return FavoriteService.INSTANCE.getResources(mediaId, pn, ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取专栏（图文）信息</b>（{@code x/article/viewinfo}，B4 批 #1，2026-09-22）。
     *
     * <p>📌 <b>它是本库的一处"跨表遗留"</b>：{@code INTERFACE_PLAN.md} §3 的证据表把它标成 B2，
     * 但 §4-B2 的明细表<b>从来没有这一项</b> ⇒ B2 交付时被漏下，最终落在 B4。
     * 2026-09-22 实测它<b>匿名可用</b>，因此没有跟 B4 其余"做不动"的项一起挂起。
     *
     * <p>🔴 <b>本门面里只有本方法不需要凭据</b>：上面所有方法要么<b>真需登录</b>（{@code -101}），
     * 要么<b>取决于收藏夹可见性</b>（{@code -403}）。本方法实测匿名与带凭据都是 {@code code=0}，
     * 且 <b>23 个键完全相同</b>，连 {@code stats} 也一致。
     * ⚠️ 唯二例外是 {@code is_author} / {@code in_list}，且它们的<b>归因不同</b>：
     * {@code is_author} 与凭据完全同向（可当"已登录"指示器，但读<b>别人的</b>文章也是 {@code true}）；
     * {@code in_list} 只跟"请求有没有带会话指纹"走 —— <b>零 Cookie 是 {@code false}，
     * 带上 {@code buvid3}/{@code buvid4} 就变 {@code true}</b>，所以它<b>连"已登录"都指示不了</b>，
     * 而本库运行时必然带指纹 ⇒ 真实调用通常拿到 {@code true}。
     * ⇒ 两者都<b>原样映射</b>，别据此判断作者身份／收藏状态／登录与否。详见 {@link ArticleInfo} 的 2×2 表。
     *
     * <p>🔴 <b>别把"我视角"当"全局统计"</b> —— 这是本方法最容易踩的地方：
     * {@code getLike()} / {@code getCoin()} / {@code getFavorite()} / {@code getAttention()}
     * 表达的是<b>当前凭据</b>对这篇文章做过什么，匿名恒为 {@code 0}／{@code false}；
     * 文章真正的汇总数在 {@code getStats()} 里。
     * <b>要"这篇文章有多少赞"读 {@code getStats().getLike()}，不是 {@code getLike()}。</b>
     * 两者同名、含义完全不同（同一篇文章实测：{@code stats.like=35} 而 {@code like=0}）。
     *
     * <p>⚠️ {@code getPre()} / {@code getNext()} 无相邻文章时实测为 <b>{@code 0}</b>，
     * 不是 {@code null} —— 别用 {@code != null} 判断"有没有下一篇"。
     *
     * <p>⚠️ 同域的旧路径 {@code x/article/view} 实测两次都不是 {@code code=0}
     * （先 {@code -352}、后 {@code -509}，<b>码值会变</b>，所以别把具体码值写进判断），本库不用它。
     *
     * @param id 专栏号（{@code cv} 后的数字，如 {@code cv4538122} 传 {@code 4538122}），必须 &gt; 0
     * @return 专栏信息，不可为 null
     * @throws IOException {@code id} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public ArticleInfo getArticleInfo(long id) throws IOException {
        try {
            return ArticleService.INSTANCE.getArticleInfo(id);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

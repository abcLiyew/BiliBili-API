package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.comment.CommentPage;
import com.esdllm.bilibiliApi.model.data.pojo.comment.EmotePanel;
import com.esdllm.bilibiliApi.model.data.pojo.comment.MainReplyPage;
import com.esdllm.bilibiliApi.model.data.pojo.comment.ReplyCount;
import com.esdllm.bilibiliApi.model.data.pojo.comment.SubReplyPage;
import com.esdllm.bilibiliApi.parse.BvCode;
import com.esdllm.bilibiliApi.service.CommentService;

import java.io.IOException;

/**
 * 评论门面：<b>视频评论列表</b>（库内第 12 个门面，2026-09-22 B1 批新增）。
 *
 * <p>按 {@code INTERFACE_PLAN.md} §7-Q1 决策 (d)「高频域独立 + 低频域合并」，
 * 评论属于<b>高频域</b>，因此单独立类（对比：观看历史/稍后再看/收藏夹目录那三项低频能力
 * 被合并进了 {@code Content}）。</p>
 *
 * <p>🆕 <b>B2 批（2026-09-22）已补齐本域剩下两项</b>：{@link #getSubReplies}（楼中楼）
 * 与 {@link #getEmotePanel}（表情包）。<b>它们的门槛不一样，别按同一句记</b>：
 * <table border="1">
 *   <caption>本门面各端点的门槛（🆕 C1 批补后两行）</caption>
 *   <tr><th>方法 / 端点</th><th>门槛</th><th>实测证据</th></tr>
 *   <tr><td>{@code x/v2/reply}（评论列表，旧版）</td><td>✅ 匿名</td><td>{@code code=0}</td></tr>
 *   <tr><td>{@code x/v2/reply/reply}（楼中楼）</td><td>✅ 匿名</td><td>{@code code=0}，16 条</td></tr>
 *   <tr><td>{@code x/emote/user/panel/web}（表情包）</td><td>🔴 <b>需凭据</b></td>
 *       <td>匿名 {@code code=0} 但 {@code packages=null}；带凭据 68 个包</td></tr>
 *   <tr><td>{@code x/v2/reply/main}（新版评论列表）</td><td>⚠️ <b>匿名只给 3 条，实质需凭据</b></td>
 *       <td>匿名 {@code replies=3} + <b>{@code is_end} 谎报 {@code true}</b>；凭据 {@code 20} / {@code false}</td></tr>
 *   <tr><td>{@code x/v2/reply/count}（评论总数）</td><td>✅ 匿名（且取值与凭据相同）</td>
 *       <td>两格都是 {@code code=0}，同一个 {@code count}</td></tr>
 * </table>
 * ⇒ 表情包放在本门面是因为"评论表情"的语义最近，<b>但它是 B2 批唯一需要凭据的一项</b>；
 * C1 批的 {@code reply/main} 则属于"<b>匿名能拿到东西、但拿到的是残的</b>"这一类
 * —— 它比"完全拿不到"更难发现，所以两个方法都单独写了警告。
 *
 * <p><b>🔴 本门面唯一需要读懂的地方：{@code oid} 要的是 {@code aid}，不是 {@code bvid}。</b>
 * 把 {@code BV…} 丢给端点<b>不会报错</b>，只会得到一个空的 {@code replies} ——
 * 与"这个视频真的没有评论"完全同形。所以本门面提供了两条路径：
 * <ul>
 *   <li>{@link #getReplies(long, int, int)} —— 已经知道 {@code aid} 时用，<b>0 次额外请求</b>；</li>
 *   <li>{@link #getRepliesByBvid(String, int, int)} —— 只有 {@code bvid} 时用，
 *       内部用<b>纯算法</b>换 {@code aid}（{@code parse.BvCode}，同样是 <b>0 次额外请求</b>）。</li>
 * </ul>
 * 📌 <b>2026-09-23（B5 批）变化</b>：上面第二条路径<b>从前要多打一次 {@code x/web-interface/view}</b>
 * 才能换到 {@code aid}（当时的理由是"不这么干会静默拿空列表"）。现在改走纯算法 ——
 * {@code bvid} 本来就是 {@code aid} 的 base58 编码，<b>那一次出站纯属浪费</b>。
 * ⇒ 两条路径现在<b>都是零额外请求</b>，区别只剩"你手里是哪个 id"。
 *
 * <p><b>🔴 评论不是"评论"一个东西，它有三块会重叠的内容</b>：
 * {@code replies}（正文）/ {@code top_replies}（置顶）/ {@code upper.top}（UP 主置顶）。
 * 同一条置顶评论可能同时出现在三处 —— <b>要列表就只读 {@code replies}</b>，
 * 否则会看到重复项。这不是接口的 bug，是本库不替你做的取舍。
 *
 * <p><b>门槛</b>：✅ <b>不需要签名、不需要凭据</b>（2026-09-22 实测匿名即 {@code code=0}）。
 * 文档把本端点标成 {@code Wbi}（需签名），实测不需要 —— 与 {@code search/all/v2}、
 * {@code search/type} 同族的过时标注。
 *
 * <p><b>能力边界</b>（本批刻意不做的，别在这里找）：
 * <ul>
 *   <li><b>发表 / 删除 / 点赞评论</b> —— 写操作，<b>本库任何地方都不做</b>（需 {@code csrf}
 *       且会改动账号）。</li>
 * </ul>
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code UserSpace} / {@code VideoExtra} / {@code Content} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class Comment {

    /**
     * <b>取评论列表第一页</b>（按热度排序，B 站网页端默认）。
     *
     * <p>等价于 {@link #getReplies(long, int, int, int) getReplies(aid, 20, 1, SORT_HOT)} 的语义
     * （热度排序、每页 20 条）—— 只是本方法把 {@code pn} 显式留给调用方。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>；只有 bvid 请用 {@link #getRepliesByBvid}）
     * @param pn  页码（从 1 开始）
     * @param ps  每页条数（{@code ≤0} 时按 20）
     * @return 评论页，不可为 null
     * @throws IOException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public CommentPage getReplies(long aid, int pn, int ps) throws IOException {
        try {
            return CommentService.INSTANCE.getReplies(aid, pn, ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取评论列表</b>（可指定排序）。
     *
     * <p>排序只用 {@link CommentService} 上的三个常量（{@code SORT_TIME} / {@code SORT_LIKE} /
     * {@code SORT_HOT}），传别的值会回落到<b>热度</b>而不是原样发给服务端 ——
     * 把脏参数交出去只会换来一个 {@code -400}，而 {@code -400} 在这里很难与"参数名写错"区分开。
     *
     * <p>⚠️ 返回里 {@code replies} / {@code top_replies} / {@code upper.top} <b>会重叠</b>，
     * 见类注释。
     *
     * @param aid  稿件 avid（<b>不是 bvid</b>）
     * @param pn   页码（从 1 开始）；{@code ≤0} 时按 1
     * @param ps   每页条数；{@code ≤0} 时按 20
     * @param sort 排序（0=时间、1=点赞、2=热度）；非法值按热度
     * @return 评论页，不可为 null
     * @throws IOException 参数非法、网络失败、业务码非 0、或 {@code data} 为空
     */
    public CommentPage getReplies(long aid, int pn, int ps, int sort) throws IOException {
        try {
            return CommentService.INSTANCE.getReplies(aid, pn, ps, sort);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>按 BV 号取评论列表</b>（内部换算出 {@code aid}）。
     *
     * <p>🔴 <b>为什么需要这条重载</b>：端点要 {@code oid=aid}，而调用方手里通常是 {@code bvid}。
     * 直接猜 aid 或把 bvid 当 aid 传，会静默拿到空列表 —— 这个错误<b>没有任何异常提示</b>，
     * 定位成本远高于"换算"这一步。所以这条重载存在的意义是<b>把易错点从调用方挪进库里</b>。
     *
     * <p>📌 <b>2026-09-23（B5 批）起，换算走纯算法、<u>不再发起任何额外请求</u></b>
     * （{@code parse.BvCode}）：{@code bvid} 就是 {@code aid} 的 base58 编码，
     * 此前"打一次 {@code view} 换 aid"的做法<b>被省掉了</b>。
     * ⇒ 本方法与 {@link #getReplies(long, int, int)} 现在同样是 <b>1 次出站</b>（只打评论端点）。
     *
     * <p>📌 已经拿过 {@code VideoInfo} 的调用方仍可直接用 {@link #getReplies(long, int, int)}
     * （{@code VideoInfo#getAid()} 就是要的东西）—— 两条路现在都只有一次请求，按手里的 id 选即可。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param pn   页码（从 1 开始）
     * @param ps   每页条数
     * @return 评论页，不可为 null
     * @throws IOException {@code bvid} 格式非法（长度不是 12 / 不以 {@code BV} 开头 /
     *                     含 base58 码表外的字符），或后续任一步失败
     *                     （见 {@link #getReplies(long, int, int)}）
     */
    public CommentPage getRepliesByBvid(String bvid, int pn, int ps) throws IOException {
        long aid;
        try {
            aid = BvCode.toAid(bvid);
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException 是 runtime，逃出门面会让下游的 catch (IOException) 兜不住 ——
            // 本门面所有方法都声明 throws IOException，所以在这里转成受检异常（语义也更准：换算失败）。
            throw new IOException("换算 aid 失败：" + e.getMessage(), e);
        }
        try {
            return CommentService.INSTANCE.getReplies(aid, pn, ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ B2 评论域扩（2026-09-22 新增）

    /**
     * <b>取楼中楼（二级评论）</b>（{@code x/v2/reply/reply}，B2 批 #4）。
     *
     * <p>✅ <b>匿名可用</b>（2026-09-22 实测 {@code code=0}，两次分别 9 / 16 条）。
     * 什么时候需要它：{@link #getReplies(long, int, int)} 返回的每条评论里
     * {@code replies} 只是<b>预览</b>（实测 3 条），要"这条评论下的全部回复"就走这里。
     *
     * <p>🔴 <b>{@code root} 是一级评论的 {@code rpid}</b>，不是 aid、不是 bvid：
     * <pre>{@code
     * CommentPage page = comment.getReplies(aid, 1, 20);
     * long root = page.getReplies().get(0).getRpid();
     * SubReplyPage subs = comment.getSubReplies(aid, root, 1, 20);
     * }</pre>
     *
     * <p>🔴 <b>楼中楼只有一层</b>：实测每条 {@code replies[].replies} 都是 {@code null}，
     * <b>不要递归</b>。
     *
     * <p>⚠️ 返回的 {@code page} / {@code upper} 复用了评论列表的那两个嵌套类型，但
     * <b>本端点没有 {@code page.acount}</b>、{@code upper} <b>也只有 {@code mid}</b>
     * —— 读到 {@code null} 不是数据丢了，是端点不给。详见 {@code SubReplyPage}。
     *
     * <p>⚠️ <b>空楼中楼是合法结果</b>（没人回就是没人回），不会抛异常。
     *
     * @param aid  稿件 avid（<b>不是 bvid</b>）
     * @param root 一级评论的 {@code rpid}
     * @param pn   页码（从 1 开始）；{@code ≤0} 时按 1
     * @param ps   每页条数；{@code ≤0} 时按 20
     * @return 楼中楼页，不可为 null
     * @throws IOException {@code aid}/{@code root} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public SubReplyPage getSubReplies(long aid, long root, int pn, int ps) throws IOException {
        try {
            return CommentService.INSTANCE.getSubReplies(aid, root, pn, ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取表情包面板</b>（{@code x/emote/user/panel/web}，B2 批 #7）。
     *
     * <p>🔴 <b>本方法是本门面唯一需要凭据的一个</b>（2026-09-22 双向实测）：
     * 匿名返回 {@code code=0} 但 {@code data.packages=null}；注入凭据后才给出 68 个包。
     * ⇒ 缺凭据时本方法<b>抛 IOException</b>（不是安静地给空列表）——
     * 否则"我没带凭据"会被读成"这个用户没有表情包"。
     * <b>建议先 {@code Login#getCredentialStatus()} 再调。</b>
     *
     * <p>⚠️ 它在"评论门面"里但业务上属于"我账号里的表情包"，与具体某个视频/评论<b>无关</b>
     * —— 参数里没有 aid、没有 oid，这也是它必须凭据的原因（它本来就是"我的"数据）。
     *
     * <p>⚠️ <b>响应很大</b>（实测 425912 字符 / 68 包 / 约 1555 个表情）。
     * 要"某个包的表情"请自己在结果里筛 —— <b>端点没有按包查询的参数</b>。
     *
     * @param business 业务位；{@code null} 或空白时按 {@code reply}。
     *                 ⚠️ 实测 {@code reply} 与 {@code dynamic} <b>返回完全一致</b>，
     *                 本库不声称两者不同（参数只为将来保留）
     * @return 表情包面板，不可为 null
     * @throws IOException 网络失败、HTTP 非 2xx、业务码非 0、{@code data} 为空，
     *                     或<b>{@code code=0} 但 {@code packages} 为空</b>（几乎总是"没注入凭据"）
     */
    public EmotePanel getEmotePanel(String business) throws IOException {
        try {
            return CommentService.INSTANCE.getEmotePanel(business);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取表情包面板</b>（{@code business=reply}）—— 等价于
     * {@link #getEmotePanel(String) getEmotePanel("reply")}。
     *
     * @return 表情包面板，不可为 null
     * @throws IOException 同 {@link #getEmotePanel(String)}
     */
    public EmotePanel getEmotePanel() throws IOException {
        return getEmotePanel(CommentService.EMOTE_BUSINESS_REPLY);
    }

    // ------------------------------------------------------------------ C1 批（2026-09-24 新增）

    /**
     * <b>取新版主评论列表</b>（{@code x/v2/reply/main}，默认按热度取第一页）。
     *
     * <p>它对应的是<b>现行网页端</b>评论区那条链路（本门面原有的
     * {@link #getReplies(long, int, int)} 是旧版端点）。两者的取舍：
     * <table border="1">
     *   <caption>三条评论链路（2026-09-24 实测）</caption>
     *   <tr><th>方法</th><th>翻页方式</th><th>匿名能拿到什么</th></tr>
     *   <tr><td>{@link #getReplies(long, int, int)}（旧版）</td><td>{@code pn} 页码</td>
     *       <td>✅ 完整一页</td></tr>
     *   <tr><td>{@link #getSubReplies}</td><td>{@code pn} 页码</td><td>✅ 完整一页</td></tr>
     *   <tr><td><b>本方法</b>（新版）</td><td>{@code cursor.next} <b>游标</b></td>
     *       <td>🔴 <b>带指纹只有 3 条且谎报 {@code is_end}</b>（零 Cookie 反而完整）</td></tr>
     * </table>
     * ⇒ <b>只想匿名读评论，用旧版那条（{@link #getReplies(long, int, int)}）；
     * 要用现行网页端的链路，请先注入凭据</b> —— 否则你会拿到"看起来只有 3 条评论"的结果。
     *
     * <p>🔴 <b>本方法最该记住的一条：匿名指纹档的终止信号是假的</b>（2026-09-24 三轮复现
     * + 同日下午变量分离）。带匿名指纹时 {@code replies} 只有 <b>3</b> 条、
     * {@code cursor.is_end} 却是 <code>true</code>，而同一响应的 {@code cursor.all_count} 是
     * <b>11062</b>；同一时刻<b>零 Cookie 反而能拿到完整 20 条</b> —— 截断的触发器是<b>指纹</b>，
     * 不是"缺凭据"（本库匿名出站默认自动领指纹，所以"库的匿名"恰好是被截的那一档）。
     * ⇒ 判"到底了没有"<b>不能只看 {@code is_end}</b>；
     * 要问总数请用 {@link #getReplyCount(long)}（它不受匿名影响）。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>）
     * @return 评论页，不可为 null
     * @throws IOException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、
     *                     {@code data} 为空，或 {@code all_count > 0} 却一条评论都不给
     */
    public MainReplyPage getMainReplies(long aid) throws IOException {
        try {
            return CommentService.INSTANCE.getMainReplies(aid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取新版主评论列表</b>（可指定排序与游标）。
     *
     * <p>🔴 <b>翻页靠回传 {@code cursor.next}，不是页码</b>：
     * <pre>{@code
     * MainReplyPage p1 = comment.getMainReplies(aid, CommentService.MAIN_MODE_HOT, 0, 20);
     * int next = p1.getCursor().getNext();
     * MainReplyPage p2 = comment.getMainReplies(aid, CommentService.MAIN_MODE_HOT, next, 20);
     * }</pre>
     * ⇒ <b>但匿名指纹档翻第二页会直接抛异常</b>（服务端回 {@code replies=null}
     * —— {@code all_count} 可能说有多条、也可能一并消失 ⇒ 两种都判为"服务端自相矛盾"
     * 并显式失败）。
     * 这是<b>刻意</b>的：否则 {@code null} 会被读成"没有评论"。
     *
     * <p>⚠️ {@code mode} 只用 {@link CommentService#MAIN_MODE_HOT} /
     * {@link CommentService#MAIN_MODE_HOT_AND_TIME} / {@link CommentService#MAIN_MODE_TIME}；
     * 传别的值（<b>包括 0</b>）会回落到热度，不原样发给服务端
     * —— 实测 {@code mode=4} 会换来 {@code -400 invalid mode}，而 {@code 0} 被服务端归一成 3。
     *
     * @param aid  稿件 avid（<b>不是 bvid</b>）
     * @param mode 排序；非法值按 {@code MAIN_MODE_HOT}
     * @param next 游标：第一页传 {@code 0}，其后回传上一页的 {@code cursor.next}
     * @param ps   每页条数（上游定义域 1–30）；{@code ≤0} 时按 20
     * @return 评论页，不可为 null
     * @throws IOException 参数非法、网络失败、HTTP 非 2xx、业务码非 0、{@code data} 为空，
     *                     或<b>服务端说有评论却一条不给</b>（匿名翻页的固定形态，
     *                     见 {@link CommentService#getMainReplies(long, int, int, int)}）
     */
    public MainReplyPage getMainReplies(long aid, int mode, int next, int ps) throws IOException {
        try {
            return CommentService.INSTANCE.getMainReplies(aid, mode, next, ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取评论总数</b>（{@code x/v2/reply/count}）。
     *
     * <p>✅ <b>匿名可用、不需签名</b>，且<b>匿名与带凭据取值相同</b>（2026-09-24 实测）。
     *
     * <p>📌 <b>它是判断"评论有没有被静默截断"的唯一可靠依据</b>：
     * {@link #getMainReplies(long)} 带匿名指纹时只回 3 条并谎称"到底了"，
     * 而本方法给出的总数（该样本 <b>11062</b>）<b>不受匿名影响</b>。
     * ⇒ <b>"这条稿件有多少条评论"问这里，不要数 {@code replies}</b>。
     *
     * <p>⚠️ 它只给"有多少条"，<b>不给内容</b> —— 要内容仍须走上面两条列表链路。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>）
     * @return 评论总数，不可为 null
     * @throws IOException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public ReplyCount getReplyCount(long aid) throws IOException {
        try {
            return CommentService.INSTANCE.getReplyCount(aid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

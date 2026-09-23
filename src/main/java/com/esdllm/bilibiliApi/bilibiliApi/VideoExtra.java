package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.video.AiSummary;
import com.esdllm.bilibiliApi.model.data.pojo.video.OnlineTotal;
import com.esdllm.bilibiliApi.model.data.pojo.video.PlayUrl;
import com.esdllm.bilibiliApi.model.data.pojo.video.ViewDetail;
import com.esdllm.bilibiliApi.parse.BvCode;
import com.esdllm.bilibiliApi.service.VideoService;

import java.io.IOException;

/**
 * 视频附加信息门面：<b>AI 摘要 / 播放地址 / 一站式详情 / 在线观看数</b>。
 *
 * <p>本门面各项能力的门槛<b>各不相同，且分布很散</b> —— 这是它最需要注意的地方：
 * <table border="1">
 *   <caption>方法 × 门槛（全部为 2026-09 实测）</caption>
 *   <tr><th>方法</th><th>WBI 签名</th><th>登录凭据</th><th>备注</th></tr>
 *   <tr><td>{@link #getAiSummary(String)}</td><td><b>要</b></td><td><b>要</b></td>
 *       <td>缺签名是 {@code -403}，签上名才露出 {@code -101}</td></tr>
 *   <tr><td>{@link #getPlayUrl(String, Long)}</td><td>不要</td><td><b>可选</b></td>
 *       <td>唯一"可选"的一项：匿名也能拿地址，凭据买到的是更高清晰度；⚠️ 依赖出口信誉</td></tr>
 *   <tr><td>{@link #getViewDetail(String)}</td><td>不要</td><td>不要</td><td>匿名即通</td></tr>
 *   <tr><td>{@link #getOnlineTotal(String, Long)}</td><td>不要</td><td>不要</td><td>匿名即通</td></tr>
 *   <tr><td>{@link #isNoteForbidden(long)}</td><td>不要</td><td>不要</td>
 *       <td>匿名即通；⚠️ 但它<b>不校验 aid 是否存在</b>，见方法注释（B5 批）</td></tr>
 *   <tr><td>{@link #toAid(String)} / {@link #toBvid(long)}</td>
 *       <td colspan="2"><b>不发请求</b></td>
 *       <td>纯算法换算、零出站 ⇒ 因此刻意<b>不声明</b> {@code throws IOException}（B5 批）</td></tr>
 * </table>
 * ⇒ 别把"这个门面"当成一个门槛整体看：<b>同一个类里有"两样都要"的，也有一样都不要的，
 * 还有根本不发请求的</b>。
 *
 * <p>库内第 9 个门面（见 {@code Search} 的说明）。新增类，<b>不触碰任何既有签名</b>，
 * 对 XatiiBot 是纯增量。
 *
 * <p>为什么单独一个门面而不是塞进 {@code BilibiliClient}：{@code BilibiliClient} 的
 * 类名与 public 方法签名是<b>逐字冻结</b>的下游契约（XatiiBot 依赖），
 * 新能力一律走新类 —— 这是本库自 v1 起就写死的规矩。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code Live} / {@code BilibiliClient} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class VideoExtra {

    /**
     * <b>取视频 AI 摘要</b>（自动先取 {@code cid}）。
     *
     * <p>🔴 <b>本端点要"WBI 签名 + 登录凭据"两样，缺一不可</b>。它是本库唯一一个
     * "<b>签上名之后错误码会变</b>"的端点，也正是"两步判据"最好的教材：
     * <pre>
     * 匿名 · 无签名 → -403     匿名 · 有签名 → <b>-101</b>
     * 凭据 · 无签名 → -403     凭据 · 有签名 → <b>code=0</b>
     * </pre>
     * 第二格是关键：{@code -403}（访问权限不足）只是"没签名"的表象，
     * 签上名后暴露出真正缺的是登录（{@code -101}）。只看第一格会得出"这是权限问题、做不了"的错误结论。
     *
     * <p>⚠️ <b>判断"有没有摘要"只能用 {@link AiSummary#hasSummary()}</b>：
     * 实测 {@code data.code} 与 {@code data.status} 都是 0 而摘要正常返回，
     * 拿它们当判据会把"有摘要"读成"没有"。有些视频本身就没有摘要（正常情况，不是错误）。
     *
     * <p>⚠️ 本方法会先打一次 {@code x/web-interface/view} 拿 {@code cid}，
     * <b>多花一次请求</b>。已在别处拿过 {@code VideoInfo} 的调用方请直接用
     * {@link #getAiSummary(String, Long)}。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return AI 摘要，不可为 null（可能 {@code hasSummary()==false}）
     * @throws IOException {@code bvid} 为空、取 {@code cid} 失败、取不到 WBI 密钥、网络失败、
     *                     HTTP 非 2xx、业务码非 0（含 {@code -101} 未登录、{@code -352} 风控），
     *                     或 {@code data} 为空
     */
    public AiSummary getAiSummary(String bvid) throws IOException {
        try {
            return VideoService.INSTANCE.getAiSummary(bvid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取视频 AI 摘要</b>（已知 {@code cid}，省掉一次请求）。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param cid  视频分 P 的 cid；{@code VideoInfo#getCid()} 就是这个值
     * @return AI 摘要，不可为 null（可能 {@code hasSummary()==false}）
     * @throws IOException {@code bvid}/{@code cid} 为空、门槛/网络/业务码问题，见
     *                     {@link #getAiSummary(String)}
     */
    public AiSummary getAiSummary(String bvid, Long cid) throws IOException {
        try {
            return VideoService.INSTANCE.getAiSummary(bvid, cid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取视频流地址</b>（默认 720P / MP4）。
     *
     * <p>🔴 <b>要不要凭据：本批唯一"可选"的一项</b>。实测（2026-09-22）
     * <b>匿名</b>就能拿到 {@code code=0} + 可播放地址；凭据买到的是<b>更高清晰度</b>，
     * 而不是"能不能用"。所以未注入凭据时本方法<b>不会</b>失败，只是 {@code PlayUrl#getQuality()}
     * 会低一些。这与本批其余五项（<b>必须</b>有凭据）是一处关键差别，别一起记。
     *
     * <p>⚠️ <b>但这条链路会因出口信誉整条失败（HTTP {@code 412}）</b>，且历史上反复过
     * （09-13 挂、09-21 通、09-22 又挂）。所以它是"可能失败"的接口：
     * 抛出来的 {@code 412} 不是参数写错。库内已经处理了路径选择（走不带 {@code /wbi/} 的那条，
     * 理由见 {@code BilibiliEndpoint#playUrlPlainUrl}）与身份轮换，但<b>不保证</b>一定成功。
     *
     * <p>⚠️ 地址带时限（实测约 2 小时），<b>不要持久化缓存</b>。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param cid  分 P 的 cid（{@code VideoInfo#getCid()}）
     * @return 播放地址，不可为 null
     * @throws IOException {@code bvid}/{@code cid} 非法、网络失败、HTTP 非 2xx（含 {@code 412} 风控）、
     *                     业务码非 0、{@code data} 为空，或服务端一条可用地址都没给
     */
    public PlayUrl getPlayUrl(String bvid, Long cid) throws IOException {
        try {
            return VideoService.INSTANCE.getPlayUrl(bvid, cid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取视频流地址</b>（可指定清晰度与封装）。
     *
     * <p>🔴 <b>想要 1080P 就必须走 DASH</b>，而这是本条链路唯一"两个通道结果长得完全不同"的地方：
     * <ul>
     *   <li>{@code getPlayUrl(bvid, cid)} / {@code fnval=1} → <b>MP4</b>：地址在 {@code PlayUrl#getDurl()}，
     *       一条能直接播的整文件。实测<b>上限 720P</b>（{@code qn} 传 80 也只回 {@code quality=64}）。</li>
     *   <li>{@code getPlayUrl(bvid, cid, 80, 16)} / {@code fnval=16} → <b>DASH</b>：地址在
     *       {@code PlayUrl#getDash()}，实测能到 <b>1080P</b>（{@code quality=80}）。
     *       ⚠️ 但 DASH 的音视频是<b>两条独立流</b>，本库<b>不合流</b> —— 交给你的是素材，不是成品。</li>
     * </ul>
     *
     * @param bvid  BV 号（{@code BV1xxx...}）
     * @param cid   分 P 的 cid
     * @param qn    期望清晰度（{@code 64}=720P、{@code 80}=1080P…）；{@code null}/≤0 时按 64。
     *              <b>期望 ≠ 承诺</b>，实际值看 {@code PlayUrl#getQuality()}
     * @param fnval 封装（{@code 1}=MP4、{@code 16}=DASH）；{@code null}/≤0 时按 1（与 {@code qn} 同口径）
     * @return 播放地址，不可为 null
     * @throws IOException 同 {@link #getPlayUrl(String, Long)}
     */
    public PlayUrl getPlayUrl(String bvid, Long cid, Integer qn, Integer fnval) throws IOException {
        try {
            return VideoService.INSTANCE.getPlayUrl(bvid, cid, qn, fnval);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ B1 匿名高频域（2026-09-22 新增）

    /**
     * <b>取视频一站式详情</b>（{@code x/web-interface/view/detail}）—— 本批性价比最高的一项。
     *
     * <p>✅ <b>既不需要签名、也不需要凭据</b>（实测匿名 {@code code=0}）。
     *
     * <p>🔴 <b>一次出站顶四项需求</b>，拿到它就不要再分开打了：
     * <table border="1">
     *   <caption>一个响应里能取出什么（2026-09-22 实测）</caption>
     *   <tr><th>取法</th><th>得到</th></tr>
     *   <tr><td>{@code getView()}</td><td>视频主体（标题/简介/时长/UP 主…）</td></tr>
     *   <tr><td>{@code getView().getStat()}</td><td><b>状态数</b>（播放/点赞/投币/收藏/弹幕/评论/分享…13 项）</td></tr>
     *   <tr><td>{@code getTags()}</td><td><b>视频标签</b>（实测 11 个）</td></tr>
     *   <tr><td>{@code getRelated()}</td><td><b>相关推荐</b>（实测 40 条）</td></tr>
     *   <tr><td>{@code getCard()}</td><td>UP 主概览（粉丝数/投稿数/获赞数）</td></tr>
     * </table>
     *
     * <p>🔴 <b>唯一的例外是评论</b>：{@code getReply()} 只有 {@code page}（{@code null}）与
     * {@code replies}（<b>一条热评</b>）。要完整评论请用 {@code Comment#getRepliesByBvid} ——
     * <b>这一点必须记住</b>，因为"一站式"这个名字很容易让人以为评论也包了。
     *
     * <p>⚠️ {@code getCard()} 与 {@code CardInfo#getUserName} / {@code UserSpace} 在字段上重叠，
     * 已经打过名片的调用方<b>按需取用、不必两次请求同一份数据</b>。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return 一站式详情，不可为 null
     * @throws IOException {@code bvid} 为空、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public ViewDetail getViewDetail(String bvid) throws IOException {
        try {
            return VideoService.INSTANCE.getViewDetail(bvid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取在线观看数</b>（{@code x/player/online/total}）。
     *
     * <p>✅ 匿名可用（实测 {@code code=0}）—— 尽管文档把它标成"APP 端、需签名"。
     *
     * <p>⚠️ {@code cid} <b>必需</b>：只给 bvid 拿不到数据。手里的 {@code VideoInfo}
     * 直接用它 {@code getCid()} 即可（或从 {@link #getViewDetail} 的 {@code getView()} 里取）。
     *
     * <p>⚠️ 返回的 {@code total} / {@code count} 在 JSON 里是<b>字符串数字</b>（{@code "690"}），
     * 模型已经转成数字类型；但要知道原始形状是字符串。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param cid  分 P 的 cid
     * @return 在线观看数，不可为 null
     * @throws IOException {@code bvid}/{@code cid} 非法、网络失败、业务码非 0、或 {@code data} 为空
     */
    public OnlineTotal getOnlineTotal(String bvid, Long cid) throws IOException {
        try {
            return VideoService.INSTANCE.getOnlineTotal(bvid, cid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ B5 匿名补充域（2026-09-23 新增）

    /**
     * <b>取笔记入口是否被禁</b>（{@code x/note/is_forbid}）。
     *
     * <p>✅ <b>匿名可用、不需签名</b>（2026-09-23 实测：匿名与带凭据都是 {@code code=0}、
     * 形状完全相同）。
     *
     * <p>🔴 <b>本方法最该记住的一条：它不校验 {@code aid} 是否存在。</b>实测
     * {@code aid=1}（不存在的稿件）照样返回 {@code code=0} —— 也就是说
     * <b>返回值只对"真实存在的稿件"有意义</b>，传错 id <b>不会报错</b>，
     * 只会安静地给出一个与该稿件无关的布尔。这与本库其它"传错 id 会 {@code -404}"的端点不同
     * （同一批实测：{@code aid=1} 时 {@code x/note/is_forbid} 是 {@code 0}、
     * 而 {@code x/web-interface/view} 是 {@code 62012}）⇒ <b>别把它的成功当"id 有效"的证明</b>。
     *
     * <p>⚠️ 它是**只读**的“能不能进笔记”查询，<b>与"笔记内容"无关</b>：
     * {@code x/note/info}（取笔记正文）需要真实 {@code cvid}，本库<b>没有</b>也不打算给
     * （拿不到入口参数，见 {@code API_FACTS.md} §2.17）。想拿笔记正文的调用方不要在这里找。
     *
     * <p>⚠️ 响应里字段缺失时按 {@code false} 处理（服务端给的是布尔，不给 {@code null}）。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>；只有 bvid 时先用 {@link #toAid(String)} 换算）
     * @return {@code true} 表示该稿件的笔记入口被禁
     * @throws IOException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public boolean isNoteForbidden(long aid) throws IOException {
        try {
            return VideoService.INSTANCE.isNoteForbidden(aid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>bvid → aid</b>（纯算法，<b>零出站</b>）。
     *
     * <p>🔴 <b>为什么它值得单独暴露</b>：{@code bvid} 就是 {@code aid} 的 base58 编码，
     * 两者一一对应、<b>不需要请求任何接口</b>。而本库与 B 站的大量端点要的是 {@code aid}
     * （评论的 {@code oid}、笔记的 {@code aid}…），调用方手里却常年只有 {@code bvid}。
     * 此前唯一的办法是打一次 {@code x/web-interface/view} 换 {@code aid}（本库自己的
     * {@code Comment#getRepliesByBvid} 就这么干过）—— 本方法把那一次出站<b>彻底省掉</b>。
     *
     * <p>⚠️ <b>它不发请求，所以刻意不声明 {@code throws IOException}</b> ——
     * 逼调用方 {@code catch} 一个永不抛出的受检异常纯属噪音（与 {@code Wbi#signQuery} 的
     * 离线重载同一条规矩）。参数非法时抛的是 {@link IllegalArgumentException}。
     *
     * <p>⚠️ 它是<b>纯函数、不联网</b>，所以<b>不会告诉你这个 bvid 是否真实存在</b> ——
     * 格式合法即返回一个数。要"这个稿件在不在"请用 {@link #getViewDetail(String)}。
     *
     * @param bvid BV 号，形如 {@code BV1L9Uoa9EUx}（12 字符）
     * @return 对应的 {@code aid}（正整数）
     * @throws IllegalArgumentException {@code bvid} 格式不对（长度不是 12、不以 {@code BV} 开头，
     *                                  或含 base58 码表以外的字符 {@code 0} / {@code I} / {@code O} / {@code l}）
     */
    public long toAid(String bvid) {
        return BvCode.toAid(bvid);
    }

    /**
     * <b>aid → bvid</b>（纯算法，<b>零出站</b>）。
     *
     * <p>与 {@link #toAid(String)} 互逆，同样不发请求、同样不声明 {@code throws IOException}。
     *
     * <p>⚠️ {@code aid} 的可编码上限是 <b>{@code 2^51 - 1}</b>（这是 BV 号算法的硬边界，
     * 不是本库的限制），超出即 {@link IllegalArgumentException}。
     *
     * @param aid 稿件 avid（{@code 1 .. 2^51 - 1}）
     * @return 对应的 BV 号（12 字符）
     * @throws IllegalArgumentException {@code aid} 不在 {@code [1, 2^51)} 内
     */
    public String toBvid(long aid) {
        return BvCode.toBvid(aid);
    }
}

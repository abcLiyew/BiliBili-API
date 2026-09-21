package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.user.AccInfo;
import com.esdllm.bilibiliApi.model.data.pojo.user.ArchiveSearchResult;
import com.esdllm.bilibiliApi.model.data.pojo.user.SeasonsArchives;
import com.esdllm.bilibiliApi.service.UserService;

import java.io.IOException;

/**
 * 用户空间门面：<b>空间信息 / 投稿列表 / 视频合集</b>。
 *
 * <p>库内第 8 个门面（见 {@code Search} 的说明）。新增类，<b>不触碰任何既有签名</b>，
 * 对 XatiiBot 是纯增量。
 *
 * <p>⚠️ <b>与 {@code CardInfo} 的分工（别为同一份数据打两次请求）</b>：
 * <table border="1">
 *   <caption>两个门面各自独有的东西</caption>
 *   <tr><th>数据</th><th>去哪拿</th><th>说明</th></tr>
 *   <tr><td>粉丝数 / 投稿总数 / 获赞数</td><td>{@code CardInfo#getCard(uid)}</td>
 *       <td>本门面<b>没有</b>这些（{@code acc/info} 不含统计量）</td></tr>
 *   <tr><td>昵称 / 头像 / 等级 / 认证 / 大会员 / 签名</td><td>两边都有</td>
 *       <td>重叠，按需选一边即可</td></tr>
 *   <tr><td>{@code is_followed}（当前凭据是否已关注）</td><td>本门面 {@link #getAccInfo}</td>
 *       <td>{@code card} 没有</td></tr>
 *   <tr><td>投稿列表 / 合集内容</td><td>本门面</td><td>{@code card} 只有数量，没有列表</td></tr>
 * </table>
 *
 * <p><b>🔴 三个端点的"要不要登录"各不相同，这是本批最有价值的一组实测结论</b>
 * （2026-09-21，2×2 矩阵：匿不匿名 × 签不签名；<b>已两次独立复核</b>）：
 * <table border="1">
 *   <caption>端点 × 门槛</caption>
 *   <tr><th>方法</th><th>端点</th><th>WBI 签名</th><th>登录凭据</th></tr>
 *   <tr><td>{@link #getAccInfo}</td><td>{@code space/wbi/acc/info}</td>
 *       <td><b>要</b></td><td><b>要</b>（匿名无论签不签名都是 {@code -352}）</td></tr>
 *   <tr><td>{@link #getArchives}</td><td>{@code space/wbi/arc/search}</td>
 *       <td><b>要</b></td><td><b>要</b>（匿名签名仍 {@code -352}）</td></tr>
 *   <tr><td>{@link #getSeasonArchives}</td><td>{@code polymer/…/seasons_archives_list}</td>
 *       <td>不要</td><td>不要</td></tr>
 * </table>
 * ⚠️ 本批<b>没有一个"只要签名、不要登录"的端点</b> —— {@link #getAccInfo} 一度被记成那样，
 * 复核后推翻（原因与判据见 {@code BilibiliEndpoint} 的实测表与"第三处翻案"）。
 * 所以本门面<b>两个数据方法都需要先用
 * {@code Login#getCredentialStatus()} 确认凭据</b>，而不是拿异常当判据。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code Live} / {@code BilibiliClient} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class UserSpace {

    /**
     * <b>取用户空间信息</b>（{@code x/space/wbi/acc/info}）。
     *
     * <p>🔴 <b>它需要"WBI 签名 + 登录凭据"两样</b>（本库会自动算签名，凭据要调用方注入）。
     * 匿名调用会拿到 {@code -352 风控校验失败} —— 这个端点的<b>风控在签名校验之前</b>，
     * 所以"匿名 + 正确签名"也照样是 {@code -352}，别据此以为签名算错了。
     *
     * <p>拿不到粉丝数/投稿数（那些在 {@code CardInfo#getCard}）——
     * {@link AccInfo} 里有的是 {@code is_followed}（当前凭据是否关注）、更完整的 {@code vip}、
     * 以及 {@code live_room}（此人是否在播：{@code roomStatus}/{@code liveStatus}）。
     *
     * @param mid 用户 mid
     * @return 空间信息，不可为 null
     * @throws IOException {@code mid} ≤ 0、取不到 WBI 密钥、网络失败、业务码非 0，
     *                     或 {@code data} 为空（未注入凭据时即 {@code -352}/{@code -101}）
     */
    public AccInfo getAccInfo(long mid) throws IOException {
        try {
            return UserService.INSTANCE.getAccInfo(mid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取 UP 主投稿列表</b>（{@code x/space/wbi/arc/search}，按发布时间倒序）。
     *
     * <p>🔴 <b>需要"签名 + 登录"两样</b>：匿名签名仍 {@code -352}。未注入凭据时会失败，
     * 详见类注释的门槛表。
     *
     * @param mid 用户 mid
     * @param pn  页码（从 1 开始）
     * @param ps  每页条数（B 站网页端用 30；本库不夹上限，给多少发多少）
     * @return 投稿列表，不可为 null；{@code result.getList().getVlist()} 即当页稿件
     * @throws IOException 参数非法、取不到 WBI 密钥、网络失败、业务码非 0，或 {@code data} 为空
     */
    public ArchiveSearchResult getArchives(long mid, int pn, int ps) throws IOException {
        try {
            return UserService.INSTANCE.getArchives(mid, pn, ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取 UP 主投稿列表</b>（可指定排序）。
     *
     * @param mid   用户 mid
     * @param pn    页码（从 1 开始）
     * @param ps    每页条数
     * @param order 排序：{@code pubdate}（最新发布）/ {@code click}（最多播放）/ {@code stow}（最多收藏）；
     *              空值按 {@code pubdate}
     * @return 投稿列表，不可为 null
     * @throws IOException 同 {@link #getArchives(long, int, int)}
     */
    public ArchiveSearchResult getArchives(long mid, int pn, int ps, String order) throws IOException {
        try {
            return UserService.INSTANCE.getArchives(mid, pn, ps, order);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取视频合集内容</b>（{@code x/polymer/web-space/seasons_archives_list}）。
     *
     * <p>✅ <b>既不需要签名、也不需要登录</b>（匿名无签名即 {@code code=0}）—— 本批唯一的零门槛端点。
     *
     * <p>🔴 <b>{@code seasonId} 必须是真实存在的 id</b>，传 {@code 1} 只会得到
     * {@code -404 啥都木有}。不知道 id 时先用 {@link #findSeasonId(long)}。
     *
     * @param mid      合集所属 UP 主 mid
     * @param seasonId 合集 id（真实值）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 合集内容，不可为 null；{@code result.getArchives()} 即当页稿件
     * @throws IOException {@code seasonId} ≤ 0、网络失败、业务码非 0，或 {@code data} 为空
     */
    public SeasonsArchives getSeasonArchives(long mid, long seasonId, int pageNum, int pageSize)
            throws IOException {
        try {
            return UserService.INSTANCE.getSeasonArchives(mid, seasonId, pageNum, pageSize);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 取该 UP 主的<b>某个真实合集 id</b> —— 给 {@link #getSeasonArchives} 当入口用。
     *
     * <p>存在的理由：原定的"合集目录"端点 {@code polymer/web-space/seasons/list}
     * <b>已 HTTP 404 下线</b>（2026-09-21 复验），而 {@code seasons_archives_list} 又必须要真实 id。
     * 本方法从该 UP 的最新投稿里挑第一个非 0 的 {@code season_id}（实测可行）。
     *
     * <p>⚠️ <b>局限，别当成"列出全部合集"</b>：只翻一页投稿、只给<b>一个</b> id，
     * 且这个 id 一定是该 UP 有投稿进过的合集。"列出全部合集"在本库当前能力下做不到（入口已下线）。
     *
     * <p>⚠️ 它依赖投稿列表端点，因此<b>同样需要登录</b>。
     *
     * @param mid 用户 mid
     * @return 某个真实 {@code season_id}；首页投稿里没有合集稿件时返回 {@code null}
     * @throws IOException 参数非法、网络失败，或业务码非 0
     */
    public Long findSeasonId(long mid) throws IOException {
        try {
            return UserService.INSTANCE.findSeasonId(mid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import com.esdllm.bilibiliApi.model.data.pojo.user.*;
import com.esdllm.bilibiliApi.model.data.pojo.video.VideoBrief;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 用户名片数据服务（{@code CardInfo} 门面的后端）。
 *
 * <p>P1 起承担 {@code CardInfo} 门面的实际数据获取职责。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常）。
 * 旧版 {@code CardInfo.getBilibiliLiveResp(Long)} 的 {@code throws IOException} 声明作为红线保留，
 * 但实现已统一为"Service 抛 BilibiliException（runtime），门面边界透传不转换"——
 * §4.8.1 修：受检异常只能来自真受检异常路径，本服务不存在 IOException 路径。
 *
 * <p><b>无状态</b>：本服务不在内部缓存任何数据。"单槽缓存"留在门面层（{@code CardInfo.resp} 字段），
 * 由门面的 {@code isCached} 守卫跨 getter 复用——参 §6.4 "现状即如此，别改坏"。
 *
 * <p><b>2026-09-21 扩容（WBI 批）</b>：新增 {@link #getAccInfo(long)}（空间信息，<b>签名 + 登录</b>）、
 * {@link #getArchives(long, int, int)}（投稿列表，签名 + 登录）、
 * {@link #getSeasonArchives}（合集内容，<b>两者都不要</b>）与 {@link #findSeasonId(long)}
 * （取合集的备用前置）。三条既有方法一行未改。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class UserService {

    /** 单例入口，无状态。 */
    public static final UserService INSTANCE = new UserService();

    /**
     * 取一次完整 {@link BilibiliCardResp}（含 {@code card} + {@code archive_count} + 粉丝/点赞/...）。
     *
     * @param uid bilibili 用户的 uid
     * @return 不可为 null
     * @throws BilibiliException {@code uid} 为空 / ≤ 0、网络异常、JSON 解析失败、业务码非 0、{@code data} 为空
     */
    public BilibiliCardResp getCard(Long uid) {
        if (Objects.isNull(uid)) {
            throw new BilibiliException("uid不能为空");
        }
        if (uid <= 0) {
            throw new BilibiliException("uid不能小于0");
        }
        String url = BilibiliEndpoint.cardBaseUrl + uid;
        HttpResponse<String> response = BilibiliHttp.get(url);
        BilibiliCardResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliCardResp.class);
        } catch (Exception e) {
            throw new BilibiliException(e);
        }
        if (Objects.isNull(resp) || Objects.isNull(resp.getData()) || resp.getCode() != 0) {
            throw new BilibiliException("获取卡片信息失败");
        }
        return resp;
    }

    // ------------------------------------------------------------------ WBI 域（2026-09-21 新增）

    /**
     * <b>取用户空间信息</b>（{@code x/space/wbi/acc/info}）。
     *
     * <p>🔴 <b>它需要"WBI 签名 + 登录凭据"两样</b>。⚠️ 本端点一度被记成"匿名签名即通"，
     * 2026-09-21 复核（两次独立复现）实为：匿名<b>无论签不签名都是 {@code -352 风控校验失败}</b>，
     * 带凭据才是 {@code -403}（缺签名）/ {@code code=0}（齐了）。
     * 注意这里的顺序 —— 它<b>风控在签名校验之前</b>，所以"匿名那两格"看不出签名有没有用，
     * <b>只有带凭据才能把"缺签名"与"缺登录"分开</b>（详见 {@code BilibiliEndpoint} 的实测表）。
     *
     * <p>与 {@link #getCard(Long)} 的关系：两者字段<b>部分重叠</b>（昵称/头像/等级/认证/大会员），
     * 但本端点没有粉丝数/投稿数（那些在 {@code card} 的 {@code archive_count}/{@code follower}），
     * 而有 {@code is_followed}（当前凭据是否关注）与更完整的 {@code vip}。按需选用，别为同一份数据打两次。
     *
     * @param mid 用户 mid
     * @return 空间信息，不可为 null
     * @throws BilibiliException {@code mid} ≤ 0、取不到 WBI 密钥、网络失败、或业务码非 0
     *                           （未注入凭据时会拿到 {@code -352} 风控 / {@code -101} 未登录 ——
     *                           这不是"端点坏了"，而是它需要凭据）
     */
    public AccInfo getAccInfo(long mid) {
        if (mid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mid", String.valueOf(mid));

        HttpResponse<String> response = BilibiliHttp.getSigned(
                BilibiliEndpoint.accInfoUrl, params,
                BilibiliEndpoint.jsonAccept, spaceReferer(mid));
        AccInfo data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取用户空间信息");
        log.info("空间信息 mid={}：{}（等级 {}）", data.getMid(), data.getName(), data.getLevel());
        return data;
    }

    /**
     * <b>取 UP 主投稿列表</b>（{@code x/space/wbi/arc/search}）。
     *
     * <p>🔴 <b>需要"签名 + 登录"两样</b>：匿名签名仍 {@code -352}（实测）。未注入凭据时会拿到
     * {@code -352}/{@code -101} —— 与 {@link #getAccInfo(long)} 同样是"两样都要"，
     * 只是本端点<b>签名校验在前</b>（匿名先是 {@code -403}），
     * 调用方应先用 {@code Login#getCredentialStatus()} 判断凭据状态，而不是拿异常当判据。
     *
     * @param mid 用户 mid
     * @param pn  页码（从 1 开始）
     * @param ps  每页条数（B 站网页端用 30）
     * @return 投稿列表，不可为 null
     * @throws BilibiliException 参数非法、取不到 WBI 密钥、网络失败、业务码非 0、或 {@code data} 为空
     */
    public ArchiveSearchResult getArchives(long mid, int pn, int ps) {
        return getArchives(mid, pn, ps, "pubdate");
    }

    /**
     * <b>取 UP 主投稿列表</b>（可指定排序）。
     *
     * @param mid   用户 mid
     * @param pn    页码（从 1 开始）
     * @param ps    每页条数
     * @param order 排序：{@code pubdate}（最新发布）/ {@code click}（最多播放）/ {@code stow}（最多收藏）
     * @return 投稿列表，不可为 null
     * @throws BilibiliException 参数非法、取不到 WBI 密钥、网络失败、业务码非 0、或 {@code data} 为空
     */
    public ArchiveSearchResult getArchives(long mid, int pn, int ps, String order) {
        if (mid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("mid", String.valueOf(mid));
        params.put("pn", String.valueOf(Math.max(1, pn)));
        params.put("ps", String.valueOf(Math.max(1, ps)));
        params.put("order", (order == null || order.isBlank()) ? "pubdate" : order);

        HttpResponse<String> response = BilibiliHttp.getSigned(
                BilibiliEndpoint.arcSearchUrl, params,
                BilibiliEndpoint.jsonAccept, spaceReferer(mid));
        ArchiveSearchResult data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取用户投稿");
        int count = data.getList() == null || data.getList().getVlist() == null
                ? 0 : data.getList().getVlist().size();
        log.info("投稿 mid={} 第 {} 页：本页 {} 条 / 共 {} 条",
                mid, pn, count, data.getPage() == null ? null : data.getPage().getCount());
        return data;
    }

    /**
     * <b>取合集内容</b>（{@code x/polymer/web-space/seasons_archives_list}）。
     *
     * <p>✅ 与本批其它端点不同：<b>既不需要签名、也不需要登录</b>（2026-09-21 实测，
     * 匿名无签名即 {@code code=0}）。所以这里走的是普通 GET，不引签名依赖。
     *
     * <p>🔴 <b>{@code seasonId} 必须是真实存在的 id</b>：传 {@code 1} 只会得到
     * {@code -404 啥都木有}。不知道 id 时先用 {@link #findSeasonId(long)}。
     *
     * @param mid      合集所属 UP 主 mid
     * @param seasonId 合集 id（真实值）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 合集内容，不可为 null
     * @throws BilibiliException {@code seasonId} ≤ 0、网络失败、业务码非 0、或 {@code data} 为空
     */
    public SeasonsArchives getSeasonArchives(long mid, long seasonId, int pageNum, int pageSize) {
        if (seasonId <= 0) {
            throw new BilibiliException("合集内容获取失败：season_id 必须是真实 id（传 1 会得到 -404 啥都木有）");
        }
        String url = BilibiliEndpoint.seasonsArchivesUrl
                + "?mid=" + mid
                + "&season_id=" + seasonId
                + "&page_num=" + Math.max(1, pageNum)
                + "&page_size=" + Math.max(1, pageSize);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept, spaceReferer(mid));
        SeasonsArchives data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取合集内容");
        log.info("合集 season_id={}：{}，本页 {} 条 / 共 {} 条",
                seasonId, data.getMeta() == null ? "无元信息" : data.getMeta().getName(),
                data.getArchives() == null ? 0 : data.getArchives().size(),
                data.getPage() == null ? null : data.getPage().getTotal());
        return data;
    }

    /**
     * 取该 UP 主的<b>某个真实合集 id</b> —— 给 {@link #getSeasonArchives} 当入口用。
     *
     * <p><b>为什么需要它</b>：原定的"合集目录"端点 {@code polymer/web-space/seasons/list}
     * 已 <b>HTTP 404 下线</b>（2026-09-21 复验），而 {@code seasons_archives_list} 又必须要一个真实
     * {@code season_id}。本方法从该 UP 的最新投稿（{@code arc/search}）里挑第一个非 0 的
     * {@code season_id} —— 实测可行（本机取到 {@code 5485575}）。
     *
     * <p>⚠️ <b>局限，别把它当"列出全部合集"</b>：它只翻一页投稿、只给<b>一个</b> id，
     * 而且这个 id 一定是该 UP 有投稿进过的合集。要"列出全部合集"在本库当前能力下做不到
     * （入口端点已下线），这一点在 {@code INTERFACE_PLAN.md} 里记着。
     *
     * <p>⚠️ 本方法依赖 {@code arc/search}，因此<b>同样需要登录</b>。
     *
     * @param mid 用户 mid
     * @return 某个真实 {@code season_id}；该 UP 的首页投稿里没有合集稿件时返回 {@code null}
     * @throws BilibiliException 参数非法、网络失败、或业务码非 0
     */
    public Long findSeasonId(long mid) {
        ArchiveSearchResult archives = getArchives(mid, 1, 50);
        if (archives.getList() == null || archives.getList().getVlist() == null) {
            return null;
        }
        for (ArchiveSearchResult.ArchiveItem item : archives.getList().getVlist()) {
            if (item.getSeason_id() != null && item.getSeason_id() > 0) {
                log.info("从投稿里找到合集 season_id={}（稿件 {}）", item.getSeason_id(), item.getBvid());
                return item.getSeason_id();
            }
        }
        log.info("投稿首页里没有属于合集的稿件，取不到 season_id（mid={}）", mid);
        return null;
    }

    // ------------------------------------------------------------------ 凭据域（2026-09-22 B3.5 新增）

    /**
     * <b>取 UP 主累计数据</b>（{@code x/space/upstat}）：视频播放 / 专栏阅读 / 累计获赞。
     *
     * <p>🔴 <b>本方法是本库处理"静默空"的样板，读它比读任何文档都快</b>：
     * 该端点匿名时返回 <b>{@code code=0} + 空 {@code data}</b>（不是 {@code -101}！）。
     * 而空 {@code data} 会让"判 data 非 null"全部通过，于是安静地返回一个三字段全 null 的对象 ——
     * 与"这个 UP 主的播放量真的是 0"无法区分。
     * ⇒ 所以这里<b>把"整体为空"判成失败并显式抛异常</b>，理由写在异常消息里，而不是交给调用方去猜。
     *
     * <p>⚠️ 想要<b>粉丝数 / 投稿数</b>请用 {@link #getCard(Long)}，本端点没有那两项。
     *
     * <p>不需 WBI 签名。带凭据实测 {@code archive.view=9065} / {@code article.view=308} / {@code likes=408}。
     *
     * @param mid 用户 mid
     * @return 累计数据，不可为 null（整体为空时会抛异常而不是返回全 null 对象）
     * @throws BilibiliException {@code mid} ≤ 0、网络失败、业务码非 0，
     *                           或<b>服务端回 {@code code=0} 但 {@code data} 为空</b>
     *                           （几乎总是"没注入凭据"）
     */
    public UpStat getUpStat(long mid) {
        if (mid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        HttpResponse<String> response = BilibiliHttp.get(BilibiliEndpoint.upstatUrl + mid,
                BilibiliEndpoint.jsonAccept, spaceReferer(mid));
        UpStat data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取UP主累计数据");
        if (data.getArchive() == null && data.getArticle() == null && data.getLikes() == null) {
            throw new BilibiliException(0,
                    "获取UP主累计数据失败：服务端返回 code=0，但 data 是空对象 —— "
                            + "这不是'数据为 0'，而是'没有给出数据'",
                    "该端点匿名时正是这种形态（code=0 + 空 data），"
                            + "请先 Login#getCredentialStatus() 确认已注入有效凭据");
        }
        log.info("UP 累计数据 mid={}：播放 {} / 阅读 {} / 获赞 {}", mid,
                data.getArchive() == null ? null : data.getArchive().getView(),
                data.getArticle() == null ? null : data.getArticle().getView(),
                data.getLikes());
        return data;
    }

    /**
     * <b>取粉丝列表</b>（{@code x/relation/followers}）。
     *
     * <p>🔴 <b>真需登录</b>：匿名直接 {@code -101 账号未登录}（实测），无降级。
     * 与 {@link #getUpStat(long)} 那种"匿名也 {@code code=0} 但没数据"的形态不同。
     *
     * <p>⚠️ {@code vmid} 只对<b>自己的</b> mid 有意义，别当"查任意 UP 粉丝榜"用。
     *
     * <p>⚠️ 昵称字段是 {@code uname} 不是 {@code name}（见 {@code RelationList.RelationUser}）。
     *
     * @param vmid 用户 mid（实际只对本人有效）
     * @param pn   页码（从 1 开始）
     * @param ps   每页条数
     * @return 粉丝列表，不可为 null
     * @throws BilibiliException 参数非法、网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public RelationList getFollowers(long vmid, int pn, int ps) {
        return getRelations(BilibiliEndpoint.relationFollowersUrl, vmid, pn, ps,
                BilibiliEndpoint.spaceFansReferer, "获取粉丝列表");
    }

    /**
     * <b>取关注列表</b>（{@code x/relation/followings}）。
     *
     * <p>门槛与形状与 {@link #getFollowers(long, int, int)} 完全一致（两个端点实测同形），
     * 差别只在语义方向。同样<b>真需登录</b>（匿名 {@code -101}）。
     *
     * @param vmid 用户 mid（实际只对本人有效）
     * @param pn   页码（从 1 开始）
     * @param ps   每页条数
     * @return 关注列表，不可为 null
     * @throws BilibiliException 同 {@link #getFollowers(long, int, int)}
     */
    public RelationList getFollowings(long vmid, int pn, int ps) {
        return getRelations(BilibiliEndpoint.relationFollowingsUrl, vmid, pn, ps,
                BilibiliEndpoint.spaceFollowReferer, "获取关注列表");
    }

    // ------------------------------------------------------------------ 匿名域（2026-09-22 B1 新增）

    /**
     * <b>取用户关系数</b>（{@code x/relation/stat}）：关注数 / 粉丝数。
     *
     * <p>🔴 <b>本端点是"匿名可读"的，别与同域的名单端点混为一谈</b>（2026-09-22 实测）：
     * <table border="1">
     *   <caption>数量 vs 名单，门槛完全不同</caption>
     *   <tr><th>要什么</th><th>方法</th><th>匿名（无凭据）</th></tr>
     *   <tr><td><b>数量</b></td><td>本方法</td><td><b>{@code code=0}</b>，查任意用户都行</td></tr>
     *   <tr><td>名单</td><td>{@link #getFollowers} / {@link #getFollowings}</td>
     *       <td>{@code -101 账号未登录}，且只对本人 mid 有效</td></tr>
     * </table>
     * ⇒ 这条差别是本批最容易被"顺手合并"掉的地方：两者同域、同参数形状，
     * 但一个不需要凭据、一个必须有 —— <b>别为了少写一个方法把名单当成数量的重载</b>。
     *
     * <p>⚠️ 与 {@link #getCard(Long)} 的 {@code follower} 是同源数据的两个入口，
     * 已经打过名片的调用方不必再打这一条。
     *
     * @param vmid 用户 mid（<b>任意用户都有效</b>，不像名单端点只限本人）
     * @return 关系数，不可为 null
     * @throws BilibiliException {@code vmid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public RelationStat getRelationStat(long vmid) {
        if (vmid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        HttpResponse<String> response = BilibiliHttp.get(
                BilibiliEndpoint.relationStatUrl + "?vmid=" + vmid,
                BilibiliEndpoint.jsonAccept, spaceReferer(vmid));
        RelationStat data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取用户关系数");
        log.info("关系数 vmid={}：关注 {} / 粉丝 {}", vmid, data.getFollowing(), data.getFollower());
        return data;
    }

    /**
     * 粉丝 / 关注两个端点的共用实现。
     *
     * <p>合并的理由很窄：它们<b>实测连响应形状都一样</b>（{@code list}/{@code re_version}/{@code total}），
     * 参数也相同，只有 URL、Referer 与文案不同。这与"抽一个共享工具类给全库用"是两回事 ——
     * 它仍然私有在本服务内，改动面不会外溢。
     */
    private RelationList getRelations(String endpoint, long vmid, int pn, int ps,
                                     String refererTemplate, String action) {
        if (vmid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        String url = endpoint + "?vmid=" + vmid
                + "&pn=" + Math.max(1, pn)
                + "&ps=" + Math.max(1, ps);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                refererTemplate.formatted(String.valueOf(vmid)));
        RelationList data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, action);
        log.info("{} vmid={} 第 {} 页：本页 {} 人 / 共 {} 人", action, vmid, pn,
                data.getList() == null ? 0 : data.getList().size(), data.getTotal());
        return data;
    }

    /** 空间页 Referer（参数是 mid，形状见 {@code BilibiliEndpoint.spaceReferer}） */
    private static String spaceReferer(long mid) {
        return BilibiliEndpoint.spaceReferer.formatted(String.valueOf(mid));
    }

    // ------------------------------------------------------------------ 匿名域扩容（2026-09-23 B5 新增）

    /**
     * 「没有置顶视频」的业务码。
     *
     * <p>🔴 <b>它是正常结果，不是错误</b> —— 一个 UP 主没设置置顶时服务端就是这个码。
     * 因此本服务把它<b>翻译成 {@code null}</b>，而不是让它冒成异常（见
     * {@link #getTopArchive(long)}）。取值来自 2026-09-23 实测（{@code vmid=1} → {@code 53016}）。
     */
    private static final int CODE_NO_TOP_ARCHIVE = 53016;

    /**
     * <b>取 UP 主置顶视频</b>（{@code x/space/top/arc}，B5 批）。
     *
     * <p>✅ <b>匿名可用、不需签名、不需凭据</b>（2026-09-23 实测）。
     *
     * <p>🔴 <b>两种"没有"要分清</b>：本方法返回 {@code null} <b>只表示"这个 UP 主没有置顶视频"</b>
     * （服务端 {@code code=53016}），<b>不表示</b>"UP 不存在"—— 后者是 {@code -404}，
     * 会照常抛异常。别把 {@code null} 当成"查不到这个人"。
     *
     * <p>🔴 <b>它不能直接用 {@code requireData}</b>：那一层对 {@code code != 0} 一律抛异常，
     * 而 {@code 53016} 是"成功但没有内容"。所以这里<b>特意 catch 回来</b>再翻译成 {@code null} ——
     * 否则调用方会收到一个"异常"，误以为出了故障。
     *
     * <p>⚠️ <b>参数名是 {@code vmid}</b>，与 {@code is_forbid} 那类端点共用同一种命名，
     * 但与本库多数端点的 {@code mid} 不同 —— 传错就是 {@code -400}（09-22 盘点正是栽在这里）。
     *
     * @param vmid 用户 mid（<b>任意用户都有效</b>，不像粉丝/关注名单只限本人）
     * @return 置顶视频；该 UP 没有置顶时返回 {@code null}
     * @throws BilibiliException {@code vmid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0（{@code 53016} 除外）、
     *                           或 {@code code=0} 但 {@code data} 为空
     */
    public VideoBrief getTopArchive(long vmid) {
        if (vmid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        HttpResponse<String> response = BilibiliHttp.get(
                BilibiliEndpoint.spaceTopArcUrl + "?vmid=" + vmid,
                BilibiliEndpoint.jsonAccept, spaceReferer(vmid));
        try {
            VideoBrief data = ResponseParserSupport.requireData(response, new TypeReference<>() {
            }, "获取UP主置顶视频");
            log.info("置顶视频 vmid={}：bvid={}（{}）", vmid, data.getBvid(), data.getTitle());
            return data;
        } catch (BilibiliException e) {
            if (e.getCode() == CODE_NO_TOP_ARCHIVE) {
                log.info("UP {} 没有置顶视频（code={}，正常业务码，按 null 返回）", vmid, CODE_NO_TOP_ARCHIVE);
                return null;
            }
            throw e;
        }
    }

    private UserService() {}
}

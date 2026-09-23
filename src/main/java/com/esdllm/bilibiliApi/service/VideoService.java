package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliVideoResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.video.*;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 视频数据服务（{@code BilibiliClient} 门面的后端）。
 *
 * <p>P1 起承担 {@code BilibiliClient} 门面的实际数据获取职责。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常）。
 * 旧 {@code BilibiliClient.getVideoInfo(String/Long)} 的 {@code throws IOException} 声明
 * 作为红线保留，由门面边界按 §4.8.1 重新包装后抛出（{@code IOException}）。
 *
 * <p><b>无状态</b>：本服务不持有任何视频缓存。"单槽缓存"留在门面层（{@code BilibiliClient.videoInfo} 字段），
 * 由门面的 {@code isCached} 守卫跨 getter 复用——参 §6.4 "现状即如此，别改坏"。
 *
 * <p><b>2026-09-21 扩容（WBI 批）</b>：新增 {@link #getAiSummary(String, Long)} /
 * {@link #getAiSummary(String)}（AI 摘要，<b>签名 + 登录</b>都要）。
 * 两个既有 {@code getVideoInfo} 重载一行未改。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class VideoService {

    /** 单例入口，无状态。 */
    public static final VideoService INSTANCE = new VideoService();

    /**
     * 取一次视频信息（按 BV 号）。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return 不可为 null
     * @throws BilibiliException {@code bvid} 为空、网络异常、JSON 解析失败、业务码非 0
     */
    public VideoInfo getVideoInfo(String bvid) {
        if (bvid == null) {
            throw new BilibiliException("BV号不能为空");
        }
        String url = BilibiliEndpoint.videoBaseUrl + bvid;
        return doGet(url);
    }

    /**
     * 取一次视频信息（按 AV 号）。
     *
     * @param aid AV 号（数字 id）
     * @return 不可为 null
     * @throws BilibiliException {@code aid} 为空或 ≤ 0、网络异常、JSON 解析失败、业务码非 0
     */
    public VideoInfo getVideoInfo(Long aid) {
        if (aid == null || aid <= 0) {
            throw new BilibiliException("AV号不能为空");
        }
        String url = BilibiliEndpoint.videoAvBaseUrl + aid;
        return doGet(url);
    }

    private VideoInfo doGet(String url) {
        HttpResponse<String> response = BilibiliHttp.get(url);
        BilibiliVideoResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliVideoResp.class);
        } catch (Exception e) {
            throw new BilibiliException("获取视频信息失败 错误在" + VideoService.class.getName() + "错误消息：" + e);
        }
        if (resp == null || resp.getCode() != 0) {
            throw new BilibiliException("获取视频信息失败");
        }
        return resp.getData();
    }

    // ------------------------------------------------------------------ AI 摘要（2026-09-21 新增）

    /**
     * <b>取视频 AI 摘要</b>（{@code x/web-interface/view/conclusion/get}）—— 已知 {@code cid}。
     *
     * <p>🔴 <b>本端点要"WBI 签名 + 登录凭据"两样，缺一不可</b>（2026-09-21 的 2×2 实测）：
     * <pre>
     * 匿名 · 无签名 → -403     匿名 · 有签名 → <b>-101</b>
     * 凭据 · 无签名 → -403     凭据 · 有签名 → <b>code=0</b>
     * </pre>
     * 关键在第二格：<b>签上名之后错误码从 {@code -403} 变成 {@code -101}</b>，
     * 说明 {@code -403} 只是"没签名"的表象，真正缺的是登录。只看匿名那一列会误判成"权限不足、做不了"。
     *
     * <p>它走 {@link BilibiliHttp#getSigned} 而不是普通 GET：既为了算 {@code w_rid}，
     * 也为了让 {@code -101}/{@code -352} 的原生语义直接冒到调用方。
     * 未注入凭据时调用方会拿到 {@code -101}（不可重试），
     * 建议先用 {@code Login#getCredentialStatus()} 判断凭据状态，别拿异常当判据。
     *
     * <p>⚠️ <b>"有没有摘要"不要看 {@link AiSummary#getStatus()}，也不看它自己的 {@code code}</b>：
     * 实测两者都是 0 而摘要正常返回。唯一稳定的判据是 {@link AiSummary#hasSummary()}。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param cid  视频分 P 的 cid（{@code VideoInfo#getCid()}）；也可走 {@link #getAiSummary(String)} 自动取
     * @return AI 摘要，不可为 null（可能 {@code hasSummary()==false}，表示该视频暂无摘要）
     * @throws BilibiliException {@code bvid}/{@code cid} 为空、取不到 WBI 密钥、网络失败、
     *                           HTTP 非 2xx、业务码非 0（含 {@code -101} 未登录、{@code -352} 风控）、或 {@code data} 为空
     */
    public AiSummary getAiSummary(String bvid, Long cid) {
        if (bvid == null || bvid.isBlank()) {
            throw new BilibiliException("BV号不能为空");
        }
        if (cid == null || cid <= 0) {
            throw new BilibiliException("cid不能为空");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", String.valueOf(cid));

        HttpResponse<String> response = BilibiliHttp.getSigned(
                BilibiliEndpoint.viewConclusionUrl, params,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.videoReferer.formatted(bvid));
        AiSummary data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取AI摘要");
        log.info("AI 摘要 bvid={} cid={}：{}（大纲 {} 段）", bvid, cid,
                data.hasSummary() ? "有" : "无",
                data.getModel_result() == null || data.getModel_result().getOutline() == null
                        ? 0 : data.getModel_result().getOutline().size());
        return data;
    }

    /**
     * <b>取视频 AI 摘要</b>（自动先取 {@code cid}）。
     *
     * <p>它会先打一次 {@code x/web-interface/view}（{@link #getVideoInfo(String)}）拿 {@code cid}
     * —— 即<b>多花一次请求</b>。已在别处拿过 {@code VideoInfo} 的调用方应直接用
     * {@link #getAiSummary(String, Long)}，别走本方法。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return AI 摘要，不可为 null
     * @throws BilibiliException 取 {@code cid} 失败，或其后的任一失败原因（见
     *                           {@link #getAiSummary(String, Long)}）
     */
    public AiSummary getAiSummary(String bvid) {
        Long cid = getVideoInfo(bvid).getCid();
        if (cid == null || cid <= 0) {
            throw new BilibiliException("获取AI摘要失败：视频信息里没有可用的 cid（bvid=" + bvid + "）");
        }
        return getAiSummary(bvid, cid);
    }

    // ------------------------------------------------------------------ 播放地址（2026-09-22 B3.5 新增）

    /**
     * <b>取视频流地址</b>（默认 720P / MP4）。
     *
     * <p>等价于 {@link #getPlayUrl(String, Long, Integer, Integer) getPlayUrl(bvid, cid, 64, 1)}
     * —— 拿到一条<b>能直接播</b>的 MP4 地址，在 {@code getDurl().get(0).getUrl()} 上。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param cid  分 P 的 cid（{@code VideoInfo#getCid()}）
     * @return 播放地址，不可为 null
     * @throws BilibiliException 参数非法、网络失败、HTTP 非 2xx（含 {@code 412} 风控）、
     *                           业务码非 0、{@code data} 为空，或服务端<b>一条可用地址都没给</b>
     */
    public PlayUrl getPlayUrl(String bvid, Long cid) {
        return getPlayUrl(bvid, cid, 64, 1);
    }

    /**
     * <b>取视频流地址</b>（可指定清晰度与封装）。
     *
     * <p>🔴 <b>走的是 {@code x/player/playurl} —— 不带 {@code /wbi/} 的那条路径</b>。
     * 这不是笔误，是 2026-09-22 实测出来的：同一个 bvid/cid、同一枚凭据、同一分钟，
     * {@code /wbi/} 那条在<b>七种组合下全是 HTTP 412</b>（含签名、含凭据、含指纹轮换 6 代），
     * 而<b>去掉 {@code /wbi/}</b> 立刻 {@code code=0}。详见 {@code BilibiliEndpoint#playUrlPlainUrl}。
     *
     * <p><b>两条通道，参数与结果的位置都不同</b>（实测数字见 {@link PlayUrl} 的表）：
     * <ul>
     *   <li>{@code fnval=1} → <b>MP4</b>，地址在 {@code durl}。<b>上限 720P</b>：
     *       本次实测传 {@code qn=80} 也只回 {@code quality=64}。</li>
     *   <li>{@code fnval=16} → <b>DASH</b>，地址在 {@code dash}，<b>能到 1080P</b>（{@code quality=80}）。
     *       ⚠️ DASH 是<b>音视频分离</b>的两条流，本库<b>不合流</b> —— 交给你的是素材，不是成品。</li>
     * </ul>
     *
     * <p>⚠️ <b>会不会失败取决于出口信誉，不是取决于参数</b>：这条链路历史上"挂 → 通 → 又挂"，
     * 拿到 {@code 412} 不是代码错了。库内已尽力（风控身份轮换），但<b>不保证</b>一定能换到能用的身份。
     *
     * <p>⚠️ 返回的地址带 {@code deadline}（实测约 2 小时后过期），<b>不要长期缓存</b>。
     *
     * @param bvid  BV 号（{@code BV1xxx...}）
     * @param cid   分 P 的 cid
     * @param qn    期望清晰度：{@code 16}/360P、{@code 32}/480P、{@code 64}/720P、{@code 80}/1080P；
     *              {@code null} 或 ≤ 0 时按 {@code 64}。<b>期望 ≠ 承诺</b> —— 实际给了什么看
     *              {@code PlayUrl#getQuality()}（实测传 80 走 MP4 只给 64）
     * @param fnval 封装：{@code 1}=MP4、{@code 16}=DASH；{@code null} 或 ≤ 0 时按 {@code 1}
     *              （与 {@code qn} 同口径：两个参数都只认正数，非正数一律回落默认）
     * @return 播放地址，不可为 null
     * @throws BilibiliException {@code bvid}/{@code cid} 非法、网络失败、HTTP 非 2xx（含 {@code 412}）、
     *                           业务码非 0、{@code data} 为空，或服务端既没给 {@code durl} 也没给 {@code dash}
     */
    public PlayUrl getPlayUrl(String bvid, Long cid, Integer qn, Integer fnval) {
        if (bvid == null || bvid.isBlank()) {
            throw new BilibiliException("BV号不能为空");
        }
        if (cid == null || cid <= 0) {
            throw new BilibiliException("cid不能为空");
        }
        int qnValue = (qn == null || qn <= 0) ? 64 : qn;
        // 与 qn 用同一条口径（null 或 ≤0 都回落），别一个用 <0 一个用 ≤0：
        // 本库只认 1=MP4 / 16=DASH 两个通道，0 不在契约内，原样发出去等于把脏参数交给服务端
        int fnvalValue = (fnval == null || fnval <= 0) ? 1 : fnval;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", String.valueOf(cid));
        params.put("qn", String.valueOf(qnValue));
        params.put("fnval", String.valueOf(fnvalValue));
        // fourk=1 才允许出现 4K 档位；不传它时服务端连"可选 4K"都不会列出来
        params.put("fourk", "1");

        String url = BilibiliEndpoint.playUrlPlainUrl + "?" + queryOf(params);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.videoReferer.formatted(bvid));
        PlayUrl data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取视频流地址");

        boolean hasMp4 = data.getDurl() != null && !data.getDurl().isEmpty();
        boolean hasDash = data.getDash() != null;
        if (!hasMp4 && !hasDash) {
            // 这里必须显式失败：{@code code=0} 只说明"接口调通了"。若把"一个地址都没有"的响应
            // 当成功交出去，调用方会在很久以后对着 null 猜"是不是我取错层了"。
            throw new BilibiliException(0,
                    "获取视频流地址失败：服务端返回 code=0，但既没有 durl 也没有 dash"
                            + "（本次 qn=" + qnValue + " fnval=" + fnvalValue + "）",
                    "没有可播放地址");
        }
        log.info("播放地址 bvid={} cid={}：quality={} format={} —— {}",
                bvid, cid, data.getQuality(), data.getFormat(),
                hasMp4 ? ("MP4，durl " + data.getDurl().size() + " 段")
                        : "DASH，音视频分流（本库不合流）");
        return data;
    }

    // ------------------------------------------------------------------ B1 匿名高频域（2026-09-22 新增）

    /**
     * <b>取视频一站式详情</b>（{@code x/web-interface/view/detail}）。
     *
     * <p>🔴 <b>它一个端点顶四个需求，是本批"最省出站"的一项</b>（2026-09-22 实测，16 个顶层键）：
     * <ul>
     *   <li>{@code View}（49 键，<b>内含完整 {@code stat} 13 项</b>）⇒ 视频详情 <b>+ 状态数</b>；</li>
     *   <li>{@code Tags}（11 条）⇒ 视频标签；{@code Related}（40 条）⇒ 相关推荐；</li>
     *   <li>{@code Card}（UP 主概览）。</li>
     * </ul>
     * ⇒ 想要标签/相关/状态数时<b>直接读这一个响应</b>，不要再去打 {@code tag/archive/tags}
     * 或 {@code archive/related} —— 同一份数据打两次请求没有任何收益。
     *
     * <p>🔴 <b>但评论不在此列</b>：响应里的 {@code Reply} 只有 {@code page}（{@code null}）与
     * {@code replies}（<b>一条热评</b>）。完整评论要走 {@link CommentService#getReplies}。
     * 这条边界很反直觉，所以两个地方都写了。
     *
     * <p>匿名可用（实测 {@code code=0}），不需要 WBI 签名。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return 一站式详情，不可为 null
     * @throws BilibiliException {@code bvid} 为空、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public ViewDetail getViewDetail(String bvid) {
        if (bvid == null || bvid.isBlank()) {
            throw new BilibiliException("BV号不能为空");
        }
        String url = BilibiliEndpoint.viewDetailUrl + "?bvid=" + bvid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.videoReferer.formatted(bvid));
        ViewDetail data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取视频详情");
        // 这里把"顺手拿到了几项"打出来：它是判断本端点形状有没有变的第一个信号
        log.info("视频详情 bvid={}：标题={}，标签 {} 个，相关 {} 条，热评 {} 条{}",
                bvid,
                data.getView() == null ? null : data.getView().getTitle(),
                data.getTags() == null ? 0 : data.getTags().size(),
                data.getRelated() == null ? 0 : data.getRelated().size(),
                data.getReply() == null || data.getReply().getReplies() == null
                        ? 0 : data.getReply().getReplies().size(),
                data.getView() == null || data.getView().getStat() == null ? ""
                        : "，播放 " + data.getView().getStat().getView());
        return data;
    }

    /**
     * <b>取在线观看数</b>（{@code x/player/online/total}）。
     *
     * <p>匿名可用（实测 {@code code=0}）—— 文档标"APP 端、需签名"，实测都不需要。
     *
     * <p>⚠️ {@code total} / {@code count} 在 JSON 里是<b>字符串数字</b>（{@code "690"}），
     * 模型用 {@code Long} 接（fastjson 自动转）。
     *
     * <p>⚠️ {@code cid} 是<b>必需</b>的：只给 bvid 拿不到结果。已有 {@link VideoInfo} 的调用方
     * 直接用 {@code getCid()}。
     *
     * @param bvid BV 号
     * @param cid  分 P 的 cid
     * @return 在线观看数，不可为 null
     * @throws BilibiliException {@code bvid}/{@code cid} 非法、网络失败、业务码非 0、或 {@code data} 为空
     */
    public OnlineTotal getOnlineTotal(String bvid, Long cid) {
        if (bvid == null || bvid.isBlank()) {
            throw new BilibiliException("BV号不能为空");
        }
        if (cid == null || cid <= 0) {
            throw new BilibiliException("cid不能为空");
        }
        String url = BilibiliEndpoint.onlineTotalUrl + "?bvid=" + bvid + "&cid=" + cid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.videoReferer.formatted(bvid));
        OnlineTotal data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取在线观看数");
        log.info("在线观看 bvid={} cid={}：总数 {} / 人数 {}", bvid, cid, data.getTotal(), data.getCount());
        return data;
    }

    /**
     * <b>取视频排行榜</b>（{@code x/web-interface/ranking/v2}）。
     *
     * <p>🔴 <b>本方法唯一需要读懂的地方是 Referer</b>：这个端点对<b>站根</b> Referer
     * （本库其它端点的默认值）会返回 {@code -352 风控校验失败}，换成排行榜页就 {@code code=0}
     * （2026-09-22 实测，两轮 4 次复现；见 {@code BilibiliEndpoint#rankingUrl} 的对照表）。
     * 所以这里固定用 {@code rankingReferer} —— <b>改了这一行会让整条链路挂掉，而且报的是风控码，
     * 看起来像"出口被封"而不是"Referer 写错了"。</b>
     *
     * <p>匿名可用，不需要签名。
     *
     * @param rid  分区 id（{@code 0}=全站、{@code 1}=动画…）；{@code <0} 时按 {@code 0}
     * @param type 榜单类型（实测用 {@code all}）；空值时按 {@code all}
     * @return 榜单，不可为 null
     * @throws BilibiliException 网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public RankingList getRanking(int rid, String type) {
        String typeValue = (type == null || type.isBlank()) ? "all" : type;
        String url = BilibiliEndpoint.rankingUrl
                + "?rid=" + Math.max(0, rid) + "&type=" + typeValue;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.rankingReferer);
        RankingList data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取排行榜");
        log.info("排行榜 rid={} type={}：{} 条（{}）", rid, typeValue,
                data.getList() == null ? 0 : data.getList().size(), data.getNote());
        return data;
    }

    /**
     * <b>取热门视频</b>（{@code x/web-interface/popular}）。
     *
     * <p>匿名可用，<b>用站根 Referer 即可</b>（与 {@link #getRanking} 不同 ——
     * 两者是同一分钟实测对照过的，只有排行榜对站根敏感）。
     *
     * <p>⚠️ 响应只有 {@code list} 与 {@code no_more}，<b>没有页码</b>：
     * 想翻页要自己记住传进来的 {@code pn}；{@code no_more=true} 就该停了。
     *
     * @param ps 每页条数；{@code ≤0} 时按 20
     * @param pn 页码；{@code ≤0} 时按 1
     * @return 热门列表，不可为 null
     * @throws BilibiliException 网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public PopularList getPopular(int ps, int pn) {
        int psValue = ps <= 0 ? 20 : ps;
        int pnValue = Math.max(1, pn);
        String url = BilibiliEndpoint.popularUrl + "?ps=" + psValue + "&pn=" + pnValue;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        PopularList data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取热门视频");
        log.info("热门视频 第 {} 页：{} 条，还有更多={}", pnValue,
                data.getList() == null ? 0 : data.getList().size(), !Boolean.TRUE.equals(data.getNo_more()));
        return data;
    }

    // ------------------------------------------------------------------ B5 批（2026-09-23）

    /**
     * <b>取笔记入口是否被禁</b>（{@code x/note/is_forbid}，B5 批）。
     *
     * <p>✅ <b>匿名可用、不需签名</b>；匿名与带凭据<b>形状完全相同</b>（2026-09-23 实测）。
     *
     * <p>🔴 <b>它不校验 {@code aid} 是否存在 —— 这是本方法最该记住的一条。</b>
     * 实测 {@code aid=1}（不存在的稿件）返回的仍是 {@code code=0} + 一个布尔
     * ⇒ <b>调用方不能拿"调用成功"当"这个稿件存在"的证明</b>。
     * 同批对照：同一个 {@code aid=1} 在 {@code web-interface/view} 上是 {@code 62012}
     * —— 想在取本值之前确认稿件有效，请自己先打一次 view。
     *
     * <p>⚠️ 服务端给的是布尔；字段缺失时按 {@code false} 处理（不抛异常），
     * 因为"没给"在这种单布尔端点里只可能是解析异常，而不是一个需要调用方处置的状态。
     *
     * @param aid 稿件 avid（<b>不是 bvid</b>）
     * @return {@code true} 表示该稿件的笔记入口被禁
     * @throws BilibiliException {@code aid} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public boolean isNoteForbidden(long aid) {
        if (aid <= 0) {
            throw new BilibiliException("aid不能小于0");
        }
        String url = BilibiliEndpoint.noteIsForbidUrl + "?aid=" + aid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        NoteForbid data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取笔记入口状态");
        boolean forbidden = Boolean.TRUE.equals(data.getForbid_note_entrance());
        log.info("笔记入口 aid={}：{}", aid, forbidden ? "已禁" : "可用");
        return forbidden;
    }

    /** 按插入顺序拼 query（值不做 URL 编码：本批参数全是数字与 {@code BV…} 这类安全串） */
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

    private VideoService() {}
}

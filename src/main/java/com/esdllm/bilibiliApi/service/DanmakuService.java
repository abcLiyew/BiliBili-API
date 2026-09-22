package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuItem;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuXml;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * <b>弹幕服务</b>（{@code Danmaku} 门面的后端，B2 批 #1）。
 *
 * <p>🔴 <b>本服务是全库唯一不能走 {@code ResponseParserSupport} 的</b>：
 * 那个收口的前提是"响应是 JSON 且有 {@code code}/{@code data} 外层"，
 * 而 {@code x/v1/dm/list.so} 返回的是<b>裸 XML</b>。所以这里自己判 HTTP 状态
 * （复用 {@link ErrorMapper#forHttpStatus} 保持文案口径一致），再把正文交给
 * {@link DanmakuXml#parse(String)}。
 *
 * <p>🔴 <b>参数是 {@code cid}，既不是 {@code aid} 也不是 {@code bvid}</b>：
 * 端点的参数名叫 {@code oid}，但它的值必须是<b>分 P 的 {@code cid}</b>。
 * 传 {@code aid} 或 {@code BV…} 都会得到 HTTP 400 或空 XML，<b>不会报"参数错"</b>。
 * 拿 {@code cid} 的常规路径：{@code VideoExtra#getViewDetail} 的 {@code pages[].cid}，
 * 或 {@code FavResourceList.Media.Ugc#getFirst_cid()}。
 *
 * <p>✅ <b>{@code Content-Encoding: deflate} 由 HTTP 层自动解开</b>（2026-09-22 实测，
 * 见 {@code B2PreflightSmokeTest} 的 {@code VERDICT=A}）。这一点是承重的：
 * 若哪天底层不再自动解压，{@code getBody()} 会变成乱码，而 {@link DanmakuXml#parse}
 * 会<b>立刻抛异常</b>而不是静默给空列表 —— 这条链路的失败模式是"响亮地坏掉"。
 *
 * <p><b>异常语义</b>：只抛 {@link BilibiliException}（运行时），门面边界包装成 {@code IOException}。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class DanmakuService {

    /** 单例入口，无状态。 */
    public static final DanmakuService INSTANCE = new DanmakuService();

    /**
     * <b>取某个分 P 的全部弹幕</b>（{@code x/v1/dm/list.so}）。
     *
     * <p>✅ <b>匿名可用</b>，且 <b>{@code Referer} 不影响结果</b> —— 2026-09-22 用
     * 三格对照（不传 / 站根 / 视频页）实测三者的<b>响应字节数完全相同</b>，
     * 所以本方法用全库默认的站根 Referer，<b>不需要调用方再传 bvid</b>。
     * （对照：{@code x/web-interface/ranking/v2} 是"站根会挂"的那种，见 {@code rankingReferer}。）
     *
     * <p>⚠️ <b>没有翻页参数，{@link DanmakuXml#getMaxlimit()} 就是硬上限</b>
     * （实测有 {@code 300} / {@code 1000} 两种取值，随视频设置而变）。
     * 当返回条数<b>正好等于</b>上限时，本方法会打一条 {@code WARN} —— 那是"被截断了"，
     * 不是"这个视频只有这么多"。
     *
     * <p>⚠️ <b>零弹幕是合法结果</b>：实测一个没有弹幕的视频返回的 XML 里只有
     * {@code <i>} 头、没有 {@code <d>}，{@code maxlimit=300}。这种情况返回
     * {@link DanmakuXml} 且 {@code getDanmaku()} 为空列表，<b>不抛异常</b> ——
     * 与 {@code fav/folder/created/list-all} 的"空列表必须抛"是相反的处理，
     * 区别在于：这边空列表<b>无法</b>由"缺凭据"造成（本端点匿名就给全量）。
     *
     * @param cid 分 P 的 {@code cid}（<b>不是 aid、不是 bvid</b>），必须 &gt; 0
     * @return 弹幕集合（含 {@code chatid}/{@code maxlimit} 等头部信息），不可为 null
     * @throws BilibiliException {@code cid} ≤ 0、HTTP 非 2xx、或响应不是弹幕 XML
     *                           （后者最常见成因见 {@link DanmakuXml#parse}）
     */
    public DanmakuXml getDanmaku(long cid) {
        if (cid <= 0) {
            throw new BilibiliException("cid不能小于0");
        }
        String url = BilibiliEndpoint.dmListUrl + "?oid=" + cid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);

        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), "获取弹幕");
        if (httpError != null) {
            throw httpError;
        }
        DanmakuXml data = DanmakuXml.parse(response.getBody());

        int size = data.getDanmaku() == null ? 0 : data.getDanmaku().size();
        Integer maxlimit = data.getMaxlimit();
        if (maxlimit != null && maxlimit > 0 && size >= maxlimit) {
            log.warn("弹幕 cid={}：拿到 {} 条，已达 maxlimit={} —— 结果被截断，"
                            + "该端点没有翻页参数，多出来的弹幕这个接口拿不到",
                    cid, size, maxlimit);
        } else {
            log.info("弹幕 cid={}：{} 条（maxlimit={}）", cid, size, maxlimit);
        }
        return data;
    }

    /**
     * <b>只要弹幕文本列表</b>的便捷重载 —— 等价于 {@code getDanmaku(cid).getDanmaku()}。
     *
     * <p>存在的理由：绝大多数调用方只要 {@code List<DanmakuItem>}。但
     * <b>{@link DanmakuXml#getMaxlimit()} 是判断"是否被截断"的唯一依据</b>，
     * 需要这个信息时请用 {@link #getDanmaku(long)}。
     *
     * @param cid 分 P 的 {@code cid}
     * @return 弹幕列表；无弹幕时为空列表（<b>不会是 null</b>）
     * @throws BilibiliException 同 {@link #getDanmaku(long)}
     */
    public List<DanmakuItem> getDanmakuList(long cid) {
        List<DanmakuItem> list = getDanmaku(cid).getDanmaku();
        return list == null ? Collections.emptyList() : list;
    }

    private DanmakuService() {
    }
}

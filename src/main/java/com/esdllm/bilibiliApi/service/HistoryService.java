package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson2.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.content.HistoryCursor;
import com.esdllm.bilibiliApi.model.data.pojo.content.ToViewList;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>观看历史 / 稍后再看</b>数据服务（{@code Content} 门面的后端，B3.5 批 #3 / #5）。
 *
 * <p>这两件事放在同一个服务里，是因为它们<b>同属"个人播放队列"</b>：
 * 一个记录"看过什么"、一个记录"打算看什么"，参数与门槛都一致（都<b>真需登录</b>，无 WBI 签名），
 * 而各自的响应结构差异大到没必要共用 POJO。
 *
 * <p>📌 <b>两者都被 {@code INTERFACE_PLAN.md} 误归过 B4"写操作"栏</b>，那是个分类错误 ——
 * 它们都是 <b>GET 只读</b>。真正的写操作（往"稍后再看"里增删、清空历史）另有端点，
 * 且要带 {@code csrf=bili_jct}，<b>本库不做</b>。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常），
 * 由门面边界按既有约定包装成 {@code IOException}。
 *
 * <p><b>无状态</b>：不缓存任何数据。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class HistoryService {

    /** 单例入口，无状态。 */
    public static final HistoryService INSTANCE = new HistoryService();

    /**
     * <b>取观看历史第一页</b>（{@code x/web-interface/history/cursor}）。
     *
     * @param ps 每页条数（实测只吃<b>很小的</b>合法区间，传超大值会被服务端拒掉而不是"尽量多给"）
     * @return 历史，不可为 null
     * @throws BilibiliException {@code ps} ≤ 0、网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public HistoryCursor getWatchHistory(int ps) {
        return getWatchHistory(ps, null, null, null);
    }

    /**
     * <b>取观看历史（可翻页）</b>。
     *
     * <p>🔴 <b>翻页是游标式的，不是页码式</b>：把上一条结果里的
     * {@code data.cursor.max} / {@code view_at} / {@code business} <b>原样传进来</b>即得下一页。
     * 三个值<b>缺一不可</b> —— 只带 {@code max} 在跨业务（如从视频翻到直播）时会跳错位置。
     * 本方法把它们做成显式参数，就是为了让"翻页这件事"在调用方看得见，
     * 而不是藏在某个内部状态里（本服务无状态，这一点是刻意的）。
     *
     * @param ps       每页条数；{@code null} 时不传该参数（由服务端决定）
     * @param max      游标主键；首页传 {@code null}
     * @param viewAt   游标时间；首页传 {@code null}
     * @param business 业务类型（{@code archive} / {@code live} / {@code article}）；首页传 {@code null}
     * @return 历史，不可为 null
     * @throws BilibiliException 参数非法、网络失败、业务码非 0（含 {@code -101} 未登录）、或 {@code data} 为空
     */
    public HistoryCursor getWatchHistory(Integer ps, Long max, Long viewAt, String business) {
        Map<String, String> params = new LinkedHashMap<>();
        if (ps != null && ps > 0) {
            params.put("ps", String.valueOf(ps));
        }
        if (max != null) {
            params.put("max", String.valueOf(max));
        }
        if (viewAt != null) {
            params.put("view_at", String.valueOf(viewAt));
        }
        if (business != null && !business.isBlank()) {
            params.put("business", business);
        }
        String url = BilibiliEndpoint.historyCursorUrl
                + (params.isEmpty() ? "" : "?" + queryOf(params));
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.historyReferer);
        HistoryCursor data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取观看历史");
        log.info("观看历史：本页 {} 条，下一游标 max={} view_at={} business={}",
                data.getList() == null ? 0 : data.getList().size(),
                data.getCursor() == null ? null : data.getCursor().getMax(),
                data.getCursor() == null ? null : data.getCursor().getView_at(),
                data.getCursor() == null ? null : data.getCursor().getBusiness());
        return data;
    }

    /**
     * <b>取"稍后再看"整个列表</b>（{@code x/v2/history/toview}）。
     *
     * <p>🔴 <b>它不分页</b>：一次把整个列表给完（实测 37 条），响应只有 {@code count} 与 {@code list} 两个键。
     * 与 {@link #getWatchHistory(int)} 的游标翻页形态完全不同。
     *
     * <p>🔴 <b>真需登录</b>：匿名 {@code -101 账号未登录}（实测），无降级。
     *
     * @return 稍后再看列表，不可为 null
     * @throws BilibiliException 网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public ToViewList getToView() {
        HttpResponse<String> response = BilibiliHttp.get(BilibiliEndpoint.historyToViewUrl,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.watchLaterReferer);
        ToViewList data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取稍后再看");
        log.info("稍后再看：{} 条", data.getCount());
        return data;
    }

    /** 按插入顺序拼 query（值全是数字或短枚举串，无需 URL 编码） */
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

    private HistoryService() {}
}

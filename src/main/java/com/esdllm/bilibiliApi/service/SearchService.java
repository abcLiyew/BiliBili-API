package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 搜索服务（{@code Search} 门面的后端）。
 *
 * <p>两个端点，分工不同：
 * <table border="1">
 *   <caption>综合搜索 vs 分类搜索</caption>
 *   <tr><th></th><th>综合 {@code search/all/v2}</th><th>分类 {@code search/type}</th></tr>
 *   <tr><td>一次拿到</td><td>12 个分组（视频/用户/番剧…）</td><td>单一分类，但能翻页取全</td></tr>
 *   <tr><td>适合</td><td>"搜索框下拉"式的混合展示</td><td>"只看视频"或"只看用户"</td></tr>
 * </table>
 *
 * <p><b>🔴 关于签名的决定（有实测数据支撑，不是抄文档）</b>：文档把这两条都标成 Wbi 鉴权，
 * 而 2026-09-21 的 2×2 实测显示<b>四种组合（匿不匿名 × 签不签名）全部 {@code code=0}</b>——
 * 也就是说<b>当前并不强制签名</b>。这里仍然走 {@link BilibiliHttp#getSigned}：
 * <ol>
 *   <li>密钥有当天缓存（见 {@code WbiKeyStore}），稳态下<b>不产生额外出站</b>；</li>
 *   <li>哪天 B 站恢复强制签名，本库不会突然集体失效 —— 而"哪天"我们无法预知；</li>
 *   <li>三个搜索方法走同一条出口，排障时只有一个地方要看。</li>
 * </ol>
 *
 * <p><b>标题里的 HTML 高亮</b>：{@code title} / {@code uname} 会带
 * {@code <em class="keyword">} 标签。本服务<b>不改写</b>服务端数据（调用方可能正想拿它做高亮），
 * 但每个条目都提供了 {@code getCleanTitle()} / {@code getCleanUname()} ——
 * <b>要展示就用它们</b>，别自己写正则。
 *
 * <p><b>异常语义</b>：本服务只抛 {@link BilibiliException}（runtime），
 * 门面边界再转成 {@code IOException}（与库内既有分层一致）。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class SearchService {

    /** 单例入口，无状态。 */
    public static final SearchService INSTANCE = new SearchService();

    /** {@code search_type} 取值：视频。 */
    public static final String SEARCH_TYPE_VIDEO = "video";

    /** {@code search_type} 取值：用户。 */
    public static final String SEARCH_TYPE_USER = "bili_user";

    /**
     * 每页条数的上限。
     *
     * <p>B 站 {@code search/type} 实测认 20 / 50，再大也会被服务端夹回去。这里用来把
     * 调用方给的荒谬值（0、负数、1000）夹到合理范围，避免拿一个必然被改写的结果去分页。
     */
    public static final int MAX_PAGE_SIZE = 50;

    /** 默认每页条数（B 站网页端就是 20）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private SearchService() {
    }

    /**
     * <b>综合搜索</b>：一次拿到 12 个分组的混合结果。
     *
     * @param keyword 关键词（非空；会被 URL 编码后发出）
     * @param page    页码（从 1 开始；小于 1 按 1 处理）
     * @return 综合结果；取视频用 {@code result.videos()}，取用户用 {@code result.users()}
     * @throws BilibiliException 关键词为空、网络失败、业务码非 0、或响应形状不符
     */
    public SearchAllResult searchAll(String keyword, int page) {
        requireKeyword(keyword);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        params.put("page", String.valueOf(normalizePage(page)));

        HttpResponse<String> response = BilibiliHttp.getSigned(
                BilibiliEndpoint.searchAllUrl, params,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.searchReferer);
        SearchAllResult data = requireData(response,
                new TypeReference<>() {
                }, "综合搜索");
        log.info("综合搜索「{}」第 {} 页：{} 个分组，视频 {} 条、用户 {} 条",
                keyword, data.getPage(), data.getResult() == null ? 0 : data.getResult().size(),
                data.videos().size(), data.users().size());
        return data;
    }

    /**
     * <b>分类搜索 · 视频</b>。
     *
     * <p>与 {@link #searchAll} 的区别：只给视频，但 {@code numResults} 往往是全部可搜到的数量，
     * 能翻页取全（综合搜索只给一页混合结果）。
     *
     * @param keyword 关键词（非空）
     * @param page    页码（从 1 开始）
     * @return 分页结果；{@code result.getResult()} 即视频列表
     * @throws BilibiliException 同上
     */
    public SearchTypeResult<SearchVideo> searchVideos(String keyword, int page) {
        return searchType(keyword, SEARCH_TYPE_VIDEO, page, "视频搜索",
                new TypeReference<>() {
                });
    }

    /**
     * <b>分类搜索 · 用户</b>。
     *
     * @param keyword 关键词（非空）
     * @param page    页码（从 1 开始）
     * @return 分页结果；{@code result.getResult()} 即用户列表
     * @throws BilibiliException 同上
     */
    public SearchTypeResult<SearchUser> searchUsers(String keyword, int page) {
        return searchType(keyword, SEARCH_TYPE_USER, page, "用户搜索",
                new TypeReference<>() {
                });
    }

    /** 分类搜索的共用实现：泛型只在这里出现一次，两个公开方法各自给出具体的 {@code TypeReference} */
    private <T> SearchTypeResult<T> searchType(String keyword, String searchType, int page, String action,
                                               TypeReference<ApiResponse<SearchTypeResult<T>>> type) {
        requireKeyword(keyword);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", searchType);
        params.put("keyword", keyword);
        params.put("page", String.valueOf(normalizePage(page)));

        HttpResponse<String> response = BilibiliHttp.getSigned(
                BilibiliEndpoint.searchTypeUrl, params,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.searchReferer);
        SearchTypeResult<T> data = requireData(response, type, action);
        log.info("{}「{}」第 {} 页：本页 {} 条 / 共 {} 条（{} 页）",
                action, keyword, data.getPage(), data.size(),
                data.getNumResults(), data.getNumPages());
        return data;
    }

    /**
     * 校验关键词。
     *
     * <p>在发请求<b>之前</b>做（与 {@code UserService#getCard} 的既有做法一致）：
     * 空关键词会被服务端当成一次"必然失败的搜索"，白打一次出站，还会让出口信誉白白受损。
     */
    private static void requireKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BilibiliException("搜索失败：关键词不能为空");
        }
    }

    /** 页码归一：B 站从 1 开始，而 {@code page=0} 会拿到与预期不同的结果，这里统一夹到合法范围 */
    private static int normalizePage(int page) {
        return Math.max(1, page);
    }

    /**
     * HTTP 状态 → 反序列化 → 业务码 → 取 data，四步一次做完。
     *
     * <p>失败时一定带上<b>能定位的证据</b>（HTTP 状态或响应原文片段）——
     * 搜索这一块的失败在调用方看来全是"没搜到东西"，不给证据就只能靠猜。
     *
     * <p>⚠️ 关键词的 URL 编码由签名出口一并完成，本类<b>不自己编码</b> ——
     * 若要换实现，请注意 WBI 口径会<b>删掉</b>参数值里的 {@code !'()*} 四个字符
     * （{@code WbiSigner#percentEncode} 的规则，B 站前端同样如此），
     * 而通用查询编码器（{@code URLEncoder}）不会删 —— 两者的差别在搜 {@code It's} 这类词时才会暴露。
     *
     * @param response 原始响应
     * @param type     目标类型（fastjson 的 {@code TypeReference}，用于保留泛型）
     * @param action   正在做的事（拼失败文案）
     * @param <T>      data 类型
     * @return 非 null 的 data
     * @throws BilibiliException HTTP 非 2xx、响应无法解析、业务码非 0、或 data 为空
     */
    private static <T> T requireData(HttpResponse<String> response, TypeReference<ApiResponse<T>> type,
                                     String action) {
        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), action);
        if (httpError != null) {
            throw httpError;
        }
        ApiResponse<T> parsed;
        try {
            parsed = JSON.parseObject(response.getBody(), type);
        } catch (Exception e) {
            throw new BilibiliException(0, action + "失败：HTTP " + response.getStatus()
                    + " 的响应无法解析（前 120 字：" + brief(response.getBody()) + "）", "响应形状不符");
        }
        return ResponseParserSupport.unwrap(parsed, action);
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }
}

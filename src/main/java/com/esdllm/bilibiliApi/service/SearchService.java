package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.search.HotSearch;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.parse.ApiResponse;
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

    /** 热搜榜默认条数（实测该值即为服务端实际返回条数）。 */
    public static final int DEFAULT_HOT_LIMIT = 10;

    /** 热搜榜单次条数上限（请求侧夹紧用；⚠️ 见 {@link #getHotSearch(int)} 的说明）。 */
    public static final int MAX_HOT_LIMIT = 50;

    private SearchService() {
    }

    /**
     * <b>取热搜榜</b>（{@code x/web-interface/search/square}，B2 批 #3）。
     *
     * <p>✅ <b>完全不需要凭据、也不需要 WBI 签名</b>（2026-09-22 实测匿名 {@code code=0}，
     * 两次一致）。所以它<b>不走</b> {@code getSigned} —— 上面三个搜索方法走签名是"防将来"，
     * 而这个端点连响应里都没有签名相关字段，给它签名只是白算一次。
     *
     * <p>🔴 <b>榜单在 {@code data.trending}，不是 {@code data} 本身</b>：
     * {@code data} 顶层只有 {@code trending} 一个键，里面才是 {@code title} / {@code list}。
     * 调用方拿条目要走 {@code getHotSearch(10).getTrending().getList()}。
     *
     * <p>⚠️ <b>返回的 {@code trackid} 是字符串且已超出 {@code long} 范围</b>
     * （实测 {@code 12414231099029457647}）—— 别对它做数值运算，见 {@code HotSearch.Trending}。
     *
     * <p>⚠️ {@code limit} <b>只验过 10</b>：实测传 {@code limit=10} 就返回 10 条。
     * 更大的值是否被服务端接受<b>未验证</b>（它可能把越界值夹回自己的默认值），
     * 所以这里夹到 1–{@link #MAX_HOT_LIMIT}，但<b>不承诺</b>一定能拿到那么多条 ——
     * 实际条数以返回的 {@code list} 长度为准。
     *
     * @param limit 期望条数；{@code ≤0} 时按 {@link #DEFAULT_HOT_LIMIT}，超过上限则夹紧
     * @return 热搜榜，不可为 null
     * @throws BilibiliException 网络失败、HTTP 非 2xx、业务码非 0，
     *                           或 <b>{@code code=0} 但榜单为空</b>
     *                           （该端点匿名可用，所以空榜单几乎只可能是形状变了或被风控）
     */
    public HotSearch getHotSearch(int limit) {
        int size = limit <= 0 ? DEFAULT_HOT_LIMIT : Math.min(limit, MAX_HOT_LIMIT);
        String url = BilibiliEndpoint.searchSquareUrl + "?limit=" + size;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        HotSearch data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取热搜榜");
        if (data.getTrending() == null
                || data.getTrending().getList() == null
                || data.getTrending().getList().isEmpty()) {
            // 与 FavoriteService 的空列表守卫同理：这是"没给出数据"，不是"今天没有热搜"。
            // 该端点匿名即可用，所以空榜单不能再用"缺凭据"解释 —— 只能是形状变了或被风控。
            throw new BilibiliException(0,
                    "获取热搜榜失败：服务端返回 code=0，但 trending.list 为空",
                    "该端点匿名可用，空榜单通常是响应形状变了或命中风控；"
                            + "请先确认响应里还有 data.trending.list 这个路径");
        }
        log.info("热搜榜「{}」：{} 条（trackid={}）",
                data.getTrending().getTitle(), data.getTrending().getList().size(),
                data.getTrending().getTrackid());
        return data;
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
        SearchAllResult data = ResponseParserSupport.requireData(response,
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

    /**
     * 分类搜索的共用实现：泛型只在这里出现一次，两个公开方法各自给出具体的 {@code TypeReference}。
     *
     * <p>⚠️ 关键词的 URL 编码由<b>签名出口</b>一并完成，本类<b>不自己编码</b> ——
     * 若要换实现，请注意 WBI 口径会<b>删掉</b>参数值里的 {@code !'()*} 四个字符
     * （{@code WbiSigner#percentEncode} 的规则，B 站前端同样如此），
     * 而通用查询编码器（{@code URLEncoder}）不会删 —— 两者的差别在搜 {@code It's} 这类词时才会暴露。
     */
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
        SearchTypeResult<T> data = ResponseParserSupport.requireData(response, type, action);
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

}

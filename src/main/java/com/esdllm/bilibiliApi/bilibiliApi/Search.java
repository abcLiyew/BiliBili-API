package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.search.HotSearch;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.service.SearchService;

import java.io.IOException;

/**
 * 搜索门面：<b>综合搜索（12 个分组的混合结果）+ 分类搜索（视频 / 用户，可翻页取全）</b>。
 *
 * <p>库内第 7 个门面（前 6 个：{@code BilibiliClient} / {@code CardInfo} / {@code Dynamic} /
 * {@code Live} / {@code ShortChain} / {@code Login}）。新增类，<b>不触碰任何既有签名</b>，
 * 对 XatiiBot 是纯增量。
 *
 * <p><b>两种搜索怎么选</b>：
 * <table border="1">
 *   <caption>综合 vs 分类</caption>
 *   <tr><th></th><th>{@link #searchAll}</th><th>{@link #searchVideos} / {@link #searchUsers}</th></tr>
 *   <tr><td>端点</td><td>{@code wbi/search/all/v2}</td><td>{@code wbi/search/type}</td></tr>
 *   <tr><td>一次拿到</td><td>最多 12 个分组，<b>每组只给一页</b></td><td>单一分类，但给总数、能一直翻</td></tr>
 *   <tr><td>适合</td><td>"搜索框下拉"式的混合展示</td><td>"只看视频"或"只看用户"</td></tr>
 * </table>
 * 一句话：要<b>混着看</b>用 {@code searchAll}，要<b>取全某一类</b>用 {@code searchVideos/searchUsers}。
 *
 * <p>⚠️ <b>综合搜索的 12 个分组里，实测只有 {@code video} 稳定有内容</b>，其余 11 组经常是空数组。
 * 所以 {@link SearchAllResult#videos()} 是主力，{@link SearchAllResult#users()} 在实际关键词下
 * 未必有值 —— 要用户结果<b>请直接走 {@link #searchUsers}</b>，别指望综合搜索顺带给。
 *
 * <p><b>标题里的 HTML 高亮</b>：{@code title} / {@code uname} 会带
 * {@code <em class="keyword">} 标签（关键词高亮）。本门面<b>原样返回</b>服务端数据，
 * 但每个条目都提供了 {@code getCleanTitle()} / {@code getCleanUname()} ——
 * <b>要展示就用它们</b>，别自己写正则。
 *
 * <p><b>典型用法</b>：
 * <pre>{@code
 * Search search = new Search();
 * SearchTypeResult<SearchVideo> page = search.searchVideos("影视飓风", 1);
 * for (SearchVideo v : page.getResult()) {
 *     System.out.println(v.getCleanTitle());     // 已剥离高亮标签
 * }
 * }</pre>
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code Live} / {@code BilibiliClient} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}）：失败原因多半是业务码或响应形状问题，
 * 抹掉内层消息等于把排障线索一起抹掉。
 *
 * @author 饿死的流浪猫
 */
public class Search {

    /**
     * <b>综合搜索</b>：一次拿到多个分组的混合结果。
     *
     * <p>取视频用 {@code result.videos()}；{@code result.section("video")} 可按分组名精确取。
     * ⚠️ 用户结果别从这里拿（见类注释的实测结论），要用户走 {@link #searchUsers}。
     *
     * @param keyword 关键词（非空）
     * @param page    页码（从 1 开始；小于 1 按 1 处理）
     * @return 综合结果，不可为 null
     * @throws IOException 关键词为空、网络失败、业务码非 0，或响应形状不符
     */
    public SearchAllResult searchAll(String keyword, int page) throws IOException {
        try {
            return SearchService.INSTANCE.searchAll(keyword, page);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>分类搜索 · 视频</b>。
     *
     * <p>与 {@link #searchAll} 的区别：只给视频，但 {@code numResults} 往往是全部可搜到的数量，
     * 因此<b>能翻页取全</b>（综合搜索只给一页混合结果）。
     *
     * @param keyword 关键词（非空）
     * @param page    页码（从 1 开始；小于 1 按 1 处理）
     * @return 分页结果；{@code result.getResult()} 即视频列表，{@code result.size()} 为本页条数
     * @throws IOException 关键词为空、网络失败、业务码非 0，或响应形状不符
     */
    public SearchTypeResult<SearchVideo> searchVideos(String keyword, int page) throws IOException {
        try {
            return SearchService.INSTANCE.searchVideos(keyword, page);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>分类搜索 · 用户</b>。
     *
     * <p>这是拿"用户搜索结果"的<b>正路</b>：实测在真实关键词下返回 20 条、19 个字段全给，
     * 而综合搜索的同名分组经常是空数组（见类注释）。
     *
     * @param keyword 关键词（非空）
     * @param page    页码（从 1 开始；小于 1 按 1 处理）
     * @return 分页结果；{@code result.getResult()} 即用户列表
     * @throws IOException 关键词为空、网络失败、业务码非 0，或响应形状不符
     */
    public SearchTypeResult<SearchUser> searchUsers(String keyword, int page) throws IOException {
        try {
            return SearchService.INSTANCE.searchUsers(keyword, page);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取热搜榜</b>（{@code x/web-interface/search/square}，B2 批 #3）。
     *
     * <p>✅ <b>零门槛</b>：不需要凭据，也<b>不需要 WBI 签名</b>（2026-09-22 实测匿名
     * {@code code=0}）。所以它<b>不走签名出口</b> —— 上面三个搜索方法走签名是"防服务端
     * 哪天恢复强制签名"，而这个端点的响应里连签名相关字段都没有，给它签名只是白算一次。
     *
     * <p>🔴 <b>榜单在 {@code data.trending} 里，不是 {@code data} 本身</b>：
     * <pre>{@code
     * HotSearch hot = search.getHotSearch(10);
     * for (HotSearch.Item item : hot.getTrending().getList()) {
     *     System.out.println(item.getKeyword() + "  " + item.getHeat_score());
     * }
     * }</pre>
     *
     * <p>⚠️ {@code trending.trackid} 是<b>字符串</b>，实测值已超出 {@code long} 范围
     * （{@code 12414231099029457647}）—— 别对它做数值运算。
     *
     * <p>⚠️ 条目里的 {@code icon} / {@code uri} / {@code goTo}（JSON 键是 {@code goto}，
     * 因为它是 Java 保留字）<b>实测常为空串</b>，别当必填。
     *
     * @param limit 期望条数；{@code ≤0} 按 10。⚠️ 实测只验过 10，更大的值<b>未验证</b>
     *              （服务端可能夹回自己的默认值），实际条数以 {@code getTrending().getList().size()} 为准
     * @return 热搜榜，不可为 null
     * @throws IOException 网络失败、HTTP 非 2xx、业务码非 0，或 {@code code=0} 但榜单为空
     */
    public HotSearch getHotSearch(int limit) throws IOException {
        try {
            return SearchService.INSTANCE.getHotSearch(limit);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取热搜榜</b>（默认 10 条）—— 等价于 {@link #getHotSearch(int) getHotSearch(10)}。
     *
     * @return 热搜榜，不可为 null
     * @throws IOException 同 {@link #getHotSearch(int)}
     */
    public HotSearch getHotSearch() throws IOException {
        return getHotSearch(SearchService.DEFAULT_HOT_LIMIT);
    }
}

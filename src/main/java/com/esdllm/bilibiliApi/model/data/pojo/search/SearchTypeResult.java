package com.esdllm.bilibiliApi.model.data.pojo.search;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>分类搜索结果</b> —— {@code x/web-interface/wbi/search/type} 的 {@code data}。
 *
 * <p>与 {@link SearchAllResult} 的区别：那个是"一次给所有分组"，这个是"只给一个分类、
 * 但可以翻页拿全"（{@code numResults} 往往上千）。同一个端点在 {@code search_type} 不同时
 * 返回的元素形状不同，所以这里做成<b>泛型</b>：
 * <pre>{@code
 * // 搜视频
 * SearchTypeResult<SearchVideo> videos = ...;
 * // 搜用户
 * SearchTypeResult<SearchUser> users = ...;
 * }</pre>
 *
 * <p>✅ 与 {@link SearchAllResult} 一样，<b>实测免签名、免登录</b>（已复核）；
 * 本库仍走 {@code getSigned}，理由见 {@code SearchService} 类注释。
 *
 * @param <T> 元素类型（{@link SearchVideo} / {@link SearchUser}）
 * @author 饿死的流浪猫
 */
@Data
public class SearchTypeResult<T> {

    /** 当前页码 */
    private Integer page;

    /** 每页条数 */
    private Integer pagesize;

    /** 结果总数 */
    private Integer numResults;

    /** 总页数 */
    private Integer numPages;

    /** 搜索词建议 */
    private String suggest_keyword;

    /** 本次搜索会话 id */
    private String seid;

    /** 请求类型（B 站内部值） */
    private String rqt_type;

    /** 命中的"彩蛋"（低频） */
    private Integer egg_hit;

    /** 是否命中网页综合搜索 */
    private Boolean is_hit_web_inf;

    /** 展示的列（低频） */
    private List<String> show_column;

    /** 实验分组（低频） */
    private List<String> exp_list;

    /** 黑名单键（低频） */
    private List<String> in_black_key;

    /** 白名单键（低频） */
    private List<String> in_white_key;

    /** <b>结果数组</b>（形状由 {@code search_type} 决定） */
    private List<T> result;

    /** 分页附加信息（结构不固定，保留原始 JSON） */
    private JSONObject pageinfo;

    /**
     * 本页结果条数。
     *
     * <p>单独一个方法：{@code code=0} 配空数组与"真有数据"只能靠长度区分。
     *
     * @return 条数
     */
    public int size() {
        return result == null ? 0 : result.size();
    }
}

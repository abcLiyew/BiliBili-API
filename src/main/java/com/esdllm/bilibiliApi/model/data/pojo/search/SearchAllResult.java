package com.esdllm.bilibiliApi.model.data.pojo.search;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>综合搜索结果</b> —— {@code x/web-interface/wbi/search/all/v2} 的 {@code data}。
 *
 * <p>一次返回 12 个分组（视频 / 用户 / 番剧 / 话题 …），只有 {@code video} 组在无登录时稳定有内容。
 * 分组结构见 {@link SearchSection}，本项目里"要视频"的常规写法是
 * {@link #videos()}（它替你做了"找 video 组 + 转类型"）。
 *
 * <p>✅ <b>本端点实测免签名、免登录</b>（2026-09-21 的 2×2 实测：匿名无签名就 {@code code=0}，
 * 已复核）—— 文档把它标成 Wbi 鉴权，是<b>过时的</b>。
 * 本库仍然走 {@code BilibiliHttp#getSigned}（多一次 {@code nav} 取密钥），
 * 理由与取舍见 {@code SearchService} 类注释：密钥有当天缓存、万一哪天恢复强制签名不会集体失效、
 * 三个搜索方法共用一条出口便于排障。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SearchAllResult {

    /** 当前页码 */
    private Integer page;

    /** 每页条数 */
    private Integer pagesize;

    /** 结果总数（注意字段名是驼峰 {@code numResults}，与响应逐字对应） */
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

    /** 搜索页是否灰度中 */
    private Boolean is_search_page_grayed;

    /** 展示的模块顺序（形如 {@code ["tips","brand_ad",…]}） */
    private List<String> show_module_list;

    /** 实验分组（低频） */
    private List<String> exp_list;

    /** 黑名单键（低频） */
    private List<String> in_black_key;

    /** 白名单键（低频） */
    private List<String> in_white_key;

    /** <b>分组结果</b>（本端点的核心，异构，见 {@link SearchSection}） */
    private List<SearchSection> result;

    /** 分页附加信息（结构不固定，保留原始 JSON） */
    private JSONObject pageinfo;

    /** 顶部分类计数（低频，保留原始 JSON） */
    private JSONObject top_tlist;

    /** 客户端展示配置（低频，保留原始 JSON） */
    private JSONObject app_display_option;

    /**
     * 取全部<b>视频</b>结果（跨分组汇总）。
     *
     * <p>这是调用方最常用的入口：不必自己遍历 {@code result} 找 {@code video} 组。
     *
     * @return 视频条目列表，不可为 null（无数据时为空表）
     */
    public List<SearchVideo> videos() {
        return sectionsOf("video", SearchSection::videos);
    }

    /**
     * 取全部<b>用户</b>结果（跨分组汇总）。
     *
     * @return 用户条目列表，不可为 null（无数据时为空表）
     */
    public List<SearchUser> users() {
        return sectionsOf("bili_user", SearchSection::users);
    }

    /**
     * 按分组类型取原始分组。
     *
     * @param resultType 分组类型，如 {@code video}
     * @return 命中的分组；没有该分组时返回 {@code null}
     */
    public SearchSection section(String resultType) {
        if (result == null || resultType == null) {
            return null;
        }
        for (SearchSection section : result) {
            if (resultType.equals(section.getResult_type())) {
                return section;
            }
        }
        return null;
    }

    private <T> List<T> sectionsOf(String resultType, java.util.function.Function<SearchSection, List<T>> extractor) {
        SearchSection section = section(resultType);
        return section == null ? new ArrayList<>() : extractor.apply(section);
    }
}

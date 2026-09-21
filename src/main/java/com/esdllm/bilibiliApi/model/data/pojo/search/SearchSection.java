package com.esdllm.bilibiliApi.model.data.pojo.search;

import com.alibaba.fastjson.JSONArray;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 综合搜索结果里的<b>一个分组</b> —— {@code wbi/search/all/v2} 的 {@code data.result[]}。
 *
 * <p>🔴 <b>{@code data} 是异构的</b>：同一个数组里 12 个分组的元素形状各不相同
 * （{@code video} 是视频条目、{@code bili_user} 是用户、{@code media_bangumi} 是番剧…），
 * 决定形状的是 {@link #result_type}。所以这里刻意把 {@code data} 保留为 {@code JSONArray}，
 * 再提供两个<b>带类型</b>的便捷取法 —— 直接声明成某个具体类型会静默丢掉其余分组的数据。
 *
 * <p>实测 12 个 {@code result_type}：{@code tips} / {@code brand_ad} / {@code esports} /
 * {@code activity} / {@code web_game} / {@code card} / {@code media_bangumi} / {@code media_ft} /
 * {@code bili_user} / {@code user} / {@code star} / {@code video}。
 * <b>只有 {@code video} 在无登录时稳定有内容</b>（本机实测：其余 11 个分组都是空数组）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SearchSection {

    /**
     * 分组类型（{@code video} / {@code bili_user} / {@code media_bangumi} …）。
     *
     * <p>注意字段名就是下划线风格 —— 与响应逐字对应，不要改成 {@code resultType}。
     */
    private String result_type;

    /** 该分组的内容（异构，用 {@link #videos()} / {@link #users()} 取带类型的结果） */
    private JSONArray data;

    /**
     * 把本分组按视频条目解析。
     *
     * <p>只在 {@code result_type=video} 时有意义；其它类型会解析出一批字段全 null 的对象，
     * 所以<b>先判类型再调</b>（或者直接用 {@link SearchAllResult#videos()}，它已经做了这件事）。
     *
     * @return 视频条目列表，不可为 null（无数据时为空表）
     */
    public List<SearchVideo> videos() {
        return data == null ? new ArrayList<>() : data.toJavaList(SearchVideo.class);
    }

    /**
     * 把本分组按用户条目解析。
     *
     * @return 用户条目列表，不可为 null（无数据时为空表）
     */
    public List<SearchUser> users() {
        return data == null ? new ArrayList<>() : data.toJavaList(SearchUser.class);
    }

    /**
     * 本分组里有多少条内容。
     *
     * <p>值得单独一个方法：{@code code=0} + 空分组与"真的有数据"只能靠<b>长度</b>区分
     * （这个教训在本库出现过两次，见 {@code INTERFACE_PLAN.md} §8 的风险表）。
     *
     * @return 条数
     */
    public int size() {
        return data == null ? 0 : data.size();
    }
}

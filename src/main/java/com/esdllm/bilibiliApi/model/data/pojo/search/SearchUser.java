package com.esdllm.bilibiliApi.model.data.pojo.search;

import com.alibaba.fastjson.JSONObject;
import com.esdllm.bilibiliApi.parse.HighlightStripper;
import lombok.Data;

import java.util.List;

/**
 * 搜索结果里的<b>用户</b>条目 —— {@code wbi/search/type?search_type=bili_user} 的 {@code result[]}
 * 与 {@code all/v2} 中 {@code result_type=bili_user} 段的元素。
 *
 * <p>字段按 2026-09-21 实测响应逐字映射（实测该条目有 19 个键）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class SearchUser {

    /** 结果类型，恒为 {@code bili_user} */
    private String type;

    /** 用户 mid */
    private Long mid;

    /** 昵称（<b>可能带高亮标签</b>，展示请用 {@link #getCleanUname()}） */
    private String uname;

    /** 个性签名（可能带高亮标签） */
    private String usign;

    /** 粉丝数 */
    private Long fans;

    /** 投稿数 */
    private Long videos;

    /** 头像 */
    private String upic;

    /** 是否 NFT 头像 */
    private Integer face_nft;

    /** NFT 头像类型 */
    private Integer face_nft_type;

    /** 认证文案（如"bilibili 知名UP主"，可能带高亮标签） */
    private String verify_info;

    /** 等级 */
    private Integer level;

    /** 性别（0=保密 1=男 2=女） */
    private Integer gender;

    /** 是否是 UP 主 */
    private Integer is_upuser;

    /** 是否在直播 */
    private Integer is_live;

    /** 直播间 id（未开播时为 0） */
    private Long room_id;

    /** 是否年度大会员 */
    private Integer is_senior_member;

    /** 认证详情（结构随认证类型变化，保留原始 JSON） */
    private JSONObject official_verify;

    /** 该用户的投稿预览（形状与视频条目不同、低频，保留原始 JSON） */
    private List<JSONObject> res;

    /** 命中字段列表 */
    private List<String> hit_columns;

    /**
     * 剥离高亮标签后的昵称。
     *
     * @return 干净昵称
     */
    public String getCleanUname() {
        return HighlightStripper.strip(uname);
    }

    /**
     * 剥离高亮标签后的认证文案。
     *
     * @return 干净认证文案
     */
    public String getCleanVerifyInfo() {
        return HighlightStripper.strip(verify_info);
    }
}

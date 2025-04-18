package com.esdllm.bilibiliApi.model.data;

import com.esdllm.bilibiliApi.model.data.pojo.Profession;
import com.esdllm.bilibiliApi.model.data.pojo.video.*;
import lombok.Data;

import java.util.List;

@Data
public class VideoInfo {
    private String bvid;
    private Long aid;
    private Integer videos;
    private Integer tid;
    private Integer tid_v2;
    private String tname;
    private String tname_v2;
    private Integer copyright;
    private String pic;
    private String title;
    private Long pubdate;
    private Long ctime;
    private String desc;
    private List<DescV2> desc_v2;
    private Integer state;
    private Integer attribute;
    private Integer duration;
    private Long forward;
    private Long mission_id;
    private String redirect_url;
    private Rights rights;
    private Owner owner;
    private Stat stat;
    private Object argue_info;
    private String dynamic;
    private Long cid;
    private Dimension dimension;
    private Profession premiere;
    private Integer teenage_mode;
    private Boolean is_chargeable_season;
    private Boolean is_story;
    private Boolean is_upower_exclusive;
    private Boolean is_upower_pay;
    private Boolean is_upower_show;
    private Boolean no_cache;
    private List<Pages> pages;
    private Subtitle subtitle;
    private List<Staff> staff;
    private Boolean is_season_display;
    private UserGarb user_garb;
    private HonorReply honor_reply;
    private String like_icon;
    private Boolean need_jump_bv;
    private Boolean disable_show_up_info;
    private Boolean is_story_play;
    private Boolean is_view_self;

}

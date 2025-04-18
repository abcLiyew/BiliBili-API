package com.esdllm.bilibiliApi.model.data.pojo;

import com.esdllm.bilibiliApi.model.data.pojo.live.NewPendants;
import com.esdllm.bilibiliApi.model.data.pojo.live.StudioInfo;
import lombok.Data;

import java.util.List;

/**
 * 直播间信息
 */
@Data
public class LiveRoom {
    private Long uid;
    private Long room_id;
    private Long short_id;
    private Integer attention;
    private Integer online;
    private Boolean is_portrait;
    private String description;
    private Integer live_status;
    private Integer area_id;
    private Integer parent_area_id;
    private String parent_area_name;
    private Integer old_area_id;
    private String background;
    private String title;
    private String user_cover;
    private String keyframe;
    private Boolean is_strict_room;
    private String live_time;
    private String tags;
    private Integer is_anchor;
    private String room_silent_type;
    private Integer room_silent_level;
    private Integer room_silent_second;
    private String area_name;
    private String pardants;
    private String area_pardants;
    private List<String> hot_words;
    private Integer hot_words_status;
    private String verify;
    private NewPendants new_pendants;
    private String up_session;
    private Integer pk_status;
    private Integer pk_id;
    private Integer battle_id;
    private Integer allow_change_area_time;
    private Integer allow_upload_cover_time;
    private StudioInfo studio_info;

}

package com.esdllm.bilibiliApi.model.data.pojo.fans;

import lombok.Data;

/**
 * 用户粉丝牌勋章信息
 */
@Data
public class Medal {
    private Long uid;
    private Integer target_id;
    private Integer medal_id;
    private Integer level;
    private String medal_name;
    private Integer medal_color;
    private Integer intimacy;
    private Integer next_intimacy;
    private Integer day_limit;
    private Integer medal_color_start;
    private Integer medal_color_end;
    private Integer medal_color_border;
    private Integer is_lighted;
    private Integer light_status;
    private Integer wearing_status;
    private Integer score;
}

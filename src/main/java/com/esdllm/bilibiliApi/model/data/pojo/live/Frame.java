package com.esdllm.bilibiliApi.model.data.pojo.live;

import lombok.Data;

@Data
public class Frame {
    private String name;
    private String value;
    private Integer position;
    private String desc;
    private Integer area;
    private Integer area_old;
    private String bg_color;
    private String bg_pic;
    private Boolean use_old_area;
}

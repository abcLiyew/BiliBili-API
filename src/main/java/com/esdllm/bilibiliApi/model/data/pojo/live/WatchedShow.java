package com.esdllm.bilibiliApi.model.data.pojo.live;

import lombok.Data;

@Data
public class WatchedShow {
    private Boolean Switch;
    private Integer num;
    private String text_small;
    private String text_large;
    private String icon;
    private String icon_location;
    private String icon_web;
}

package com.esdllm.bilibiliApi.model.data.pojo;

import lombok.Data;

/**
 * 用户是否显示关注和粉丝信息
 */
@Data
public class Contract {
    private Boolean is_display;
    private Boolean is_follow_display;
}

package com.esdllm.bilibiliApi.model.data.pojo.video;

import com.esdllm.bilibiliApi.model.data.pojo.card.Official;
import lombok.Data;

@Data
public class Staff {
    private Long mid;
    private String title;
    private String name;
    private String face;
    private Vip vip;
    private Official official;
    private Long follower;
    private Integer label_style;
}

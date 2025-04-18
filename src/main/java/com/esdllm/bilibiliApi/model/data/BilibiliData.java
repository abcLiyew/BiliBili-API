package com.esdllm.bilibiliApi.model.data;

import com.esdllm.bilibiliApi.model.data.pojo.Card;
import lombok.Data;

@Data
public class BilibiliData {
    private Card card;
    private Boolean following;
    private Integer archive_count;
    private Integer article_count;
    private Integer follower;
    private Integer like_num;

}

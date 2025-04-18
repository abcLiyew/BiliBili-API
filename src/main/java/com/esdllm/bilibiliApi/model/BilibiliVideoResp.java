package com.esdllm.bilibiliApi.model;

import com.esdllm.bilibiliApi.model.data.VideoInfo;
import lombok.Data;

@Data
public class BilibiliVideoResp {
    int code;
    String message;
    int ttl;
    String msg;
    VideoInfo data;

}

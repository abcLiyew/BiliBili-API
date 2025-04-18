package com.esdllm.bilibiliApi.model;

import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import lombok.Data;

@Data
public class BilibiliLiveResp {
    int code;
    String message;
    int ttl;
    String msg;
    LiveRoom data;

}

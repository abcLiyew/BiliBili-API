package com.esdllm.bilibiliApi.model;

import com.esdllm.bilibiliApi.model.data.BilibiliData;
import lombok.Data;

@Data
public class BilibiliCardResp {
    int code;
    String message;
    int ttl;
    String msg;
    BilibiliData data;

}

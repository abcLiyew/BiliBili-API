package com.esdllm.bilibiliApi.common;

import lombok.Data;

@Data
public class ShotChainInfo {
    /**
     * 类型 0-直播，1-视频，2-动态（图文）,3-番剧 4-个人空间，5-音频，6-其他
     */
    private Integer type;
    private String chainId;
    private String prefix;
}

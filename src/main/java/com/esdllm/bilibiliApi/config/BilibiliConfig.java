package com.esdllm.bilibiliApi.config;

/**
 * 请求配置相关信息
 */
public class BilibiliConfig {
    public static final String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";
    public static final String accept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7";
    public static final String videoBaseUrl = "https://api.bilibili.com/x/web-interface/view?bvid=";
    public static final String videoAvBaseUrl = "https://api.bilibili.com/x/web-interface/view?aid=";
    public static final String cardBaseUrl = "https://api.bilibili.com/x/web-interface/card?mid=";
    public static final String dynamicBaseUrl = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail?dynamic_id=";
    public static final String liveBaseUrl = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=";
    public static final String dynamicListUrl = "https://space.bilibili.com/%s/dynamic";
    public static final String dynamicInfoUrl  = "https://www.bilibili.com/opus/";

}

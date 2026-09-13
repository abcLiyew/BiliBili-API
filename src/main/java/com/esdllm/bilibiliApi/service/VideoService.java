package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliVideoResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import kong.unirest.HttpResponse;

import java.util.Objects;

/**
 * 视频数据服务（{@code BilibiliClient} 门面的后端）。
 *
 * <p>P1 起承担 {@code BilibiliClient} 门面的实际数据获取职责。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常）。
 * 旧 {@code BilibiliClient.getVideoInfo(String/Long)} 的 {@code throws IOException} 声明
 * 作为红线保留，由门面边界按 §4.8.1 重新包装后抛出（{@code IOException}）。
 *
 * <p><b>无状态</b>：本服务不持有任何视频缓存。"单槽缓存"留在门面层（{@code BilibiliClient.videoInfo} 字段），
 * 由门面的 {@code isCached} 守卫跨 getter 复用——参 §6.4 "现状即如此，别改坏"。
 *
 * @author 饿死的流浪猫
 */
public class VideoService {

    /** 单例入口，无状态。 */
    public static final VideoService INSTANCE = new VideoService();

    /**
     * 取一次视频信息（按 BV 号）。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return 不可为 null
     * @throws BilibiliException {@code bvid} 为空、网络异常、JSON 解析失败、业务码非 0
     */
    public VideoInfo getVideoInfo(String bvid) {
        if (bvid == null) {
            throw new BilibiliException("BV号不能为空");
        }
        String url = BilibiliEndpoint.videoBaseUrl + bvid;
        return doGet(url);
    }

    /**
     * 取一次视频信息（按 AV 号）。
     *
     * @param aid AV 号（数字 id）
     * @return 不可为 null
     * @throws BilibiliException {@code aid} 为空或 ≤ 0、网络异常、JSON 解析失败、业务码非 0
     */
    public VideoInfo getVideoInfo(Long aid) {
        if (aid == null || aid <= 0) {
            throw new BilibiliException("AV号不能为空");
        }
        String url = BilibiliEndpoint.videoAvBaseUrl + aid;
        return doGet(url);
    }

    private VideoInfo doGet(String url) {
        HttpResponse<String> response = BilibiliHttp.get(url);
        BilibiliVideoResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliVideoResp.class);
        } catch (Exception e) {
            throw new BilibiliException("获取视频信息失败 错误在" + VideoService.class.getName() + "错误消息：" + e);
        }
        if (resp == null || resp.getCode() != 0) {
            throw new BilibiliException("获取视频信息失败");
        }
        return resp.getData();
    }

    private VideoService() {}
}

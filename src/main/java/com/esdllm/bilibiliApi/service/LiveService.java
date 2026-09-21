package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliLiveResp;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import kong.unirest.HttpResponse;

import java.util.Objects;

/**
 * 直播间数据服务（{@code Live} 门面的后端）。
 *
 * <p>P1 起承担 {@code Live} 门面的实际数据获取职责。门面只做"调一次→分发"。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常）。
 * 原 {@code Live.java} 旧的"throws IOException + try-catch IOException 抛 RuntimeException"
 * 反模式已收敛，由门面边界按 §4.8.1 规则做最终转译（{@code throws IOException} 的方法被允许
 * 重新包装成 {@link java.io.IOException} 抛出；非 throws 的方法透传本服务的 BilibiliException）。
 *
 * <p><b>单例</b>：本服务无任何可变共享状态（不缓存 result，不持有 roomId），
 * 唯一的 {@link #INSTANCE} 是真正的 stateless，线程安全。
 *
 * @author 饿死的流浪猫
 */
public class LiveService {

    /** 单例入口，无状态。 */
    public static final LiveService INSTANCE = new LiveService();

    /**
     * 取一次完整 {@link LiveRoom}（包含直播状态、地址、标题、封面、UID、分区、人数、标签等所有字段）。
     *
     * <p>本方法<b>每次只发一次请求</b>。门面的多个 getter 各自调一次本方法并不会互相缓存——
     * "一次调用内复用一次结果"留给调用方按 §6.4 自行组织（{@code getLiveRoom(...)} 拿到对象再分发）。
     *
     * @param roomId 直播间房间号
     * @return 不可为 null
     * @throws BilibiliException {@code roomId == null}、网络异常、JSON 解析失败、业务码非 0、{@code data} 为空
     */
    public LiveRoom load(Long roomId) {
        if (roomId == null) {
            throw new BilibiliException("获取直播间信息失败\n房间号：null");
        }
        String url = BilibiliEndpoint.liveBaseUrl + roomId;
        HttpResponse<String> response = BilibiliHttp.get(url);
        BilibiliLiveResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliLiveResp.class);
        } catch (Exception e) {
            throw new BilibiliException("获取直播消息失败" + response.getBody() + "\n房间号：" + roomId);
        }
        if (Objects.isNull(resp.getData()) || resp.getCode() != 0) {
            throw new BilibiliException("获取直播间信息失败\n房间号：" + roomId);
        }
        return resp.getData();
    }

    private LiveService() {}
}

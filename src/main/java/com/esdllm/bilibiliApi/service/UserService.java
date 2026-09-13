package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliCardResp;
import kong.unirest.HttpResponse;

import java.util.Objects;

/**
 * 用户名片数据服务（{@code CardInfo} 门面的后端）。
 *
 * <p>P1 起承担 {@code CardInfo} 门面的实际数据获取职责。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常）。
 * 旧版 {@code CardInfo.getBilibiliLiveResp(Long)} 的 {@code throws IOException} 声明作为红线保留，
 * 但实现已统一为"Service 抛 BilibiliException（runtime），门面边界透传不转换"——
 * §4.8.1 修：受检异常只能来自真受检异常路径，本服务不存在 IOException 路径。
 *
 * <p><b>无状态</b>：本服务不在内部缓存任何数据。"单槽缓存"留在门面层（{@code CardInfo.resp} 字段），
 * 由门面的 {@code isCached} 守卫跨 getter 复用——参 §6.4 "现状即如此，别改坏"。
 *
 * @author 饿死的流浪猫
 */
public class UserService {

    /** 单例入口，无状态。 */
    public static final UserService INSTANCE = new UserService();

    /**
     * 取一次完整 {@link BilibiliCardResp}（含 {@code card} + {@code archive_count} + 粉丝/点赞/...）。
     *
     * @param uid bilibili 用户的 uid
     * @return 不可为 null
     * @throws BilibiliException {@code uid} 为空 / ≤ 0、网络异常、JSON 解析失败、业务码非 0、{@code data} 为空
     */
    public BilibiliCardResp getCard(Long uid) {
        if (Objects.isNull(uid)) {
            throw new BilibiliException("uid不能为空");
        }
        if (uid <= 0) {
            throw new BilibiliException("uid不能小于0");
        }
        String url = BilibiliEndpoint.cardBaseUrl + uid;
        HttpResponse<String> response = BilibiliHttp.get(url);
        BilibiliCardResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliCardResp.class);
        } catch (Exception e) {
            throw new BilibiliException(e);
        }
        if (Objects.isNull(resp) || Objects.isNull(resp.getData()) || resp.getCode() != 0) {
            throw new BilibiliException("获取卡片信息失败");
        }
        return resp;
    }

    private UserService() {}
}

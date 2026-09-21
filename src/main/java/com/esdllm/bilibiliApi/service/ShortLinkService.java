package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.bilibiliApi.BilibiliClient;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.bilibiliApi.Live;
import com.esdllm.bilibiliApi.bilibiliApi.ShortChain;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;

import java.io.IOException;

/**
 * 短链服务（{@code ShortChain} 门面的后端）。
 *
 * <p>P1 起承担短链 URL 解析 + 跨门面取数派发职责。
 *
 * <p><b>职责分工</b>：
 * <ul>
 *   <li>{@code ShortChain} 门面：保留"短链 URL → Location 字符串 → 字段解析 → {@code ShotChainInfo}"
 *       的<b>纯本地字符串处理</b>逻辑（仅承载门面侧的 ShotChainInfo 字段与 isType 守卫）；</li>
 *   <li>{@code ShortLinkService}（本类）：承载"读 Location header" + 派发到对应 Service
 *       （{@link #getVideoInfoFromChain}/{@link #getLiveRoomFromChain}/{@link #getDynamicCardFromChain}）。</li>
 * </ul>
 *
 * <p><b>P3 变更</b>：读 Location 从 {@code ApiBase.getHttpResponseNotRedirect} 改为
 * {@link BilibiliHttp#getLocation(String)} —— 短链这条链路也回到了"唯一出口"，
 * 且不再需要在本类里接触 {@code org.apache.http.*} 类型。
 *
 * <p><b>异常语义</b>：
 * <ul>
 *   <li>{@link #resolveLocation(String)} 返回 {@code null} 表示读不到跳转地址（不抛异常）；</li>
 *   <li>{@link #getVideoInfoFromChain(String)} / {@link #getDynamicCardFromChain(String)} 抛 {@link IOException}
 *       （底层 Service 的取数失败向上转译）；</li>
 *   <li>{@link #getLiveRoomFromChain(Long)} 通过 {@code LiveService.load} 取（runtime）。</li>
 * </ul>
 *
 * @author 饿死的流浪猫
 */
public class ShortLinkService {

    /** 单例入口，无状态。 */
    public static final ShortLinkService INSTANCE = new ShortLinkService();

    /**
     * 取短链跳真实地址（{@code Location} header）。
     *
     * @param shortChainUrl 形如 {@code https://b23.tv/xxx} 的短链
     * @return {@code Location} header 值；header 不存在 / 网络失败 / 解析异常 → null
     */
    public String resolveLocation(String shortChainUrl) {
        return BilibiliHttp.getLocation(shortChainUrl);
    }

    /**
     * 从 chainId 解析视频：BV 走 bvid 端点、否则视为 "av..." 截掉前缀转 aid。
     */
    public VideoInfo getVideoInfoFromChain(String chainId) throws IOException {
        if (chainId.startsWith("BV")) {
            return new BilibiliClient().getVideoInfo(chainId);
        }
        Long aid = Long.parseLong(chainId.substring(2));
        return new BilibiliClient().getVideoInfo(aid);
    }

    /**
     * 从 chainId 解析直播间（链尾就是 roomId，纯数字）。
     */
    public LiveRoom getLiveRoomFromChain(Long roomId) {
        return new Live().getLiveRoom(roomId);
    }

    /**
     * 从 chainId 解析动态卡片（{@code Dynamic.getDynamicDetail(String)}）。
     */
    public BilibiliDynamicResp.Data.Card getDynamicCardFromChain(String chainId) throws IOException {
        return new Dynamic().getDynamicDetail(chainId);
    }

    /** 强制保留对 ShortChain 类型的引用，以触发 import 校验。 */
    @SuppressWarnings("unused")
    private static Class<?> forceShortChainImport() {
        return ShortChain.class;
    }

    private ShortLinkService() {}
}

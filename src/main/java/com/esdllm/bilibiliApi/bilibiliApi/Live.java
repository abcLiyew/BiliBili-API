package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.service.LiveService;

import java.io.IOException;
import java.util.List;

/**
 * 直播间门面。
 *
 * <p>P1 起本门面仅做"调 {@link LiveService} 一次、读字段返回"；原 {@code ApiBase} 调用、
 * 异常包装、JSON 解析全部迁到 {@code LiveService.load(Long)}。
 *
 * <p><b>异常边界</b>（§4.8.1 红线）：
 * <ul>
 *   <li>{@code throws IOException} 的方法（{@link #getLiveStatus(Long)} /
 *       {@link #getLiveTitle(Long)} / {@link #getImageUrl(Long)}）：
 *       业务异常 {@link BilibiliException} 被重新包装成 {@link IOException} 抛出，与旧版一致；</li>
 *   <li>未声明 {@code throws IOException} 的方法：透传 {@link BilibiliException}（runtime）。</li>
 * </ul>
 *
 * <p><b>缓存策略</b>（§6.4）：本门面<b>不</b>提供跨调用的实例缓存。
 * 想要"一次调用复用一次结果"的消费者（如 {@code PushInfoServiceImpl.livePush}）应：
 * <pre>{@code LiveRoom room = new Live().getLiveRoom(roomId);  // 一次请求
 * // 然后在循环里反复 read room.getXxx();}</pre>
 *
 * @author 饿死的流浪猫
 */
public class Live {

    private static final String BASE_LIVE_URL = "https://live.bilibili.com/";

    /**
     * 取完整 {@link LiveRoom}。
     *
     * @param roomId 直播间房间号
     * @return 不可为 null
     */
    public LiveRoom getLiveRoom(Long roomId) {
        return LiveService.INSTANCE.load(roomId);
    }

    /**
     * 获取直播间状态 0-未开播 1-直播中 2-轮播中
     *
     * @param roomId 直播间房间号
     * @return 直播状态码
     * @throws IOException 网络/业务异常
     */
    public int getLiveStatus(Long roomId) throws IOException {
        try {
            return LiveService.INSTANCE.load(roomId).getLive_status();
        } catch (BilibiliException e) {
            throw new IOException("获取直播间信息失败\n房间号：" + roomId, e);
        }
    }

    /**
     * 获取直播间地址（纯本地拼接，不打网络）。
     *
     * @param roomId 直播间房间号
     * @return {@code https://live.bilibili.com/{roomId}}
     */
    public String getLiveUrl(Long roomId) {
        return BASE_LIVE_URL + roomId;
    }

    /**
     * 获取直播间标题
     *
     * @param roomId 直播间房间号
     * @return 标题
     * @throws IOException 网络/业务异常
     */
    public String getLiveTitle(Long roomId) throws IOException {
        try {
            return LiveService.INSTANCE.load(roomId).getTitle();
        } catch (BilibiliException e) {
            throw new IOException("获取直播间信息失败\n房间号：" + roomId, e);
        }
    }

    /**
     * 获取直播间封面 URL
     *
     * @param roomId 直播间房间号
     * @return 封面 URL
     * @throws IOException 网络/业务异常
     */
    public String getImageUrl(Long roomId) throws IOException {
        try {
            return LiveService.INSTANCE.load(roomId).getUser_cover();
        } catch (BilibiliException e) {
            throw new IOException("获取直播间信息失败\n房间号：" + roomId, e);
        }
    }

    /**
     * 获取主播 uid
     *
     * @param roomId 直播间房间号
     * @return uid
     */
    public Long getUid(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getUid();
    }

    /**
     * 获取直播间分区名
     *
     * @param roomId 直播间房间号
     * @return 分区名
     */
    public String getLiveArea(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getArea_name();
    }

    /**
     * 获取直播间观看人数
     *
     * @param roomId 直播间房间号
     * @return 人数
     */
    public int getOnline(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getOnline();
    }

    /**
     * 获取直播间关键帧 URL
     *
     * @param roomId 直播间房间号
     * @return 关键帧 URL
     */
    public String getKeyFrame(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getKeyframe();
    }

    /**
     * 获取直播间标签（逗号分隔）
     *
     * @param roomId 直播间房间号
     * @return 标签
     */
    public String getTags(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getTags();
    }

    /**
     * 获取直播间描述
     *
     * @param roomId 直播间房间号
     * @return 描述
     */
    public String getDescription(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getDescription();
    }

    /**
     * 获取直播间开播时间
     *
     * @param roomId 直播间房间号
     * @return 开播时间字符串
     */
    public String getLiveTime(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getLive_time();
    }

    /**
     * 获取直播间 PK 状态
     *
     * @param roomId 直播间房间号
     * @return PK 状态码
     */
    public int getPkStatus(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getPk_status();
    }

    /**
     * 获取直播间热词
     *
     * @param roomId 直播间房间号
     * @return 热词列表
     */
    public List<String> getHotWords(Long roomId) {
        return LiveService.INSTANCE.load(roomId).getHot_words();
    }
}

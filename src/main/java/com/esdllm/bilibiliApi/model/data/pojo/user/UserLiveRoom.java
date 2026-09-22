package com.esdllm.bilibiliApi.model.data.pojo.user;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

/**
 * 用户主页上的直播间状态（{@code acc/info} 的 {@code data.live_room}）。
 *
 * <p><b>未开播也会有这个对象</b>（{@code liveStatus=0}），别用"字段是否为 null"判断"有没有直播" ——
 * 判据是 {@link #liveStatus} 是否为 1。
 *
 * <p>字段按 2026-09-21 实测响应逐字映射。注意<b>没有</b> {@code online}（在线人数）这个键 ——
 * 想看在线/看过人数在 {@link #watched_show} 里（实测也有 {@code online=0} 的形态，
 * 那是 B 站不同灰度下发的键不同，所以这里不把它当必填）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class UserLiveRoom {

    /** 房间是否存在（1 = 有直播间） */
    private Integer roomStatus;

    /** 是否正在直播（1 = 直播中） */
    private Integer liveStatus;

    /** 直播间地址（形如 {@code https://live.bilibili.com/<roomid>?broadcast_type=0&is_room_feed=1}） */
    private String url;

    /** 直播间标题 */
    private String title;

    /** 封面 */
    private String cover;

    /** 直播间 id */
    private Long roomid;

    /** 轮播状态（0 = 非轮播；轮播是"放录像当直播"） */
    private Integer roundStatus;

    /** 开播类型 */
    private Integer broadcast_type;

    /** "多少人看过"气泡（结构较深、纯展示，保留原始 JSON） */
    private JSONObject watched_show;

    /** 在线人数；部分响应里没有这个键（可能为 null） */
    private Long online;
}

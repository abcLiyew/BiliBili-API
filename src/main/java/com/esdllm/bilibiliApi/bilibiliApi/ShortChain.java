package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.common.ShotChainInfo;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.service.ShortLinkService;
import lombok.Data;

import java.io.IOException;
import java.util.Objects;

/**
 * 哔哩哔哩短链解析门面。
 *
 * <p>P1 起把"读 Location + 派发到对应门面取数"迁到 {@link ShortLinkService}；
 * 本门面保留"短链 URL → Location 字符串 → 字段解析 → ShotChainInfo"的纯本地字符串处理逻辑，
 * 及 {@code ShotChainInfo} 实例字段与 {@code isType(int)} 守卫。
 *
 * @author 饿死的流浪猫
 */
@Data
public class ShortChain {
    private ShotChainInfo shotChainInfo;

    public ShortChain(String shortChainUrl) {
        this.shotChainInfo = getShotChainInfo(shortChainUrl);
    }

    public ShortChain() {
    }

    /**
     * 解析短链
     *
     * @param shortChainUrl 短链地址
     * @return ShotChainInfo 短链信息，无法解析时返回 null
     */
    public ShotChainInfo getShotChainInfo(String shortChainUrl) {
        String location = ShortLinkService.INSTANCE.resolveLocation(shortChainUrl);
        if (location == null || location.isEmpty()) {
            return null;
        }
        String[] arrayStr = location.split("/");
        return getShotChainInfo(arrayStr, location);
    }

    private ShotChainInfo getShotChainInfo(String[] arrayStr, String location) {
        ShotChainInfo info = new ShotChainInfo();
        // 正常跳转地址形如 https://www.bilibili.com/video/BV1xx，切分后至少 3 段；
        // 段数不足时直接归为"其他"，避免数组越界
        if (arrayStr == null || arrayStr.length < 3) {
            info.setType(6);
            return info;
        }
        int indexOf = arrayStr[arrayStr.length - 1].indexOf("?");
        if (indexOf <= 0) {
            indexOf = arrayStr[arrayStr.length - 1].length();
        }
        String chainId = arrayStr[arrayStr.length - 1].substring(0, indexOf);
        info.setChainId(chainId);
        String prefix = location.substring(0, location.lastIndexOf('/') + 1);
        info.setPrefix(prefix);
        String parent = arrayStr[arrayStr.length - 2];
        if (parent.equals("video")) {
            info.setType(1);
        } else if (parent.equals("opus")) {
            info.setType(2);
        } else if (isLive(arrayStr)) {
            info.setType(0);
        } else if (parent.equals("play")) {
            info.setType(3);
        } else if (isSpace(arrayStr)) {
            info.setType(4);
        } else if (parent.equals("audio")) {
            info.setType(5);
        } else {
            info.setType(6);
        }
        return info;
    }

    private Boolean isLive(String[] arrStr) {
        return arrStr[2].startsWith("live") || arrStr[2].startsWith("www.live");
    }

    private Boolean isSpace(String[] arrStr) {
        return arrStr[2].startsWith("space") || arrStr[2].startsWith("www.space");
    }

    /**
     * 判断当前短链是否为指定类型
     *
     * @param type 0-直播 1-视频 2-动态 3-番剧 4-空间 5-音频 6-其他
     * @return 是则返回 true
     */
    private boolean isType(int type) {
        return shotChainInfo != null && Objects.equals(shotChainInfo.getType(), type);
    }

    /**
     * 获取短链类型
     *
     * @param url 短链地址
     * @return 类型名称
     */
    public String getShotChainType(String url) {
        return getString(getShotChainInfo(url));
    }

    /**
     * 获取短链类型
     *
     * @param info 短链信息
     * @return 类型名称
     */
    public String getShotChainType(ShotChainInfo info) {
        return getString(info);
    }

    /**
     * 获取短链类型（基于本实例 {@code shotChainInfo}）
     *
     * @return 类型名称
     */
    public String getShotChainType() {
        return getString(shotChainInfo);
    }

    private String getString(ShotChainInfo info) {
        if (info == null || info.getType() == null) {
            return "未知";
        }
        int type = info.getType();
        return switch (type) {
            case 0 -> "直播";
            case 1 -> "视频";
            case 2 -> "动态";
            case 3 -> "番剧";
            case 4 -> "空间";
            case 5 -> "音频";
            case 6 -> "其他";
            default -> "未知";
        };
    }

    /**
     * 取视频信息（按短链缓存的 chainId 自动判 BV / AV）。
     */
    public VideoInfo getVideoInfo() {
        if (!isType(1)) {
            throw new BilibiliException("不是视频");
        }
        try {
            return ShortLinkService.INSTANCE.getVideoInfoFromChain(shotChainInfo.getChainId());
        } catch (IOException e) {
            throw new BilibiliException("短链解析失败" + e);
        }
    }

    /**
     * 取直播间信息（按短链缓存的 chainId 作 roomId）。
     */
    public LiveRoom getLiveRoom() {
        if (!isType(0)) {
            throw new BilibiliException("不是直播");
        }
        Long roomId = Long.parseLong(shotChainInfo.getChainId());
        return ShortLinkService.INSTANCE.getLiveRoomFromChain(roomId);
    }

    /**
     * 取动态卡片。
     */
    public BilibiliDynamicResp.Data.Card getDynamicCard() {
        if (!isType(2)) {
            throw new BilibiliException("不是动态");
        }
        try {
            return ShortLinkService.INSTANCE.getDynamicCardFromChain(shotChainInfo.getChainId());
        } catch (IOException e) {
            throw new BilibiliException("短链解析失败" + e);
        }
    }
}

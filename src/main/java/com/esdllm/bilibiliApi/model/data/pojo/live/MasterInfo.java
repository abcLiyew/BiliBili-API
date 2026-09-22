package com.esdllm.bilibiliApi.model.data.pojo.live;

import lombok.Data;

import java.util.List;

/**
 * <b>主播信息</b> —— {@code live_user/v1/Master/info} 的 {@code data}（B1 批 #8）。
 *
 * <p>字段按 2026-09-22 实测逐字映射，实测响应（匿名、{@code uid=2}）：
 * <pre>
 * {"info": {"uid": 2, "uname": "碧诗", "face": "https://…", "official_verify": {"type": 0, "desc": "…"}, "gender": 1},
 *  "exp": {"master_level": {"level": 30, "color": 10512625, "current": [2870000, 11883810], "next": [3730000, 15613810]}},
 *  "follower_num": 1429244, "room_id": 1024, "medal_name": "逸国", "glory_count": 0,
 *  "pendant": "", "link_group_num": 0, "room_news": {"content": "", "ctime": "", "ctime_text": ""}}
 * </pre>
 *
 * <p><b>它一次回答两个问题</b>：这人是谁（{@link #info}）与这人在哪个房间（{@link #room_id}）。
 * 从房间号出发的场景请先用 {@code Live} 门面拿 uid，再回到本端点取主播侧数据 ——
 * <b>注意参数是 uid，不是房间号</b>（传房间号只会查到别人或查不到）。
 *
 * <p>⚠️ {@link #pendant} 实测是<b>字符串</b>（空串）而不是对象 —— 直播间挂件信息不在这个字段里，
 * 别按对象取。
 *
 * @author 饿死的流浪猫
 */
@Data
public class MasterInfo {

    /** 主播基本信息 */
    private Info info;

    /** 主播等级相关（实测只有 {@code master_level} 一项） */
    private Exp exp;

    /** <b>粉丝数</b>（实测 1429244；与 {@code x/relation/stat} 的 {@code follower} 同源） */
    private Long follower_num;

    /** <b>直播间号</b>（实测 1024）—— 拿它去调 {@code room/v1/Room/playUrl} 取拉流地址 */
    private Long room_id;

    /** 粉丝勋章名（实测 {@code "逸国"}；可能为空串） */
    private String medal_name;

    /** 荣誉数（实测 0） */
    private Integer glory_count;

    /** 挂件（实测是<b>字符串</b>且为空串，不是对象） */
    private String pendant;

    /** 关联分组数（实测 0） */
    private Integer link_group_num;

    /** 房间公告 */
    private RoomNews room_news;

    /** 主播基本信息 */
    @Data
    public static class Info {

        /** 主播 uid */
        private Long uid;

        /** 昵称 */
        private String uname;

        /** 头像地址 */
        private String face;

        /** 认证信息（{@code type} + {@code desc}） */
        private OfficialVerify official_verify;

        /** 性别（{@code 0}=保密、{@code 1}=男、{@code 2}=女） */
        private Integer gender;
    }

    /** 认证信息 */
    @Data
    public static class OfficialVerify {

        /** 认证类型（{@code 0}=个人认证、{@code -1}=无） */
        private Integer type;

        /** 认证文案（如 {@code "bilibili个人认证:bilibili创始人（站长）"}） */
        private String desc;
    }

    /** 主播等级容器 */
    @Data
    public static class Exp {

        /** 主播等级 */
        private MasterLevel master_level;
    }

    /**
     * 主播等级。
     *
     * <p>⚠️ {@link #current} 与 {@link #next} 是<b>两元素数组</b>（实测 {@code [2870000, 11883810]}），
     * 不是单值 —— 它们是"当前分数 / 下一级所需分数"这类<b>成对</b>的量，
     * 想省事请直接用 {@link #level}（等级值本身），别猜数组下标含义。
     */
    @Data
    public static class MasterLevel {

        /** 等级值（实测 30） */
        private Integer level;

        /** 等级配色（十进制 RGB 数值，实测 10512625） */
        private Integer color;

        /** 当前分值对（实测 {@code [2870000, 11883810]}） */
        private List<Long> current;

        /** 升级所需分值对（实测 {@code [3730000, 15613810]}） */
        private List<Long> next;
    }

    /**
     * 房间公告。
     *
     * <p>⚠️ 实测三个字段<b>都是空串</b>（该主播没设公告）。别把空串当"接口没返回"。
     */
    @Data
    public static class RoomNews {

        /** 公告内容 */
        private String content;

        /** 公告时间 */
        private String ctime;

        /** 公告时间文案 */
        private String ctime_text;
    }
}

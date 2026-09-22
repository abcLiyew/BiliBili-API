package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

/**
 * <b>在线观看数</b> —— {@code x/player/online/total} 的 {@code data}（B1 批 #4）。
 *
 * <p>字段按 2026-09-22 实测逐字映射，实测响应只有一个这形状：
 * <pre>
 * {"total": "690", "count": "215",
 *  "show_switch": {"total": true, "count": true},
 *  "abtest": {"group": "b"}}
 * </pre>
 *
 * <p>🔴 <b>{@link #total} 与 {@link #count} 在 JSON 里是字符串</b>（{@code "690"}），
 * 不是 number。这里声明为 {@code Long}，靠 fastjson 的宽松转换接住 —— 这样调用方拿到的就是数字。
 * ⇒ 但要知道<b>原始形状是字符串</b>：若哪天服务端回空串 {@code ""}，转换会抛异常而不是给出 0，
 * 届时第一反应应该是"形状变了"，而不是"数值是 0"。
 *
 * <p>⚠️ 文档把这个端点标成"APP 端、需签名"，<b>实测匿名即 {@code code=0}</b> —— 又一处
 * "文档 vs 实测"分歧，已记在 {@code README} 的分歧表里。
 *
 * @author 饿死的流浪猫
 */
@Data
public class OnlineTotal {

    /** 在线<b>总数</b>（JSON 里是字符串数字，见类注释） */
    private Long total;

    /** 在线<b>人数</b>（JSON 里是字符串数字） */
    private Long count;

    /** 服务端是否允许展示这两项（实测都为 true；为 false 时前端不展示该数字） */
    private ShowSwitch show_switch;

    /** AB 实验分组（B 站内部用；实测 {@code {"group":"b"}}） */
    private AbTest abtest;

    /** 展示开关 */
    @Data
    public static class ShowSwitch {

        /** 是否展示总数 */
        private Boolean total;

        /** 是否展示人数 */
        private Boolean count;
    }

    /** AB 实验分组（不用于业务，只做留档） */
    @Data
    public static class AbTest {

        /** 分组标识（实测 {@code b}） */
        private String group;
    }
}

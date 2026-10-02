package com.esdllm.bilibiliApi.model.data.pojo.user;

import lombok.Data;

/**
 * <b>UP 主内容概览</b> —— {@code x/space/navnum} 的 {@code data}（C2 批）。
 *
 * <p>实测响应（2026-10-02，{@code mid=2}，匿名，13 个键）：
 * <pre>
 * {"album": 127, "article": 0, "audio": 1, "bangumi": 122,
 *  "channel": {"guest": 0, "master": 0}, "cinema": 34,
 *  "favourite": {"guest": 4, "master": 4}, "opus": 127, "playlist": 0,
 *  "pugv": 0, "season_num": 0, "tag": 61, "video": 43}
 * </pre>
 *
 * <p>✅ <b>不需要凭据</b>（2026-10-02 匿名 / 凭据两格均 {@code code=0}）。
 *
 * <p>🔴 <b>本端点不校验 {@code mid} 是否存在</b>（2026-10-02 六格实测）⇒ <b>查无此人 = 13 键全 0</b>，
 * 与"这个账号真的一无所有"<b>同形</b>，响应里没有任何能区分二者的信号
 * （六格表的出处见 {@code BilibiliEndpoint#spaceNavNumUrl}）。
 * ⚠️ 所以<b>不要把"全 0"当成"用户不存在"</b> —— 这是两个不同的事实，该做的动作也不同
 * （前者该报错或跳过，后者只是一个合法的 0）。
 *
 * <p>🔴 <b>与 {@code CardInfo} 的分工 —— 别为同一件事打两次请求，也别指望这里给粉丝数</b>：
 * <table border="1">
 *   <caption>两个"用户统计"来源</caption>
 *   <tr><th>要什么</th><th>去哪</th></tr>
 *   <tr><td>粉丝数 / 总投稿数 / 累计获赞</td><td>{@code CardInfo#getCard(uid)}</td></tr>
 *   <tr><td><b>按内容类型各自的投稿数</b>（视频 / 专栏 / 音频 / 相册…）</td><td><b>本类</b></td></tr>
 * </table>
 *
 * <p>⚠️ {@link #channel} 与 {@link #favourite} 是<b>嵌套对象</b> {@code {guest, master}}，
 * <b>不是数字</b> —— 直接声明成 {@code Long} 会静默变 {@code null}。
 *
 * @author 饿死的流浪猫
 */
@Data
public class NavNum {

    /** 视频投稿数（实测 {@code 43}） */
    private Long video;

    /** 专栏投稿数（实测 {@code 0}） */
    private Long article;

    /** 音频投稿数（实测 {@code 1}） */
    private Long audio;

    /** 番剧（追番）数（实测 {@code 122}） */
    private Long bangumi;

    /** 影视数（实测 {@code 34}） */
    private Long cinema;

    /** 相册数（实测 {@code 127}） */
    private Long album;

    /** 课程数（实测 {@code 0}） */
    private Long pugv;

    /** 合集数（实测 {@code 0}） */
    private Long season_num;

    /** 歌单数（实测 {@code 0}） */
    private Long playlist;

    /** 动态（opus）数（实测 {@code 127}） */
    private Long opus;

    /** 标签数（实测 {@code 61}） */
    private Long tag;

    /** 频道数（⚠️ <b>嵌套对象</b>，不是数字） */
    private GuestMaster channel;

    /** 收藏数（⚠️ <b>嵌套对象</b>，不是数字） */
    private GuestMaster favourite;

    /**
     * {@code guest} / {@code master} 二元组 —— 一个"可见性维度"的计数。
     *
     * <p>⚠️ 实测 {@code channel} 与 {@code favourite} <b>都是这个形状</b>
     * （{@code favourite} 实测 {@code {guest: 4, master: 4}}）。它<b>不是</b>一个可直接
     * 相加的数字，也不是"总数" —— 两个字段的语义差异<b>未查</b>，本库只原样透出。
     */
    @Data
    public static class GuestMaster {

        /** 游客视角可见的数量 */
        private Long guest;

        /** 本人视角可见的数量 */
        private Long master;
    }
}

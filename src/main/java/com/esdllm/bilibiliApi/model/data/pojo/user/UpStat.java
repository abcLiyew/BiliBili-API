package com.esdllm.bilibiliApi.model.data.pojo.user;

import lombok.Data;

/**
 * <b>UP 主累计数据</b> —— {@code x/space/upstat} 的 {@code data}（B3.5 批 #2）。
 *
 * <p>实测响应（2026-09-22，带凭据）：
 * <pre>
 * {"archive": {"enable_vt": 0, "view": 9065, "vt": 0},
 *  "article": {"view": 308},
 *  "likes": 408}
 * </pre>
 *
 * <p>🔴 <b>本类是本库"静默空"最典型的样本，用之前务必读这段</b>：
 * 同一端点、同一 mid，<b>匿名调用照样返回 {@code code=0}，但 {@code data} 是一个空对象 {@code {}}</b>
 * —— 不是 {@code -101}，<b>没有任何错误信号</b>。而空 data 会让"判 data 非 null"这类检查全部通过，
 * 于是安静地得到一个三个字段全 null 的对象，与"这个 UP 主的播放量真的是 0"完全无法区分。
 *
 * <p>⇒ 所以 {@code UserService#getUpStat} 把"整体为空"当<b>失败</b>处理（抛异常并说明原因），
 * 而不是把全 null 的对象交出去。要区分"接口调通了"与"真拿到了数据"，只有这一条路。
 *
 * <p>⚠️ 想看<b>粉丝数 / 投稿数</b>别来这里 —— 本端点<b>没有</b>它们，
 * 那些在 {@code CardInfo#getCard}（{@code follower} / {@code archive_count}）。
 * 本端点给的是"累计播放 / 累计阅读 / 累计获赞"。
 *
 * @author 饿死的流浪猫
 */
@Data
public class UpStat {

    /** 视频累计播放 */
    private Archive archive;

    /** 专栏累计阅读 */
    private Article article;

    /** 累计获赞 */
    private Long likes;

    /**
     * 视频维度统计。
     *
     * <p>⚠️ {@code vt} 字段名太短、含义不透明，且实测为 0 —— 按 B 站内部值原样透出，不做解释。
     */
    @Data
    public static class Archive {

        /** 累计播放量 */
        private Long view;

        /** B 站内部值（实测 0） */
        private Long vt;

        /** B 站内部值（实测 0） */
        private Integer enable_vt;
    }

    /** 专栏维度统计（实测只有 {@code view} 一个键） */
    @Data
    public static class Article {

        /** 累计阅读量 */
        private Long view;
    }
}

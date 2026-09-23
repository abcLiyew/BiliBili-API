package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

/**
 * 视频权限位。
 *
 * <p>🔴 <b>{@link #free_watch} 与 {@link #pay_free_watch} 是"同一个概念的两个键名"，
 * 两个都要留 —— 这是 2026-09-23 实测发现的端点差异，删掉任何一个都会让那个端点的取值为 null</b>：
 * <table border="1">
 *   <caption>键名随端点变（fastjson2 只认精确同名，不认"差不多"）</caption>
 *   <tr><th>端点</th><th>键名</th><th>对应字段</th></tr>
 *   <tr><td>{@code x/web-interface/view}</td><td>{@code free_watch}</td><td>{@link #free_watch}</td></tr>
 *   <tr><td>{@code x/space/top/arc}</td><td><b>{@code pay_free_watch}</b></td>
 *       <td>{@link #pay_free_watch}</td></tr>
 * </table>
 * ⚠️ 两个名字"看着像同一个东西"，但 fastjson2 <b>不会</b>把 {@code pay_free_watch} 填进
 * {@code free_watch}（1.x 的宽容匹配早已不存在，见 {@code KeyNameMappingGuardTest}）
 * ⇒ 少一个字段，那个端点的值就<b>静默变 null</b>，不报任何错。
 */
@Data
public class Rights {
    private Integer bp;
    private Integer elec;
    private Integer download;
    private Integer movie;
    private Integer pay;
    private Integer hd5;
    private Integer no_reprint;
    private Integer autoplay;
    private Integer ugc_pay;
    private Integer is_cooperation;
    private Integer ugc_pay_preview;
    private Integer no_background;
    private Integer clean_mode;
    private Integer is_stein_gate;
    private Integer is_360;
    private Integer no_share;
    private Integer arc_pay;
    private Integer free_watch;

    /** {@code view} 走 {@link #free_watch}，{@code space/top/arc} 走这个键 —— 见类注释。 */
    private Integer pay_free_watch;
}

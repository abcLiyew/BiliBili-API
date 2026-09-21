package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.video.AiSummary;
import com.esdllm.bilibiliApi.service.VideoService;

import java.io.IOException;

/**
 * 视频附加信息门面：<b>AI 摘要</b>（以及后续 B1 批次的播放地址等）。
 *
 * <p>库内第 9 个门面（见 {@code Search} 的说明）。新增类，<b>不触碰任何既有签名</b>，
 * 对 XatiiBot 是纯增量。
 *
 * <p>为什么单独一个门面而不是塞进 {@code BilibiliClient}：{@code BilibiliClient} 的
 * 类名与 public 方法签名是<b>逐字冻结</b>的下游契约（XatiiBot 依赖），
 * 新能力一律走新类 —— 这是本库自 v1 起就写死的规矩。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code Live} / {@code BilibiliClient} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class VideoExtra {

    /**
     * <b>取视频 AI 摘要</b>（自动先取 {@code cid}）。
     *
     * <p>🔴 <b>本端点要"WBI 签名 + 登录凭据"两样，缺一不可</b>。它是本库唯一一个
     * "<b>签上名之后错误码会变</b>"的端点，也正是"两步判据"最好的教材：
     * <pre>
     * 匿名 · 无签名 → -403     匿名 · 有签名 → <b>-101</b>
     * 凭据 · 无签名 → -403     凭据 · 有签名 → <b>code=0</b>
     * </pre>
     * 第二格是关键：{@code -403}（访问权限不足）只是"没签名"的表象，
     * 签上名后暴露出真正缺的是登录（{@code -101}）。只看第一格会得出"这是权限问题、做不了"的错误结论。
     *
     * <p>⚠️ <b>判断"有没有摘要"只能用 {@link AiSummary#hasSummary()}</b>：
     * 实测 {@code data.code} 与 {@code data.status} 都是 0 而摘要正常返回，
     * 拿它们当判据会把"有摘要"读成"没有"。有些视频本身就没有摘要（正常情况，不是错误）。
     *
     * <p>⚠️ 本方法会先打一次 {@code x/web-interface/view} 拿 {@code cid}，
     * <b>多花一次请求</b>。已在别处拿过 {@code VideoInfo} 的调用方请直接用
     * {@link #getAiSummary(String, Long)}。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @return AI 摘要，不可为 null（可能 {@code hasSummary()==false}）
     * @throws IOException {@code bvid} 为空、取 {@code cid} 失败、取不到 WBI 密钥、网络失败、
     *                     HTTP 非 2xx、业务码非 0（含 {@code -101} 未登录、{@code -352} 风控），
     *                     或 {@code data} 为空
     */
    public AiSummary getAiSummary(String bvid) throws IOException {
        try {
            return VideoService.INSTANCE.getAiSummary(bvid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取视频 AI 摘要</b>（已知 {@code cid}，省掉一次请求）。
     *
     * @param bvid BV 号（{@code BV1xxx...}）
     * @param cid  视频分 P 的 cid；{@code VideoInfo#getCid()} 就是这个值
     * @return AI 摘要，不可为 null（可能 {@code hasSummary()==false}）
     * @throws IOException {@code bvid}/{@code cid} 为空、门槛/网络/业务码问题，见
     *                     {@link #getAiSummary(String)}
     */
    public AiSummary getAiSummary(String bvid, Long cid) throws IOException {
        try {
            return VideoService.INSTANCE.getAiSummary(bvid, cid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

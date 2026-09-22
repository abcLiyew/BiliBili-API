package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.video.PopularList;
import com.esdllm.bilibiliApi.model.data.pojo.video.RankingList;
import com.esdllm.bilibiliApi.service.VideoService;

import java.io.IOException;

/**
 * 榜单门面：<b>视频排行榜 / 热门视频</b>（库内第 14 个门面，2026-09-22 B1 批新增）。
 *
 * <p>两项都是"我不指定 id、让 B 站告诉我现在什么在火"，参数形状相近（都是 {@code rid}/{@code pn}/{@code ps}
 * 这一族）、返回元素<b>同一个形状</b>（{@code VideoBrief}），所以合在一个门面里。
 * 按 {@code INTERFACE_PLAN.md} §7-Q1 决策 (d)，榜单属于<b>高频域</b>，因此独立成类。
 *
 * <p><b>两者的差别（比想象的大）</b>：
 * <table border="1">
 *   <caption>实测对照（2026-09-22）</caption>
 *   <tr><th></th><th>排行榜 {@link #getRanking}</th><th>热门 {@link #getPopular}</th></tr>
 *   <tr><td>是什么</td><td>按分区、按算法排名的<b>榜单</b></td><td>全站<b>综合热度</b>流</td></tr>
 *   <tr><td>能指定分区吗</td><td>能（{@code rid}）</td><td>不能</td></tr>
 *   <tr><td>元素里有名次吗</td><td>有（{@code VideoBrief#getScore()}）</td><td>没有</td></tr>
 *   <tr><td>有总页数/页码吗</td><td>没有（一次给完，实测 95 条）</td><td>没有，但有 {@code no_more} 终止标志</td></tr>
 *   <tr><td>Referer 敏感吗</td><td>🔴 <b>敏感</b>（站根会 {@code -352}，库内已固定用排行榜页）</td>
 *       <td>不敏感</td></tr>
 * </table>
 * ⇒ <b>别把两个方法当成"同一件事的两个参数"</b>：它们的返回类型不同、语义不同，
 * 唯一相同的是元素形状。
 *
 * <p><b>门槛</b>：✅ 两者都<b>不需要签名、不需要凭据</b>（实测匿名 {@code code=0}）。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code UserSpace} / {@code VideoExtra} / {@code Content} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class Ranking {

    /**
     * <b>取视频排行榜</b>（默认全站、{@code type=all}）。
     *
     * @param rid 分区 id（{@code 0}=全站、{@code 1}=动画…）；{@code <0} 时按 {@code 0}
     * @return 榜单，不可为 null
     * @throws IOException 网络失败、HTTP 非 2xx、业务码非 0（含出口风控 {@code 412}）、或 {@code data} 为空
     */
    public RankingList getRanking(int rid) throws IOException {
        return getRanking(rid, "all");
    }

    /**
     * <b>取视频排行榜</b>（可指定分区与类型）。
     *
     * <p>🔴 <b>本端点对 Referer 敏感，且只有"站根"这一种会挂</b>：站根 Referer 下返回
     * {@code -352 风控校验失败}，换排行榜页就 {@code code=0}（2026-09-22 实测，两轮 4 次复现；
     * 详见 {@code BilibiliEndpoint#rankingUrl} 的对照表）。
     * ⇒ <b>那是库内已经处理好、调用方不需要关心的事</b>；写在这里只是提醒：
     * 这条链路历史上"看起来像出口被封"，实际只是请求头不对。
     *
     * <p>⚠️ <b>榜单没有分页</b>：一次给完（实测 95 条），调用方自己做截取。
     *
     * @param rid  分区 id（{@code 0}=全站、{@code 1}=动画…）；{@code <0} 时按 {@code 0}
     * @param type 榜单类型；空值时按 {@code all}
     * @return 榜单，不可为 null
     * @throws IOException 网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public RankingList getRanking(int rid, String type) throws IOException {
        try {
            return VideoService.INSTANCE.getRanking(rid, type);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取热门视频</b>（一页，按热度）。
     *
     * <p>⚠️ <b>它没有页码返回</b>：响应里只有 {@code list} 与 {@code no_more}，
     * 所以"现在第几页"只能由调用方自己记住传进来的 {@code pn}。
     * 翻到 {@code no_more=true} 就该停 —— 再翻只会拿到空列表。
     *
     * <p>与 {@link #getRanking(int)} 不同，本端点<b>对站根 Referer 不敏感</b>（同一分钟对照过）。
     *
     * @param ps 每页条数；{@code ≤0} 时按 20
     * @param pn 页码（从 1 开始）；{@code ≤0} 时按 1
     * @return 热门列表，不可为 null
     * @throws IOException 网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public PopularList getPopular(int ps, int pn) throws IOException {
        try {
            return VideoService.INSTANCE.getPopular(ps, pn);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuItem;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuXml;
import com.esdllm.bilibiliApi.service.DanmakuService;

import java.io.IOException;
import java.util.List;

/**
 * 弹幕门面：<b>某个分 P 的全部弹幕</b>（库内第 15 个门面，2026-09-22 B2 批新增）。
 *
 * <p>为什么单独立类：按 {@code INTERFACE_PLAN.md} §7-Q1 决策 (d)「高频域独立 + 低频域合并」，
 * 弹幕是<b>高频域</b>（弹幕播放器、弹幕分析、内容审核都要它），且它<b>不属于评论域</b>
 * —— 评论挂 {@code aid}，弹幕挂 {@code cid}，两者的 id 体系都不同，合进 {@code Comment}
 * 只会制造混淆。所以它有自己的类。</p>
 *
 * <p><b>🔴 本门面唯一需要读懂的地方：{@code cid} 既不是 {@code aid} 也不是 {@code bvid}。</b>
 * 端点的参数名叫 {@code oid}，但它要的值是<b>分 P 的 {@code cid}</b>。传错不会报"参数错"，
 * 只会给你 HTTP 400 或一份没有弹幕的空 XML。拿 {@code cid} 的两条正路：
 * <ul>
 *   <li>{@code VideoExtra#getViewDetail(bvid)} → {@code pages[].cid}（<b>分 P 视角，最常用</b>）；</li>
 *   <li>{@code Content#getResources(mediaId, pn, ps)} → 条目的 {@code ugc.first_cid}
 *       （收藏夹里的第一个分 P）。</li>
 * </ul>
 *
 * <p><b>🔴 本端点与库内其它端点的三处不同，都是实测确认的</b>：
 * <ol>
 *   <li><b>它返回 XML，不是 JSON</b> —— 全库唯一。所以它没有 {@code code}/{@code data} 外层，
 *       也没有"业务错误码"这个概念，失败只能靠 HTTP 状态与响应形状判断。</li>
 *   <li><b>响应头是 {@code Content-Encoding: deflate}</b>（实测），但<b>已被 HTTP 层自动解开</b>
 *       —— 业务层拿到的是明文 XML，<b>不需要自己解压</b>。（这正是 B2 开工前专门预检的那个问题：
 *       若未解压，就必须给 {@code BilibiliHttp} 加一个返回 {@code byte[]} 的重载。结论是不需要。）</li>
 *   <li><b>{@code Referer} 对它没有任何影响</b> —— 实测三格对照（不传 / 站根 / 视频页）
 *       的<b>响应字节数完全相同</b>。所以本门面不需要你再传 bvid。
 *       （对照：{@code Ranking} 的 {@code ranking/v2} 是"站根会挂"的那种，两个端点的脾气相反。）</li>
 * </ol>
 *
 * <p><b>门槛</b>：✅ <b>匿名可用</b>，不需要凭据、不需要签名。
 *
 * <p><b>能力边界</b>：
 * <ul>
 *   <li><b>没有翻页</b>：{@code maxlimit}（实测见过 300 / 1000 两种）就是硬上限，
 *       想要更多弹幕<b>这个端点给不了</b>；返回条数正好等于上限时本库会打 {@code WARN}。</li>
 *   <li><b>发弹幕 / 删弹幕</b> —— 写操作，<b>本库任何地方都不做</b>（需 {@code csrf} 且会改动账号）。</li>
 *   <li><b>实时弹幕流</b>（WebSocket 长连接）不在本库范围内。</li>
 * </ul>
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code Content} / {@code Comment} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * <p><b>典型用法</b>：
 * <pre>{@code
 * Danmaku danmaku = new Danmaku();
 * DanmakuXml xml = danmaku.getDanmaku(cid);
 * for (DanmakuItem d : xml.getDanmaku()) {
 *     System.out.printf("[%.2fs] %s%n", d.getTime(), d.getText());
 * }
 * // 想判断"是不是被截断了"：xml.getDanmaku().size() == xml.getMaxlimit()
 * }</pre>
 *
 * @author 饿死的流浪猫
 */
public class Danmaku {

    /**
     * <b>取某个分 P 的全部弹幕</b>（{@code x/v1/dm/list.so}）。
     *
     * <p>返回的是 {@link DanmakuXml} 而不是裸列表，原因是 <b>{@code maxlimit} 是判断
     * "结果有没有被截断"的唯一依据</b> —— 只给列表就把它丢了。只要文本列表请用
     * {@link #getDanmakuList(long)}。
     *
     * <p>🔴 <b>零弹幕是合法结果</b>，不是错误：实测一个没人发弹幕的视频返回的 XML 里
     * 只有 {@code <i>} 头（{@code maxlimit=300}）、没有任何 {@code <d>} 元素。
     * 这种情况返回空列表，<b>不抛异常</b>。
     *
     * <p>⚠️ 每条弹幕的 {@code p} 属性实测是 <b>9 段</b>（老文档只写 7 段），
     * 解析是<b>容错</b>的：段数不足只会让个别字段为 {@code null}，不会让整条失败。
     * 字段含义见 {@code Danmaku} 的类注释。
     *
     * @param cid 分 P 的 {@code cid}（<b>不是 aid、不是 bvid</b>），必须 &gt; 0
     * @return 弹幕集合（含 {@code chatid} / {@code maxlimit} 等头部信息），不可为 null
     * @throws IOException {@code cid} ≤ 0、网络失败、HTTP 非 2xx，
     *                     或响应不是弹幕 XML（最常见成因是"拿到的是没解压的字节流"，
     *                     异常消息里会直接说明）
     */
    public DanmakuXml getDanmaku(long cid) throws IOException {
        try {
            return DanmakuService.INSTANCE.getDanmaku(cid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取弹幕文本列表</b> —— 等价于 {@code getDanmaku(cid).getDanmaku()}。
     *
     * <p>⚠️ 拿不到 {@code maxlimit}，也就<b>无法判断结果是否被截断</b>。
     * 需要那个判断时请用 {@link #getDanmaku(long)}。
     *
     * @param cid 分 P 的 {@code cid}
     * @return 弹幕列表；无弹幕时为空列表（<b>不会是 null</b>）
     * @throws IOException 同 {@link #getDanmaku(long)}
     */
    public List<DanmakuItem> getDanmakuList(long cid) throws IOException {
        try {
            return DanmakuService.INSTANCE.getDanmakuList(cid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

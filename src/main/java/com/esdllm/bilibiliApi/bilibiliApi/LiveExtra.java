package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveArea;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveStream;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveSubArea;
import com.esdllm.bilibiliApi.model.data.pojo.live.MasterInfo;
import com.esdllm.bilibiliApi.service.LiveService;

import java.io.IOException;
import java.util.List;

/**
 * 直播扩展门面：<b>拉流地址 / 主播信息</b>（库内第 13 个门面，2026-09-22 B1 批新增）。
 *
 * <p><b>为什么另立一个类而不是加进 {@code Live}</b>：{@code Live} 的类名与 public 方法签名
 * 是<b>逐字冻结</b>的下游契约（XatiiBot 依赖），新能力一律走新类 —— 这是本库自 v1 起写死的规矩。
 * 它和 {@code VideoExtra} 对 {@code BilibiliClient} 的关系是同一回事。
 *
 * <p><b>什么是"已有的"、什么是"新加的"（别重复查）</b>：
 * <table border="1">
 *   <caption>直播域两个门面的分工</caption>
 *   <tr><th>要什么</th><th>去哪拿</th><th>门槛</th></tr>
 *   <tr><td>房间标题 / 状态 / 封面 / 人气 / 分区 / 开播时间</td><td>{@code Live#getLiveRoom(roomId)}</td>
 *       <td>匿名</td></tr>
 *   <tr><td><b>可播放的拉流地址</b></td><td>{@link #getLiveStream(Long, Integer)}</td>
 *       <td>匿名（本门面新增）</td></tr>
 *   <tr><td>主播昵称 / 头像 / 认证 / 粉丝数 / 房间号</td><td>{@link #getMasterInfo(Long)}</td>
 *       <td>匿名（本门面新增）</td></tr>
 *   <tr><td><b>直播分区（一级 + 二级整棵树）</b></td><td>{@link #getAreaList()}</td>
 *       <td>匿名（B2 批新增）</td></tr>
 * </table>
 * ⚠️ {@code Live#getLiveRoom} 返回的 {@code LiveRoom} 里那个"地址"字段是<b>房间页地址</b>
 * （给人点的网页链接），<b>不是拉流地址</b> —— 想要能喂给播放器的流地址必须走本门面。
 * 这两个东西都叫"地址"，是最容易搞混的一处。
 *
 * <p><b>门槛：本门面两个方法都是零门槛</b>（2026-09-22 实测匿名 {@code code=0}）——
 * 既不需要 WBI 签名，也不需要登录凭据。这是它与 {@code VideoExtra#getPlayUrl} 最大的区别：
 * 后者受出口信誉影响、历史反复，而直播拉流<b>一直可用</b>。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code Live} / {@code VideoExtra} / {@code Content} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class LiveExtra {

    /**
     * <b>取直播流地址</b>（默认原画）。
     *
     * <p>等价于 {@link #getLiveStream(Long, Integer) getLiveStream(roomId, null)} ——
     * 清晰度按 {@code 10000}（原画）。直播<b>不消耗清晰度权限</b>，所以不像点播那样
     * "传 80 只给 64"，默认给原画是合理的。
     *
     * @param roomId 直播间房间号
     * @return 直播流信息，不可为 null
     * @throws IOException {@code roomId} 非法、网络失败、HTTP 非 2xx、业务码非 0、
     *                     {@code data} 为空，或服务端一条地址都没给
     */
    public LiveStream getLiveStream(Long roomId) throws IOException {
        return getLiveStream(roomId, null);
    }

    /**
     * <b>取直播流地址</b>（可指定清晰度）。
     *
     * <p>🔴 <b>{@code roomId} 是直播间号</b>（B 站页面上 {@code live.bilibili.com/1024} 里的
     * {@code 1024}）—— <b>不是</b>视频的 {@code cid}，也不是 {@code uid}。
     *
     * <p>⚠️ <b>返回的是 {@code .flv} 直播流</b>（多条 CDN，<b>不是分片</b>）：
     * {@code durl[0].length} 实测恒为 {@code 0}（直播没有总时长），地址带 {@code expires} 时效。
     * ⇒ <b>不要尝试"下载完再播"，也不要长期缓存地址。</b>
     *
     * <p>⚠️ 本方法在 {@code durl} 为空时<b>抛异常而不是返回空结果</b>：
     * {@code code=0} 只说明"接口调通了"，把"一个地址都没有"当成功交出去，
     * 调用方会在很久以后对着 null 猜。这与 {@code VideoExtra#getPlayUrl} 的处置一致。
     *
     * @param roomId 直播间房间号
     * @param qn     清晰度；{@code null} 或 ≤ 0 时按 {@code 10000}（原画，实测）
     * @return 直播流信息，不可为 null
     * @throws IOException {@code roomId} 非法、网络失败、业务码非 0、{@code data} 为空，或没有可用地址
     */
    public LiveStream getLiveStream(Long roomId, Integer qn) throws IOException {
        try {
            return LiveService.INSTANCE.getLiveStream(roomId, qn);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取主播信息</b>（昵称 / 头像 / 认证 / 粉丝数 / 房间号 / 勋章名 / 主播等级）。
     *
     * <p>🔴 <b>参数是主播 uid，不是房间号</b> —— 手里只有房间号时，先用
     * {@code Live#getLiveRoom(roomId)} 拿 {@code uid} 再进来。反过来本方法返回的
     * {@code room_id} 可以帮你回到房间域（去取拉流地址）。
     *
     * <p>✅ 零门槛（实测匿名 {@code code=0}）。
     *
     * <p>⚠️ {@code getPendant()} 实测是<b>字符串</b>（空串）而不是对象 —— 别按对象取。
     *
     * @param uid 主播 uid
     * @return 主播信息，不可为 null
     * @throws IOException {@code uid} 非法、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public MasterInfo getMasterInfo(Long uid) throws IOException {
        try {
            return LiveService.INSTANCE.getMasterInfo(uid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ B2 直播域扩（2026-09-22 新增）

    /**
     * <b>取直播分区</b>（{@code room/v1/Area/getList}，B2 批 #8）。
     *
     * <p>✅ 零门槛（实测匿名 {@code code=0}）。
     *
     * <p>🔴 <b>本方法没有入参，这是刻意的</b>：文档里的 {@code parent_area_id} <b>实测不起作用</b>。
     * 四格对照（不传 / {@code =1} / {@code =2} / {@code =999}）返回<b>完全一致</b> ——
     * 连<b>不存在的父分区 id 也照样返回全树</b>（12 个一级分区 / 450 个二级分区）。
     * 本库因此不暴露这个参数：给一个"传了没用"的入参，只会让人以为是自己传错了。
     * <b>要按父分区筛，在返回结果上自己挑</b>：
     * <pre>{@code
     * for (LiveArea area : liveExtra.getAreaList()) {
     *     if (area.getId() == 2) {                 // 2 = 网游
     *         area.getList().forEach(sub -> System.out.println(sub.getName()));
     *     }
     * }
     * }</pre>
     *
     * <p>⚠️ <b>一次调用会把整棵树带回来</b>（合计 450 个二级分区）—— 这是端点的形状，
     * 没有更省的形式。
     *
     * <p>⚠️ 🔴 <b>两层不能共用 POJO</b>：一级分区的 {@code id} 是 {@code Integer}、
     * 二级分区的 {@code id} 是 {@code String}（实测 {@code "86"}），
     * 详见 {@link LiveArea} 与 {@link LiveSubArea}。
     *
     * <p>⚠️ 分区随运营调整，<b>不要把某个 id 硬编码进业务逻辑</b>。
     *
     * @return 一级分区列表（每个含自己的二级分区），不可为 null
     * @throws IOException 网络失败、HTTP 非 2xx、业务码非 0，或 {@code code=0} 但分区列表为空
     */
    public List<LiveArea> getAreaList() throws IOException {
        try {
            return LiveService.INSTANCE.getAreaList();
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

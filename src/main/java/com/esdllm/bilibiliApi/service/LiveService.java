package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.BilibiliLiveResp;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveArea;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveStream;
import com.esdllm.bilibiliApi.model.data.pojo.live.MasterInfo;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;

/**
 * 直播间数据服务（{@code Live} 门面的后端）。
 *
 * <p>P1 起承担 {@code Live} 门面的实际数据获取职责。门面只做"调一次→分发"。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常）。
 * 原 {@code Live.java} 旧的"throws IOException + try-catch IOException 抛 RuntimeException"
 * 反模式已收敛，由门面边界按 §4.8.1 规则做最终转译（{@code throws IOException} 的方法被允许
 * 重新包装成 {@link java.io.IOException} 抛出；非 throws 的方法透传本服务的 BilibiliException）。
 *
 * <p><b>单例</b>：本服务无任何可变共享状态（不缓存 result，不持有 roomId），
 * 唯一的 {@link #INSTANCE} 是真正的 stateless，线程安全。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class LiveService {

    /** 单例入口，无状态。 */
    public static final LiveService INSTANCE = new LiveService();

    /**
     * 取一次完整 {@link LiveRoom}（包含直播状态、地址、标题、封面、UID、分区、人数、标签等所有字段）。
     *
     * <p>本方法<b>每次只发一次请求</b>。门面的多个 getter 各自调一次本方法并不会互相缓存——
     * "一次调用内复用一次结果"留给调用方按 §6.4 自行组织（{@code getLiveRoom(...)} 拿到对象再分发）。
     *
     * @param roomId 直播间房间号
     * @return 不可为 null
     * @throws BilibiliException {@code roomId == null}、网络异常、JSON 解析失败、业务码非 0、{@code data} 为空
     */
    public LiveRoom load(Long roomId) {
        if (roomId == null) {
            throw new BilibiliException("获取直播间信息失败\n房间号：null");
        }
        String url = BilibiliEndpoint.liveBaseUrl + roomId;
        HttpResponse<String> response = BilibiliHttp.get(url);
        BilibiliLiveResp resp;
        try {
            resp = JSON.parseObject(response.getBody(), BilibiliLiveResp.class);
        } catch (Exception e) {
            throw new BilibiliException("获取直播消息失败" + response.getBody() + "\n房间号：" + roomId);
        }
        if (Objects.isNull(resp.getData()) || resp.getCode() != 0) {
            throw new BilibiliException("获取直播间信息失败\n房间号：" + roomId);
        }
        return resp.getData();
    }

    // ------------------------------------------------------------------ B1 直播域扩（2026-09-22 新增）

    /**
     * <b>取直播流地址</b>（{@code room/v1/Room/playUrl}）—— 本库"能查到直播间"跨到"能拉流"的那一项。
     *
     * <p>✅ <b>零门槛</b>：不需要登录、不需要签名（2026-09-22 实测匿名 {@code code=0}）。
     * 它和视频域的 {@code playurl} 是两回事 —— 那个受出口信誉影响、历史上反复过多次，
     * 这个<b>一直可用</b>（本批唯一"没有历史包袱"的播放地址端点）。
     *
     * <p>🔴 <b>{@code cid} 是直播间号，不是视频 cid</b>：为了不再增加一个易混的名字，
     * 参数名沿用本服务的 {@code roomId} —— 端点自己要的是 {@code cid}，拼 query 时映射过去。
     *
     * <p>⚠️ 返回的是 {@code .flv} 直播流（多条 CDN，非分片），地址带 {@code expires} 时效。
     *
     * @param roomId 直播间房间号
     * @param qn     清晰度；{@code null} 或 ≤ 0 时按 {@code 10000}（原画，实测）——
     *               <b>直播不消耗清晰度权限</b>，所以这里不像点播那样"传 80 只给 64"
     * @return 直播流信息，不可为 null
     * @throws BilibiliException {@code roomId} 为空、网络失败、HTTP 非 2xx、业务码非 0、
     *                           {@code data} 为空，或<b>服务端一条地址都没给</b>
     */
    public LiveStream getLiveStream(Long roomId, Integer qn) {
        if (roomId == null || roomId <= 0) {
            throw new BilibiliException("房间号不能为空");
        }
        int qnValue = (qn == null || qn <= 0) ? 10000 : qn;
        String url = BilibiliEndpoint.liveStreamUrl
                + "?cid=" + roomId + "&qn=" + qnValue + "&platform=web";
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.liveReferer);
        LiveStream data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取直播流地址");
        if (data.getDurl() == null || data.getDurl().isEmpty()) {
            // 与视频域 playurl 同样的理由：code=0 只说明"接口调通了"。
            // 把"没有可用地址"当成功交出去，调用方会在很久以后对着 null 猜。
            throw new BilibiliException(0,
                    "获取直播流地址失败：服务端返回 code=0，但 durl 为空（本次 qn=" + qnValue + "）",
                    "没有可播放地址");
        }
        log.info("直播流 room={}：qn={}，{} 条地址", roomId, data.getCurrent_qn(), data.getDurl().size());
        return data;
    }

    /**
     * <b>取主播信息</b>（{@code live_user/v1/Master/info}）。
     *
     * <p>✅ 零门槛（实测匿名 {@code code=0}）。
     *
     * <p>🔴 <b>参数是主播 uid，不是房间号</b>。手里只有房间号时先用 {@link #load(Long)} 拿
     * {@code uid}；反过来，本方法返回的 {@code room_id} 可以用来校验/回到房间域。
     *
     * <p>它一次给全"这人是谁 + 在哪个房间"：昵称 / 头像 / 认证 / 性别 / 粉丝数 /
     * 房间号 / 勋章名 / 主播等级。
     *
     * @param uid 主播 uid
     * @return 主播信息，不可为 null
     * @throws BilibiliException {@code uid} 为空或 ≤ 0、网络失败、HTTP 非 2xx、业务码非 0、或 {@code data} 为空
     */
    public MasterInfo getMasterInfo(Long uid) {
        if (uid == null || uid <= 0) {
            throw new BilibiliException("uid不能为空");
        }
        String url = BilibiliEndpoint.liveMasterInfoUrl + "?uid=" + uid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.liveReferer);
        MasterInfo data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取主播信息");
        log.info("主播 info uid={}：{}，粉丝 {}，直播间 {}",
                uid, data.getInfo() == null ? null : data.getInfo().getUname(),
                data.getFollower_num(), data.getRoom_id());
        return data;
    }

    // ------------------------------------------------------------------ B2 直播域扩（2026-09-22 新增）

    /**
     * <b>取直播分区（整棵树）</b>（{@code room/v1/Area/getList}，B2 批 #8）。
     *
     * <p>✅ <b>零门槛</b>：匿名 {@code code=0}（2026-09-22 实测）。
     *
     * <p>🔴 <b>没有入参，是刻意的</b>：文档里的 {@code parent_area_id} <b>实测不起作用</b> ——
     * 四格对照（不传 / {@code =1} / {@code =2} / {@code =999}）返回的<b>完全一致</b>
     * （都是 12 个一级分区、450 个二级分区），连<b>不存在的父分区 id 也照样返回全树</b>。
     * 所以本库不暴露这个参数：暴露一个"传了没用"的入参，只会让人以为是自己传错了。
     * 要按父分区筛，在返回结果上自己挑 {@code LiveArea#getId()}。
     *
     * <p>⚠️ <b>一次调用会把 2500+ 条数据全带回来</b>（12 个一级 × 各自 2–195 个二级，
     * 合计 450 个二级分区）。这跟"只取分区名列表"的直觉不一样，但端点确实没有更省的形式。
     *
     * <p>⚠️ 分区数量与内容会随运营调整，<b>不要把某个 id 硬编码进业务逻辑</b>。
     *
     * @return 一级分区列表（每个含自己的二级分区），不可为 null
     * @throws BilibiliException 网络失败、HTTP 非 2xx、业务码非 0，
     *                           或 <b>{@code code=0} 但分区列表为空</b>（该端点匿名可用，空多半是形状变了）
     */
    public List<LiveArea> getAreaList() {
        HttpResponse<String> response = BilibiliHttp.get(BilibiliEndpoint.liveAreaListUrl,
                BilibiliEndpoint.jsonAccept, BilibiliEndpoint.liveReferer);
        List<LiveArea> data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取直播分区");
        if (data.isEmpty()) {
            throw new BilibiliException(0,
                    "获取直播分区失败：服务端返回 code=0，但分区列表为空",
                    "该端点匿名可用，空列表通常是响应形状变了");
        }
        int sub = 0;
        for (LiveArea area : data) {
            sub += area.getList() == null ? 0 : area.getList().size();
        }
        log.info("直播分区：{} 个一级分区 / {} 个二级分区", data.size(), sub);
        return data;
    }

    private LiveService() {}
}

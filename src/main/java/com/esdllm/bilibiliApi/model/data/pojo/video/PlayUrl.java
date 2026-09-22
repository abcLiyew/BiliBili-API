package com.esdllm.bilibiliApi.model.data.pojo.video;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>视频流地址</b> —— {@code x/player/playurl} 的 {@code data}（B3.5 批 #1）。
 *
 * <p>字段名与响应 JSON <b>逐字对应</b>，映射是 fastjson 按名匹配 —— "看着像但改一个字"会让字段静默为 null。
 * 下面这些是 <b>2026-09-22 现场响应的真实形状</b>（不是从文档抄的）。
 *
 * <p><b>两条通道，返回的字段完全不同，别混读</b>：
 * <table border="1">
 *   <caption>同一 bvid/cid、带凭据，实测（qn=64 与 qn=80 各跑一次）</caption>
 *   <tr><th>{@code fnval}</th><th>通道</th><th>结果落在哪</th><th>{@code quality} 实测</th><th>{@code format}</th></tr>
 *   <tr><td>{@code 1}</td><td>MP4</td><td>{@link #durl}（可空）</td>
 *       <td>{@code 64}=720P<br>（<b>传 {@code qn=80} 也只给 64</b>）</td><td>{@code mp4720}</td></tr>
 *   <tr><td>{@code 16}</td><td>DASH</td><td>{@link #dash}（{@link #durl} 为 null）</td>
 *       <td>{@code 80}=1080P</td><td>{@code flv}</td></tr>
 * </table>
 * ⇒ <b>想要 1080P 就必须走 DASH</b>：MP4 通道这次只给到 720P，{@code qn} 传多大都没用
 * （{@code accept_quality} 里真的只有 {@code [64,16]}）。
 *
 * <p><b>为什么另有一个 {@code platform=html5}</b>：叠加 {@code platform=html5&high_quality=1} 时，
 * 响应会多出 {@link #support_formats}、{@link #last_play_time} / {@link #last_play_cid}，
 * 且 {@code durl[0].backup_url} 变成 {@code null}（只给一条 URL）。实测它连<b>匿名</b>都通，
 * 是出口信誉差时的兜底形态（但画质上限仍由服务端决定）。
 *
 * <p>⚠️ <b>{@link #durl} 与 {@link #dash} 都可能为 null</b>：前者只在 MP4 通道有值，
 * 后者只在 DASH 通道有值。取地址前必须判空并给出"这次是哪种通道"的提示，
 * 否则下游会拿到一个 NPE 而不是一句能读懂的话。
 *
 * <p>⚠️ 地址里的 {@code deadline} 是<b>过期时间戳</b>（实测约 2 小时后），
 * 拿到的 URL <b>不能长期缓存</b>，过期后要重新调本接口换一条。
 *
 * @author 饿死的流浪猫
 */
@Data
public class PlayUrl {

    /** 数据来源（实测 {@code "local"}） */
    private String from;

    /**
     * 服务端结果串（实测 {@code "suee"}）。
     *
     * <p>⚠️ 注意它不是 {@code "suee"} 之外还有别的取值这种问题 —— 是它<b>本来就这么拼</b>
     * （疑似 {@code success} 被截断的线上遗留）。<b>别拿它当成功判据</b>：
     * 判成功请用外层 {@code code=0} + {@link #durl}/{@link #dash} 非空。
     */
    private String result;

    /** 本响应实际给出的清晰度（{@code 16}=360P / {@code 32}=480P / {@code 64}=720P / {@code 80}=1080P） */
    private Integer quality;

    /** 本响应实际给出的封装格式（MP4 通道实测 {@code mp4720}，DASH 通道实测 {@code flv}） */
    private String format;

    /** 时长（毫秒） */
    private Long timelength;

    /** 本视频可接受的格式串（逗号分隔，如 {@code mp4720,mp4}） */
    private String accept_format;

    /** 可接受清晰度的中文描述，与 {@link #accept_quality} <b>按下标一一对应</b> */
    private List<String> accept_description;

    /** 可接受清晰度的数值列表（与 {@link #accept_description} 同序） */
    private List<Integer> accept_quality;

    /** 视频编码 id（实测 {@code 7} = AVC/H.264） */
    private Integer video_codecid;

    /** 拖动定位参数名（实测 {@code start}） */
    private String seek_param;

    /** 拖动定位类型（实测 {@code offset}） */
    private String seek_type;

    /** <b>MP4 通道</b>的分片数组；DASH 通道下为 {@code null} */
    private List<Durl> durl;

    /** <b>DASH 通道</b>的音视频分流；MP4 通道下为 {@code null} */
    private Dash dash;

    /** 可选清晰度明细（实测只在 {@code platform=html5} 时出现） */
    private List<SupportFormat> support_formats;

    /** 上次播放进度（毫秒；实测只在 {@code platform=html5} 时出现） */
    private Long last_play_time;

    /** 上次播放的分 P cid（实测只在 {@code platform=html5} 时出现） */
    private Long last_play_cid;

    /**
     * MP4 分片。
     *
     * <p>单 P 视频实测只有一个元素（{@code order=1}）；多 P / FLV 分段时会有多个，
     * 调用方要按 {@link #order} 顺序拼接才是一个完整文件。
     */
    @Data
    public static class Durl {

        /** 分片序号（从 1 开始） */
        private Integer order;

        /** 本片时长（毫秒） */
        private Long length;

        /** 本片字节数 */
        private Long size;

        /** 前置预留字节（实测空串，B 站内部用） */
        private String ahead;

        /** 视频头预留字节（实测空串，B 站内部用） */
        private String vhead;

        /** <b>可播放地址</b>（主）；带时限，见类注释的 {@code deadline} 提醒 */
        private String url;

        /** 备用地址（同资源的不同 CDN）；{@code platform=html5} 时实测为 {@code null} */
        private List<String> backup_url;
    }

    /**
     * DASH 分流容器。
     *
     * <p>🔴 <b>DASH 是音视频分离的</b>：{@link #video} 与 {@link #audio} 是<b>两条独立的流</b>，
     * 本库只负责把地址交出来，<b>不做合流</b>（合流要 ffmpeg 之类的工具，远超一个 HTTP 客户端的职责）。
     * 所以"给一段能直接双击播放的地址"这件事，MP4 通道才做得到；DASH 通道给的是素材。
     */
    @Data
    public static class Dash {

        /** 时长（秒，实测 {@code 56}） */
        private Long duration;

        /** 缓冲下限（毫秒，camelCase 形态） */
        private Double minBufferTime;

        /** 缓冲下限（毫秒，snake_case 形态）—— 同一份数据两个键名，B 站两个都给 */
        private Double min_buffer_time;

        /** 视频流（通常只有 1 条：你请求的那个清晰度） */
        private List<DashMedia> video;

        /** 音频流（可能多条：不同码率） */
        private List<DashMedia> audio;
    }

    /**
     * DASH 的一条流（视频或音频）。
     *
     * <p>⚠️ {@code baseUrl} 与 {@code base_url} <b>是同一份数据的两个键名</b>，B 站两个都发
     * （实测两串完全相同）。这里都映射出来，取用时任选一个，<b>优先 {@link #baseUrl}</b>（camelCase 是主）。
     */
    @Data
    public static class DashMedia {

        /** 清晰度 id（视频流才有意义：{@code 80}=1080P） */
        private Integer id;

        /** 播放地址（camelCase 主键） */
        private String baseUrl;

        /** 播放地址（snake_case 副本，与 {@link #baseUrl} 同值） */
        private String base_url;

        /** 带宽（bps） */
        private Long bandwidth;

        /** MIME 类型（如 {@code video/mp4}） */
        private String mimeType;

        /** MIME 类型（snake_case 副本） */
        private String mime_type;

        /** 编码串（如 {@code avc1.640028}） */
        private String codecs;

        /** 宽（视频流才有值） */
        private Integer width;

        /** 高（视频流才有值） */
        private Integer height;

        /** 帧率（camelCase，实测是字符串） */
        private String frameRate;

        /** 帧率（snake_case 副本） */
        private String frame_rate;

        /** 备用地址（camelCase） */
        private List<String> backupUrl;

        /** 备用地址（snake_case） */
        private List<String> backup_url;

        /** 分片索引信息（结构较深、低频，保留原始 JSON） */
        private JSONObject segment_base;

        /** 分片索引信息（结构较深、低频，保留原始 JSON） */
        private JSONObject SegmentBase;
    }

    /**
     * 可选清晰度明细（{@code platform=html5} 时出现）。
     *
     * <p>它比 {@link PlayUrl#accept_description} 多给了 {@code display_desc} 与 {@code superscript}
     * —— 前者是"720P"这种短标签、后者是上标（如 {@code 大会员}）。做清晰度选择菜单时用它比用
     * {@code accept_description} 更顺手。
     */
    @Data
    public static class SupportFormat {

        /** 清晰度数值（与 {@link PlayUrl#accept_quality} 同源） */
        private Integer quality;

        /** 封装格式（如 {@code flv} / {@code mp4720}） */
        private String format;

        /** 新描述（如 {@code 1080P 高清}） */
        private String new_description;

        /** 短标签（如 {@code 1080P}） */
        private String display_desc;

        /** 上标文案（如 {@code 大会员}，无则空串） */
        private String superscript;

        /** 编码列表（实测为 {@code null}） */
        private List<String> codecs;

        /** 为什么可看该清晰度（B 站内部值） */
        private Integer can_watch_qn_reason;

        /** 为什么受限于该清晰度（B 站内部值） */
        private Integer limit_watch_reason;

        /** 埋点字段（不用于业务） */
        private JSONObject report;
    }
}

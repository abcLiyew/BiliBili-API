package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.service.DynamicService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * 动态门面。
 *
 * <p>P1 起本门面仅做"调 {@link DynamicService} 一次、读字段返回"；
 * 原 {@code ApiBase} 调用、JSON 解析、schema 适配、字段映射、长图渲染数据加载等
 * 全部迁到服务层。
 *
 * <p><b>红线</b>：
 * <ul>
 *   <li>{@link DynamicInfo} 静态内部类（§2 红线，必须保留字段名/类型不变）；</li>
 *   <li>3 个 public 方法的签名（含 {@code throws IOException} / {@code throws InterruptedException}）逐字不变；</li>
 *   <li>对外行为：相同入参 → 相同返回值；异常路径按 §4.8.1 转译。</li>
 * </ul>
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class Dynamic {
    /**
     * 动态列表信息
     */
    @Data
    public static class DynamicInfo {
        /**
         * 动态ID，如果为null，则该条动态为转发动态
         */
        private String dynamicId;
        /**
         * 标签，只有置顶动态有值，并且值为"置顶"
         */
        private String tag;
        /**
         * 发布时间+动作，如"04月20日 · 发布了动态视频"，"04月20日 · 投稿了视频"，如果是直播动态则值为"直播了"
         */
        private String time;
        /**
         * 标题
         */
        private String title;
        /**
         * 内容
         */
        private String desc;
        /**
         * 图片链接，如果为空数组，则该条动态不是图文投稿
         */
        private List<String> imageUrl;
        /**
         * 视频BV号，如果为null，则该条动态不是视频投稿
         */
        private String bvid;
        /**
         * 转发动态ID，如果为null，则该条动态不是转发动态
         */
        private String shareDynamicId;
        /**
         * 发布者 UID（{@code module_author.mid}）。
         *
         * <p><b>2026-09-14 新增（附加字段，不改动上面任何既有字段）</b>：
         * 关注流 {@code feed/all} 一次返回多个 UP 的动态，调用方必须靠本字段把每条动态
         * 归到"是哪条订阅的"。{@code feed/space}（按 uid 查）场景下等于请求时的 uid。
         */
        private String uid;
        /**
         * 发布者昵称（{@code module_author.name}）。
         *
         * <p>2026-09-14 新增。有了它，走关注流时不必再额外调一次名片接口取昵称
         * （少一次出站请求，而请求密度正是风控敏感项）。
         */
        private String userName;
    }

    /**
     * 获取动态详情。
     *
     * <p>端点：{@code x/polymer/web-dynamic/v1/detail?id={dynamicId}}（实测匿名可用）。
     * 旧端点所在的 {@code api.vc.bilibili.com/dynamic_svr} 已整站下线（HTTP 404）。
     *
     * <p><b>异常约定</b>：所有失败在门面边界统一转成签名里声明的 {@link IOException}。
     *
     * @param dynamicId 动态ID（opus id / dynamic id 均可，新旧格式都支持）
     * @return 动态卡片详情
     * @throws IOException IO异常，或取数失败（消息里含 B 站 code 与语义化说明）
     */
    public BilibiliDynamicResp.Data.Card getDynamicDetail(String dynamicId) throws IOException {
        return DynamicService.INSTANCE.getDetail(dynamicId);
    }

    /**
     * 获取动态长图（Java2D 自绘，无浏览器依赖）。
     *
     * <p><b>签名兼容性</b>：签名仍为 {@code throws InterruptedException}（与本方法旧实现完全一致；
     * Java 允许声明一个从未实际抛出的受检异常，这是合法的"占位声明"）。
     * 渲染过程中抛出的 {@link IOException} 在边界处包成 {@link RuntimeException}，与本方法
     * 旧实现的失败语义一致。{@code XatiiBot} 侧不需要任何改动。
     *
     * <p><b>覆盖范围</b>：同 {@code RenderModelLoader} —— 视频/转发动态在 opus 端点返回
     * 空 {@code modules}，由 {@code RenderModelLoader} 自动回退至 {@code v1/detail}（旧 schema）。
     *
     * @param dynamicId 动态 ID
     * @return 动态长图
     */
    public BufferedImage getDynamicImg(String dynamicId) throws InterruptedException {
        try {
            return DynamicService.INSTANCE.getImg(dynamicId);
        } catch (IOException e) {
            // 失败的语义与本方法旧实现（Selenium 路线）保持一致——catch-all 包成 RuntimeException
            throw new RuntimeException("动态图片渲染失败：" + e.getMessage(), e);
        }
    }

    /**
     * 获取指定用户的空间动态列表。
     *
     * <p><b>实现路径</b>：桌面端动态 feed 接口（{@code x/polymer/web-dynamic/v1/feed/space}）。
     * {@code features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote} 不能漏
     * （详见 {@code BilibiliEndpoint.dynamicFeedUrl}）。
     *
     * <p><b>签名兼容性</b>：保留 {@code throws InterruptedException} 合法占位声明（同
     * {@link #getDynamicImg(String)}），{@code XatiiBot} 零改动。
     *
     * @param uid 用户 UID
     * @return 动态列表
     */
    public List<DynamicInfo> getDynamicInfoList(String uid) throws InterruptedException {
        try {
            return DynamicService.INSTANCE.getInfoList(uid);
        } catch (IOException e) {
            throw new RuntimeException("获取动态列表失败：" + e.getMessage(), e);
        } catch (BilibiliException e) {
            throw new RuntimeException("获取动态列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 获取<b>关注流</b>动态列表（登录账号所关注 UP 的最新动态，一次请求覆盖全部）。
     *
     * <p>端点：{@code x/polymer/web-dynamic/v1/feed/all}。
     *
     * <p><b>为什么需要它</b>（2026-09-14 实测）：{@code feed/space} 会被 B 站 WAF
     * 以 {@code {"code":-412,"message":"request was banned"}} <b>按客户端封禁</b> ——
     * 同一台机器、同一枚有效 Cookie，{@code x/frontend/finger/spi} 返回 200、
     * 本方法返回 200/code=0（15 万字节真实数据），只有 {@code feed/space} 这条路径被拒。
     * 换 buvid、换请求头形状、拉长间隔都无效（不是频率问题，是"这条路被封"）。
     *
     * <p>因此当 {@code feed/space} 不可用时，关注流是<b>同机可用</b>的替代数据源，
     * 而且更省请求：一轮只需 1 次请求（原来每个 uid 1 次）。
     *
     * <p><b>前提与限制</b>：
     * <ul>
     *   <li>需要登录 Cookie，且该账号<b>已关注</b>目标 UP —— 未关注的 UP 不会出现在这里；</li>
     *   <li>返回里会混入推荐的直播/动态（如 {@code DYNAMIC_TYPE_LIVE_RCMD}），
     *       调用方应按 {@link DynamicInfo#getUid()} 过滤；</li>
     *   <li>只含最新一页（约 20 条），轮询间隔内足以覆盖。</li>
     * </ul>
     *
     * <p>签名与 {@link #getDynamicInfoList(String)} 同款（保留占位声明），
     * {@code XatiiBot} 侧不需要改动既有调用。
     *
     * @return 关注流动态列表（可能为空，永不为 null）
     */
    public List<DynamicInfo> getFollowFeed() throws InterruptedException {
        try {
            return DynamicService.INSTANCE.getFollowFeed();
        } catch (IOException e) {
            throw new RuntimeException("获取关注流失败：" + e.getMessage(), e);
        } catch (BilibiliException e) {
            throw new RuntimeException("获取关注流失败：" + e.getMessage(), e);
        }
    }
}

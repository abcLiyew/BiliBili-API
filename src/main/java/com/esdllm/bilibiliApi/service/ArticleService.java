package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.content.ArticleInfo;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>专栏（图文）</b>数据服务（{@code Content} 门面的后端，B4 批 #1，2026-09-22）。
 *
 * <p>📌 <b>它是"跨表遗留"落地的</b>：{@code INTERFACE_PLAN.md} §3 把
 * {@code x/article/viewinfo} 标成 B2，但 §4-B2 明细表从来没有这一项 ⇒ B2 时没做。
 * 2026-09-22 实测确认它<b>匿名可用</b>，因此没有随 B4 其余"做不动"的项一起挂起。
 *
 * <p>🔴 <b>门槛：匿名可用</b>（同一分钟 A/B：匿名与凭据都 {@code code=0}，23 个键相同）。
 * ⚠️ 但两个布尔字段 {@code is_author} / {@code in_list} <b>归因不同</b>（真机 2×2 实测）：
 * <b>{@code is_author} 跟凭据走</b>（无凭据 {@code false} → 有凭据 {@code true}，读别人的文章也是
 * {@code true}）；<b>{@code in_list} 只跟"请求有没有会话标识"走</b>（零 Cookie {@code false}，
 * 带匿名指纹或凭据都是 {@code true}）。两者字面语义都与实测不符，详见 {@link ArticleInfo} 的类注释。
 * <b>所以本服务不对这两个字段做任何加工</b>，原样交给调用方。
 *
 * <p>⚠️ <b>不需要 WBI 签名</b>，也<b>不需要凭据</b>；{@code Referer} 实测免疫
 * （四格全 {@code code=0}），走全库默认站根。
 *
 * <p><b>异常语义</b>：本服务<b>只抛 {@link BilibiliException}</b>（运行时异常），
 * 由门面边界按既有约定包装成 {@code IOException}。
 *
 * <p><b>无状态</b>：不缓存任何数据。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class ArticleService {

    /** 单例入口，无状态。 */
    public static final ArticleService INSTANCE = new ArticleService();

    /**
     * <b>取专栏信息</b>（{@code x/article/viewinfo}）。
     *
     * <p>🔴 <b>入参是专栏号，不是 {@code cv} 字符串</b>：地址栏里的 {@code cv4538122}
     * 传 {@code 4538122}。传 {@code cv} 前缀或 0 都会被服务端拒掉。
     *
     * <p>⚠️ <b>要"这篇文章有多少赞"请读 {@code getStats().getLike()}，
     * 而不是 {@code getLike()}</b> —— 后者是"我点过赞没"，匿名恒 {@code 0}。
     * 这一对同名字段是本服务最容易踩的地方，详见 {@link ArticleInfo}。
     *
     * @param id 专栏号（{@code cv} 后的数字，必须 &gt; 0）
     * @return 专栏信息，不可为 null
     * @throws BilibiliException {@code id} ≤ 0、网络失败、业务码非 0，或 {@code data} 为空
     */
    public ArticleInfo getArticleInfo(long id) {
        if (id <= 0) {
            throw new BilibiliException("专栏号必须为正数，收到：" + id);
        }
        String url = BilibiliEndpoint.articleViewInfoUrl + "?id=" + id;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        ArticleInfo data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取专栏信息");
        log.info("专栏信息：id={} title={} author={} stats.view={} stats.like={}",
                id, data.getTitle(), data.getAuthor_name(),
                data.getStats() == null ? null : data.getStats().getView(),
                data.getStats() == null ? null : data.getStats().getLike());
        return data;
    }

    private ArticleService() {
    }
}

package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderInfo;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavResourceList;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>收藏夹数据服务</b>（{@code Content} 门面的后端，B3.5 批 #6；B2 批另有扩项）。
 *
 * <p>📌 本服务覆盖三个端点，<b>它们门槛不同</b>，先看这张表再看方法：
 * <table border="1">
 *   <caption>三个收藏夹端点</caption>
 *   <tr><th>方法 / 端点</th><th>门槛</th><th>备注</th></tr>
 *   <tr><td>{@link #getCreatedFolders}({@code folder/created/list-all})</td>
 *       <td>🔴 需凭据（B 形态）</td><td>"我创建的"夹目录</td></tr>
 *   <tr><td>{@link #getFolderInfo}({@code folder/info})</td>
 *       <td>⚠️ 看夹的可见性</td><td>公开夹匿名可读；含 {@code attr=1} 的夹匿名 {@code -403}</td></tr>
 *   <tr><td>{@link #getResources}({@code resource/list})</td>
 *       <td>⚠️ 同上</td><td>夹内内容，同样 {@code -403} 规则</td></tr>
 * </table>
 *
 * <p>🔴 <b>{@code -403} 在本域有"两义"</b>：既可能是"缺 WBI 签名"，也可能是
 * <b>"资源权限不足"（访问的是私密夹）</b>。本库在后一种情形下会把服务端的原话
 * {@code 访问权限不足} 连同"这两种成因"的解释一起抛出去，见 {@link #favoriteFailure}。
 *
 * <p><b>异常语义</b>：只抛 {@link BilibiliException}（运行时），门面边界包装成 {@code IOException}。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class FavoriteService {

    /** 单例入口，无状态。 */
    public static final FavoriteService INSTANCE = new FavoriteService();

    /**
     * <b>取某个用户"创建的"收藏夹目录</b>（{@code x/v3/fav/folder/created/list-all}）。
     *
     * <p>🔴 <b>外层 {@code code=0} 不够用，本方法因此显式判"列表有没有内容"</b>：
     * 实测<b>匿名也返回 {@code code=0}</b>，但不给列表 —— 与 {@code x/space/upstat} 同属
     * "外层码骗人"那一类。若把空列表当成功返回，调用方会以为"此人没有收藏夹"，
     * 而这个结论与"我没带凭据"完全无法区分。所以这里把空列表当<b>失败</b>抛出并说明原因。
     *
     * <p>⚠️ 返回的 {@code list[].id} 才是查夹内内容的 {@code media_id}；{@code fid} 是另一套短 id。
     * 详见 {@link FavFolderList.FavFolder}。
     *
     * @param upMid 目标用户 mid（实测只有本人的 mid 能给到有效数据）
     * @return 收藏夹目录，不可为 null
     * @throws BilibiliException {@code upMid} ≤ 0、网络失败、业务码非 0，
     *                           或<b>服务端回 {@code code=0} 但列表为空</b>（几乎总是"没注入凭据"）
     */
    public FavFolderList getCreatedFolders(long upMid) {
        if (upMid <= 0) {
            throw new BilibiliException("mid不能小于0");
        }
        String url = BilibiliEndpoint.favFolderListAllUrl + "?up_mid=" + upMid;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.spaceFavlistReferer.formatted(String.valueOf(upMid)));
        FavFolderList data = ResponseParserSupport.requireData(response, new TypeReference<>() {
        }, "获取收藏夹目录");
        if (data.getList() == null || data.getList().isEmpty()) {
            throw new BilibiliException(0,
                    "获取收藏夹目录失败：服务端返回 code=0，但 list 为空 —— "
                            + "这不是'此人没有收藏夹'，而是'没有给出数据'",
                    "该端点匿名时正是这种形态（code=0 但不给列表），"
                            + "请先 Login#getCredentialStatus() 确认已注入有效凭据");
        }
        log.info("收藏夹目录 up_mid={}：{} 个", upMid, data.getCount());
        return data;
    }

    // ------------------------------------------------------------------ B2 收藏夹扩（2026-09-22 新增）

    /**
     * <b>取收藏夹详情</b>（{@code x/v3/fav/folder/info}，B2 批 #5）。
     *
     * <p>⚠️ <b>门槛取决于夹本身</b>（2026-09-22 同一分钟实测）：
     * <table border="1">
     *   <caption>同一端点、同一时刻、只换 media_id（全匿名）</caption>
     *   <tr><th>{@code media_id}</th><th>标题</th><th>{@code attr}</th><th>结果</th></tr>
     *   <tr><td>3526698880</td><td>小雨绒Candy</td><td>2</td><td>{@code code=0}</td></tr>
     *   <tr><td>1095405480</td><td>默认收藏夹</td><td>1</td><td><b>{@code -403 访问权限不足}</b></td></tr>
     * </table>
     * ⇒ 🔴 <b>别用 {@code attr} 反推公开性</b>（B3.5 批曾把方向写反，B2 已订正）：
     * 判据是响应码。私密夹要用<b>本人</b>的凭据才读得到。
     *
     * <p>⚠️ 返回的 {@code id} 才是夹内查询要用的 {@code media_id}；{@code fid} 是另一套短 id。
     *
     * @param mediaId 夹 id（{@code list-all} 里那条的 {@code id}，<b>不是</b> {@code fid}）
     * @return 夹详情，不可为 null
     * @throws BilibiliException {@code mediaId} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0
     *                           （含<b>私密夹的 {@code -403}</b>，消息里会说明两种成因）
     */
    public FavFolderInfo getFolderInfo(long mediaId) {
        if (mediaId <= 0) {
            throw new BilibiliException("media_id不能小于0");
        }
        String url = BilibiliEndpoint.favFolderInfoUrl + "?media_id=" + mediaId;
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        FavFolderInfo data;
        try {
            data = ResponseParserSupport.requireData(response, new TypeReference<>() {
            }, "获取收藏夹详情");
        } catch (BilibiliException e) {
            throw favoriteFailure("获取收藏夹详情", mediaId, e);
        }
        log.info("收藏夹详情 media_id={}：{}（attr={}，内容 {} 条）",
                mediaId, data.getTitle(), data.getAttr(), data.getMedia_count());
        return data;
    }

    /**
     * <b>取收藏夹内容（一页）</b>（{@code x/v3/fav/resource/list}，B2 批 #6）。
     *
     * <p>门槛与 {@link #getFolderInfo} <b>完全相同</b>（实测两个端点对同一个夹的裁决一致）：
     * 公开夹匿名可读，含 {@code attr=1} 的夹匿名 {@code -403}。
     *
     * <p>🔴 <b>"本页 0 条"与"夹里没有内容"是两件事</b>，所以这里加了一道交叉校验：
     * 若 {@code info.media_count > 0} 却一条 {@code medias} 都没给，本方法<b>抛异常</b>。
     * 反过来说，<b>{@code media_count=0} 的空夹是合法结果，不会抛</b> ——
     * 与 {@link #getCreatedFolders} 那种"空列表一律报错"的处理刚好相反，
     * 因为这里的空<b>能</b>由 {@code media_count} 佐证，而那里不能。
     *
     * <p>⚠️ 翻页靠 {@code pn}，默认每页 20 条（实测 {@code ps=20} 时正好给 20 条），
     * 是否还有下一页看 {@code has_more}。
     *
     * <p>⚠️ 内容条目里 {@code season} / {@code ogv} 实测为 {@code null}（视频内容用不到），
     * 形状未验证 —— 详见 {@code FavResourceList.Media}。
     *
     * @param mediaId 夹 id
     * @param pn      页码（从 1 开始）；{@code ≤0} 时按 1
     * @param ps      每页条数；{@code ≤0} 时按 20
     * @return 一页内容（含夹信息 {@code info}），不可为 null
     * @throws BilibiliException {@code mediaId} ≤ 0、网络失败、HTTP 非 2xx、业务码非 0
     *                           （含私密夹的 {@code -403}），或<b>夹里明明有内容却一条都没给</b>
     */
    public FavResourceList getResources(long mediaId, int pn, int ps) {
        if (mediaId <= 0) {
            throw new BilibiliException("media_id不能小于0");
        }
        String url = BilibiliEndpoint.favResourceListUrl + "?media_id=" + mediaId
                + "&pn=" + Math.max(1, pn)
                + "&ps=" + (ps <= 0 ? 20 : ps);
        HttpResponse<String> response = BilibiliHttp.get(url, BilibiliEndpoint.jsonAccept,
                BilibiliEndpoint.referer);
        FavResourceList data;
        try {
            data = ResponseParserSupport.requireData(response, new TypeReference<>() {
            }, "获取收藏夹内容");
        } catch (BilibiliException e) {
            throw favoriteFailure("获取收藏夹内容", mediaId, e);
        }

        int got = data.getMedias() == null ? 0 : data.getMedias().size();
        Integer declared = data.getInfo() == null ? null : data.getInfo().getMedia_count();
        if (got == 0 && declared != null && declared > 0) {
            throw new BilibiliException(0,
                    "获取收藏夹内容失败：夹信息说有 " + declared + " 条，但本页一条都没给（media_id="
                            + mediaId + "，pn=" + Math.max(1, pn) + "）",
                    "两种可能：① pn 超出范围（该夹内容不足这么多页）；"
                            + "② 响应形状变了。先换 pn=1 复验，再看原始响应的 data.medias");
        }
        log.info("收藏夹内容 media_id={} 第 {} 页：{} 条 / 共 {} 条（has_more={}）",
                mediaId, Math.max(1, pn), got, declared, data.getHas_more());
        return data;
    }

    /**
     * 把收藏夹域的失败包装成"能分辨 {@code -403} 两种成因"的异常。
     *
     * <p>为什么单独立一个方法：{@code -403} 在本库是<b>两义码</b> ——
     * 既可能是"缺 WBI 签名"，也可能是"资源权限不足"。这两个端点的失败<b>几乎总是后者</b>
     * （实测服务端原话就是 {@code 访问权限不足}），而 {@code ErrorMapper} 的通用文案
     * 不会替调用方把这个区分讲出来。所以这里补一句人名话。
     *
     * <p>⚠️ 只在 {@code -403} 时改写；其它码原样抛出，避免把通用错误掩盖成收藏夹专属文案。
     */
    private static BilibiliException favoriteFailure(String action, long mediaId, BilibiliException cause) {
        if (cause.getCode() != -403) {
            return cause;
        }
        return new BilibiliException(-403,
                action + "失败：code=-403，" + cause.getDescription() + "（media_id=" + mediaId + "）",
                "-403 有两种成因：① 缺 WBI 签名（本域不需要签名，所以基本不是这个）；"
                        + "② 资源权限不足 —— 这个夹是私密的，匿名读不到，需要注入夹主人的凭据。"
                        + "另注：夹的 attr 字段不能用来提前判断公开性，实测含 attr=1 的夹匿名为 -403");
    }

    private FavoriteService() {
    }
}

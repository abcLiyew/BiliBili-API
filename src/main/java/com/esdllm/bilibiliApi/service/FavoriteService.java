package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>收藏夹目录</b>数据服务（{@code Content} 门面的后端，B3.5 批 #6）。
 *
 * <p>📌 <b>本批只做"列出自己创建的收藏夹"</b>。夹内内容（{@code x/v3/fav/resource/list}）
 * 刻意不做：它对<b>私密</b>夹匿名会返回 {@code -403}，而那个 {@code -403} 是"资源权限不足"、
 * <b>不是</b>"缺 WBI 签名"（同一个码在本库有两种成因，见 {@code ErrorMapper}）——
 * 这种容易让人误判的接口不放进这一批。
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
        FavFolderList data = requireData(response, new TypeReference<>() {
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

    /**
     * HTTP 状态 → 反序列化 → 业务码 → 取 data。
     *
     * <p>与其它 Service 的同名私有方法一样是<b>刻意的拷贝</b>（理由见 {@code HistoryService}）。
     *
     * @param response 原始响应
     * @param type     目标类型
     * @param action   正在做的事
     * @param <T>      data 类型
     * @return 非 null 的 data
     * @throws BilibiliException HTTP 非 2xx、响应无法解析、业务码非 0、或 data 为空
     */
    private static <T> T requireData(HttpResponse<String> response, TypeReference<ApiResponse<T>> type,
                                     String action) {
        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), action);
        if (httpError != null) {
            throw httpError;
        }
        ApiResponse<T> parsed;
        try {
            parsed = JSON.parseObject(response.getBody(), type);
        } catch (Exception e) {
            throw new BilibiliException(0, action + "失败：HTTP " + response.getStatus()
                    + " 的响应无法解析（前 120 字：" + brief(response.getBody()) + "）", "响应形状不符");
        }
        return ResponseParserSupport.unwrap(parsed, action);
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }

    private FavoriteService() {}
}

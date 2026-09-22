package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
import com.esdllm.bilibiliApi.model.data.pojo.content.HistoryCursor;
import com.esdllm.bilibiliApi.model.data.pojo.content.ToViewList;
import com.esdllm.bilibiliApi.service.FavoriteService;
import com.esdllm.bilibiliApi.service.HistoryService;

import java.io.IOException;

/**
 * 内容管理门面：<b>观看历史 / 稍后再看 / 收藏夹目录</b>（库内第 11 个门面，2026-09-22 B3.5 批新增）。
 *
 * <p><b>为什么把这三件事装进一个门面</b>：这是 {@code INTERFACE_PLAN.md} §7-Q1 决策
 * <b>(d)「混合：高频域独立 + 低频域合并」</b>的产物 —— 高频域（视频 / 评论 / 直播 / 用户 / 搜索）
 * 各自独立成类，而这三项<b>彼此无关、频率低、又都是"我自己的内容"</b>，
 * 给每个建一个门面只会增加调用方的认知成本。
 * <b>门面数量 = 调用方认知成本</b>，这个门面就是按这一条原则合并出来的。
 *
 * <p>⚠️ <b>它不是"内容"的万能入口</b>：夹内内容（{@code fav/resource/list}）、专栏正文、
 * 弹幕、番剧都不在这里（前者在 B4 且对私密夹会 {@code -403}，后几项在别的批次）。
 * 名字宽泛是合并的代价，边界靠这句话与每个方法的 javadoc 划清。
 *
 * <p><b>🔴 本门面所有方法都依赖调用方提供的有效凭据</b>（三者实测全部<b>真需登录</b>）：
 * 未注入凭据时会拿到 {@code -101 账号未登录}（{@link #getWatchHistory} / {@link #getToView}），
 * 或 {@code code=0} 但<b>空列表</b>（{@link #getFavoriteFolders} —— 这种最阴，
 * 外层码骗人，只有"list 为空"能暴露它）。
 * ⇒ 建议<b>先问一次 {@code Login#getCredentialStatus()}</b>，别拿异常当唯一判据
 * （{@code -101} 该重新登录，{@code -352} 是风控/环境，两者处置完全不同）。
 *
 * <p>🔴 <b>本门面全是 GET 只读</b>（这是它与"写操作"的边界，也是它敢做进库里的原因）：
 * 往"稍后再看"里增删、清空历史、收藏/取消收藏<b>都不在这里，也不在本库任何地方</b> ——
 * 那些需要 {@code csrf=bili_jct} 且会改动账号，若哪天要做必须另立门面与只读面严格隔离。
 *
 * <p><b>异常边界</b>：本门面所有方法都声明 {@code throws IOException}，
 * 库内的 {@link BilibiliException} 在边界处被包装成 {@link IOException}
 * —— 与 {@code Login} / {@code UserSpace} / {@code VideoExtra} 的既有约定一致。
 * <b>包装时保留内层消息</b>（用 {@code e.getMessage()}），理由见 {@code Login} 的同段说明。
 *
 * @author 饿死的流浪猫
 */
public class Content {

    /**
     * <b>取观看历史第一页</b>（{@code x/web-interface/history/cursor}）。
     *
     * <p>🔴 <b>真需登录</b>：匿名 {@code -101 账号未登录}（实测），无降级。
     *
     * <p>要翻页请用 {@link #getWatchHistory(Integer, Long, Long, String)} —— 本条只给第一页，
     * 且它返回的 {@code HistoryCursor#getCursor()} 就是下一页的输入。
     *
     * @param ps 每页条数（实测只吃<b>很小的</b>合法区间：传超大值会被服务端直接拒掉，
     *           而不是"尽量多给"）
     * @return 观看历史，不可为 null
     * @throws IOException {@code ps} ≤ 0、网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public HistoryCursor getWatchHistory(int ps) throws IOException {
        try {
            return HistoryService.INSTANCE.getWatchHistory(ps);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取观看历史（游标翻页）</b>。
     *
     * <p>🔴 <b>翻页是游标式的，不是页码式的 —— 这是本方法唯一需要读懂的地方</b>：
     * 把上一条结果里的 {@code cursor.max} / {@code cursor.view_at} / {@code cursor.business}
     * 原样传进来，就是下一页。三个值<b>缺一不可</b>（只带 {@code max} 在跨业务时跳错位置）。
     * <pre>{@code
     * HistoryCursor first = content.getWatchHistory(20);
     * HistoryCursor.CursorPos c = first.getCursor();
     * HistoryCursor next  = content.getWatchHistory(20, c.getMax(), c.getView_at(), c.getBusiness());
     * }</pre>
     * 本库<b>刻意不把这个游标存在内部</b>：服务无状态，翻页的"位置"属于调用方，
     * 藏在库里会让"翻到哪了"变得不可见，也会让并发调用互相踩。
     *
     * @param ps       每页条数；{@code null} 或 ≤ 0 时不传该参数
     * @param max      游标主键；首页传 {@code null}
     * @param viewAt   游标时间；首页传 {@code null}
     * @param business 业务类型（{@code archive}/{@code live}/{@code article}）；首页传 {@code null}
     * @return 观看历史，不可为 null
     * @throws IOException 参数非法、网络失败、业务码非 0（含 {@code -101} 未登录）、或 {@code data} 为空
     */
    public HistoryCursor getWatchHistory(Integer ps, Long max, Long viewAt, String business)
            throws IOException {
        try {
            return HistoryService.INSTANCE.getWatchHistory(ps, max, viewAt, business);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取"稍后再看"整个列表</b>（{@code x/v2/history/toview}）。
     *
     * <p>🔴 <b>它不分页</b>：一次把整个列表给完（实测 37 条），响应只有 {@code count} 与 {@code list}
     * —— 与 {@link #getWatchHistory(int)} 的游标翻页形态完全不同，别按同一种方式用。
     *
     * <p>🔴 <b>真需登录</b>：匿名 {@code -101}（实测）。
     *
     * <p>📌 这个端点曾被 {@code INTERFACE_PLAN.md} 误归进 B4"写操作"，已纠正：它是 <b>GET 只读</b>。
     * 往列表里<b>加/删</b>才是写操作（另有端点 + {@code csrf}），本库不做。
     *
     * @return 稍后再看列表，不可为 null
     * @throws IOException 网络失败、业务码非 0（未注入凭据时即 {@code -101}）、或 {@code data} 为空
     */
    public ToViewList getToView() throws IOException {
        try {
            return HistoryService.INSTANCE.getToView();
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * <b>取"我创建的"收藏夹目录</b>（{@code x/v3/fav/folder/created/list-all}）。
     *
     * <p>🔴 <b>这里缺凭据的形态是本门面最阴的一种，值得单独记</b>：实测<b>匿名也返回 {@code code=0}</b>，
     * 但<b>不给列表</b> —— 与 {@code x/space/upstat} 同属"外层码骗人"那一类。
     * ⇒ 判据不是 {@code code}，而是"list 有没有内容"。本方法因此把空列表当<b>失败</b>抛出，
     * 而不是安静地返回一个空目录（那会被读成"此人没有收藏夹"，与"我没带凭据"无法区分）。
     *
     * <p>⚠️ 取到的 {@code list[].id} 才是夹内内容要用的 {@code media_id}；{@code fid} 是另一套短 id，
     * 混用会查不到。{@code attr=2} 表示私密夹（后续查内容时权限会收紧）。
     *
     * <p>⚠️ 夹内内容（{@code fav/resource/list}）<b>本批不做</b>：它对私密夹匿名会 {@code -403}，
     * 而那个 {@code -403} 是"资源权限不足"不是"缺签名"，容易误判 —— 单独排期。
     *
     * @param upMid 目标用户 mid（实测只有本人的 mid 能给到有效数据）
     * @return 收藏夹目录，不可为 null
     * @throws IOException {@code upMid} ≤ 0、网络失败、业务码非 0，
     *                     或服务端回 {@code code=0} 但列表为空（几乎总是"没注入凭据"）
     */
    public FavFolderList getFavoriteFolders(long upMid) throws IOException {
        try {
            return FavoriteService.INSTANCE.getCreatedFolders(upMid);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}

package com.esdllm.bilibiliApi.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.adapter.DynamicSchemaAdapter;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.bilibiliApi.Dynamic.DynamicInfo;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.AnonymousSession;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import com.esdllm.bilibiliApi.render.Java2DImageRenderer;
import com.esdllm.bilibiliApi.render.RenderModel;
import com.esdllm.bilibiliApi.render.RenderModelLoader;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态数据服务（{@code Dynamic} 门面的后端）。
 *
 * <p>P1 起承担 {@code Dynamic} 门面的实际数据获取职责（包括请求 → JSON 解析 → schema 适配 /
 * 字段映射 / 渲染数据加载全部）。
 *
 * <p><b>异常语义</b>：
 * <ul>
 *   <li>{@link #getDetail(String)} 抛 {@link IOException}（向下兼容 §4.8.1：包装库内
 *       {@link BilibiliException} 后抛 IO 异常）；</li>
 *   <li>{@link #getImg(String)} 抛 {@link IOException}（{@link RenderModelLoader#load}
 *       抛 IO 异常；{@link Java2DImageRenderer#render} 内部已把 IO 包成 {@code RuntimeException}，
 *       不再单独声明）；</li>
 *   <li>{@link #getInfoList(String)} 抛 {@link IOException}。门面层 catch 后转
 *       {@code RuntimeException}（与原 Selenium 路径"逐字一致"）。</li>
 * </ul>
 *
 * <p><b>无状态</b>：本服务不在内部缓存 list/detail/img。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public class DynamicService {

    /** 单例入口，无状态。 */
    public static final DynamicService INSTANCE = new DynamicService();

    /**
     * 取动态详情（{@code x/polymer/web-dynamic/v1/detail?id=}）。
     *
     * <p>响应解析用 {@code com.alibaba.fastjson}；两套 schema（LEGACY / DESKTOP）由
     * {@link DynamicSchemaAdapter#toCard} 在本方法内收敛成冻结模型。
     *
     * @param dynamicId 动态 ID（opus id / dynamic id 均可，新旧格式都支持）
     * @return 不可为 null
     * @throws BilibiliException 参数为空
     * @throws IOException 网络/JSON/业务异常
     */
    public BilibiliDynamicResp.Data.Card getDetail(String dynamicId) throws IOException {
        if (dynamicId == null || dynamicId.isEmpty()) {
            throw new BilibiliException("动态ID不能为空");
        }
        try {
            JSONObject item = requestDynamicItem(dynamicId);
            return DynamicSchemaAdapter.toCard(item);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 取动态长图（{@link RenderModelLoader#load} 拉视图模型 + {@link Java2DImageRenderer} 画）。
     */
    public BufferedImage getImg(String dynamicId) throws IOException {
        if (dynamicId == null || dynamicId.isEmpty()) {
            throw new BilibiliException("动态ID不能为空");
        }
        RenderModel model = RenderModelLoader.load(dynamicId);
        return new Java2DImageRenderer().render(model);
    }

    /**
     * 取指定用户的动态列表（桌面端 feed，{@code x/polymer/web-dynamic/v1/feed/space}）。
     *
     * <p>{@code features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote} 是必填项
     * （详见 {@link BilibiliEndpoint#dynamicFeedUrl}）。
     *
     * <p><b>空列表语义</b>：本方法在结果为空时会<b>换一副匿名身份再取一次</b>（只一次），
     * 因为该端点存在"{@code code=0} 但 {@code items=[]}"的静默风控形态，无法与
     * "该 UP 真没发动态"区分。开关见 {@link HttpPolicy#isRotateOnEmptyFeed()}。
     */
    public List<DynamicInfo> getInfoList(String uid) throws IOException {
        if (uid == null || uid.isEmpty()) {
            throw new BilibiliException("用户UID不能为空");
        }

        List<DynamicInfo> first = fetchFeed(uid);
        if (!first.isEmpty()) {
            return first;
        }

        // —— 空列表兜底：换一副身份再试一次（2026-09-13 新增）——
        // v1/feed/space 有一种失败形态与业务码无关：实测 code=0 但 data.items=[]（静默空），
        // 调用方无法与"该 UP 真没发动态"区分 → 上游 XatiiBot 会据此认为没有新动态（漏推送）。
        // BilibiliHttp 的分类只看业务码，兜不住这一层，所以在这里补一次"换身份重试"：
        //   只重试一次，不循环；是否启用受 HttpPolicy.isRotateOnEmptyFeed() 控制。
        // 注意与"盲重试"的区别：这里是换一副全新指纹再取，不是拿被标记的指纹硬撞。
        if (!HttpPolicy.isRotateOnEmptyFeed()) {
            return first;
        }
        AnonymousSession.Identity rotated = AnonymousSession.rotate();
        log.warn("动态列表为空（uid={}）：可能是『code=0 但 items 为空』的静默风控，"
                + "已轮换匿名身份至第 {} 代并重试一次", uid, rotated.generation());

        List<DynamicInfo> second = fetchFeed(uid);
        if (second.isEmpty()) {
            log.warn("换身份重试后动态列表仍为空（uid={}）—— 该 UP 确实没有动态时属正常；"
                    + "否则说明静默风控持续生效，本次会漏报", uid);
        }
        return second;
    }

    /**
     * 取一次动态列表（单次请求 + 解析），<b>不含</b>任何重试/轮换策略 —— 策略由
     * {@link #getInfoList(String)} 决定。抽出本方法是为了让"空列表换身份重试"能复用同一段取数逻辑。
     *
     * @param uid 用户 UID
     * @return 解析后的动态列表（可能为空，永不为 null）
     */
    private List<DynamicInfo> fetchFeed(String uid) {
        String url = String.format(BilibiliEndpoint.dynamicFeedUrl, uid);
        log.info("正在获取动态列表:{}", url);

        HttpResponse<String> response;
        try {
            response = BilibiliHttp.get(url);
        } catch (Exception e) {
            throw new BilibiliException(e, "获取动态列表失败：请求发送异常：" + e.getMessage());
        }
        if (response == null) {
            throw new BilibiliException("获取动态列表失败：请求无响应");
        }

        // HTTP 层先拦一道（与 getDetail 同一规则）。
        // ★ 不加这一步的后果（2026-09-13 实测踩到）：412 风控返回的是 HTML 页面，
        //   会被下面的 JSON 解析报成"响应不是合法 JSON"，把排障方向带偏；
        //   而真实原因是风控，文案必须点明且不得自动重试。
        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), "获取动态列表");
        if (httpError != null) {
            throw httpError;
        }

        JSONObject body;
        try {
            body = JSON.parseObject(response.getBody());
        } catch (Exception ignored) {
            // ★ 不把异常直接包进 message：fastjson 的 syntax error 会把整段输入拼进它自己的
            //   message，而风控页 HTML 有数 KB —— 实测会把日志刷爆。用 brief() 截到 120 字。
            //   （getDetail 一直是这么做的，getInfoList 漏了，本轮补齐。）
            throw new BilibiliException("获取动态列表失败：响应不是合法 JSON（前 120 字："
                    + brief(response.getBody()) + "）");
        }
        int code = body.getIntValue("code");
        if (code != 0) {
            throw new BilibiliException("获取动态列表失败：B 站 code=" + code
                    + " message=" + body.getString("message"));
        }
        JSONObject data = body.getJSONObject("data");
        if (data == null) {
            return List.of();
        }
        JSONArray items = data.getJSONArray("items");
        if (items == null) {
            return List.of();
        }

        List<DynamicInfo> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                continue;
            }
            DynamicInfo info = parseFeedItem(item);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    // —————————— 以下为服务内私有辅助（迁自门面）——————————

    /**
     * 请求详情接口并取出 {@code data.item}。
     */
    private JSONObject requestDynamicItem(String dynamicId) throws IOException {
        String url = BilibiliEndpoint.dynamicDetailUrl + dynamicId;

        HttpResponse<String> response;
        try {
            response = BilibiliHttp.get(url);
        } catch (Exception e) {
            throw new IOException("获取动态详情失败：请求发送异常：" + e.getMessage(), e);
        }
        if (response == null) {
            throw new IOException("获取动态详情失败：请求无响应");
        }

        // HTTP 层先拦一道（412 风控必须有可读提示，且明确"不可重试"）
        BilibiliException httpError = ErrorMapper.forHttpStatus(response.getStatus(), "获取动态详情");
        if (httpError != null) {
            throw new IOException(httpError.getMessage(), httpError);
        }

        ApiResponse<JSONObject> resp;
        try {
            resp = JSON.parseObject(response.getBody(), new TypeReference<ApiResponse<JSONObject>>() {
            });
        } catch (Exception e) {
            throw new IOException("获取动态详情失败：响应不是合法 JSON（前 120 字："
                    + brief(response.getBody()) + "）", e);
        }

        // code 校验：4101139（参数名错）/ 4101105（id 不存在）等都会在这里被语义化
        JSONObject data = ResponseParserSupport.unwrap(resp, "获取动态详情");

        JSONObject item = data.getJSONObject("item");
        if (item == null) {
            throw new IOException("获取动态详情失败：data 里没有 item");
        }
        return item;
    }

    /** 截断响应体，避免把整页 HTML/JS 塞进异常消息 */
    private static String brief(String body) {
        if (body == null) {
            return "null";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    /**
     * 把桌面端 feed 中的一条 item 映射成 {@link DynamicInfo}。
     *
     * <p>本方法不抛异常：B 站某些 item 类型（如 {@code DYNAMIC_TYPE_LIVE_RCMD} 直播推荐）
     * 在 {@code modules} 里没有 {@code module_dynamic} —— 这种 item 直接返回 {@code null}
     * 让外层 caller 跳过；调用方拿到的是一个"少几条但都对"的列表，
     * <b>不是</b>"为了某一条不合规矩而整列抛掉"。
     */
    private static DynamicInfo parseFeedItem(JSONObject item) {
        DynamicInfo info = new DynamicInfo();
        String type = item.getString("type");

        // modules 两套形态，这里都容错处理（真实响应形态以实测为准）：
        //   形态 A（2026-09-13 实测 desktop feed 的真实形态，12 条样本全部命中）
        //         ：JSONObject，键形如 module_author / module_dynamic / module_tag / module_stat
        //   形态 B（v1/detail 之外的另一套 schema 里出现过）
        //         ：JSONArray，每个元素 { module_type, module_author / module_desc / module_dynamic / ... }
        // 统一抽成 moduleAuthor / moduleDynamic / moduleTag 三个 JSONObject。
        Object modulesRaw = item.get("modules");
        JSONObject moduleAuthor = null;
        JSONObject moduleDynamic = null;
        JSONObject moduleTag = null;
        if (modulesRaw instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (!(o instanceof JSONObject m)) {
                    continue;
                }
                String mt = m.getString("module_type");
                if ("MODULE_TYPE_AUTHOR".equals(mt)) {
                    moduleAuthor = m.getJSONObject("module_author");
                } else if ("MODULE_TYPE_DYNAMIC".equals(mt)) {
                    moduleDynamic = m.getJSONObject("module_dynamic");
                } else if ("MODULE_TYPE_TAG".equals(mt)) {
                    moduleTag = m.getJSONObject("module_tag");
                }
            }
        } else if (modulesRaw instanceof JSONObject obj) {
            moduleAuthor = obj.getJSONObject("module_author");
            moduleDynamic = obj.getJSONObject("module_dynamic");
            moduleTag = obj.getJSONObject("module_tag");
        } else {
            return null;
        }

        String idStr = item.getString("id_str");

        // —— 1. shareDynamicId（仅 FORWARD 类型，从 orig.id_str 取） ——
        if ("DYNAMIC_TYPE_FORWARD".equals(type) && moduleDynamic != null) {
            JSONObject orig = moduleDynamic.getJSONObject("orig");
            if (orig != null) {
                info.setShareDynamicId(orig.getString("id_str"));
            }
        }

        // —— 2. tag（置顶标）：module_tag.text 为主，module_author.is_top 兜底 ——
        String tagText = moduleTag == null ? null : moduleTag.getString("text");
        if (tagText != null && !tagText.isEmpty()) {
            info.setTag(tagText);
        } else if (moduleAuthor != null && Boolean.TRUE.equals(moduleAuthor.getBoolean("is_top"))) {
            info.setTag("置顶");
        }

        // —— 3. dynamicId（非转发才有；FORWARD 时 id_str 留给 shareDynamicId） ——
        if (!"DYNAMIC_TYPE_FORWARD".equals(type) && idStr != null) {
            info.setDynamicId(idStr);
        }

        // —— 4. time（**语义承载字段**，见 REFACTOR_PLAN.md §2.4） ——
        //
        // ⚠️ 字段名以真实响应为准（2026-09-13 实测 dump，12 条样本）：
        //     module_author 里 **没有** pub_text 字段（恒为 null）；
        //     相对时间文案在 **pub_time**（"7小时前" / "昨天 11:00" / "3天前"）；
        //     动作词在 **pub_action**（"投稿了视频" / "发布了动态视频"，纯图文时为空串）。
        //
        // 下游 XatiiBot PushInfoServiceImpl.java:196 判断 getTime().startsWith("刚刚")
        // 来触发推送，因此这里必须给出**与前端同款**的相对时间文案，
        // 且当 pub_action 非空时按前端习惯拼成 "昨天 11:00 · 投稿了视频"。
        // 拼接不影响 startsWith("刚刚")（"刚刚 · 投稿了视频" 同样命中）。
        if (moduleAuthor != null) {
            String pubTime = moduleAuthor.getString("pub_time");
            if (pubTime != null && !pubTime.isEmpty()) {
                String pubAction = moduleAuthor.getString("pub_action");
                String time = (pubAction == null || pubAction.isEmpty())
                        ? pubTime
                        : pubTime + " · " + pubAction;
                info.setTime(time);
            }
        }

        // —— 5. title / desc / imageUrl / bvid（按 major.type 分支 + 桌面端 dyn_* 兜底） ——
        if (moduleDynamic != null) {
            JSONObject major = moduleDynamic.getJSONObject("major");
            String descText = extractDescText(moduleDynamic.getJSONObject("desc"));
            if (descText != null) {
                info.setDesc(descText);
            }

            // 桌面端 feed 的"主体"放在 module_dynamic.dyn_*（不是 major）；LEGACY 走 major。
            // 桌面端字段可能在（feed）或不在（v1/detail 的 item.modules.module_dynamic 里）；
            // 解析顺序：先 dyn_*（桌面端），没有再 major（旧 schema）。

            // —— 5.1 DRAW：图片来自 dyn_draw.items[].src 或 major.draw.items[].src ——
            JSONObject dynDraw = moduleDynamic.getJSONObject("dyn_draw");
            if (dynDraw != null) {
                JSONArray items = dynDraw.getJSONArray("items");
                if (items != null) {
                    List<String> urls = new ArrayList<>();
                    for (int j = 0; j < items.size(); j++) {
                        JSONObject it = items.getJSONObject(j);
                        if (it == null) continue;
                        String src = RenderModelLoader.normalizeUrl(it.getString("src"));
                        if (src != null && !src.isEmpty() && !urls.contains(src)) {
                            urls.add(src);
                        }
                    }
                    info.setImageUrl(urls);
                }
            } else if (major != null && "MAJOR_TYPE_DRAW".equals(major.getString("type"))) {
                JSONObject draw = major.getJSONObject("draw");
                if (draw != null) {
                    info.setImageUrl(extractDrawImages(draw));
                }
            }

            // —— 5.2 ARCHIVE：bvid/title 来自 dyn_archive 或 major.archive ——
            JSONObject dynArchive = moduleDynamic.getJSONObject("dyn_archive");
            if (dynArchive != null) {
                info.setTitle(dynArchive.getString("title"));
                String bvId = dynArchive.getString("bvid");
                if (bvId == null || bvId.isEmpty()) {
                    bvId = dynArchive.getString("bv_id");
                }
                info.setBvid(bvId);
            } else if (major != null && "MAJOR_TYPE_ARCHIVE".equals(major.getString("type"))) {
                JSONObject archive = major.getJSONObject("archive");
                if (archive != null) {
                    info.setTitle(archive.getString("title"));
                    String bvId = archive.getString("bv_id");
                    if (bvId == null || bvId.isEmpty()) {
                        bvId = archive.getString("bvid");
                    }
                    info.setBvid(bvId);
                }
            }

            // —— 5.3 OPUS：图片来自 dyn_opus.pics[].url 或 major.opus.pics[].url ——
            if (dynArchive == null && dynDraw == null) {
                JSONObject dynOpus = moduleDynamic.getJSONObject("dyn_opus");
                if (dynOpus != null) {
                    info.setTitle(dynOpus.getString("title"));
                    info.setImageUrl(extractOpusPics(dynOpus));
                } else if (major != null && "MAJOR_TYPE_OPUS".equals(major.getString("type"))) {
                    JSONObject opus = major.getJSONObject("opus");
                    if (opus != null) {
                        info.setTitle(opus.getString("title"));
                        info.setImageUrl(extractOpusPics(opus));
                    }
                }
            }

            // —— 5.4 ARTICLE ——
            if (major != null && "MAJOR_TYPE_ARTICLE".equals(major.getString("type"))) {
                JSONObject article = major.getJSONObject("article");
                if (article != null) {
                    info.setTitle(article.getString("title"));
                    info.setImageUrl(extractArticleCovers(article));
                }
            }

            // —— 5.5 LIVE_RCMD ——
            if (major != null && "MAJOR_TYPE_LIVE_RCMD".equals(major.getString("type"))) {
                JSONObject live = major.getJSONObject("live_rcmd");
                if (live != null) {
                    JSONObject content = live.getJSONObject("content");
                    if (content != null) {
                        info.setTitle(content.getString("title"));
                    }
                }
            }
        }

        return info;
    }

    /** desc.text 容错提取（缺 desc 节点时返回 null） */
    private static String extractDescText(JSONObject desc) {
        if (desc == null) {
            return null;
        }
        String t = desc.getString("text");
        if (t == null || t.isEmpty()) {
            // 兜底：把 rich_text_nodes 里所有 text 拼起来（旧版浏览器路径会做这事，
            // 这里只为"万一 desc.text 缺"场景兜底，主流 case 不会进）
            JSONArray nodes = desc.getJSONArray("rich_text_nodes");
            if (nodes != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nodes.size(); i++) {
                    JSONObject n = nodes.getJSONObject(i);
                    if (n == null) continue;
                    String tt = n.getString("text");
                    if (tt != null && !tt.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(tt);
                    }
                }
                if (sb.length() > 0) t = sb.toString();
            }
        }
        return t;
    }

    /** DRAW 类型 major.draw.items[].src → 去重+https 规范化 */
    private static List<String> extractDrawImages(JSONObject draw) {
        List<String> out = new ArrayList<>();
        JSONArray items = draw.getJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (it == null) continue;
            String src = RenderModelLoader.normalizeUrl(it.getString("src"));
            if (src != null && !src.isEmpty() && !out.contains(src)) {
                out.add(src);
            }
        }
        return out;
    }

    /** OPUS 类型 major.opus.pics[].url */
    private static List<String> extractOpusPics(JSONObject opus) {
        List<String> out = new ArrayList<>();
        JSONArray pics = opus.getJSONArray("pics");
        if (pics == null) return out;
        for (int i = 0; i < pics.size(); i++) {
            JSONObject p = pics.getJSONObject(i);
            if (p == null) continue;
            String url = RenderModelLoader.normalizeUrl(p.getString("url"));
            if (url != null && !url.isEmpty() && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    /** ARTICLE 类型 major.article.covers[] */
    private static List<String> extractArticleCovers(JSONObject article) {
        List<String> out = new ArrayList<>();
        JSONArray covers = article.getJSONArray("covers");
        if (covers == null) return out;
        for (int i = 0; i < covers.size(); i++) {
            String c = covers.getString(i);
            String url = RenderModelLoader.normalizeUrl(c);
            if (url != null && !url.isEmpty() && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    /** 强制 Dynamic 类型的 import 被 IDE/编译器接受（防止 "unused import"）。 */
    @SuppressWarnings("unused")
    private static Class<?> forceDynamicImport() {
        return Dynamic.class;
    }

    private DynamicService() {}
}

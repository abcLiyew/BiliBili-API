package com.esdllm.bilibiliApi.bilibiliApi;



import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.adapter.DynamicSchemaAdapter;
import com.esdllm.bilibiliApi.config.BilibiliConfig;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ErrorMapper;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import com.esdllm.bilibiliApi.render.Java2DImageRenderer;
import com.esdllm.bilibiliApi.render.RenderModel;
import com.esdllm.bilibiliApi.render.RenderModelLoader;
import kong.unirest.HttpResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
         * 标签，只有置顶动态有值，并且值为“置顶"
         */
        private String tag;
        /**
         * 发布时间+动作，如“04月20日 · 发布了动态视频”，“04月20日 · 投稿了视频”，如果是直播动态则值为“直播了”
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
    }

    /**
     * 获取动态详情。
     *
     * <p>端点：{@code x/polymer/web-dynamic/v1/detail?id={dynamicId}}（实测匿名可用）。
     * 旧端点所在的 {@code api.vc.bilibili.com/dynamic_svr} 已整站下线（HTTP 404），
     * 换源前本方法在真实调用下必然失败。
     *
     * <p><b>异常约定</b>：所有失败在门面边界统一转成签名里声明的 {@link IOException}。
     * 原因是下游 XatiiBot 的两个调用点（{@code BilibiliAnalysisImpl.java:49}、
     * {@code ShortChain.getDynamicCard()}）都<b>只有 {@code catch (IOException)}</b> ——
     * 若抛 RuntimeException（含 {@code BilibiliException}），异常会直接穿透出去把消息处理器打挂，
     * 连日志都留不下。库内部仍然统一用 {@code BilibiliException}，只在这一层做转换。
     *
     * @param dynamicId 动态ID（opus id / dynamic id 均可，新旧格式都支持）
     * @return 动态卡片详情
     * @throws IOException IO异常，或取数失败（消息里含 B 站 code 与语义化说明）
     */
    public BilibiliDynamicResp.Data.Card getDynamicDetail(String dynamicId) throws IOException {
        if (dynamicId == null || dynamicId.isEmpty()) {
            // 参数校验属于调用方编程错误，保持原有的 BilibiliException（不受 IOException 影响）
            throw new BilibiliException("动态ID不能为空");
        }
        try {
            JSONObject item = requestDynamicItem(dynamicId);
            // 两套 schema（LEGACY / DESKTOP）在这里收敛成冻结模型
            return DynamicSchemaAdapter.toCard(item);
        } catch (BilibiliException e) {
            // 门面边界：库内统一的 BilibiliException → 契约声明的 IOException
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * 请求详情接口并取出 {@code data.item}。
     *
     * @param dynamicId 动态ID
     * @return 响应里的 item 节点
     * @throws IOException 网络层失败
     */
    private JSONObject requestDynamicItem(String dynamicId) throws IOException {
        String url = BilibiliConfig.dynamicDetailUrl + dynamicId;

        HttpResponse<String> response;
        try {
            response = ApiBase.getCloseableHttpResponse(url);
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
     * 获取动态长图（Java2D 自绘，无浏览器依赖）。
     *
     * <p><b>渲染路径</b>：
     * <ol>
     *   <li>{@link RenderModelLoader#load(String)} 取
     *       {@code x/polymer/web-dynamic/v1/opus/detail?id=} 的 opus schema → {@link RenderModel}。
     *       选 opus 而不是 {@code v1/detail} 的硬原因是后者对图文类动态 {@code desc} 为 null
     *       且丢失图文顺序（详见 {@code RenderModelLoader} 类注释）。</li>
     *   <li>{@link Java2DImageRenderer#render(RenderModel)} 把视图模型画成 {@link BufferedImage}。
     *       字体来自打包的 Noto Sans SC 子集，headless 环境也能画，
     *       不依赖系统字体、不依赖 ChromeDriver。</li>
     * </ol>
     *
     * <p><b>签名兼容性</b>：签名仍是 {@code throws InterruptedException}（与本方法旧实现
     * 完全一致；Java 允许声明一个从未实际抛出的受检异常，这是合法的"占位声明"），
     * 渲染过程中抛出的 {@link IOException} 在边界处包成 {@link RuntimeException}，与
     * 本方法旧实现的失败语义一致。{@code XatiiBot} 侧不需要任何改动。
     *
     * <p><b>覆盖范围</b>：同 {@link RenderModelLoader} —— 只覆盖图文/opus 类动态；
     * 视频动态与转发动态在 opus 端点返回空 {@code modules}，本方法在那一层就抛
     * {@link IOException}，调用方按"不可渲染"语义处理。
     *
     * @param dynamicId 动态 ID（opus id / dynamic id 均可，新旧格式都支持）
     * @return 动态长图
     */
    public BufferedImage getDynamicImg(String dynamicId) throws InterruptedException {
        if (dynamicId == null || dynamicId.isEmpty()) {
            // 编程错误，沿用 §"门面边界异常规则"——参数空直接抛 BilibiliException
            throw new BilibiliException("动态ID不能为空");
        }
        try {
            RenderModel model = RenderModelLoader.load(dynamicId);
            return new Java2DImageRenderer().render(model);
        } catch (IOException e) {
            // 失败的语义与本方法旧实现（Selenium 路线）保持一致：
            // 旧实现也是 catch-all 包成 RuntimeException。XatiiBot 拿到的是"渲染失败"提示，
            // 没有 catch 该异常的具体代码（确认过），不会因包名/类型变化而误判。
            throw new RuntimeException("动态图片渲染失败：" + e.getMessage(), e);
        }
    }

    /**
     * 获取指定用户的空间动态列表。
     *
     * <p><b>实现路径</b>：桌面端动态 feed 接口
     * {@code https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?host_mid=&features=...}
     * —— 直接通过 {@link com.esdllm.bilibiliApi.http.BilibiliHttp#get} 拉 JSON 后逐项映射。
     *
     * <p><b>{@code features} 不能漏</b>：{@code features=itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote}
     * 是必须的，缺了这一项响应仍 200，但 {@code data.items} 永远是空数组（移动端走的是另一套，
     * 不带 {@code features} 也能拿到东西）。这条常量写在 {@code BilibiliConfig.dynamicFeedUrl} 里。
     *
     * <p><b>字段映射（保持对 XatiiBot 兼容）</b>：
     * <ul>
     *   <li>{@code shareDynamicId} ← {@code item.modules.module_dynamic.orig.id_str}（仅 DYNAMIC_TYPE_FORWARD）</li>
     *   <li>{@code tag} ← {@code item.modules.module_tag.text}（置顶标"置顶"）</li>
     *   <li>{@code dynamicId} ← {@code item.id_str}（非转发）</li>
     *   <li>{@code time} ← {@code item.modules.module_author.pub_text}（如"4小时前"）</li>
     *   <li>{@code title} ← {@code item.modules.module_dynamic.major.{archive,opus,article}.title}（按类型取一个）</li>
     *   <li>{@code desc} ← {@code item.modules.module_dynamic.desc.text}</li>
     *   <li>{@code imageUrl} ← {@code item.modules.module_dynamic.major.draw.items[].src}
     *       或 {@code major.opus.pics[].url} 或 {@code major.article.covers[]}（视频封面单独进 {@code bvid}，
     *       不混入图片列表）</li>
     *   <li>{@code bvid} ← {@code major.archive.bvid}（如 {@code BV1xVY26dEbz}）</li>
     * </ul>
     *
     * <p><b>签名兼容性</b>：保留 {@code throws InterruptedException} 合法占位声明（同
     * {@link #getDynamicImg(String)}），{@code XatiiBot} 零改动。
     *
     * <p><b>URL 规范化</b>：{@link com.esdllm.bilibiliApi.render.RenderModelLoader#normalizeUrl}
     * 同款逻辑（{@code //} → {@code https://}、{@code http://} → {@code https://}）；不在本类另写一份。
     *
     * @param uid 用户 UID
     * @return 动态列表
     */
    public List<DynamicInfo> getDynamicInfoList(String uid) throws InterruptedException {
        List<DynamicInfo> result = new ArrayList<>();
        if (uid == null || uid.isEmpty()) {
            // 编程错误，沿用 §"门面边界异常规则"
            throw new BilibiliException("用户UID不能为空");
        }

        String url = String.format(BilibiliConfig.dynamicFeedUrl, uid);
        log.info("正在获取动态列表:{}", url);

        kong.unirest.HttpResponse<String> response;
        try {
            response = com.esdllm.bilibiliApi.http.BilibiliHttp.get(url);
        } catch (Exception e) {
            throw new RuntimeException("获取动态列表失败：请求发送异常：" + e.getMessage(), e);
        }
        if (response == null) {
            throw new RuntimeException("获取动态列表失败：请求无响应");
        }

        com.alibaba.fastjson.JSONObject body;
        try {
            body = com.alibaba.fastjson.JSON.parseObject(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("获取动态列表失败：响应不是合法 JSON", e);
        }
        int code = body.getIntValue("code");
        if (code != 0) {
            throw new RuntimeException("获取动态列表失败：B 站 code=" + code
                    + " message=" + body.getString("message"));
        }
        com.alibaba.fastjson.JSONObject data = body.getJSONObject("data");
        if (data == null) {
            return result;
        }
        com.alibaba.fastjson.JSONArray items = data.getJSONArray("items");
        if (items == null) {
            return result;
        }

        for (int i = 0; i < items.size(); i++) {
            com.alibaba.fastjson.JSONObject item = items.getJSONObject(i);
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

    /**
     * 把桌面端 feed 中的一条 item 映射成 {@link DynamicInfo}。
     *
     * <p>本方法不抛异常：B 站某些 item 类型（如 {@code DYNAMIC_TYPE_LIVE_RCMD} 直播推荐）
     * 在 {@code modules} 里没有 {@code module_dynamic} —— 这种 item 直接返回 {@code null}
     * 让外层 caller 跳过；调用方拿到的是一个"少几条但都对"的列表，
     * <b>不是</b>"为了某一条不合规矩而整列抛掉"。
     *
     * @param item feed items[i]
     * @return 视图模型（null = 跳过此项）
     */
    private static DynamicInfo parseFeedItem(com.alibaba.fastjson.JSONObject item) {
        DynamicInfo info = new DynamicInfo();
        String type = item.getString("type");

        com.alibaba.fastjson.JSONObject modules = item.getJSONObject("modules");
        if (modules == null) {
            return null;
        }
        com.alibaba.fastjson.JSONObject moduleDynamic = modules.getJSONObject("module_dynamic");

        String idStr = item.getString("id_str");

        // —— 1. shareDynamicId（仅 FORWARD 类型，从 orig.id_str 取） ——
        if ("DYNAMIC_TYPE_FORWARD".equals(type) && moduleDynamic != null) {
            com.alibaba.fastjson.JSONObject orig = moduleDynamic.getJSONObject("orig");
            if (orig != null) {
                info.setShareDynamicId(orig.getString("id_str"));
            }
        }

        // —— 2. tag（置顶标） ——
        com.alibaba.fastjson.JSONObject moduleTag = modules.getJSONObject("module_tag");
        if (moduleTag != null) {
            String tagText = moduleTag.getString("text");
            if (tagText != null && !tagText.isEmpty()) {
                info.setTag(tagText);
            }
        }

        // —— 3. dynamicId（非转发才有；FORWARD 时 id_str 留给 shareDynamicId） ——
        if (!"DYNAMIC_TYPE_FORWARD".equals(type) && idStr != null) {
            info.setDynamicId(idStr);
        }

        // —— 4. time（pub_text 原样保留，前端同款文案："3小时前" / "昨天 11:00 · 投稿了视频"） ——
        com.alibaba.fastjson.JSONObject moduleAuthor = modules.getJSONObject("module_author");
        if (moduleAuthor != null) {
            String pubText = moduleAuthor.getString("pub_text");
            if (pubText != null) {
                info.setTime(pubText);
            }
        }

        // —— 5. title / desc / imageUrl / bvid（按 major.type 分支） ——
        if (moduleDynamic != null) {
            com.alibaba.fastjson.JSONObject major = moduleDynamic.getJSONObject("major");
            String descText = extractDescText(moduleDynamic.getJSONObject("desc"));
            if (descText != null) {
                info.setDesc(descText);
            }
            if (major != null) {
                String majorType = major.getString("type");
                if ("MAJOR_TYPE_DRAW".equals(majorType)) {
                    com.alibaba.fastjson.JSONObject draw = major.getJSONObject("draw");
                    if (draw != null) {
                        info.setImageUrl(extractDrawImages(draw));
                    }
                } else if ("MAJOR_TYPE_ARCHIVE".equals(majorType)) {
                    com.alibaba.fastjson.JSONObject archive = major.getJSONObject("archive");
                    if (archive != null) {
                        info.setTitle(archive.getString("title"));
                        String bvId = archive.getString("bv_id");
                        if (bvId == null || bvId.isEmpty()) {
                            bvId = archive.getString("bvid");
                        }
                        info.setBvid(bvId);
                    }
                } else if ("MAJOR_TYPE_OPUS".equals(majorType)) {
                    com.alibaba.fastjson.JSONObject opus = major.getJSONObject("opus");
                    if (opus != null) {
                        info.setTitle(opus.getString("title"));
                        info.setImageUrl(extractOpusPics(opus));
                    }
                } else if ("MAJOR_TYPE_ARTICLE".equals(majorType)) {
                    com.alibaba.fastjson.JSONObject article = major.getJSONObject("article");
                    if (article != null) {
                        info.setTitle(article.getString("title"));
                        info.setImageUrl(extractArticleCovers(article));
                    }
                } else if ("MAJOR_TYPE_LIVE_RCMD".equals(majorType)) {
                    // 直播推荐：title 从 live_rcmd.content.title（保留旧行为兼容）
                    com.alibaba.fastjson.JSONObject live = major.getJSONObject("live_rcmd");
                    if (live != null) {
                        com.alibaba.fastjson.JSONObject content = live.getJSONObject("content");
                        if (content != null) {
                            info.setTitle(content.getString("title"));
                        }
                    }
                }
                // MAJOR_TYPE_NONE：无 major 主体，title 留空；imageUrl 留空
            }
        }

        return info;
    }

    /** desc.text 容错提取（缺 desc 节点时返回 null） */
    private static String extractDescText(com.alibaba.fastjson.JSONObject desc) {
        if (desc == null) {
            return null;
        }
        String t = desc.getString("text");
        if (t == null || t.isEmpty()) {
            // 兜底：把 rich_text_nodes 里所有 text 拼起来（旧版浏览器路径会做这事，
            // 这里只为"万一 desc.text 缺"场景兜底，主流 case 不会进）
            com.alibaba.fastjson.JSONArray nodes = desc.getJSONArray("rich_text_nodes");
            if (nodes != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nodes.size(); i++) {
                    com.alibaba.fastjson.JSONObject n = nodes.getJSONObject(i);
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
    private static List<String> extractDrawImages(com.alibaba.fastjson.JSONObject draw) {
        List<String> out = new ArrayList<>();
        com.alibaba.fastjson.JSONArray items = draw.getJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            com.alibaba.fastjson.JSONObject it = items.getJSONObject(i);
            if (it == null) continue;
            String src = com.esdllm.bilibiliApi.render.RenderModelLoader.normalizeUrl(it.getString("src"));
            if (src != null && !src.isEmpty() && !out.contains(src)) {
                out.add(src);
            }
        }
        return out;
    }

    /** OPUS 类型 major.opus.pics[].url */
    private static List<String> extractOpusPics(com.alibaba.fastjson.JSONObject opus) {
        List<String> out = new ArrayList<>();
        com.alibaba.fastjson.JSONArray pics = opus.getJSONArray("pics");
        if (pics == null) return out;
        for (int i = 0; i < pics.size(); i++) {
            com.alibaba.fastjson.JSONObject p = pics.getJSONObject(i);
            if (p == null) continue;
            String url = com.esdllm.bilibiliApi.render.RenderModelLoader.normalizeUrl(p.getString("url"));
            if (url != null && !url.isEmpty() && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    /** ARTICLE 类型 major.article.covers[] */
    private static List<String> extractArticleCovers(com.alibaba.fastjson.JSONObject article) {
        List<String> out = new ArrayList<>();
        com.alibaba.fastjson.JSONArray covers = article.getJSONArray("covers");
        if (covers == null) return out;
        for (int i = 0; i < covers.size(); i++) {
            String c = covers.getString(i);
            String url = com.esdllm.bilibiliApi.render.RenderModelLoader.normalizeUrl(c);
            if (url != null && !url.isEmpty() && !out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }
}

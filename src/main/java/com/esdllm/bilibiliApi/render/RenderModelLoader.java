package com.esdllm.bilibiliApi.render;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import com.esdllm.bilibiliApi.parse.ApiResponse;
import com.esdllm.bilibiliApi.parse.ResponseParserSupport;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 B 站的动态响应转换成 {@link RenderModel}。
 *
 * <p><b>端点策略（按优先级）</b>：
 * <ol>
 *   <li>{@code x/polymer/web-dynamic/v1/opus/detail?id=} —— 主路径，返回 OPUS schema，
 *       对图文类动态天然友好（自带段落顺序 + emoji 贴图）。</li>
 *   <li>若 opus 回空 {@code modules}，自动回落到
 *       {@code x/polymer/web-dynamic/v1/detail?id=} —— 该端点对图文的 {@code desc} 是 null，
 *       但<b>对视频/转发动态才是唯一可解析的源</b>。从
 *       {@code modules.module_dynamic.major.{archive,opus,article}} 抽提必要字段。</li>
 * </ol>
 * 因此对<b>任意类型</b>（DRAW / VIDEO / FORWARD / ARTICLE / LIVE）的动态
 * {@link #load(String)} 都能产出可用模型。
 *
 * <p><b>为什么渲染优先 opus 而不是 {@code v1/detail}</b>：实测对比后有三个硬原因——
 * <ol>
 *   <li>{@code v1/detail?id=} 对"图文 + 多图"类动态返回的 {@code module_dynamic.desc} 是
 *       {@code null}，<b>整条响应的 JSON 里搜不到一个正文字符</b>（实测 id
 *       {@code 1247440317376888835}，正文 258 字，detail 响应里全文检索无命中）；</li>
 *   <li>detail 把正文与图片放在两个互相独立的 module 里，<b>丢失了它们的先后顺序</b>，
 *       而长图必须按原文顺序排版；</li>
 *   <li>{@code v1/opus/detail?id=} 直接给出 {@code MODULE_TYPE_CONTENT.module_content.paragraphs}，
 *       段落自带 {@code para_type}（1=文本 2=图片）且<b>顺序即原文顺序</b>，还带 emoji 贴图地址。</li>
 * </ol>
 *
 * <p><b>覆盖范围</b>：DRAW / VIDEO / FORWARD / ARTICLE / LIVE 都能产模型，
 * 但前三种是常态化兼容，后两种以"封面 + 标题 + 互动数"兜底渲染。
 */
@Slf4j
public final class RenderModelLoader {

    /** 段落类型：文本 */
    private static final int PARA_TYPE_TEXT = 1;
    /** 段落类型：图片 */
    private static final int PARA_TYPE_PIC = 2;

    /** unicode emoji 贴图 CDN（Twemoji，CC-BY 4.0） */
    private static final String TWEMOJI_BASE =
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@15.0.3/assets/72x72/";

    private RenderModelLoader() {
    }

    /**
     * 拉取并解析一条动态，产出可直接渲染的视图模型。
     *
     * <p>主路径走 opus，回退走 {@code v1/detail}（详见类注释）。
     *
     * @param dynamicId 动态 ID（新旧格式均可）
     * @return 视图模型
     * @throws IOException 网络失败或响应不可用
     */
    public static RenderModel load(String dynamicId) throws IOException {
        if (dynamicId == null || dynamicId.isEmpty()) {
            throw new IOException("动态ID不能为空");
        }

        // —— 主路径：opus（图文最佳） ——
        try {
            JSONObject opusItem = fetchItem(BilibiliEndpoint.opusDetailUrl + dynamicId, "获取动态内容");
            RenderModel model = parse(opusItem);
            if (!model.getBlocks().isEmpty()) {
                return model;
            }
            // modules 拿不到（视频/转发类此端点常返空），但 item.type 可能标了 VIDEO/FORWARD/etc。
            // 只要 type 不是 DRAW，就直接走 legacy；DRAW 但 blocks 空是极少见的退化场景，仍尝试 legacy。
            log.debug("opus 端点 modules 空（type={}），回落到 v1/detail：id={}",
                    model.getType(), dynamicId);
        } catch (IOException e) {
            // opus 端点对非 opus 类型的 ID 会返 code=4101105 / item 缺失 / JSON 异常；
            // 这都不是"真实错误"——视频/转发用 opus 端点本来就是错的。我们都视为"走 fallback"信号。
            // 真正致命错误（code=-352 风控 / 真·网络断）也会再次发生，legacy 也会同样抛。
            log.debug("opus 端点不可用，回落到 v1/detail：id={}（{}）",
                    dynamicId, e.getMessage());
        }

        // —— 回退：v1/detail（视频/转发唯一可解析源；图文也走这个兜底）——
        JSONObject detailItem = fetchItem(BilibiliEndpoint.dynamicDetailUrl + dynamicId, "获取动态详情");
        RenderModel fallback = parseLegacy(detailItem);
        if (fallback.getBlocks().isEmpty() && fallback.getType() == RenderModel.Type.UNKNOWN) {
            throw new IOException("opus 与 v1/detail 都拿不到可渲染内容：id=" + dynamicId);
        }
        return fallback;
    }

    /**
     * 从 URL 拉一次响应，校验 code==0 后返回 {@code data.item} JSON。
     *
     * @param url 接口完整 URL
     * @param action 错误信息前缀
     * @return 响应里的 item 节点
     * @throws IOException 网络失败 / code!=0 / data.item 缺失
     */
    private static JSONObject fetchItem(String url, String action) throws IOException {
        HttpResponse<String> response;
        try {
            // 必须走 BilibiliHttp（自动带匿名 buvid3）——API 直连不带设备指纹会被判 -352 风控
            response = BilibiliHttp.get(url);
        } catch (Exception e) {
            throw new IOException(action + "失败：请求发送异常：" + e.getMessage(), e);
        }
        if (response == null) {
            throw new IOException(action + "失败：请求无响应");
        }

        ApiResponse<JSONObject> resp;
        try {
            resp = JSON.parseObject(response.getBody(), new TypeReference<ApiResponse<JSONObject>>() {
            });
        } catch (Exception e) {
            throw new IOException(action + "失败：响应不是合法 JSON", e);
        }

        JSONObject data;
        try {
            // 门面边界规则：库内统一 BilibiliException，出口转成签名声明的 IOException
            data = ResponseParserSupport.unwrap(resp, action);
        } catch (BilibiliException e) {
            throw new IOException(e.getMessage(), e);
        }
        JSONObject item = data.getJSONObject("item");
        if (item == null) {
            throw new IOException(action + "失败：data 里没有 item");
        }
        return item;
    }

    /**
     * 解析 {@code data.item} 节点（OPUS schema）。legacy schema 走 {@link #parseLegacy}。
     *
     * @param item OPUS schema 的 item
     * @return 视图模型
     */
    static RenderModel parse(JSONObject item) {
        RenderModel model = new RenderModel();
        model.setDynamicId(item.getString("id_str"));
        model.setType(mapType(item.getString("type")));
        // OPUS 端点拿到的动态形态基本都是 DRAW —— 但若 item.type 是 AV/FORWARD 等（实测罕见），
        // 我们仍如实标记，renderer 看到 type != DRAW 会自动切到 video 分支。

        JSONArray modules = item.getJSONArray("modules");
        if (modules == null) {
            return model;
        }

        JSONArray topAlbumPics = null;
        for (int i = 0; i < modules.size(); i++) {
            JSONObject module = modules.getJSONObject(i);
            String type = module.getString("module_type");
            if (type == null) {
                continue;
            }
            switch (type) {
                case "MODULE_TYPE_AUTHOR":
                    parseAuthor(module.getJSONObject("module_author"), model);
                    break;
                case "MODULE_TYPE_TOP":
                    model.setTop(true);
                    topAlbumPics = topAlbumPics(module);
                    break;
                case "MODULE_TYPE_CONTENT":
                    parseContent(module.getJSONObject("module_content"), model);
                    break;
                case "MODULE_TYPE_STAT":
                    parseStat(module.getJSONObject("module_stat"), model);
                    break;
                default:
                    break;
            }
        }

        // 置顶动态的图片可能只出现在 MODULE_TYPE_TOP 的 album 里（实测陈睿/老番茄的动态即如此）
        if (topAlbumPics != null && !topAlbumPics.isEmpty() && !hasImageBlock(model)) {
            RenderModel.ImageBlock block = new RenderModel.ImageBlock();
            block.setPics(toPics(topAlbumPics));
            model.getBlocks().add(block);
        }
        return model;
    }

    /**
     * 把 OPUS 端点拿到的"图文明明非空但模块不全"或"空 modules"的 item 改造一下，
     * <b>或</b>处理 v1/detail 端点的 LEGACY schema —— 由调用方挑一个入口。
     *
     * <p>v1/detail 的 item.modules 是 {@code JSONObject}（key 是 {@code module_*}），而且
     * 对 VIDEO/FORWARD 类型能给出 {@code module_dynamic.major.{archive,opus,article}} 等主体。
     * 本方法按 {@link RenderModel.Type} 分支填充 {@link RenderModel#getBlocks()}：
     * <ul>
     *   <li>{@link RenderModel.Type#DRAW}：和 {@link #parse} 路径一致（兜底）</li>
     *   <li>{@link RenderModel.Type#VIDEO}：1 张封面图 + 标题/BV号文本 + 描述</li>
     *   <li>{@link RenderModel.Type#FORWARD}：转发原文的小摘要（标题/desc）+ 引用块</li>
     *   <li>{@link RenderModel.Type#ARTICLE} / {@link RenderModel.Type#LIVE}：标题 + 封面</li>
     * </ul>
     */
    static RenderModel parseLegacy(JSONObject item) {
        RenderModel model = new RenderModel();
        model.setDynamicId(item.getString("id_str"));
        model.setType(mapType(item.getString("type")));

        JSONObject modules = item.getJSONObject("modules");
        if (modules == null) {
            return model;
        }
        parseAuthor(modules.getJSONObject("module_author"), model);
        parseStat(modules.getJSONObject("module_stat"), model);

        JSONObject moduleDynamic = modules.getJSONObject("module_dynamic");
        if (moduleDynamic == null) {
            return model;
        }

        // 标题 + 描述 + 互动数（desc.text 可能为 null，转发类型下也允许空）
        String descText = extractLegacyDescText(moduleDynamic.getJSONObject("desc"));
        JSONObject major = moduleDynamic.getJSONObject("major");
        String majorType = major == null ? null : major.getString("type");

        switch (model.getType()) {
            case VIDEO:
                appendVideoBlocks(model, major, majorType, descText);
                break;
            case FORWARD:
                appendForwardBlocks(model, moduleDynamic, descText);
                break;
            case ARTICLE:
                appendArticleBlocks(model, major, descText);
                break;
            case LIVE:
                appendLiveBlocks(model, major, descText);
                break;
            case DRAW:
                // LEGACY schema 的 DRAW 没 desc —— 已是退化场景，至少把 major.draw.items 抓回来当图
                appendDrawFallbackBlocks(model, major, descText);
                break;
            default:
                // UNKNOWN：什么都不做，blocks 空，load() 会再抛
                break;
        }
        return model;
    }

    /** 把 B 站 dynamic.type 字面量映射到 {@link RenderModel.Type} */
    static RenderModel.Type mapType(String type) {
        if (type == null) {
            return RenderModel.Type.UNKNOWN;
        }
        switch (type) {
            case "DYNAMIC_TYPE_DRAW":
                return RenderModel.Type.DRAW;
            case "DYNAMIC_TYPE_AV":
                return RenderModel.Type.VIDEO;
            case "DYNAMIC_TYPE_FORWARD":
                return RenderModel.Type.FORWARD;
            case "DYNAMIC_TYPE_ARTICLE":
                return RenderModel.Type.ARTICLE;
            case "DYNAMIC_TYPE_LIVE_RCMD":
            case "DYNAMIC_TYPE_LIVE":
                return RenderModel.Type.LIVE;
            default:
                return RenderModel.Type.UNKNOWN;
        }
    }

    /** LEGACY schema 的 desc 是对象，text + rich_text_nodes；这里降级到只取 text */
    private static String extractLegacyDescText(JSONObject desc) {
        if (desc == null) {
            return null;
        }
        String t = desc.getString("text");
        return (t == null || t.isEmpty()) ? null : t;
    }

    private static void appendVideoBlocks(RenderModel model, JSONObject major, String majorType, String descText) {
        if (!"MAJOR_TYPE_ARCHIVE".equals(majorType) || major == null) {
            return;
        }
        JSONObject archive = major.getJSONObject("archive");
        if (archive == null) {
            return;
        }
        // 封面图（一张）
        String cover = archive.getString("cover");
        if (cover != null && !cover.isEmpty()) {
            RenderModel.ImageBlock image = new RenderModel.ImageBlock();
            RenderModel.Pic pic = new RenderModel.Pic();
            pic.setUrl(normalizeUrl(cover));
            pic.setWidth(archive.getIntValue("width"));
            pic.setHeight(archive.getIntValue("height"));
            image.getPics().add(pic);
            model.getBlocks().add(image);
        }
        // 标题 + BV 号 + 时长（合并到一段文本里）
        String title = archive.getString("title");
        String bv = nonNull(archive.getString("bvid"));
        String duration = nonNull(archive.getString("duration_text"));
        StringBuilder header = new StringBuilder();
        if (title != null) {
            header.append(title);
        }
        if (bv != null || duration != null) {
            header.append('\n');
            if (bv != null) {
                header.append(bv);
            }
            if (duration != null) {
                if (bv != null) header.append(" · ");
                header.append(duration);
            }
        }
        if (header.length() > 0) {
            RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
            textBlock.getSpans().add(textSpan(header.toString()));
            model.getBlocks().add(textBlock);
        }
        if (descText != null && !descText.isEmpty()) {
            RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
            textBlock.getSpans().add(textSpan(descText));
            model.getBlocks().add(textBlock);
        }
    }

    private static void appendForwardBlocks(RenderModel model, JSONObject moduleDynamic, String descText) {
        // —— 转发者自己的描述 ——
        if (descText != null && !descText.isEmpty()) {
            RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
            textBlock.getSpans().add(textSpan("转发：" + descText));
            model.getBlocks().add(textBlock);
        }
        // —— 原动态的小摘要（不展开做长图渲染，只展示一两条关键信息）——
        JSONObject orig = moduleDynamic.getJSONObject("orig");
        if (orig != null) {
            RenderModel.Type origType = mapType(orig.getString("type"));
            String origSummary = summarizeOrig(orig, origType);
            if (origSummary != null && !origSummary.isEmpty()) {
                RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
                textBlock.getSpans().add(textSpan("「" + origSummary + "」"));
                model.getBlocks().add(textBlock);
            }
        }
    }

    private static String summarizeOrig(JSONObject orig, RenderModel.Type origType) {
        JSONObject modules = orig.getJSONObject("modules");
        if (modules == null) {
            return null;
        }
        switch (origType) {
            case VIDEO: {
                JSONObject major = modules.getJSONObject("module_dynamic");
                if (major == null) return null;
                JSONObject mj = major.getJSONObject("major");
                if (mj == null) return null;
                JSONObject archive = mj.getJSONObject("archive");
                if (archive == null) return null;
                String title = archive.getString("title");
                String bv = archive.getString("bvid");
                String dur = archive.getString("duration_text");
                StringBuilder sb = new StringBuilder();
                if (title != null) sb.append(title);
                if (bv != null || dur != null) {
                    sb.append(" (");
                    if (bv != null) sb.append(bv);
                    if (bv != null && dur != null) sb.append(" · ");
                    if (dur != null) sb.append(dur);
                    sb.append(')');
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
            case DRAW:
            case ARTICLE:
            case LIVE:
            case UNKNOWN:
            default: {
                JSONObject md = modules.getJSONObject("module_dynamic");
                if (md == null) return null;
                JSONObject desc = md.getJSONObject("desc");
                if (desc == null) return null;
                return desc.getString("text");
            }
        }
    }

    private static void appendArticleBlocks(RenderModel model, JSONObject major, String descText) {
        if (major == null) return;
        JSONObject article = major.getJSONObject("article");
        if (article == null) return;
        // 封面（最多一张）
        String cover = article.getString("cover");
        if (cover == null) {
            // 备选：article.covers[0]
            com.alibaba.fastjson.JSONArray covers = article.getJSONArray("covers");
            if (covers != null && !covers.isEmpty()) {
                cover = covers.getString(0);
            }
        }
        if (cover != null && !cover.isEmpty()) {
            RenderModel.ImageBlock image = new RenderModel.ImageBlock();
            RenderModel.Pic pic = new RenderModel.Pic();
            pic.setUrl(normalizeUrl(cover));
            image.getPics().add(pic);
            model.getBlocks().add(image);
        }
        String title = article.getString("title");
        if (title != null && !title.isEmpty()) {
            RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
            textBlock.getSpans().add(textSpan(title));
            model.getBlocks().add(textBlock);
        }
        if (descText != null && !descText.isEmpty()) {
            RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
            textBlock.getSpans().add(textSpan(descText));
            model.getBlocks().add(textBlock);
        }
    }

    private static void appendLiveBlocks(RenderModel model, JSONObject major, String descText) {
        if (major == null) return;
        // 直播推荐：major.live_rcmd.content.title
        JSONObject live = major.getJSONObject("live_rcmd");
        if (live != null) {
            JSONObject content = live.getJSONObject("content");
            if (content != null) {
                String title = content.getString("title");
                if (title != null && !title.isEmpty()) {
                    RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
                    textBlock.getSpans().add(textSpan(title));
                    model.getBlocks().add(textBlock);
                }
            }
        }
        if (descText != null && !descText.isEmpty()) {
            RenderModel.TextBlock textBlock = new RenderModel.TextBlock();
            textBlock.getSpans().add(textSpan(descText));
            model.getBlocks().add(textBlock);
        }
    }

    private static void appendDrawFallbackBlocks(RenderModel model, JSONObject major, String descText) {
        // LEGACY schema 的 DRAW 没 desc；只能从 major.draw.items 当图
        if (major == null) return;
        JSONObject draw = major.getJSONObject("draw");
        if (draw == null) return;
        com.alibaba.fastjson.JSONArray items = draw.getJSONArray("items");
        if (items == null || items.isEmpty()) return;
        RenderModel.ImageBlock image = new RenderModel.ImageBlock();
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (it == null) continue;
            RenderModel.Pic pic = new RenderModel.Pic();
            pic.setUrl(normalizeUrl(it.getString("src")));
            pic.setWidth(it.getIntValue("width"));
            pic.setHeight(it.getIntValue("height"));
            if (pic.getUrl() != null && !pic.getUrl().isEmpty()) {
                image.getPics().add(pic);
            }
        }
        if (!image.getPics().isEmpty()) {
            model.getBlocks().add(image);
        }
    }

    private static String nonNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static void parseAuthor(JSONObject author, RenderModel model) {
        if (author == null) {
            return;
        }
        RenderModel.Author a = model.getAuthor();
        a.setName(author.getString("name"));
        a.setFaceUrl(normalizeUrl(author.getString("face")));
        a.setPubTime(author.getString("pub_time"));
        JSONObject vip = author.getJSONObject("vip");
        if (vip != null) {
            JSONObject label = vip.getJSONObject("label");
            if (label != null && label.getString("text") != null && !label.getString("text").isEmpty()) {
                a.setBadge(label.getString("text"));
            }
        }
    }

    private static void parseStat(JSONObject stat, RenderModel model) {
        if (stat == null) {
            return;
        }
        RenderModel.Stat s = model.getStat();
        s.setForward(count(stat.getJSONObject("forward")));
        s.setComment(count(stat.getJSONObject("comment")));
        s.setLike(count(stat.getJSONObject("like")));
    }

    private static void parseContent(JSONObject content, RenderModel model) {
        if (content == null) {
            return;
        }
        JSONArray paragraphs = content.getJSONArray("paragraphs");
        if (paragraphs == null) {
            return;
        }
        for (int i = 0; i < paragraphs.size(); i++) {
            JSONObject para = paragraphs.getJSONObject(i);
            Integer paraType = para.getInteger("para_type");

            JSONObject pic = para.getJSONObject("pic");
            if (pic != null && pic.getJSONArray("pics") != null) {
                RenderModel.ImageBlock block = new RenderModel.ImageBlock();
                block.setPics(toPics(pic.getJSONArray("pics")));
                if (!block.getPics().isEmpty()) {
                    model.getBlocks().add(block);
                }
                continue;
            }

            JSONObject text = para.getJSONObject("text");
            if (text != null && text.getJSONArray("nodes") != null) {
                RenderModel.TextBlock block = parseTextNodes(text.getJSONArray("nodes"));
                if (!block.getSpans().isEmpty()) {
                    model.getBlocks().add(block);
                }
                continue;
            }

            // 其余段落类型（标题/引用/代码/分割线/链接卡）本期按纯文本兜底
            String fallback = paragraphFallbackText(para);
            if (fallback != null && !fallback.isEmpty()) {
                RenderModel.TextBlock block = new RenderModel.TextBlock();
                block.getSpans().addAll(splitUnicodeEmoji(fallback));
                model.getBlocks().add(block);
            } else if (paraType != null && paraType == PARA_TYPE_PIC) {
                log.debug("段落 {} 无可用内容，已跳过", paraType);
            }
        }
    }

    private static RenderModel.TextBlock parseTextNodes(JSONArray nodes) {
        RenderModel.TextBlock block = new RenderModel.TextBlock();
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String nodeType = node.getString("type");
            if (nodeType == null) {
                continue;
            }
            if ("TEXT_NODE_TYPE_WORD".equals(nodeType)) {
                JSONObject word = node.getJSONObject("word");
                String words = word == null ? null : word.getString("words");
                if (words != null && !words.isEmpty()) {
                    block.getSpans().addAll(splitUnicodeEmoji(words));
                }
            } else if ("TEXT_NODE_TYPE_RICH".equals(nodeType)) {
                RenderModel.Span span = parseRichNode(node.getJSONObject("rich"));
                if (span != null) {
                    block.getSpans().add(span);
                }
            }
        }
        return block;
    }

    /** 把 rich 节点映射成片段：emoji 取贴图地址，话题/链接/@ 标记颜色由渲染器处理 */
    private static RenderModel.Span parseRichNode(JSONObject rich) {
        if (rich == null) {
            return null;
        }
        String richType = rich.getString("type");
        String text = rich.getString("text");
        if (text == null || text.isEmpty()) {
            text = rich.getString("orig_text");
        }
        if (richType == null) {
            return null;
        }

        RenderModel.Span span = new RenderModel.Span();
        switch (richType) {
            case "RICH_TEXT_NODE_TYPE_EMOJI": {
                span.setKind(RenderModel.SpanKind.EMOJI);
                span.setText(text);
                JSONObject emoji = rich.getJSONObject("emoji");
                if (emoji != null) {
                    String icon = emoji.getString("icon_url");
                    if (icon == null || icon.isEmpty()) {
                        icon = emoji.getString("webp_url");
                    }
                    span.setImageUrl(normalizeUrl(icon));
                }
                return span;
            }
            case "RICH_TEXT_NODE_TYPE_TOPIC":
                span.setKind(RenderModel.SpanKind.TOPIC);
                span.setText(text);
                return span;
            case "RICH_TEXT_NODE_TYPE_WEB":
                span.setKind(RenderModel.SpanKind.LINK);
                span.setText(text);
                return span;
            case "RICH_TEXT_NODE_TYPE_AT":
            case "RICH_TEXT_NODE_TYPE_USER":
                span.setKind(RenderModel.SpanKind.AT);
                span.setText(text);
                return span;
            default:
                span.setKind(RenderModel.SpanKind.OTHER);
                span.setText(text);
                return span;
        }
    }

    /**
     * 把纯文本按 unicode emoji 切开。
     *
     * <p>这些 emoji <b>不在内置中文字体里</b>（如 🌟 U+1F31F / 📢 U+1F4E2），若直接交给
     * Java2D 会画不出来。这里把它们单独切成 EMOJI 片段并给出 CDN 贴图地址，由渲染器按图片绘制。
     *
     * <p>注意判定刻意保守：只认 {@code U+1F000-1FAFF} 等"确定是 emoji"的区段，以及
     * 后面跟着变体选择符 {@code U+FE0F} 的字符。这样 ★☆●○ 这类<b>中文字体里本来就有</b>
     * 的符号不会被误判成 emoji 而去找贴图。
     *
     * @param raw 原始文本
     * @return 片段列表
     */
    static List<RenderModel.Span> splitUnicodeEmoji(String raw) {
        List<RenderModel.Span> spans = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int len = raw.length();
        while (i < len) {
            int cp = raw.codePointAt(i);
            int cpLen = Character.charCount(cp);
            boolean nextIsVs16 = i + cpLen < len && raw.charAt(i + cpLen) == '\uFE0F';

            if (isEmojiCodePoint(cp)) {
                int end = i + cpLen;
                // 吃掉变体选择符与 ZWJ 序列（如 👨👩👧）
                if (nextIsVs16) {
                    end += 1;
                }
                while (end < len && raw.charAt(end) == '\u200D') {
                    end += 1;
                    if (end < len) {
                        int cp2 = raw.codePointAt(end);
                        end += Character.charCount(cp2);
                    }
                }
                if (buf.length() > 0) {
                    spans.add(textSpan(buf.toString()));
                    buf.setLength(0);
                }
                String seq = raw.substring(i, end);
                RenderModel.Span emoji = new RenderModel.Span();
                emoji.setKind(RenderModel.SpanKind.EMOJI);
                emoji.setText(seq);
                emoji.setImageUrl(twemojiUrl(seq));
                spans.add(emoji);
                i = end;
                continue;
            }

            buf.appendCodePoint(cp);
            i += cpLen;
        }
        if (buf.length() > 0) {
            spans.add(textSpan(buf.toString()));
        }
        return spans;
    }

    private static RenderModel.Span textSpan(String text) {
        RenderModel.Span span = new RenderModel.Span();
        span.setKind(RenderModel.SpanKind.TEXT);
        span.setText(text);
        return span;
    }

    /** 判定是否按 emoji 处理（保守规则，见 {@link #splitUnicodeEmoji} 注释） */
    static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF)
                || (cp >= 0x231A && cp <= 0x231B)
                || (cp >= 0x23E9 && cp <= 0x23FA)
                || (cp >= 0x25AA && cp <= 0x25AB)
                || (cp >= 0x25FB && cp <= 0x25FE)
                || (cp >= 0x26AA && cp <= 0x26AB)
                || (cp >= 0x2B1B && cp <= 0x2B1C)
                || cp == 0x2B50 || cp == 0x2B55
                || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
                || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299;
    }

    /** 把 emoji 序列换算成 Twemoji 的文件名 */
    static String twemojiUrl(String sequence) {
        StringBuilder code = new StringBuilder();
        int i = 0;
        while (i < sequence.length()) {
            int cp = sequence.codePointAt(i);
            if (cp == 0xFE0F) {
                i += 1;
                continue;
            }
            if (code.length() > 0) {
                code.append('-');
            }
            code.append(Integer.toHexString(cp));
            i += Character.charCount(cp);
        }
        return TWEMOJI_BASE + code + ".png";
    }

    private static JSONArray topAlbumPics(JSONObject topModule) {
        JSONObject top = topModule.getJSONObject("module_top");
        if (top == null) {
            return null;
        }
        JSONObject display = top.getJSONObject("display");
        if (display == null) {
            return null;
        }
        JSONObject album = display.getJSONObject("album");
        return album == null ? null : album.getJSONArray("pics");
    }

    private static boolean hasImageBlock(RenderModel model) {
        for (RenderModel.Block b : model.getBlocks()) {
            if (b instanceof RenderModel.ImageBlock) {
                return true;
            }
        }
        return false;
    }

    private static List<RenderModel.Pic> toPics(JSONArray array) {
        List<RenderModel.Pic> pics = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject o = array.getJSONObject(i);
            if (o == null) {
                continue;
            }
            RenderModel.Pic pic = new RenderModel.Pic();
            pic.setUrl(normalizeUrl(o.getString("url")));
            pic.setWidth(o.getIntValue("width"));
            pic.setHeight(o.getIntValue("height"));
            if (pic.getUrl() != null && !pic.getUrl().isEmpty()) {
                pics.add(pic);
            }
        }
        return pics;
    }

    /** 段落兜底文本（标题/引用/列表等） */
    private static String paragraphFallbackText(JSONObject para) {
        for (String key : new String[]{"heading", "blockquote", "code"}) {
            JSONObject o = para.getJSONObject(key);
            if (o != null) {
                String t = extractAnyText(o);
                if (t != null && !t.isEmpty()) {
                    return t;
                }
            }
        }
        return null;
    }

    private static String extractAnyText(JSONObject o) {
        JSONArray nodes = o.getJSONArray("nodes");
        if (nodes == null) {
            return o.getString("text");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            JSONObject word = n.getJSONObject("word");
            if (word != null && word.getString("words") != null) {
                sb.append(word.getString("words"));
            }
        }
        return sb.toString();
    }

    private static long count(JSONObject o) {
        return o == null ? 0L : o.getLongValue("count");
    }

    /**
     * B 站图片 CDN 给的是 {@code http://}，直接请求会被跳转（且 http→https 的跳转
     * {@code HttpURLConnection} 默认不跟随），这里统一规范成 https。
     *
     * <p>是 {@code public} 因为 {@code Dynamic.getDynamicInfoList} 也要用 —— 两个调用方写两份
     * 容易在细节上分叉（{@code //} vs {@code http://} vs {@code https://} 哪个先检查）。
     *
     * @param url 原始地址
     * @return 规范化地址
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("http://")) {
            return "https://" + url.substring("http://".length());
        }
        return url;
    }
}

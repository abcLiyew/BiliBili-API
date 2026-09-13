package com.esdllm.bilibiliApi.render;

import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯 Java2D 的动态长图渲染器。
 *
 * <p><b>为什么不用浏览器</b>：原来的实现用 Selenium 起无头 Chrome 去截 opus 页面，
 * 在"无界面 Linux"上有两个治不好的问题 ——
 * <ol>
 *   <li>需要装 Chrome（本机实测 Selenium Manager 自动下载了 341MB 的 Chrome for Testing）；</li>
 *   <li>页面用 CSS 指定的中文字体在最小容器里根本不存在，正文会渲染成豆腐块；</li>
 * </ol>
 * 而且即便把浏览器和字体都备齐，页面自身的登录浮层还会挡住正文（实测 2026 版前端）。
 *
 * <p>Java2D 方案没有这些依赖：字体来自 jar 内置（见 {@link FontRegistry}），
 * 绘制全程 {@code java.awt.headless=true}，不需要任何图形界面或浏览器。
 *
 * <p>文字换行按"不可拆分单元"贪心填充：中文逐字可断，西文按词、URL 整段不断，
 * 并处理了"避头尾"（收尾标点不落行首、起首标点不落行尾）。
 */
@Slf4j
public class Java2DImageRenderer implements DynamicImageRenderer {

    /** 正文区宽度 */
    public static final int CONTENT_WIDTH = 700;

    private static final int PADDING = 28;
    private static final int AVATAR = 50;
    private static final float NAME_SIZE = 18f;
    private static final float META_SIZE = 13f;
    private static final float BADGE_SIZE = 11f;
    private static final float TITLE_SIZE = 21f;
    private static final float BODY_SIZE = 17f;
    private static final int TITLE_LINE_HEIGHT = 34;
    private static final int LINE_HEIGHT = 28;
    private static final int BLOCK_GAP = 16;
    private static final int IMAGE_GAP = 6;
    private static final int IMAGE_RADIUS = 6;
    private static final int EMOJI_SIZE = 20;
    private static final int MAX_SINGLE_IMAGE_HEIGHT = 1100;

    private static final Color C_TEXT = new Color(0x18, 0x19, 0x1C);
    private static final Color C_MUTED = new Color(0x94, 0x99, 0xA0);
    private static final Color C_LINK = new Color(0x00, 0x8A, 0xC5);
    private static final Color C_BRAND = new Color(0xFB, 0x72, 0x99);
    private static final Color C_DIVIDER = new Color(0xE3, 0xE5, 0xE7);
    private static final Color C_PLACEHOLDER = new Color(0xEE, 0xEF, 0xF1);
    private static final Color C_PLACEHOLDER_EDGE = new Color(0xD0, 0xD3, 0xD8);

    /** 已告警过的缺字码位，避免同一张图里刷屏 */
    private final Set<Integer> reportedMissing = new HashSet<>();

    @Override
    public BufferedImage render(RenderModel model) {
        int width = CONTENT_WIDTH + PADDING * 2;
        List<Cmd> cmds = new ArrayList<>();
        int height = layout(model, cmds, width);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            applyHints(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            for (Cmd cmd : cmds) {
                cmd.paint(g);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ------------------------------------------------------------------ 布局

    private int layout(RenderModel model, List<Cmd> cmds, int totalWidth) {
        Graphics2D mg = measuringGraphics();
        try {
            // 先并发把要用的图抓回来（带 CDN 尺寸后缀）。必须放在测量之前：
            // 串行下载 9 张大图实测把一次渲染拖到近 60 秒，并发后耗时由最慢一张决定。
            prefetchImages(model);

            Font nameFont = FontRegistry.emphasis(NAME_SIZE);
            Font metaFont = FontRegistry.regular(META_SIZE);
            Font badgeFont = FontRegistry.regular(BADGE_SIZE);
            Font bodyFont = FontRegistry.regular(BODY_SIZE);
            FontMetrics bodyMetrics = mg.getFontMetrics(bodyFont);

            int left = PADDING;
            int y = PADDING;

            // ---- 头部：头像 + 昵称 + 勋章 + 时间 ----
            cmds.add(new ImageCmd(HttpImageFetcher.fetch(avatarRequest(model.getAuthor().getFaceUrl())),
                    left, y, AVATAR, AVATAR, 0, true));

            int textX = left + AVATAR + 14;
            int nameBaseline = y + 22;
            String name = nullToEmpty(model.getAuthor().getName());
            cmds.add(new TextCmd(name, textX, nameBaseline, nameFont, C_BRAND, true));
            int cursor = textX + mg.getFontMetrics(nameFont).stringWidth(name);

            String badge = model.getAuthor().getBadge();
            if (badge != null && !badge.isEmpty()) {
                FontMetrics bfm = mg.getFontMetrics(badgeFont);
                int bw = bfm.stringWidth(badge) + 14;
                int bh = 18;
                int bx = cursor + 10;
                int by = nameBaseline - 15;
                cmds.add(new RoundRectCmd(bx, by, bw, bh, 9, null, C_BRAND));
                cmds.add(new TextCmd(badge, bx + 7, by + 13, badgeFont, C_BRAND, false));
            }

            String meta = nullToEmpty(model.getAuthor().getPubTime());
            if (model.isTop()) {
                meta = "置顶 · " + meta;
            }
            cmds.add(new TextCmd(meta, textX, y + 43, metaFont, C_MUTED, false));

            y += AVATAR + 22;

            // ---- 标题（可选：只有"带标题的动态 / opus 文章"才有，多数图文动态没有）----
            // B 站版式里标题在作者行之下、正文之上，字号更大且加粗；用伪粗体（重描一次）实现。
            String title = model.getTitle();
            if (title != null && !title.isBlank()) {
                Font titleFont = FontRegistry.emphasis(TITLE_SIZE);
                y = layoutSpans(RenderModelLoader.splitUnicodeEmoji(title), y, cmds,
                        titleFont, mg.getFontMetrics(titleFont), left, TITLE_LINE_HEIGHT, true);
                y += 10;
            }

            // ---- 内容块（顺序即原文顺序）----
            for (RenderModel.Block block : model.getBlocks()) {
                if (block instanceof RenderModel.TextBlock) {
                    y = layoutText((RenderModel.TextBlock) block, y, cmds, mg, bodyFont, bodyMetrics, left);
                } else if (block instanceof RenderModel.ImageBlock) {
                    y = layoutImages((RenderModel.ImageBlock) block, y, cmds, left);
                }
                y += BLOCK_GAP;
            }

            // ---- 互动数 ----
            y += 2;
            cmds.add(new LineCmd(left, y, CONTENT_WIDTH, C_DIVIDER));
            y += 22;
            RenderModel.Stat stat = model.getStat();
            String statLine = "转发 " + stat.getForward() + "    评论 " + stat.getComment()
                    + "    点赞 " + stat.getLike();
            cmds.add(new TextCmd(statLine, left, y, metaFont, C_MUTED, false));
            y += 10;

            return y + PADDING;
        } finally {
            mg.dispose();
        }
    }

    private int layoutText(RenderModel.TextBlock block, int y, List<Cmd> cmds,
                           Graphics2D mg, Font bodyFont, FontMetrics metrics, int left) {
        return layoutSpans(block.getSpans(), y, cmds, bodyFont, metrics, left, LINE_HEIGHT, false);
    }

    /**
     * 把一组富文本片段排成若干行并落成绘制指令。
     *
     * <p>抽出来是为了让<b>标题</b>复用同一套排版能力（换行、避头尾、emoji 贴图、缺字占位），
     * 否则标题得另写一份排版逻辑 —— 而长标题一旦不换行就会直接画出画布外。
     *
     * @param spans      片段
     * @param y          起始 y
     * @param cmds       绘制指令收集器
     * @param font       本段字体
     * @param metrics    本段字体度量
     * @param left       左边距
     * @param lineHeight 行高
     * @param fauxBold   是否伪粗体（内置子集只有 Regular 一个字面，加粗靠偏移重描）
     * @return 本段结束后的 y
     */
    private int layoutSpans(List<RenderModel.Span> spans, int y, List<Cmd> cmds, Font font,
                            FontMetrics metrics, int left, int lineHeight, boolean fauxBold) {
        List<Line> lines = typeset(spans, font, metrics);
        if (lines.isEmpty()) {
            return y;
        }
        int baseline = y + metrics.getAscent();
        for (Line line : lines) {
            int x = left;
            for (int i = 0; i < line.pieces.size(); i++) {
                Piece piece = line.pieces.get(i);
                int drawX = x;
                if (i == 0 && piece.isBlank()) {
                    // 行首空白直接丢弃，避免视觉上多出缩进
                    x += piece.width;
                    continue;
                }
                switch (piece.kind) {
                    case EMOJI:
                        cmds.add(new ImageCmd(piece.image, drawX, baseline - EMOJI_SIZE + 3,
                                EMOJI_SIZE, EMOJI_SIZE, 0, false));
                        break;
                    case MISSING:
                        cmds.add(new RoundRectCmd(drawX + 1, baseline - 14, piece.width - 2, 17, 4,
                                C_PLACEHOLDER, C_PLACEHOLDER_EDGE));
                        break;
                    default:
                        cmds.add(new TextCmd(piece.text, drawX, baseline, font, piece.color, fauxBold));
                        break;
                }
                x += piece.width;
            }
            baseline += lineHeight;
        }
        return y + lines.size() * lineHeight;
    }

    private int layoutImages(RenderModel.ImageBlock block, int y, List<Cmd> cmds, int left) {
        List<RenderModel.Pic> pics = block.getPics();
        if (pics == null || pics.isEmpty()) {
            return y;
        }
        if (pics.size() == 1) {
            RenderModel.Pic pic = pics.get(0);
            int w = CONTENT_WIDTH;
            int h = w;
            if (pic.getWidth() > 0 && pic.getHeight() > 0) {
                h = Math.round(w * (pic.getHeight() / (float) pic.getWidth()));
            }
            if (h > MAX_SINGLE_IMAGE_HEIGHT) {
                h = MAX_SINGLE_IMAGE_HEIGHT;
                w = Math.round(h / (pic.getHeight() / (float) pic.getWidth()));
            }
            cmds.add(new ImageCmd(HttpImageFetcher.fetch(singlePicRequest(pic.getUrl())),
                    left, y, w, h, IMAGE_RADIUS, false));
            return y + h;
        }

        int cols = gridCols(pics.size());
        int cell = gridCell(cols);
        int rows = (pics.size() + cols - 1) / cols;
        for (int i = 0; i < pics.size(); i++) {
            int r = i / cols;
            int c = i % cols;
            int px = left + c * (cell + IMAGE_GAP);
            int py = y + r * (cell + IMAGE_GAP);
            cmds.add(new ImageCmd(HttpImageFetcher.fetch(gridPicRequest(pics.get(i).getUrl(), cell)),
                    px, py, cell, cell, IMAGE_RADIUS, false));
        }
        return y + rows * cell + (rows - 1) * IMAGE_GAP;
    }

    // ------------------------------------------------------------------ 图片请求（预取与布局共用，保证缓存命中）

    /** 网格列数：1~4 张两列，更多三列 */
    private static int gridCols(int count) {
        return count <= 4 ? 2 : 3;
    }

    /** 单元格边长 */
    private static int gridCell(int cols) {
        return (CONTENT_WIDTH - IMAGE_GAP * (cols - 1)) / cols;
    }

    /** 头像：按绘制尺寸的 2 倍取图，缩小后更锐利 */
    private static HttpImageFetcher.Request avatarRequest(String url) {
        return HttpImageFetcher.Request.square(url, AVATAR * 2);
    }

    /** 单图：只限宽度，保持原始比例 */
    private static HttpImageFetcher.Request singlePicRequest(String url) {
        return HttpImageFetcher.Request.width(url, CONTENT_WIDTH);
    }

    /** 网格图：直接向 CDN 要"方形居中裁剪"，与单元格形状一致，省掉本地裁剪 */
    private static HttpImageFetcher.Request gridPicRequest(String url, int cell) {
        return HttpImageFetcher.Request.square(url, cell);
    }

    /** 表情贴图：按绘制尺寸的 2 倍取图 */
    private static HttpImageFetcher.Request emojiRequest(String url) {
        return HttpImageFetcher.Request.square(url, EMOJI_SIZE * 2);
    }

    /**
     * 收集本次渲染需要的全部图片并并发预取。
     *
     * <p>请求参数必须与布局阶段调用 {@code fetch} 时<b>逐字一致</b>（同一 record 值），
     * 否则缓存键不匹配、预取白做。因此这里统一走上面那几个 {@code *Request} 工厂方法。
     */
    private void prefetchImages(RenderModel model) {
        List<HttpImageFetcher.Request> requests = new ArrayList<>();
        if (model.getAuthor().getFaceUrl() != null) {
            requests.add(avatarRequest(model.getAuthor().getFaceUrl()));
        }
        for (RenderModel.Block block : model.getBlocks()) {
            if (block instanceof RenderModel.ImageBlock) {
                List<RenderModel.Pic> pics = ((RenderModel.ImageBlock) block).getPics();
                if (pics == null || pics.isEmpty()) {
                    continue;
                }
                if (pics.size() == 1) {
                    requests.add(singlePicRequest(pics.get(0).getUrl()));
                } else {
                    int cell = gridCell(gridCols(pics.size()));
                    for (RenderModel.Pic pic : pics) {
                        requests.add(gridPicRequest(pic.getUrl(), cell));
                    }
                }
            } else if (block instanceof RenderModel.TextBlock) {
                for (RenderModel.Span span : ((RenderModel.TextBlock) block).getSpans()) {
                    if (span.getKind() == RenderModel.SpanKind.EMOJI && span.getImageUrl() != null) {
                        requests.add(emojiRequest(span.getImageUrl()));
                    }
                }
            }
        }
        HttpImageFetcher.prefetch(requests);
    }

    // ------------------------------------------------------------------ 排版

    /**
     * 把富文本片段排成若干行。
     *
     * @param spans 片段
     * @param font  正文字体
     * @param fm    字体度量
     * @return 行列表（已含每行各片段的宽度与颜色）
     */
    private List<Line> typeset(List<RenderModel.Span> spans, Font font, FontMetrics fm) {
        List<Piece> atoms = flatten(spans, fm);
        List<Group> groups = group(atoms);
        mergeForbiddenBreaks(groups);

        List<Line> lines = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        int width = 0;

        for (Group g : groups) {
            if (g.lineBreak) {
                lines.add(new Line(current));
                current = new ArrayList<>();
                width = 0;
                continue;
            }
            if (width > 0 && width + g.width > CONTENT_WIDTH) {
                lines.add(new Line(current));
                current = new ArrayList<>();
                width = 0;
            }
            if (g.width > CONTENT_WIDTH) {
                // 单个单元比整行还宽（超长 URL / 无空格长串）→ 只能逐字符硬拆
                for (Piece p : g.pieces) {
                    if (width > 0 && width + p.width > CONTENT_WIDTH) {
                        lines.add(new Line(current));
                        current = new ArrayList<>();
                        width = 0;
                    }
                    current.add(p);
                    width += p.width;
                }
            } else {
                current.addAll(g.pieces);
                width += g.width;
            }
        }
        if (!current.isEmpty()) {
            lines.add(new Line(current));
        }
        return lines;
    }

    /** 片段 → 原子（每个码位/每个 emoji 一个原子） */
    private List<Piece> flatten(List<RenderModel.Span> spans, FontMetrics fm) {
        List<Piece> atoms = new ArrayList<>();
        if (spans == null) {
            return atoms;
        }
        for (RenderModel.Span span : spans) {
            if (span == null) {
                continue;
            }
            RenderModel.SpanKind kind = span.getKind();
            if (kind == RenderModel.SpanKind.EMOJI) {
                atoms.add(Piece.emoji(span.getImageUrl()));
                continue;
            }
            String text = nullToEmpty(span.getText());
            Color color;
            switch (kind == null ? RenderModel.SpanKind.TEXT : kind) {
                case TOPIC:
                case LINK:
                case AT:
                    color = C_LINK;
                    break;
                default:
                    color = C_TEXT;
                    break;
            }
            int i = 0;
            while (i < text.length()) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    atoms.add(Piece.lineBreak());
                    i++;
                    continue;
                }
                int cp = text.codePointAt(i);
                int cpLen = Character.charCount(cp);
                if (FontRegistry.canDisplay(cp)) {
                    String s = text.substring(i, i + cpLen);
                    atoms.add(Piece.text(s, fm.stringWidth(s), color, cp));
                } else {
                    if (reportedMissing.add(cp)) {
                        log.warn("动态长图渲染：内置字体缺字形 U+{}（'{}'），已用占位块绘制。"
                                        + "如属常用字符请扩充字体子集（tools/build-font-subset.py）",
                                Integer.toHexString(cp).toUpperCase(), new String(Character.toChars(cp)));
                    }
                    atoms.add(Piece.missing(fm.charWidth('中'), cp));
                }
                i += cpLen;
            }
        }
        return atoms;
    }

    /** 原子 → 不可拆分单元（西文词、URL、emoji、单个汉字各成一组） */
    private List<Group> group(List<Piece> atoms) {
        List<Group> groups = new ArrayList<>();
        int i = 0;
        while (i < atoms.size()) {
            Piece a = atoms.get(i);
            if (a.kind == PieceKind.BREAK) {
                groups.add(Group.lineBreak());
                i++;
                continue;
            }
            if (a.kind == PieceKind.EMOJI || a.kind == PieceKind.MISSING) {
                groups.add(new Group(List.of(a), a.width, false));
                i++;
                continue;
            }
            if (isWordChar(a.codePoint)) {
                List<Piece> pieces = new ArrayList<>();
                int w = 0;
                while (i < atoms.size() && atoms.get(i).kind == PieceKind.TEXT
                        && isWordChar(atoms.get(i).codePoint)) {
                    pieces.add(atoms.get(i));
                    w += atoms.get(i).width;
                    i++;
                }
                groups.add(new Group(pieces, w, false));
                continue;
            }
            groups.add(new Group(List.of(a), a.width, false));
            i++;
        }
        return groups;
    }

    /**
     * 避头尾处理：收尾标点不能落行首（并入上一组），起首标点不能落行尾（并入下一组）。
     */
    private void mergeForbiddenBreaks(List<Group> groups) {
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            if (g.lineBreak || g.pieces.isEmpty()) {
                continue;
            }
            // 行首是收尾标点 → 并入上一组
            Piece first = g.pieces.get(0);
            if (isClosingPunct(first.codePoint) && i > 0) {
                Group prev = groups.get(i - 1);
                if (!prev.lineBreak) {
                    prev.pieces.addAll(g.pieces);
                    prev.width += g.width;
                    groups.remove(i);
                    i--;
                    continue;
                }
            }
            // 行尾是起首标点 → 并入下一组
            Piece last = g.pieces.get(g.pieces.size() - 1);
            if (isOpeningPunct(last.codePoint) && i + 1 < groups.size()) {
                Group next = groups.get(i + 1);
                if (!next.lineBreak) {
                    next.pieces.addAll(0, g.pieces);
                    next.width += g.width;
                    groups.remove(i);
                    i--;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 字符分类

    private static boolean isWordChar(int cp) {
        if (cp <= 0) {
            return false;
        }
        if (Character.isLetterOrDigit(cp) && cp < 0x2E80) {
            return true;   // 拉丁、数字（含重音字母）
        }
        return cp == '-' || cp == '_' || cp == '.' || cp == '/' || cp == ':' || cp == '?'
                || cp == '&' || cp == '=' || cp == '%' || cp == '#' || cp == '@' || cp == '+'
                || cp == '~' || cp == '\'' || cp == '!';
    }

    private static boolean isClosingPunct(int cp) {
        return "，。、；：！？）】》」』〕｝”’·…—～%".indexOf(cp) >= 0
                || ",.;:!?)]}\"'>".indexOf(cp) >= 0;
    }

    private static boolean isOpeningPunct(int cp) {
        return "（【《「『〔｛“‘".indexOf(cp) >= 0
                || "([{\"<".indexOf(cp) >= 0;
    }

    // ------------------------------------------------------------------ 基础设施

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Graphics2D measuringGraphics() {
        Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        applyHints(g);
        return g;
    }

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private enum PieceKind {TEXT, EMOJI, MISSING, BREAK}

    /** 排版原子 */
    private static final class Piece {
        private final PieceKind kind;
        private final String text;
        private final BufferedImage image;
        private final Color color;
        private final int width;
        private final int codePoint;

        private Piece(PieceKind kind, String text, BufferedImage image, Color color, int width, int codePoint) {
            this.kind = kind;
            this.text = text;
            this.image = image;
            this.color = color;
            this.width = width;
            this.codePoint = codePoint;
        }

        static Piece text(String s, int width, Color color, int codePoint) {
            return new Piece(PieceKind.TEXT, s, null, color, width, codePoint);
        }

        static Piece emoji(String url) {
            BufferedImage img = HttpImageFetcher.fetch(emojiRequest(url));
            return new Piece(PieceKind.EMOJI, null, img, null, EMOJI_SIZE, -1);
        }

        static Piece missing(int width, int codePoint) {
            return new Piece(PieceKind.MISSING, null, null, null, width, codePoint);
        }

        static Piece lineBreak() {
            return new Piece(PieceKind.BREAK, null, null, null, 0, -1);
        }

        boolean isBlank() {
            return kind == PieceKind.TEXT && text != null && text.isBlank();
        }
    }

    /** 不可拆分单元 */
    private static final class Group {
        private final List<Piece> pieces;
        private int width;
        private final boolean lineBreak;

        Group(List<Piece> pieces, int width, boolean lineBreak) {
            this.pieces = new ArrayList<>(pieces);
            this.width = width;
            this.lineBreak = lineBreak;
        }

        static Group lineBreak() {
            return new Group(List.of(), 0, true);
        }
    }

    /** 一行 */
    private static final class Line {
        private final List<Piece> pieces;

        Line(List<Piece> pieces) {
            this.pieces = new ArrayList<>(pieces);
        }
    }

    // ------------------------------------------------------------------ 绘制指令

    private interface Cmd {
        void paint(Graphics2D g);
    }

    private static final class TextCmd implements Cmd {
        private final String text;
        private final int x;
        private final int y;
        private final Font font;
        private final Color color;
        private final boolean fauxBold;

        TextCmd(String text, int x, int y, Font font, Color color, boolean fauxBold) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.font = font;
            this.color = color;
            this.fauxBold = fauxBold;
        }

        @Override
        public void paint(Graphics2D g) {
            if (text == null || text.isEmpty()) {
                return;
            }
            g.setFont(font);
            g.setColor(color);
            g.drawString(text, x, y);
            if (fauxBold) {
                // 内置子集只有 Regular 一个静态字面，deriveFont(BOLD) 不会合成粗体，
                // 这里用 0.7px 偏移重描一次实现"伪粗体"，代价为零、效果稳定。
                g.drawString(text, x + 0.7f, y);
            }
        }
    }

    private static final class ImageCmd implements Cmd {
        private final BufferedImage image;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final int radius;
        private final boolean circle;

        ImageCmd(BufferedImage image, int x, int y, int w, int h, int radius, boolean circle) {
            this.image = image;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.radius = radius;
            this.circle = circle;
        }

        @Override
        public void paint(Graphics2D g) {
            java.awt.Shape old = g.getClip();
            java.awt.Shape shape = circle
                    ? new Ellipse2D.Float(x, y, w, h)
                    : new RoundRectangle2D.Float(x, y, w, h, radius * 2f, radius * 2f);
            if (image == null) {
                if (circle) {
                    g.setColor(C_PLACEHOLDER_EDGE);
                    g.fill(shape);
                } else {
                    g.setColor(C_PLACEHOLDER);
                    g.fill(shape);
                    g.setColor(C_PLACEHOLDER_EDGE);
                    g.draw(shape);
                }
                return;
            }
            g.clip(shape);
            // 居中裁剪（cover）：先按"填满"比例缩放，再居中偏移
            float scale = Math.max(w / (float) image.getWidth(), h / (float) image.getHeight());
            int dw = Math.round(image.getWidth() * scale);
            int dh = Math.round(image.getHeight() * scale);
            int dx = x + (w - dw) / 2;
            int dy = y + (h - dh) / 2;
            g.drawImage(image, dx, dy, dw, dh, null);
            g.setClip(old);
        }
    }

    private static final class RoundRectCmd implements Cmd {
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final int radius;
        private final Color fill;
        private final Color stroke;

        RoundRectCmd(int x, int y, int w, int h, int radius, Color fill, Color stroke) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.radius = radius;
            this.fill = fill;
            this.stroke = stroke;
        }

        @Override
        public void paint(Graphics2D g) {
            RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, radius * 2f, radius * 2f);
            if (fill != null) {
                g.setColor(fill);
                g.fill(shape);
            }
            if (stroke != null) {
                g.setColor(stroke);
                g.draw(shape);
            }
        }
    }

    private static final class LineCmd implements Cmd {
        private final int x;
        private final int y;
        private final int w;
        private final Color color;

        LineCmd(int x, int y, int w, Color color) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.color = color;
        }

        @Override
        public void paint(Graphics2D g) {
            g.setColor(color);
            g.fillRect(x, y, w, 1);
        }
    }
}

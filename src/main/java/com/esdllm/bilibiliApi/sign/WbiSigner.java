package com.esdllm.bilibiliApi.sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>WBI 签名器</b>：B 站 web 端自 2023-03 起对一批查询接口加的鉴权参数（{@code wts} + {@code w_rid}）。
 *
 * <p>缺失或错误的签名会得到 {@code code=-403 访问权限不足}，或 {@code code=0} 配一个
 * {@code data.v_voucher}（服务端留给自己的排错 id）。<b>这与"需要登录"是两件事</b>，
 * 但<b>不等于"签名可以替代登录"</b> —— 本批 5 个端点的 2×2 实测（2026-09-21，两次独立复核）
 * 显示：真正"只要签名、不要登录"的端点<b>一个都没有</b>；{@code acc/info} 一度被记成
 * 匿名签名即通，复核实为 {@code -352 风控校验失败}（详见 {@code BilibiliEndpoint} 的实测表）。
 *
 * <p><b>三步算法</b>（文档 {@code misc/sign/wbi.md}）：
 * <ol>
 *   <li>从 {@code x/web-interface/nav} 的 {@code data.wbi_img.{img_url,sub_url}} 截出
 *       {@code img_key} / {@code sub_key}（取文件名、去扩展名）；</li>
 *   <li>{@code img_key + sub_key} 按 64 位重排表取字符、截前 32 位 = {@code mixin_key}；</li>
 *   <li>参数加 {@code wts}（秒级时间戳）→ 按 key 升序 → 过滤值里的 {@code !'()*} →
 *       拼 {@code mixin_key} → MD5 = {@code w_rid}。</li>
 * </ol>
 *
 * <p><b>本类是纯函数</b>：不发请求、不缓存、不读配置。密钥的获取与缓存见 {@link WbiKeyStore}，
 * 带签名的出站见 {@code BilibiliHttp#getSigned}。这样切分是因为"算法对不对"必须能离线判定 ——
 * 密钥从哪来是另一个问题，混在一起会让签名错误与网络错误长得一样。
 *
 * <p><b>🔴 三个编码口径（这一批最容易错的地方，错了就是 -403，且没有任何线索）</b>：
 * <table border="1">
 *   <caption>与常见库的差异</caption>
 *   <tr><th>要求</th><th>踩坑写法</th><th>后果</th></tr>
 *   <tr><td>十六进制字母<b>大写</b></td>
 *       <td>部分库编成小写</td><td>中文/空格参数签名对不上，英文参数却"看起来正常"</td></tr>
 *   <tr><td>空格编成 {@code %20}</td>
 *       <td>{@code URLEncoder.encode}（按 form 约定编成 {@code +}）</td>
 *       <td>搜索关键词带空格时必然失败</td></tr>
 *   <tr><td>值里的 {@code !'()*} <b>先删掉</b>再编码</td>
 *       <td>只编码不删（会得到 {@code %21%27%28%29%2A}）</td>
 *       <td>签名与浏览器算出的不一致</td></tr>
 * </table>
 * 另外 {@code +} 本身必须编成 {@code %2B}（手写实现天然满足，用 form 编码器则会把 {@code +} 当空格）。
 *
 * <p><b>校验口径（2026-09-21 离线复算过，别照抄文档里的 demo 值）</b>：
 * <table border="1">
 *   <caption>哪些文档值能当测试锚点</caption>
 *   <tr><th>来源</th><th>值</th><th>能用吗</th></tr>
 *   <tr><td>文档正文给出的 {@code mixin_key}</td>
 *       <td>{@code 7cd084941338484aae1ad9425b84077c} + {@code 4932caff0ff746eab6f01bf08b70ac45}
 *           → {@code ea1db124af3c7062474693fa704f4ff8}</td>
 *       <td>✅ 可复现（已离线复算一致）</td></tr>
 *   <tr><td>文档 Java demo 注释里的第二组</td>
 *       <td>{@code 653657f524a547ac981ded72ea172057} + {@code 6e4909c702f846728e64f6007736a338}
 *           → {@code 72136226c6a73669787ee4fd02a74c27}</td>
 *       <td>✅ 可复现</td></tr>
 *   <tr><td>文档 <b>PHP</b> demo 注释里的 {@code w_rid}</td>
 *       <td>{@code {foo:114,bar:514,baz:1919810}} + {@code wts=1700384803}
 *           → {@code 4614cb98d60a43e50c3a3033fe3d116b}</td>
 *       <td>✅ <b>可复现</b> —— demo 里唯一与那对示例 key 配套的 {@code w_rid}</td></tr>
 *   <tr><td>文档正文 walkthrough 的 {@code w_rid}</td>
 *       <td>{@code {bar:514,foo:114,zab:1919810}} + {@code wts=1702204169}
 *           → {@code 8f6f2b5b3d485fe1886cec6a0be8c5d4}</td>
 *       <td>✅ <b>可复现</b> —— 且 {@code zab} 按字典序排在 {@code wts} <b>之后</b>，
 *           是唯一能钉住"排在 {@code wts} 后面的键也必须进签名输入"的用例</td></tr>
 *   <tr><td>文档 Python / JS / C# demo 打印的 {@code w_rid}</td>
 *       <td>{@code d3cbd2a2…} / {@code bb97e15f…} / {@code 26e82b1b…}</td>
 *       <td>❌ <b>不可复现</b>（2026-09-21 用独立实现逐个复算：这 3 个对不上示例 key，
 *           而上面两个 {@code w_rid} 逐位一致）。
 *           它们不是"算法的另一版"，而是那三次运行各自 <b>现抓 key</b> 的产物，与示例 key 无关。
 *           拿它们当断言 = 一条永远红的用例</td></tr>
 * </table>
 * 外加文档给出的<b>编码结果</b>定值（可精确复现）：
 * {@code {foo:'one one four', bar:'五一四', baz:1919810}} 必须编成
 * {@code bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four}。
 *
 * <p>🔴 <b>文档自己还有一处矛盾，顺手记下来当反证</b>：Python demo 打印的 {@code wts=1702204169}
 * 与正文 walkthrough 的 {@code wts} <b>是同一个秒</b>（= 同一时刻、同一把 key 的产物），
 * 但正文那个能用示例 key 复现、Python 那个不能 —— <b>同一时刻同一把 key 不可能给出两个不同答案</b>，
 * 所以"示例 key"与"演示输出段"至少有一边不是当时真实运行的快照。
 * 2026-09-21 实测 {@code nav} 下发的<b>正好</b>是这对示例 key（{@code imgKey=7cd08494…}、
 * {@code subKey=4932caff…}、{@code mixin_key=ea1db124…}），即<b>示例 key 目前仍在线</b>；
 * 但这<b>推不出</b>"2023 年那几个 demo 跑的时候也是它"——
 * ⚠️ <b>别拿"nav 现在给的是这对 key"去给演示输出翻案</b>，那是两个命题。
 * ⇒ 结论不变：<b>只拿正文与 PHP 的值当锚点，演示输出段一律不许抄。</b>
 *
 * <p>⚠️ 还有一处<b>口径</b>不一致：Python demo 用 {@code urllib.parse.urlencode}（空格编成 {@code +}），
 * 与正文"空格应当编码为 {@code %20}"以及 JS/Rust demo 的做法<b>不一致</b>。
 * 本库按正文 + JS 口径实现（{@code %20}），并且这条已在<b>线上验证</b>：
 * 同一个带空格的值，编 {@code %20} 拿到 {@code code=0}，编 {@code +} 拿到
 * {@code -403 访问权限不足}（服务端会把它一并纳入签名复算）。
 *
 * @author 饿死的流浪猫
 */
public final class WbiSigner {

    /**
     * 64 位重排映射表（官方文档逐字抄录，不要"优化"它）。
     *
     * <p>它是把 {@code img_key + sub_key}（64 个十六进制字符）打乱成 {@code mixin_key} 的置换表：
     * 取第 {@code MIXIN_KEY_ENC_TAB[i]} 个字符，依序拼到第 32 位为止。
     */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52,
    };

    /** {@code mixin_key} 的长度（重排后只取前 32 位）。 */
    public static final int MIXIN_KEY_LENGTH = 32;

    /** 签名字段名：签名结果。 */
    public static final String FIELD_SIGN = "w_rid";

    /** 签名字段名：秒级时间戳。 */
    public static final String FIELD_TIMESTAMP = "wts";

    /**
     * 参与签名前需要<b>剔除</b>的字符（文档口径，与 percent-encoding 无关，是"删掉"而不是"编码"）。
     *
     * <p>为什么是这五个：它们来自 JS 的 {@code encodeURIComponent} 保留字符集
     * （{@code !'()*~}），服务端两侧算法刻意对齐浏览器实现。
     * {@code ~} <b>不在</b>剔除之列 —— 它属于 RFC 3986 的 unreserved，直接原样输出。
     */
    private static final String DROPPED_CHARS = "!'()*";

    private WbiSigner() {
        throw new AssertionError("pure function holder; do not instantiate");
    }

    /**
     * 由 {@code img_key} / {@code sub_key} 算出 {@code mixin_key}。
     *
     * @param imgKey nav 的 {@code data.wbi_img.img_url} 的文件名（去掉 .png）
     * @param subKey nav 的 {@code data.wbi_img.sub_url} 的文件名（去掉 .png）
     * @return 32 字符的 {@code mixin_key}
     * @throws IllegalArgumentException 两个 key 拼接后不足 64 字符（长度不对时任何"容错"都只会得到
     *         一个<b>算得出来但服务端不认</b>的签名，不如当场失败）
     */
    public static String mixinKey(String imgKey, String subKey) {
        if (imgKey == null || subKey == null) {
            throw new IllegalArgumentException("img_key 与 sub_key 都不能为 null");
        }
        String raw = imgKey + subKey;
        if (raw.length() < MIXIN_KEY_ENC_TAB.length) {
            throw new IllegalArgumentException("img_key + sub_key 不足 " + MIXIN_KEY_ENC_TAB.length
                    + " 个字符（实际 " + raw.length() + "），无法重排出 mixin_key");
        }
        StringBuilder mixin = new StringBuilder(MIXIN_KEY_LENGTH);
        for (int i = 0; i < MIXIN_KEY_LENGTH; i++) {
            mixin.append(raw.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return mixin.toString();
    }

    /**
     * 给参数签名，时间戳取当前秒。
     *
     * <p>等价于 {@code sign(params, imgKey, subKey, System.currentTimeMillis() / 1000)}。
     *
     * @param params 原始请求参数（不含 {@code wts} / {@code w_rid}；含了也会被覆盖）
     * @param imgKey img_key
     * @param subKey sub_key
     * @return 可直接拼到 URL 上的 query 串，形如
     *         {@code bar=514&baz=1919810&foo=114&wts=<秒>&w_rid=<md5>}
     */
    public static String sign(Map<String, String> params, String imgKey, String subKey) {
        return sign(params, imgKey, subKey, System.currentTimeMillis() / 1000L);
    }

    /**
     * 给参数签名，<b>时间戳由调用方给定</b>。
     *
     * <p>公开这个重载是为了<b>可判定</b>：签名正确与否必须能离线比对已知答案
     * （见类注释里的黄金用例）。时间戳固定之后，{@code w_rid} 就是确定值 ——
     * 否则任何断言都只能写成"看起来像 32 位 hex"，那种测试抓不到编码口径的错。
     *
     * @param params 原始请求参数；值为 {@code null} 的项会被跳过（B 站前端也不会把 null 发出去）
     * @param imgKey img_key
     * @param subKey sub_key
     * @param wts    秒级 Unix 时间戳
     * @return 可直接拼到 URL 上的 query 串（{@code wts} 按排序位置参与，{@code w_rid} 在末尾）
     * @throws IllegalArgumentException key 长度不足（见 {@link #mixinKey(String, String)}）
     */
    public static String sign(Map<String, String> params, String imgKey, String subKey, long wts) {
        String mixinKey = mixinKey(imgKey, subKey);

        // 排序：TreeMap 的自然序（UTF-16 code unit）与 JS 的 Array#sort、Python 的 sorted
        // 对 ASCII 参数名完全一致。B 站的参数名全是 ASCII，故无分歧。
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isEmpty() || entry.getValue() == null) {
                    continue;
                }
                // 🔴 w_rid 绝不能参与签名 —— 它自己的值依赖签名结果，带上就是自指
                if (FIELD_SIGN.equals(key)) {
                    continue;
                }
                sorted.put(key, entry.getValue());
            }
        }
        sorted.put(FIELD_TIMESTAMP, Long.toString(wts));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(percentEncode(entry.getKey())).append('=')
                    .append(percentEncode(entry.getValue()));
        }
        String sign = md5Hex(query + mixinKey);
        return query + "&" + FIELD_SIGN + "=" + sign;
    }

    /**
     * 按 WBI 口径做 percent-encoding。
     *
     * <p>规则（三条，缺一条就会与服务端算出不同的签名）：
     * <ol>
     *   <li>先剔除 {@value #DROPPED_CHARS} 这五个字符（<b>删除</b>，不是编码成 {@code %XX}）；</li>
     *   <li>RFC 3986 unreserved（{@code A-Z a-z 0-9 - _ . ~}）原样输出，
     *       <b>其余一律</b> {@code %XX}；</li>
     *   <li>十六进制字母<b>大写</b>、按 UTF-8 逐字节编码（所以空格是 {@code %20}，中文是三字节）。</li>
     * </ol>
     *
     * <p>为什么不直接用 {@code URLEncoder}：它是 {@code application/x-www-form-urlencoded} 的编码器，
     * 空格编成 {@code +}、并且保留 {@code *}；还与 {@code encodeURIComponent} 在
     * {@code ~ ! ' ( )} 上不一致。这些差异在英文单词参数上看不出来，一遇到空格或中文就会
     * 表现成"偶发 -403"，是最难查的一类。
     *
     * @param value 原始值，可为 null（按空串处理）
     * @return 编码后的串
     */
    public static String percentEncode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int ch = b & 0xFF;
            if (DROPPED_CHARS.indexOf(ch) >= 0) {
                continue;
            }
            if (isUnreserved(ch)) {
                out.append((char) ch);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((ch >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(ch & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** RFC 3986 unreserved：字母、数字、{@code - _ . ~} */
    private static boolean isUnreserved(int ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '_' || ch == '.' || ch == '~';
    }

    /**
     * MD5 → 32 位小写十六进制（{@code w_rid} 的形状）。
     *
     * <p>与上面 percent-encoding 的"十六进制要大写"不冲突：两条规则作用在不同东西上 ——
     * 编码里的 {@code %XX} 大写，{@code w_rid} 本身是小写 hex（文档示例逐字如此）。
     *
     * @param text 待摘要文本
     * @return 32 位小写 hex
     */
    static String md5Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 MD5（java.security 规范要求），走到这里说明运行环境坏了
            throw new IllegalStateException("运行环境缺少 MD5 实现，无法计算 WBI 签名", e);
        }
    }
}

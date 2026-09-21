package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.sign.WbiKeyStore;
import com.esdllm.bilibiliApi.sign.WbiSigner;

import java.io.IOException;
import java.util.Map;

/**
 * <b>WBI 签名门面</b>：把 B 站自 2023-03 起给一批接口加的鉴权参数（{@code wts} + {@code w_rid}）
 * 算好交给调用方 —— 用于<b>本库还没有覆盖的 WBI 接口</b>。
 *
 * <p>库内第 10 个门面（前 9 个：{@code BilibiliClient} / {@code CardInfo} / {@code Dynamic} /
 * {@code Live} / {@code ShortChain} / {@code Login} / {@code Search} / {@code UserSpace} /
 * {@code VideoExtra}）。新增类，<b>不触碰任何既有签名</b>，对既有消费方是纯增量。
 *
 * <p><b>为什么把它开放出来</b>：签名算错的表现只有一种 —— {@code code=-403 访问权限不足}，
 * 与"你真的没有权限访问这个资源"<b>完全同形</b>，从响应侧一个字都反推不出来。
 * 本库为此付了整整一个批次的代价（空格必须编成 {@code %20}、值里的 {@code !'()*} 要<b>删</b>掉、
 * {@code w_rid} 不能自指、密钥按天轮换、密钥取不到要能区分可重试与不可重试），
 * 而这些坑对每一个自己实现签名的人是<b>同一套</b>。
 * B 站的 WBI 端点有几十个，本库不可能覆盖全 —— 所以把签名能力交出去，比"等库补"有用得多。
 *
 * <p><b>不需要任何凭据</b>：{@code img_key} / {@code sub_key} 由 {@code nav} 匿名下发
 * （未登录时响应是 {@code code=-101}，但 {@code data.wbi_img} <b>照常有值</b>），
 * 它们是<b>公共值、不是凭据</b>。密钥按天轮换，本门面缓存当天那一份，<b>不会每次签名都打一次 nav</b>；
 * 取不到时（网络失败 / 正被风控 / 响应形状变了）会进 30 秒失败冷却，不再反复撞 {@code nav}。
 *
 * <p><b>典型用法</b>：
 * <pre>{@code
 * Wbi wbi = new Wbi();
 * Map<String, String> params = new LinkedHashMap<>();
 * params.put("mid", "946974");
 * String url = wbi.signedUrl("https://api.bilibili.com/x/space/wbi/acc/info", params);
 * // → https://api.bilibili.com/x/space/wbi/acc/info?mid=946974&wts=1758xxxxxx&w_rid=<32 位 md5>
 * }</pre>
 *
 * <p><b>三个必须知道的口径</b>（都是线上验证过的，写在这里省掉调用方重新踩一遍）：
 * <ol>
 *   <li>返回值里的参数值<b>已经按 WBI 口径编好码</b>，可直接拼到 URL 上。
 *       若你要自己编，注意空格是 {@code %20} 而<b>不是</b> {@code +}（{@code URLEncoder} 会编成
 *       {@code +}，服务端随即判签名错）。这也是本门面<b>不提供"参数表"形态</b>的原因：
 *       把原始值交出去，调用方一用通用编码器拼接就回到那个坑里 —— 只给"已经编好"的形态，
 *       坑就没机会出现；</li>
 *   <li>签名覆盖<b>全部</b>参数，所以 {@link #signedUrl} 要求 {@code baseUrl} 里不带 query
 *       （自带了就漏签，而漏签同样只表现为 {@code -403}）；</li>
 *   <li>{@code wts} 是<b>秒级</b>时间戳；参数里若已有 {@code w_rid} 会被忽略
 *       （它自己的值依赖签名结果，带进去就是自指，每次算出来都不一样）。</li>
 * </ol>
 *
 * <p>⚠️ <b>本门面只算签名、不发请求。</b> 你自己发请求时，本库统一出口的那些能力
 * （限流、失败退避重试、指纹与身份轮换、代理）<b>不覆盖你的请求</b>；
 * 要连带享受这些，请用既有的门面或对应 {@code service.*Service}。
 *
 * <p><b>异常边界</b>：取不到密钥 → {@link IOException}（可重试）；
 * 密钥长度不对、{@code baseUrl} 自带 query 这类<b>调用方编程错误</b> → {@link IllegalArgumentException}
 * （重试无用，当场失败比得到一个"能发出去但服务端不认"的签名好查得多）。
 * 与既有门面一致：边界处<b>不让运行时业务异常逃出去</b>。
 *
 * @author 饿死的流浪猫
 */
public class Wbi {

    /**
     * 给参数签名，{@code wts} 取<b>当前秒</b>。
     *
     * @param params 原始请求参数；值为 {@code null} 的项会被跳过，名为 {@code w_rid} 的项会被忽略；
     *               可为 {@code null}（等同空表）
     * @return 可直接拼到 URL 上的 query 串，形如 {@code mid=946974&wts=<秒>&w_rid=<md5>}
     * @throws IOException 取不到 WBI 密钥（nav 不可达、正被风控、响应形状已变，或正处于失败冷却期）
     */
    public String signQuery(Map<String, String> params) throws IOException {
        WbiKeyStore.WbiKeys keys = requireKeys();
        return WbiSigner.sign(params, keys.imgKey(), keys.subKey());
    }

    /**
     * 给参数签名，<b>时间戳由调用方给定</b>。
     *
     * <p>这个重载存在的意义是<b>可判定</b>：{@code wts} 一固定，{@code w_rid} 就是确定值，
     * 于是"签名算得对不对"可以离线比对已知答案，而不是只能断言"看着像 32 位 hex"。
     * 需要复现某次请求、或要让多方算出同一个签名时也用它。
     *
     * @param params 原始请求参数（同 {@link #signQuery(Map)}）
     * @param wts    秒级 Unix 时间戳
     * @return 签名后的 query 串（{@code wts} 按排序位置参与，{@code w_rid} 恒在末尾）
     * @throws IOException 取不到 WBI 密钥（原因同 {@link #signQuery(Map)}）
     */
    public String signQuery(Map<String, String> params, long wts) throws IOException {
        WbiKeyStore.WbiKeys keys = requireKeys();
        return WbiSigner.sign(params, keys.imgKey(), keys.subKey(), wts);
    }

    /**
     * <b>离线签名</b>：密钥由调用方自带，<b>一次出站都不发</b>。
     *
     * <p>用在两种场合：
     * <ol>
     *   <li>你手上已经有 {@code img_key} / {@code sub_key}（例如从自己的浏览器里取的），
     *       不想让本库去打 {@code nav}；</li>
     *   <li>{@code nav} 暂时取不到（被风控 / 网络不通），但你有别的方式拿到密钥 ——
     *       这条路能绕开 {@code nav} 继续工作。</li>
     * </ol>
     *
     * <p>不声明 {@code throws IOException}：它没有任何 I/O，逼迫调用方 catch 一个永不抛出的
     * 受检异常只会让签名调用被迫塞进 try 块。
     *
     * @param params 原始请求参数（同 {@link #signQuery(Map)}）
     * @param imgKey {@code nav} 的 {@code data.wbi_img.img_url} 的文件名（去掉 {@code .png}）
     * @param subKey {@code nav} 的 {@code data.wbi_img.sub_url} 的文件名（去掉 {@code .png}）
     * @param wts    秒级 Unix 时间戳
     * @return 签名后的 query 串
     * @throws IllegalArgumentException 两个 key 拼接后不足 64 字符
     *         —— 长度不对时任何"容错"都只会得到一个<b>算得出来但服务端不认</b>的签名，不如当场失败
     */
    public String signQuery(Map<String, String> params, String imgKey, String subKey, long wts) {
        return WbiSigner.sign(params, imgKey, subKey, wts);
    }

    /**
     * 把签名后的 query 直接挂到 URL 上，返回<b>可以发出去</b>的完整地址。
     *
     * <p>比"自己 {@code baseUrl + "?" + signQuery(params)}"多的是一道拦截：
     * {@code baseUrl} 里若已经带了 query，这里会<b>当场报错</b>而不是安静地漏签
     * （漏签的表现是 {@code -403}，从响应侧完全看不出是"少签了几个参数"）。
     *
     * @param baseUrl 接口地址，<b>不能带 query</b>（如 {@code https://api.bilibili.com/x/space/wbi/acc/info}）
     * @param params  全部请求参数（必须一个不漏；同 {@link #signQuery(Map)} 的规则）
     * @return 完整 URL
     * @throws IllegalArgumentException {@code baseUrl} 为空、或已经带 {@code ?}
     * @throws IOException              取不到 WBI 密钥
     */
    public String signedUrl(String baseUrl, Map<String, String> params) throws IOException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        if (baseUrl.indexOf('?') >= 0) {
            throw new IllegalArgumentException("baseUrl 不能自带 query（检测到 \"?\"）："
                    + "签名必须覆盖全部参数，URL 里再带参数就会漏签，而漏签只会得到 -403、"
                    + "从响应侧查不出来。请把那些参数一并放进 params。实际：" + baseUrl);
        }
        return baseUrl + "?" + signQuery(params);
    }

    /**
     * 丢弃缓存的密钥，强制下次签名重新取一份。
     *
     * <p>用在哪：签名被服务端拒（{@code -403} 或响应里出现 {@code v_voucher}）而参数确认无误时
     * —— 那说明本地这份密钥与服务端当前的已经不一致（跨天边界、或 B 站临时轮换）。
     * 本门面内部遇到被拒也<b>会自己重取一次</b>；这个方法留给"你自己发请求、自己看到被拒"的场合。
     */
    public void invalidateKeys() {
        WbiKeyStore.invalidate();
    }

    /**
     * 取当天的密钥，取不到就按门面口径抛 {@link IOException}。
     *
     * <p>{@code WbiKeyStore#get()} 刻意用"返回 null"表达失败（好让内部区分可重试与不可重试），
     * 而门面边界不许让这种状态静默传出去 —— 拿 null 去签名只会得到一个更难查的空指针。
     */
    private static WbiKeyStore.WbiKeys requireKeys() throws IOException {
        WbiKeyStore.WbiKeys keys = WbiKeyStore.get();
        if (keys == null) {
            throw new IOException("取不到 WBI 密钥（nav 不可达、正被风控，或响应形状已变）；"
                    + "本门面已进入 " + (WbiKeyStore.FAILURE_COOLDOWN_MS / 1000) + " 秒失败冷却，稍后重试即可。"
                    + "若你手上已有 img_key / sub_key，可用四参重载离线签名，绕开 nav。");
        }
        return keys;
    }
}

package com.esdllm.bilibiliApi.sign;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import com.esdllm.bilibiliApi.http.BilibiliHttp;
import kong.unirest.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WBI 密钥（{@code img_key} / {@code sub_key}）的<b>懒加载 + 缓存</b>。
 *
 * <p>密钥每天更替，所以"取一次永久用"迟早会失效；而"每次签名都取一次"又白白多打一次
 * {@code nav}（B 站对连发请求敏感，本库的限流也正是为此）。折中是<b>一天取一次</b>：
 * 拿到当天第一份密钥后就缓存，跨自然日自动刷新。命中缓存的 {@link #get()} 不产生任何出站。
 *
 * <p><b>🔴 判空判 {@code wbi_img}，不能判 {@code code}</b>：未登录时 {@code nav} 返回
 * {@code code=-101}（"账号未登录"），但 {@code data.wbi_img} <b>照常有值</b> ——
 * 密钥与登录态完全无关。按 {@code code != 0} 判"取不到 key"会让所有匿名场景都用不了签名
 * （而匿名签名恰恰是本批最大的收益：{@code acc/info} 匿名带签名就能拿到完整数据）。
 *
 * <p><b>失败一律返回 {@code null}，不抛异常</b>。理由与 {@code Login#getCredentialStatus()}
 * 一致：调用方要能区分"密钥取不到（可重试）"与"参数错（重试无用）"。真正无法继续时，
 * 由 {@code BilibiliHttp#getSigned} 抛带原因的业务异常。
 *
 * <p><b>失败冷却</b>：连续失败时（例如出口正被风控），每个请求都去撞一次 {@code nav} 只会让
 * 情况更糟。因此失败后 30 秒内直接快失败，不再发请求。
 *
 * <p>本类<b>不落盘</b>、不读系统属性 —— 密钥是服务端公开下发的公共值，但没有持久化的必要：
 * 进程重启后第一次签名多打一次 {@code nav}，成本可忽略。
 *
 * @author 饿死的流浪猫
 */
@Slf4j
public final class WbiKeyStore {

    /**
     * 一份密钥 + 它的获取时刻（毫秒）。
     *
     * @param imgKey          {@code wbi_img.img_url} 的文件名（去扩展名）
     * @param subKey          {@code wbi_img.sub_url} 的文件名（去扩展名）
     * @param fetchedAtMillis 取到的本机时刻，仅供日志与排障
     */
    public record WbiKeys(String imgKey, String subKey, long fetchedAtMillis) {

        /** 一行摘要（<b>密钥是公共值、不是凭据</b>，可以打全；但日志里仍截断到前 8 位便于比对） */
        public String summary() {
            return "imgKey=" + head(imgKey) + "…, subKey=" + head(subKey) + "…";
        }

        private static String head(String text) {
            return text == null || text.length() <= 8 ? text : text.substring(0, 8);
        }
    }

    /**
     * 刷新判据用的时区：<b>北京时间</b>。
     *
     * <p>密钥由 B 站按<b>它自己的自然日</b>轮换，而"今天"在部署机器上可能是另一个日期
     * （时区不同 / 服务器在境外）。用北京时间只是为了让缓存边界与轮换边界对齐 ——
     * 万一对不齐，最多是多刷新一次，不会出错。
     */
    private static final ZoneId KEY_ZONE = ZoneId.of("Asia/Shanghai");

    /** 失败冷却时长（毫秒）：失败后这段时间内不再尝试取密钥 */
    public static final long FAILURE_COOLDOWN_MS = 30_000L;

    /** 缓存项：密钥 + 它属于哪一天（按 {@link #KEY_ZONE} 计算的天序号） */
    private record CacheEntry(WbiKeys keys, long epochDay) {
    }

    private static final Object LOCK = new Object();

    private static volatile CacheEntry entry;

    /** 最近一次失败的时刻（毫秒）；0 表示"没有未冷却的失败" */
    private static final AtomicLong lastFailureAt = new AtomicLong(0L);

    private WbiKeyStore() {
        throw new AssertionError("static-only key store; do not instantiate");
    }

    /**
     * 取当天的密钥，必要时先打一次 {@code nav}。
     *
     * @return 密钥；<b>取不到时返回 {@code null}</b>（网络失败、响应形状变了、或处于失败冷却期内）
     */
    public static WbiKeys get() {
        long today = LocalDate.now(KEY_ZONE).toEpochDay();
        CacheEntry current = entry;
        if (current != null && current.epochDay() == today) {
            return current.keys();
        }

        long failedAt = lastFailureAt.get();
        if (failedAt != 0L && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
            log.debug("WBI 密钥正处于失败冷却期（距上次失败 {}ms），本轮不再请求 nav",
                    System.currentTimeMillis() - failedAt);
            return null;
        }

        synchronized (LOCK) {
            // 双重检查：等锁期间可能已有线程取好了
            CacheEntry fresh = entry;
            if (fresh != null && fresh.epochDay() == today) {
                return fresh.keys();
            }
            WbiKeys fetched = fetch();
            if (fetched == null) {
                lastFailureAt.set(System.currentTimeMillis());
                return null;
            }
            lastFailureAt.set(0L);
            entry = new CacheEntry(fetched, today);
            log.info("已取得当天的 WBI 密钥：{}（当天内复用，不再请求 nav）", fetched.summary());
            return fetched;
        }
    }

    /**
     * 丢弃缓存，强制下次 {@link #get()} 重新取。
     *
     * <p>用在哪：签名被服务端拒（{@code -403} / {@code v_voucher}）时 —— 那说明本地缓存与
     * 服务端的当前密钥已经不一致（换日边界、或 B 站临时轮换）。见
     * {@code BilibiliHttp#getSigned} 的"重签一次"。
     */
    public static void invalidate() {
        synchronized (LOCK) {
            entry = null;
            lastFailureAt.set(0L);
        }
    }

    /** 当前是否已有当天缓存（不发请求）。测试与排障用。 */
    public static boolean isCached() {
        CacheEntry current = entry;
        return current != null && current.epochDay() == LocalDate.now(KEY_ZONE).toEpochDay();
    }

    /**
     * 打一次 {@code nav} 并解析出密钥。
     *
     * @return 密钥；任何一步不成立都返回 {@code null}（并留下一行可定位的日志）
     */
    private static WbiKeys fetch() {
        String body;
        try {
            HttpResponse<String> response = BilibiliHttp.get(
                    BilibiliEndpoint.navUrl, BilibiliEndpoint.jsonAccept, BilibiliEndpoint.referer);
            body = response.getBody();
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.warn("取 WBI 密钥失败：nav 返回 HTTP {}（原文前 120 字：{}）",
                        response.getStatus(), brief(body));
                return null;
            }
        } catch (RuntimeException e) {
            // 纯网络失败（一次响应都没拿到）：退避重试已经在 BilibiliHttp 内部做过，这里只记录
            log.warn("取 WBI 密钥失败：nav 请求异常（{}）", e.toString());
            return null;
        }
        WbiKeys keys = parseNav(body);
        if (keys == null) {
            log.warn("取 WBI 密钥失败：nav 响应里没有 data.wbi_img（原文前 200 字：{}）", brief(body));
        }
        return keys;
    }

    /**
     * 从 {@code nav} 的响应体里解出密钥（<b>唯一判据是 {@code data.wbi_img} 是否在</b>）。
     *
     * <p>包可见，便于单测直接喂样本 —— 这一段的分支（{@code -101} 但有 key、字段缺失、
     * 非 JSON、URL 形状变了）都值得逐个锁住。
     *
     * @param body nav 的响应原文，可为 null
     * @return 密钥；解析不出时 {@code null}
     */
    static WbiKeys parseNav(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JSONObject wbiImg;
        try {
            JSONObject root = JSON.parseObject(body);
            if (root == null) {
                return null;
            }
            JSONObject data = root.getJSONObject("data");
            wbiImg = data == null ? null : data.getJSONObject("wbi_img");
        } catch (Exception e) {
            return null;
        }
        if (wbiImg == null) {
            return null;
        }
        String imgKey = keyOf(wbiImg.getString("img_url"));
        String subKey = keyOf(wbiImg.getString("sub_url"));
        if (imgKey == null || subKey == null) {
            return null;
        }
        return new WbiKeys(imgKey, subKey, System.currentTimeMillis());
    }

    /**
     * 从 {@code https://i0.hdslb.com/bfs/wbi/<32位hex>.png} 里截出文件名（去扩展名）。
     *
     * <p>两处防御：先砍掉 query / fragment（形状哪天带上参数也不会截错），
     * 再取最后一段路径并去掉最后一个 {@code .} 之后的部分。
     *
     * @param url {@code img_url} / {@code sub_url}
     * @return 密钥；形状不符时 {@code null}
     */
    private static String keyOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String path = url.trim();
        int cut = path.length();
        int query = path.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        int hash = path.indexOf('#');
        if (hash >= 0 && hash < cut) {
            cut = hash;
        }
        path = path.substring(0, cut);

        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.isEmpty() ? null : name;
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }
}

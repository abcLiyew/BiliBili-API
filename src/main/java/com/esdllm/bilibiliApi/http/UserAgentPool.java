package com.esdllm.bilibiliApi.http;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;

/**
 * User-Agent 池：身份轮换时随设备指纹一起换，保证<b>同一代身份自洽</b>。
 *
 * <p><b>先说清楚它的分量</b>：实测结论是 B 站动态类端点的风控<b>主要看 buvid3，
 * 不看 UA</b>（同一 UA 带/不带指纹结果相反；而换 UA 不解决问题）。
 * 所以这个池子<b>不是</b>用来"靠换 UA 绕过风控"的 —— 那种做法只会得到随机噪声。
 * 它存在的唯一理由是：轮换身份时若只换指纹、不换 UA，
 * 会出现"新指纹 + 旧指纹留下的 UA"这种自相矛盾的组合，
 * 而这种不一致本身才是可疑特征。
 *
 * <p>{@link #at(int)} 的下标 0 刻意与 {@link BilibiliEndpoint#userAgent} 逐字相同，
 * 因此<b>不轮换时行为与改造前完全一致</b>（默认第一代即原 UA）。
 */
public final class UserAgentPool {

    /** 真实浏览器 UA：Windows Edge / Windows Chrome / macOS Chrome / macOS Safari */
    private static final String[] AGENTS = {
            BilibiliEndpoint.userAgent,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.4 Safari/605.1.15",
    };

    private UserAgentPool() {
    }

    /**
     * 默认面孔：Windows Edge，与 {@link BilibiliEndpoint#userAgent} 逐字一致（池中第 0 个）。
     *
     * <p>⚠️ 版本号停在 {@code Edg/131}（2024 年末）。当<b>匿名</b>面孔够用，
     * 但<b>登录</b>时建议换成你本机真实浏览器的版本 —— 见
     * {@link HttpPolicy#setUserAgent(String)} 与 {@link #edgeWindows(String)}。
     */
    public static final String EDGE_WINDOWS = AGENTS[0];

    /** Windows Edge 的 UA 模板：两个 {@code %s} 分别是 Chrome 与 Edge 的版本（真实 Edge 上两者一致） */
    private static final String EDGE_WINDOWS_TEMPLATE =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/%s Safari/537.36 Edg/%s";

    /**
     * 按版本号拼一个 Windows Edge 的 UA。
     *
     * <p>用途：让库发的 UA 跟上你本机真实浏览器的版本，而不是一直停在 {@code Edg/131}。
     * <b>取版本号最省事的办法</b>是直接在浏览器里读整串 ——
     * 地址栏敲 {@code javascript:navigator.userAgent} 回车，把结果给
     * {@link HttpPolicy#setUserAgent(String)}；如果你只想给版本号，用本方法。
     *
     * <p>注意 Edge 的"关于"页显示的是 Edge 自己的版本，而 UA 里的版本号取自 Chromium，
     * 两者可能差一位 —— 要精确就用上面那个读整串的办法。
     *
     * @param version 版本号，如 {@code "140.0.0.0"}；空白时返回 {@link #EDGE_WINDOWS}
     * @return UA 字符串
     */
    public static String edgeWindows(String version) {
        if (version == null || version.isBlank()) {
            return EDGE_WINDOWS;
        }
        String v = version.trim();
        return String.format(EDGE_WINDOWS_TEMPLATE, v, v);
    }

    /** 池大小 */
    public static int size() {
        return AGENTS.length;
    }

    /**
     * 按序号取 UA（自动取模，永不越界）。
     *
     * @param index 序号，可为任意整数
     * @return UA 字符串
     */
    public static String at(int index) {
        return AGENTS[Math.floorMod(index, AGENTS.length)];
    }

    /** 池中第 0 个（= 改造前的原 UA），用于"是否发生轮换"的对照 */
    public static String defaultAgent() {
        return AGENTS[0];
    }
}

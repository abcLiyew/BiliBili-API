package com.esdllm.bilibiliApi.parse;

/**
 * <b>bvid ⇄ aid 的纯算法换算</b>（零出站、零依赖、可离线复现）。
 *
 * <p><b>为什么需要它</b>：{@code bvid} 本来就是 {@code aid} 的 base58 编码，两者一一对应，
 * <b>不需要请求任何接口</b>。而本库多处"手里只有 bvid、端点却要 aid"（典型：评论要 {@code oid=aid}），
 * 此前一律靠<b>多打一次 {@code x/web-interface/view} 换 {@code aid}</b> —— 每次多一次出站。
 * 有了本类，那条路径<b>全省掉</b>。
 *
 * <p><b>格式</b>：{@code bvid} 恒为 12 字符 —— 前 3 位固定 {@code "BV1"}，后 9 位是 base58 结果。
 * 码表刻意<b>不含</b> {@code 0} / 大写 {@code I} / {@code O} / 小写 {@code l}（避免与形近字混淆），
 * 所以"含这四个字符"的输入一定是错的，本类据此做校验。
 *
 * <p><b>算法</b>（2020 年 av→BV 那版，与 {@code _docs/misc__bvid_desc.md} 的
 * JavaScript 实现同源；文档里的 Python 实现是等价的另一种写法，见本节末尾的推导）：
 * <pre>
 * 常量  XOR_CODE  = 23442827791579
 *       MASK_CODE = 2251799813685247        // 2^51 - 1
 *       MAX_AID   = 1 &lt;&lt; 51
 *       BASE      = 58
 *       码表      = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
 *
 * aid → bvid：把 (MAX_AID | aid) ^ XOR_CODE 反复对 58 取余，从<b>末位往前</b>填满 9 个数据位，
 *             再交换(完整串的第 3 位 ↔ 第 9 位)、(第 4 位 ↔ 第 7 位)。
 * bvid → aid：先做同样两次交换，丢掉前 3 位，把 9 位当成 58 进制数从左到右累加，
 *             最后 (值 &amp; MASK_CODE) ^ XOR_CODE。
 * </pre>
 *
 * <p>🔴 <b>两次字符交换是"漏了也只会错一部分"的那种坑</b>：交换位是在<b>完整 12 字符串</b>上算的
 * （不是 9 位数据串），而且交换的是<b>位置</b>不是值 —— 当两个位置碰巧是同一个字符时它<b>没有任何效果</b>
 * （实测 {@code BV17x411w7KC} 的第 3、9 位都是 {@code '7'}，交换与否结果相同）。所以"拿一两个样例试通"
 * <b>不能证明实现正确</b>，必须用<b>交换位字符不同</b>的样例钉住 —— {@code BvCodeTest} 里专门为此留了用例。
 *
 * <p>🔴 <b>最大风险 = 静默算错</b>：算错了不会抛异常，只会让下游拿着一个"格式合法但内容错误"的 {@code aid}
 * 去请求，然后拿到空列表（与"这个东西真的没有数据"完全同形）。因此本类的测试有两条硬要求：
 * ① 与<b>真机</b> {@code view} 返回的 {@code aid} 交叉校验；② 覆盖交换位。
 *
 * <p>📌 <b>与 Python 实现等价的推导</b>（免得后人以为两份文档对不上）：Python 版用
 * {@code ENCODE_MAP = (8,7,0,5,1,3,2,4,6)} 直接摆放各位，把它的下标代进"先顺序填 9 位、再交换"的过程
 * 可以得到同一张映射表 ⇒ <b>两种写法产出完全相同</b>。
 *
 * @author 饿死的流浪猫
 */
public final class BvCode {

    /** 固定异或值 */
    private static final long XOR_CODE = 23442827791579L;

    /** 取值掩码：{@code 2^51 - 1}（把编码时置上的第 51 位抹掉） */
    private static final long MASK_CODE = 2251799813685247L;

    /** avid 上限 {@code 2^51}（编码时按位或上去，解码时被 {@link #MASK_CODE} 抹掉） */
    private static final long MAX_AID = 1L << 51;

    /** base58 进制 */
    private static final long BASE = 58L;

    /** base58 码表（不含 {@code 0} / {@code I} / {@code O} / {@code l}） */
    private static final String ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";

    /** {@code bvid} 固定长度 */
    private static final int BV_LEN = 12;

    private BvCode() {
    }

    /**
     * <b>bvid → aid</b>。
     *
     * @param bvid BV 号，形如 {@code BV1L9Uoa9EUx}（12 字符）
     * @return 对应的 {@code aid}（正整数）
     * @throws IllegalArgumentException {@code bvid} 为空、长度不是 12、不以 {@code BV} 开头，
     *                                  或含 base58 码表以外的字符（{@code 0} / {@code I} / {@code O} / {@code l}）
     */
    public static long toAid(String bvid) {
        if (bvid == null || bvid.isBlank()) {
            throw new IllegalArgumentException("bvid 不能为空");
        }
        String s = bvid.trim();
        if (s.length() != BV_LEN || !s.startsWith("BV")) {
            throw new IllegalArgumentException(
                    "bvid 格式不对（应形如 BV1L9Uoa9EUx，12 字符且以 BV 开头）：" + bvid);
        }
        char[] chars = s.toCharArray();
        swap(chars, 3, 9);
        swap(chars, 4, 7);

        long tmp = 0;
        for (int i = 3; i < BV_LEN; i++) {
            int idx = ALPHABET.indexOf(chars[i]);
            if (idx < 0) {
                throw new IllegalArgumentException(
                        "bvid 含非法字符 '" + chars[i] + "'（base58 码表不含 0 / I / O / l）：" + bvid);
            }
            tmp = tmp * BASE + idx;
        }
        return (tmp & MASK_CODE) ^ XOR_CODE;
    }

    /**
     * <b>aid → bvid</b>。
     *
     * @param aid 稿件 avid（{@code 1 .. 2^51 - 1}）
     * @return 对应的 BV 号（12 字符）
     * @throws IllegalArgumentException {@code aid} 不在 {@code [1, 2^51)} 内
     */
    public static String toBvid(long aid) {
        if (aid <= 0 || aid >= MAX_AID) {
            throw new IllegalArgumentException(
                    "aid 超出可编码范围 [1, " + (MAX_AID - 1) + "]：" + aid);
        }
        char[] bytes = {'B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0'};
        long tmp = (MAX_AID | aid) ^ XOR_CODE;
        int idx = BV_LEN - 1;
        while (tmp > 0) {
            bytes[idx--] = ALPHABET.charAt((int) (tmp % BASE));
            tmp /= BASE;
        }
        swap(bytes, 3, 9);
        swap(bytes, 4, 7);
        return new String(bytes);
    }

    private static void swap(char[] arr, int i, int j) {
        char t = arr[i];
        arr[i] = arr[j];
        arr[j] = t;
    }
}

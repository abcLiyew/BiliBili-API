package com.esdllm.bilibiliApi.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>{@link BvCode} 的回归测试</b>（B5 批，2026-09-23）。
 *
 * <p>🔴 <b>为什么这个类的测试值得写这么细</b>：{@code bvid ⇄ aid} 是<b>纯算法</b>，
 * 算错了<b>不会抛任何异常</b> —— 它只会让下游拿着一个"格式合法、内容错误"的 aid 去请求，
 * 然后收到一个空列表（与"这个东西真的没有数据"完全同形，无法区分）。
 * 所以这里的每一条断言都不是"跑通就行"，而是针对一个具体的写错方式：
 *
 * <table border="1">
 *   <caption>本文件要挡住的错法</caption>
 *   <tr><th>写错方式</th><th>症状</th><th>由哪个用例挡</th></tr>
 *   <tr><td>漏掉两次字符交换</td><td>整表全错，但"随便抓一个样例"仍可能通过</td>
 *       <td>{@link SwapPositionTest}</td></tr>
 *   <tr><td>交换位算在 9 位数据串上（而不是完整 12 串）</td><td>整表错位</td>
 *       <td>{@link SwapPositionTest#documentsTheTrapSample}</td></tr>
 *   <tr><td>aid→bvid 时从高位开始填</td><td>整表反了</td>
 *       <td>{@link RoundTripTest}</td></tr>
 *   <tr><td>忘了 {@code & MASK_CODE} / {@code ^ XOR_CODE}</td><td>高位出现天文数字</td>
 *       <td>{@link GoldenVectorTest#goldenPairFromDocumentation}（值就摆在那里）</td></tr>
 * </table>
 *
 * <p>📌 <b>能当锚点的只有真机数据</b>：下面"真机样本"四个 aid 全部来自 2026-09-23 实测
 * {@code x/web-interface/view} 的 {@code data.aid}（见 {@code _b5_preflight_report.txt}），
 * 而 B 站文档里的定值只作<b>对照</b>用 —— 与 {@code WbiSigner} 的教训同源：
 * 演示输出段不许当锚点。
 */
@DisplayName("parse：BvCode（bvid ⇄ aid 纯算法）")
class BvCodeTest {

    /** 文档/社区实现里公开的黄金向量，用来做"与外部实现同源"的交叉验证 */
    private static final long GOLDEN_AID = 111298867365120L;
    private static final String GOLDEN_BVID = "BV1L9Uoa9EUx";

    /** aid 可编码上限（不含），即 {@code 2^51} */
    private static final long MAX_AID = 1L << 51;

    // ================================================================
    // 正向/逆向的定值
    // ================================================================

    @Nested
    @DisplayName("定值：bvid → aid")
    class GoldenVectorTest {

        @Test
        @DisplayName("★ 文档公开的黄金向量（外部实现同源，可离线复现）")
        void goldenPairFromDocumentation() {
            assertEquals(GOLDEN_AID, BvCode.toAid(GOLDEN_BVID));
            assertEquals(GOLDEN_BVID, BvCode.toBvid(GOLDEN_AID));
        }

        @Test
        @DisplayName("★ 真机交叉校验：四个 aid 全部来自 2026-09-23 实测 view 响应")
        void realWorldSamples() {
            assertEquals(80433022L, BvCode.toAid("BV1GJ411x7h7"),
                    "该值来自实测 view?bvid=BV1GJ411x7h7 的 data.aid");
            assertEquals(170001L, BvCode.toAid("BV17x411w7KC"),
                    "该值来自实测 view?bvid=BV17x411w7KC 的 data.aid");
            assertEquals(349L, BvCode.toAid("BV1xx411c7DS"),
                    "★ 这是 B 站第一个视频（aid=349 / av2/av349 体系起点），实测 view 给的就是它");
            assertEquals(111298867365120L, BvCode.toAid("BV1L9Uoa9EUx"));
        }

        @Test
        @DisplayName("aid → bvid：同样的四个样本反向也要对")
        void reverseSamples() {
            assertEquals("BV1GJ411x7h7", BvCode.toBvid(80433022L));
            assertEquals("BV17x411w7KC", BvCode.toBvid(170001L));
            assertEquals("BV1xx411c7DS", BvCode.toBvid(349L));
            assertEquals("BV1L9Uoa9EUx", BvCode.toBvid(111298867365120L));
        }

        @Test
        @DisplayName("bvid 恒为 12 字符、恒以 BV1 开头（aid 1 与 2 也要满足）")
        void lengthIsAlwaysTwelve() {
            for (long aid : new long[]{1L, 2L, 349L, 80433022L, GOLDEN_AID, MAX_AID - 1}) {
                String bvid = BvCode.toBvid(aid);
                assertEquals(12, bvid.length(), "aid=" + aid + " 得到 " + bvid);
                assertTrue(bvid.startsWith("BV1"), "aid=" + aid + " 得到 " + bvid);
            }
        }
    }

    // ================================================================
    // 交换位：本类最容易"漏了也看不出来"的地方
    // ================================================================

    /**
     * 🔴 <b>两次字符交换是这个算法最阴的一环</b>：交换的是<b>位置</b>而不是值 ——
     * 当两个位置的字符碰巧相同时，交换与否<b>结果完全一样</b>。
     *
     * <p>所以"拿一两个样例跑通"<b>证明不了实现正确</b>：{@code BV17x411w7KC}
     * 的第 3、9 位都是 {@code '7'}，它就<b>检测不出</b>漏掉 {@code swap(3, 9)}。
     * 本组用例把这件事显式钉住：一边用"必须能查出差异"的样例做正例，
     * 一边把那根"看不见的刺"（同一个样例）也写下来。
     */
    @Nested
    @DisplayName("字符交换位（漏了只会错一部分，所以最容易漏）")
    class SwapPositionTest {

        /**
         * 故意<b>不做交换</b>的对照实现。
         *
         * <p>写成测试内的局部方法（而不是去改生产代码）是为了让"交换到底有没有用"
         * 变成一个可执行的断言，而不是注释里的一句提醒。
         */
        private static long toAidWithoutSwap(String bvid) {
            String alphabet = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";
            long tmp = 0;
            for (int i = 3; i < 12; i++) {
                tmp = tmp * 58 + alphabet.indexOf(bvid.charAt(i));
            }
            return (tmp & ((1L << 51) - 1)) ^ 23442827791579L;
        }

        @Test
        @DisplayName("★ 正例：交换位字符都不同的样本，漏掉交换会算出完全不同的 aid")
        void swapActuallyMatters() {
            for (String bvid : new String[]{"BV1L9Uoa9EUx", "BV1GJ411x7h7", "BV1xx411c7DS"}) {
                assertNotEquals(toAidWithoutSwap(bvid), BvCode.toAid(bvid),
                        "★ " + bvid + " 的交换位字符不同，交换与否必须给出<b>不同</b>的结果 —— "
                                + "两者相等说明生产实现根本没做交换");
            }
        }

        @Test
        @DisplayName("★ 陷阱样本：BV17x411w7KC 的第 3、9 位都是 '7' ⇒ 它检测不出漏掉 swap(3,9)")
        void documentsTheTrapSample() {
            String trap = "BV17x411w7KC";
            assertEquals(trap.charAt(3), trap.charAt(9),
                    "本样本第 3 位与第 9 位是同一个字符 —— 交换它<b>不产生任何变化</b>");
            assertNotEquals(trap.charAt(4), trap.charAt(7),
                    "但第 4 位与第 7 位不同，所以它仍能查出漏掉 swap(4,7)");
            assertEquals(170001L, BvCode.toAid(trap), "真值仍然要对上");
        }
    }

    // ================================================================
    // 往返
    // ================================================================

    @Nested
    @DisplayName("往返（aid → bvid → aid 必须回到原点）")
    class RoundTripTest {

        @Test
        @DisplayName("★ 边界值：1 与 2^51-1 都要往返成功")
        void boundaries() {
            assertEquals(1L, BvCode.toAid(BvCode.toBvid(1L)));
            assertEquals(MAX_AID - 1, BvCode.toAid(BvCode.toBvid(MAX_AID - 1)),
                    "二进制的全 1 值是最容易碰坏掩码的一个输入");
        }

        @Test
        @DisplayName("抽样往返：连续区间 + 几个大数")
        void samples() {
            for (long aid = 1; aid <= 200; aid++) {
                assertEquals(aid, BvCode.toAid(BvCode.toBvid(aid)), "aid=" + aid);
            }
            for (long aid : new long[]{349L, 100000L, 80433022L, 170001L,
                    GOLDEN_AID, MAX_AID / 2, MAX_AID - 2}) {
                assertEquals(aid, BvCode.toAid(BvCode.toBvid(aid)), "aid=" + aid);
            }
        }

        @Test
        @DisplayName("★ aid 1 与 2 的 bvid 只差最后一位（不是「看着差不多」，是真的只差一个字符）")
        void adjacentAids() {
            String one = BvCode.toBvid(1L);
            String two = BvCode.toBvid(2L);
            assertEquals(one.substring(0, 11), two.substring(0, 11));
            assertNotEquals(one.charAt(11), two.charAt(11));
        }
    }

    // ================================================================
    // 非法输入
    // ================================================================

    @Nested
    @DisplayName("非法输入：抛 IllegalArgumentException（不是静默返回一个错值）")
    class InvalidInputTest {

        @Test
        @DisplayName("bvid 为空 / 空白 / null")
        void blankBvid() {
            assertThrows(IllegalArgumentException.class, () -> BvCode.toAid(null));
            assertThrows(IllegalArgumentException.class, () -> BvCode.toAid(""));
            assertThrows(IllegalArgumentException.class, () -> BvCode.toAid("   "));
        }

        @Test
        @DisplayName("长度不是 12：含多了少了两种")
        void wrongLength() {
            assertThrows(IllegalArgumentException.class, () -> BvCode.toAid("BV1L9Uoa9EU"));
            assertThrows(IllegalArgumentException.class, () -> BvCode.toAid("BV1L9Uoa9EUxx"));
        }

        @Test
        @DisplayName("不以 BV 开头")
        void wrongPrefix() {
            assertThrows(IllegalArgumentException.class, () -> BvCode.toAid("AV1L9Uoa9EUx"));
        }

        @Test
        @DisplayName("★ 含 base58 码表以外的字符：0 / I / O / l 这四个是码表刻意排除的形近字")
        void illegalAlphabet() {
            for (char bad : new char[]{'0', 'I', 'O', 'l'}) {
                String bvid = "BV1L9Uoa9EU" + bad;
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> BvCode.toAid(bvid), "字符 '" + bad + "' 不该被接受：" + bvid);
                assertTrue(e.getMessage().contains(String.valueOf(bad)),
                        "文案要指出是哪个字符：" + e.getMessage());
            }
        }

        @Test
        @DisplayName("aid ≤ 0 与 aid ≥ 2^51 都超界")
        void aidOutOfRange() {
            assertThrows(IllegalArgumentException.class, () -> BvCode.toBvid(0L));
            assertThrows(IllegalArgumentException.class, () -> BvCode.toBvid(-1L));
            assertThrows(IllegalArgumentException.class, () -> BvCode.toBvid(MAX_AID),
                    "★ 上界是<b>不含</b> 2^51 —— 编码时会 or 上这一位，等于它自己就溢出了");
        }

        @Test
        @DisplayName("空白包裹的合法 bvid 会被 trim 后接受（不因为首尾空格就报废）")
        void toleratesSurroundingSpace() {
            assertEquals(80433022L, BvCode.toAid("  BV1GJ411x7h7  "));
        }
    }
}

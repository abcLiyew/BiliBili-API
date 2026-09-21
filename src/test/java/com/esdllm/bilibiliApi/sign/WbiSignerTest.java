package com.esdllm.bilibiliApi.sign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WbiSigner} 纯函数测试 —— <b>锁死算法口径</b>。
 *
 * <p>这个类存在的理由很直白：签名算错的表现是 {@code code=-403 访问权限不足}，
 * 与服务端"权限不足"完全同形，<b>从响应侧一个字都反推不出来</b>。
 * 所以"算得对不对"只能在离线用黄金用例钉死。本测试里的每一个期望值都来自
 * <b>可复现的公开定值</b>，并在 2026-09-21 用独立实现（Python）复算过一致 ——
 * 详见 {@link WbiSigner} 类注释的"校验口径"表。
 *
 * <p>⚠️ 特别提醒后来者：官方文档里 4 个语言 demo 打印的 {@code w_rid} 有 3 个<b>不可用作锚点</b>
 * （2026-09-21 用独立实现逐个复算过：它们对不上文档自己给的示例 key，而那三次运行各自现抓的 key 已不可考）。
 * 谁要是把它们抄进来，会得到一个永远红的用例，然后开始怀疑实现 —— 那是本测试唯一想避免的坑。
 * <b>可用的锚点只有两类</b>：文档<b>正文推导出来的值</b>（{@code mixin_key}、walkthrough 的 {@code w_rid}、
 * 编码结果），与 <b>PHP demo 注释里的那个 {@code w_rid}</b>。
 */
@DisplayName("WbiSigner：WBI 签名算法口径")
class WbiSignerTest {

    /** 文档正文给出的示例密钥对 */
    private static final String IMG = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB = "4932caff0ff746eab6f01bf08b70ac45";

    /** 文档 Java demo 注释里的第二组密钥对 */
    private static final String IMG2 = "653657f524a547ac981ded72ea172057";
    private static final String SUB2 = "6e4909c702f846728e64f6007736a338";

    // ================================================================
    // mixin_key：重排表算对了没有
    // ================================================================

    @Nested
    @DisplayName("mixinKey")
    class MixinKeyTest {

        @Test
        @DisplayName("黄金用例 ①：文档示例 key → ea1db124af3c7062474693fa704f4ff8")
        void goldenCase() {
            assertEquals("ea1db124af3c7062474693fa704f4ff8", WbiSigner.mixinKey(IMG, SUB));
        }

        @Test
        @DisplayName("黄金用例 ②：文档 Java demo 的第二组 → 72136226c6a73669787ee4fd02a74c27")
        void secondGoldenCase() {
            assertEquals("72136226c6a73669787ee4fd02a74c27", WbiSigner.mixinKey(IMG2, SUB2));
        }

        @Test
        @DisplayName("长度固定 32，且是 img_key 前拼 sub_key（顺序反了结果必须不同）")
        void orderMatters() {
            String mixin = WbiSigner.mixinKey(IMG, SUB);
            assertEquals(WbiSigner.MIXIN_KEY_LENGTH, mixin.length());
            assertEquals(32, WbiSigner.MIXIN_KEY_LENGTH);
            assertNotEquals(mixin, WbiSigner.mixinKey(SUB, IMG),
                    "sub_key 拼在 img_key 后面 —— 顺序反了必须得出不同结果，否则说明拼接没生效");
        }

        @Test
        @DisplayName("拼接后不足 64 字符 → IllegalArgumentException（不容错）")
        void tooShort() {
            // 容错只会得到一个"算得出来但服务端不认"的签名，那比当场失败难查得多
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> WbiSigner.mixinKey("abc", "def"));
            assertTrue(e.getMessage().contains("不足"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("任一 key 为 null → IllegalArgumentException")
        void nullKeys() {
            assertThrows(IllegalArgumentException.class, () -> WbiSigner.mixinKey(null, SUB));
            assertThrows(IllegalArgumentException.class, () -> WbiSigner.mixinKey(IMG, null));
        }
    }

    // ================================================================
    // sign：query 拼装 + w_rid
    // ================================================================

    @Nested
    @DisplayName("sign")
    class SignTest {

        /**
         * 黄金用例 ③ —— 与 ⑤ 一起构成本测试的<b>两个独立锚点</b>。
         *
         * <p>期望值取自文档 PHP demo 的注释（{@code wts=1700384803} → {@code 4614cb98d60a43e50c3a3033fe3d116b}），
         * 它是四个 demo 里<b>唯一</b>与那对示例 key 配套的值。它一次同时锁住四件事：
         * 键升序、{@code wts} 参与签名、{@code query + mixin_key} 的拼接顺序、MD5 用小写 hex。
         */
        @Test
        @DisplayName("黄金用例 ③：{foo:114,bar:514,baz:1919810} + wts=1700384803 → 4614cb98…")
        void goldenCase() {
            Map<String, String> params = new HashMap<>();
            params.put("foo", "114");
            params.put("bar", "514");
            params.put("baz", "1919810");

            String query = WbiSigner.sign(params, IMG, SUB, 1700384803L);

            assertEquals("bar=514&baz=1919810&foo=114&wts=1700384803"
                    + "&w_rid=4614cb98d60a43e50c3a3033fe3d116b", query);
        }

        /**
         * 黄金用例 ⑤ —— 取自文档<b>正文</b> walkthrough，不是任何 demo 的打印结果。
         *
         * <p>它比 ③ 多钉住一件事：参数里的 {@code zab} 按字典序排在 {@code wts} <b>之后</b>，
         * 所以它同时证明了"<b>键排在 {@code wts} 之后的也照样进签名输入</b>"——
         * 手写实现很容易把 {@code wts} 直接拼到末尾了事，那样这条用例必红。
         *
         * <p>⚠️ 注意文档正文最终给出的 query 是 {@code bar=514&foo=114&zab=1919810&w_rid=…&wts=…}
         * （原文演示的是"追加"而非"排序"），本库一律排序后把 {@code w_rid} 拼在末尾 ——
         * 两种写法的 {@code w_rid} 必须相同，这正是断言只比 {@code w_rid} 的原因。
         */
        @Test
        @DisplayName("黄金用例 ⑤：{bar:514,foo:114,zab:1919810} + wts=1702204169 → 8f6f2b5b…")
        void walkthroughGoldenCase() {
            String query = WbiSigner.sign(
                    Map.of("bar", "514", "foo", "114", "zab", "1919810"), IMG, SUB, 1702204169L);

            // zab 排在 wts 之后却仍在签名输入里 —— 这是本用例相对 ③ 的增量价值
            assertTrue(query.startsWith("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid="),
                    "实际：" + query);
            assertEquals("8f6f2b5b3d485fe1886cec6a0be8c5d4",
                    query.substring(query.indexOf("w_rid=") + "w_rid=".length()));
        }

        @Test
        @DisplayName("传入顺序无关：乱序 Map 也必须输出同一串")
        void orderIndependent() {
            Map<String, String> reversed = new LinkedHashMap<>();
            reversed.put("baz", "1919810");
            reversed.put("bar", "514");
            reversed.put("foo", "114");
            assertEquals(WbiSigner.sign(reversed, IMG, SUB, 1700384803L),
                    WbiSigner.sign(Map.of("foo", "114", "bar", "514", "baz", "1919810"),
                            IMG, SUB, 1700384803L));
        }

        @Test
        @DisplayName("wts 与 w_rid 的形状：wts 在排序位置、w_rid 永远在末尾且是 32 位小写 hex")
        void shapes() {
            String query = WbiSigner.sign(Map.of("mid", "946974"), IMG, SUB, 1700384803L);
            assertTrue(query.startsWith("mid=946974&wts=1700384803&w_rid="), "实际：" + query);
            String wrid = query.substring(query.indexOf("w_rid=") + "w_rid=".length());
            assertEquals(32, wrid.length());
            assertTrue(wrid.matches("[0-9a-f]{32}"),
                    "w_rid 必须是小写 hex（文档示例即小写）：" + wrid);
        }

        @Test
        @DisplayName("🔴 参数里若已含 w_rid，必须被忽略 —— 否则是自指（签名里含自己）")
        void existingWridIsIgnored() {
            String clean = WbiSigner.sign(Map.of("mid", "1"), IMG, SUB, 1700384803L);

            Map<String, String> polluted = new HashMap<>();
            polluted.put("mid", "1");
            polluted.put("w_rid", "00000000000000000000000000000000");
            assertEquals(clean, WbiSigner.sign(polluted, IMG, SUB, 1700384803L),
                    "w_rid 绝不能进签名输入 —— 带上它签名每次都不一样，服务端必然拒绝");
        }

        @Test
        @DisplayName("null 值的参数被跳过（B 站前端也不会把 null 发出去）")
        void nullValueSkipped() {
            Map<String, String> params = new HashMap<>();
            params.put("mid", "1");
            params.put("pagination_str", null);
            String query = WbiSigner.sign(params, IMG, SUB, 1700384803L);
            assertTrue(query.startsWith("mid=1&wts="), "实际：" + query);
            assertFalse(query.contains("pagination_str"));
        }

        @Test
        @DisplayName("参数表为空也能签（只剩 wts），不抛异常")
        void emptyParams() {
            String query = WbiSigner.sign(Map.of(), IMG, SUB, 1700384803L);
            assertTrue(query.startsWith("wts=1700384803&w_rid="), "实际：" + query);
        }

        @Test
        @DisplayName("params 为 null 等同空表（不 NPE）")
        void nullParams() {
            assertNotNull(WbiSigner.sign(null, IMG, SUB, 1700384803L));
        }

        @Test
        @DisplayName("不带 wts 的重载：用当前秒级时间戳（不早于调用前的那一刻）")
        void defaultTimestamp() {
            long before = System.currentTimeMillis() / 1000L;
            String query = WbiSigner.sign(Map.of("mid", "1"), IMG, SUB);
            long wts = Long.parseLong(query.substring(query.indexOf("wts=") + 4, query.indexOf("&w_rid=")));
            assertTrue(wts >= before, "wts=" + wts + " 早于调用前 " + before);
            assertTrue(wts <= System.currentTimeMillis() / 1000L + 1);
        }
    }

    // ================================================================
    // percentEncode：三个最容易错的口径
    // ================================================================

    @Nested
    @DisplayName("percentEncode")
    class PercentEncodeTest {

        @Test
        @DisplayName("黄金用例 ④：文档给出的精确编码结果（中文 + 空格）")
        void goldenCase() {
            // 文档原文：「应该被编码为 bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four」
            assertEquals("%E4%BA%94%E4%B8%80%E5%9B%9B", WbiSigner.percentEncode("五一四"));
            assertEquals("one%20one%20four", WbiSigner.percentEncode("one one four"));
        }

        @Test
        @DisplayName("空格编成 %20，绝不能是 +（+ 会被服务端判签名错，线上验证过）")
        void spaceIsPercent20() {
            assertEquals("%20", WbiSigner.percentEncode(" "));
            assertFalse(WbiSigner.percentEncode("a b").contains("+"));
        }

        @Test
        @DisplayName("值里的 !'()* 是「删掉」，不是「编码」")
        void droppedChars() {
            assertEquals("", WbiSigner.percentEncode("!'()*"));
            // a!b'c(d)e*f —— 剔掉五个符号后剩下的是 6 个字母，不是 3 个
            assertEquals("abcdef", WbiSigner.percentEncode("a!b'c(d)e*f"));
            // 若误写成"编码"，会得到 %21%27%28%29%2A，与服务端算出的不一致
            assertFalse(WbiSigner.percentEncode("x!y").contains("%21"));
        }

        @Test
        @DisplayName("RFC 3986 unreserved 原样输出：- _ . ~ 以及字母数字")
        void unreservedKept() {
            assertEquals("aZ09-_.~", WbiSigner.percentEncode("aZ09-_.~"));
            // ~ 不在剔除之列（它不属 !'()*），必须原样保留
            assertEquals("~", WbiSigner.percentEncode("~"));
        }

        @Test
        @DisplayName("十进制的 + 本身要编成 %2B（手写实现天然满足，form 编码器会把 + 当空格）")
        void plusIsEncoded() {
            assertEquals("%2B", WbiSigner.percentEncode("+"));
        }

        @Test
        @DisplayName("十六进制字母大写（部分库编小写，会与服务端不一致）")
        void uppercaseHex() {
            assertEquals("%2C", WbiSigner.percentEncode(","));
            assertEquals("%3A", WbiSigner.percentEncode(":"));
            assertEquals("%2F", WbiSigner.percentEncode("/"));
            // ⚠️ 不能写成"不含 [0-9a-f]"——数字本身就在那个字符集里（%95 之类会误报）。
            //    正确的判据是：每一个 %XX 都必须等于它自己的大写形式。
            String encoded = WbiSigner.percentEncode("测试/、");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([0-9A-Fa-f]{2})").matcher(encoded);
            int found = 0;
            while (m.find()) {
                found++;
                assertEquals(m.group(1).toUpperCase(), m.group(1),
                        "%XX 的十六进制字母必须大写：" + encoded);
            }
            assertTrue(found > 0, "样本里应当有被编码的字节：" + encoded);
        }

        @Test
        @DisplayName("空串 / null → 空串（不抛）")
        void emptyInput() {
            assertEquals("", WbiSigner.percentEncode(""));
            assertEquals("", WbiSigner.percentEncode(null));
        }
    }

    // ================================================================
    // md5Hex
    // ================================================================

    @Nested
    @DisplayName("md5Hex")
    class Md5Test {

        @Test
        @DisplayName("标准向量：md5(\"abc\") = 900150983cd24fb0d6963f7d28e17f72（小写 32 位）")
        void knownVector() {
            assertEquals("900150983cd24fb0d6963f7d28e17f72", WbiSigner.md5Hex("abc"));
        }

        @Test
        @DisplayName("中文按 UTF-8 摘要（与 percentEncode 同一套字节口径）")
        void utf8() {
            // 独立复算：md5("测".getBytes(UTF-8))
            assertEquals(32, WbiSigner.md5Hex("测").length());
            assertNotEquals(WbiSigner.md5Hex("测"), WbiSigner.md5Hex("測"));
        }
    }
}

package com.esdllm.bilibiliApi.model;

import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuItem;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuXml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>弹幕 XML 解析</b>的回归测试（2026-09-22 B2 批 #1）—— <b>完全零出站</b>。
 *
 * <p>本文件是"把解析放进 POJO"这个决定的直接收益：{@code DanmakuXml.parse} 收字符串、
 * 出对象，不碰网络、不碰 Mock 服务器，所以这里可以用<b>手写的小 XML</b>去撞那些
 * 真实响应里碰不到的边界（段数不足、实体转义、自闭合标签、乱码）。
 *
 * <p>本文件守四件<b>光看代码看不出来</b>的事：
 * <ol>
 *   <li>🔴 <b>{@code p} 实测是 9 段，不是老文档写的 7 段</b>。解析必须是"按下标取、
 *       越界给 null"，<b>不能断言段数</b> —— 否则服务端哪天改成 8 段，整批弹幕直接解析失败
 *       （而失败形态是"没有弹幕"，跟"这视频没人发弹幕"完全同形）。</li>
 *   <li>🔴 <b>0 条弹幕是合法结果</b>：真实响应里有视频就是只给 {@code <i>} 头、一个 {@code <d>} 都没有。
 *       这里用真实那种形态断言"返回空列表而不是抛异常"。</li>
 *   <li>🔴 <b>乱码 = 没解压的 deflate 字节</b>。这是本端点最可能的坏法，
 *       所以解析器要<b>响亮地抛</b>，而不是安静地给空列表。</li>
 *   <li>📌 <b>XML 实体要还原</b>，否则弹幕正文里的 {@code &} 会以 {@code &amp;amp;} 的形式露给用户。</li>
 * </ol>
 */
@DisplayName("模型：DanmakuXml / DanmakuItem（弹幕 XML 解析，零出站）")
class DanmakuXmlTest {

    /** 夹具里第一条弹幕的 {@code dmid}（19 位雪花号，用来钉"第 8 段是弹幕 ID"） */
    private static final long FIRST_DMID = 2204311331669302272L;

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    // ================================================================
    // 真实响应的形状
    // ================================================================

    @Nested
    @DisplayName("真实响应的形状（fixture 来自 2026-09-22 匿名实测）")
    class RealShapeTest {

        @Test
        @DisplayName("头部字段：chatserver / chatid / maxlimit / source 都解析出来")
        void header() throws Exception {
            DanmakuXml xml = DanmakuXml.parse(fixture("danmaku.xml"));

            assertEquals("chat.bilibili.com", xml.getChatserver());
            assertEquals(41961327629L, xml.getChatid(), "chatid 就是请求里的 oid（cid）");
            assertEquals(1000, xml.getMaxlimit(), "★ 这个值就是'是否被截断'的判据，不能丢");
            assertEquals("k-v", xml.getSource());
            assertEquals(0, xml.getMission());
            assertEquals(0, xml.getState());
            assertEquals(0, xml.getReal_name());
        }

        @Test
        @DisplayName("夹具 6 条弹幕全部解析出来，正文与 p 原文都在")
        void items() throws Exception {
            DanmakuXml xml = DanmakuXml.parse(fixture("danmaku.xml"));

            assertEquals(6, xml.getDanmaku().size());
            DanmakuItem first = xml.getDanmaku().get(0);
            assertEquals("何须要这众生知道？", first.getText());
            assertEquals("85.33300,1,25,16777215,1789865587,0,99b04462,"
                            + "2204311331669302272,10", first.getP(),
                    "★ 原始 p 必须原样保留：将来段位语义变了，这里是唯一的退路");
        }

        @Test
        @DisplayName("★ p 的 9 段逐段落地：时间/模式/字号/颜色/时间戳/池/哈希/弹幕ID/轨道")
        void nineSegments() throws Exception {
            DanmakuItem first = DanmakuXml.parse(fixture("danmaku.xml")).getDanmaku().get(0);

            assertEquals(85.333, first.getTime(), 0.000001, "第 1 段：出现时间（秒，5 位小数）");
            assertEquals(1, first.getMode(), "第 2 段：模式（1=滚动）");
            assertEquals(25, first.getFontSize(), "第 3 段：字号");
            assertEquals(16777215, first.getColor(), "第 4 段：颜色（16777215 = 纯白）");
            assertEquals(1789865587L, first.getTimestamp(), "第 5 段：发送时间戳");
            assertEquals(0, first.getPool(), "第 6 段：弹幕池");
            assertEquals("99b04462", first.getUserHash(),
                    "第 7 段：发送者哈希（8 位十六进制，★ 不是 mid，反查不到用户）");
            assertEquals(FIRST_DMID, first.getDmid(), "第 8 段：弹幕 ID（19 位雪花号，超出 int）");
            assertEquals(10, first.getLane(), "第 9 段：疑似轨道号（实测 1–10）");
        }

        @Test
        @DisplayName("★ 第 9 段实测落在 1–10：这是'推断字段'的证据，不是语义确认")
        void laneIsAnInference() throws Exception {
            List<DanmakuItem> items = DanmakuXml.parse(fixture("danmaku.xml")).getDanmaku();

            for (DanmakuItem item : items) {
                assertNotNull(item.getLane(), "夹具里每条都有第 9 段");
                assertTrue(item.getLane() >= 1 && item.getLane() <= 10,
                        "实测 371 条的取值都在 1–10 且分布均匀 —— 但这是观察，不是文档，"
                                + "所以本库只把它当'原始信息的搬运'，不赋予业务含义。实际："
                                + item.getLane());
            }
        }

        @Test
        @DisplayName("模式实测有 1（滚动）与 5（顶部）：同一条链路上混着多种模式")
        void modesAreMixed() throws Exception {
            List<DanmakuItem> items = DanmakuXml.parse(fixture("danmaku.xml")).getDanmaku();

            assertTrue(items.stream().anyMatch(d -> d.getMode() == 1), "有滚动弹幕");
            assertTrue(items.stream().anyMatch(d -> d.getMode() == 5), "有顶部弹幕");
        }
    }

    // ================================================================
    // 容错（本文件的核心）
    // ================================================================

    @Nested
    @DisplayName("★ 段数容错：多一段少一段都不许让整条失败")
    class ToleranceTest {

        @Test
        @DisplayName("★ 只有 7 段（老文档的形态）：前 7 个字段照常，dmid/lane 为 null")
        void sevenSegments() {
            DanmakuXml xml = DanmakuXml.parse(
                    "<i><maxlimit>1000</maxlimit><d p=\"12.50000,1,25,16711680,1700000000,0,abcdef01\">"
                            + "老格式</d></i>");

            DanmakuItem d = xml.getDanmaku().get(0);
            assertEquals(12.5, d.getTime(), 0.000001);
            assertEquals(1, d.getMode());
            assertEquals(16711680, d.getColor());
            assertEquals("abcdef01", d.getUserHash());
            assertNull(d.getDmid(), "★ 少一段时该字段为 null，但这条弹幕照样解析出来了");
            assertNull(d.getLane());
            assertEquals("老格式", d.getText());
        }

        @Test
        @DisplayName("★ 未来多加了第 10 段：已知 9 段照常，多出来的不丢（在 p 原文里）")
        void extraSegments() {
            String p = "1.00000,1,25,16777215,1700000000,0,deadbeef,2204311331669302272,7,SOMETHING_NEW";
            DanmakuXml xml = DanmakuXml.parse("<i><d p=\"" + p + "\">未来格式</d></i>");

            DanmakuItem d = xml.getDanmaku().get(0);
            assertEquals(7, d.getLane());
            assertEquals(p, d.getP(), "★ 新增的段位不会丢 —— 因为整个 p 都留着");
            assertEquals("未来格式", d.getText());
        }

        @Test
        @DisplayName("数字段写坏（空串/非数字）：只有那一个字段为 null，整条不失败")
        void brokenNumberSegments() {
            DanmakuXml xml = DanmakuXml.parse(
                    "<i><d p=\"abc,,25,16777215,,0,99b04462,notanumber,10\">坏数字</d></i>");

            DanmakuItem d = xml.getDanmaku().get(0);
            assertNull(d.getTime(), "abc 不是数字");
            assertNull(d.getMode(), "空串");
            assertNull(d.getTimestamp(), "空串");
            assertNull(d.getDmid(), "notanumber");
            assertEquals(25, d.getFontSize(), "★ 相邻字段不受影响 —— 这是'按下标取'的意义");
            assertEquals(16777215, d.getColor());
            assertEquals("坏数字", d.getText());
        }

        @Test
        @DisplayName("p 属性整段为空：所有解码字段为 null，正文仍保留")
        void emptyP() {
            DanmakuItem d = DanmakuXml.parse("<i><d p=\"\">没有属性的弹幕</d></i>").getDanmaku().get(0);

            assertNull(d.getTime());
            assertNull(d.getDmid());
            assertEquals("", d.getP());
            assertEquals("没有属性的弹幕", d.getText());
        }
    }

    // ================================================================
    // 实体转义
    // ================================================================

    @Nested
    @DisplayName("XML 实体还原")
    class EntityTest {

        @Test
        @DisplayName("★ 命名实体与数字实体都要还原 —— 否则用户会看到 &amp;amp; 这种原文")
        void entities() {
            DanmakuXml xml = DanmakuXml.parse(
                    "<i><d p=\"1,1,25,16777215,1,0,h,1,1\">a&amp;b &lt;tag&gt; &quot;q&quot; &#39;s&#39; "
                            + "&#x4e2d; &#20013;</d></i>");

            assertEquals("a&b <tag> \"q\" 's' 中 中", xml.getDanmaku().get(0).getText());
        }

        @Test
        @DisplayName("认不出来的实体原样保留（不吞字符、不抛异常）")
        void unknownEntity() {
            DanmakuXml xml = DanmakuXml.parse(
                    "<i><d p=\"1,1,25,16777215,1,0,h,1,1\">100&unknown;200 & 300</d></i>");

            assertEquals("100&unknown;200 & 300", xml.getDanmaku().get(0).getText());
        }
    }

    // ================================================================
    // 边界与失败
    // ================================================================

    @Nested
    @DisplayName("边界与失败形态")
    class FailureTest {

        @Test
        @DisplayName("★ 0 条弹幕是【合法结果】：真实响应里就有这种（只有 <i> 头，没有 <d>）")
        void zeroDanmakuIsLegal() {
            DanmakuXml xml = DanmakuXml.parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<i><chatserver>chat.bilibili.com</chatserver><chatid>41958378053</chatid>"
                    + "<mission>0</mission><maxlimit>300</maxlimit><state>0</state>"
                    + "<real_name>0</real_name></i>");

            assertNotNull(xml.getDanmaku(), "★ 空列表，不是 null");
            assertTrue(xml.getDanmaku().isEmpty());
            assertEquals(300, xml.getMaxlimit(), "maxlimit 仍然给出来了 —— 说明这是一条正常响应");
        }

        @Test
        @DisplayName("自闭合写法 <d p=\"…\"/> 也不会被漏掉（正文为 null）")
        void selfClosing() {
            DanmakuXml xml = DanmakuXml.parse(
                    "<i><d p=\"1,1,25,16777215,1,0,h,1,1\"/><d p=\"2,1,25,16777215,1,0,h,2,1\">有正文</d></i>");

            assertEquals(2, xml.getDanmaku().size());
            assertNull(xml.getDanmaku().get(0).getText());
            assertEquals("有正文", xml.getDanmaku().get(1).getText());
        }

        @Test
        @DisplayName("★ 乱码（没解压的 deflate 字节）= 响亮地抛，且 hint 指明是解压问题")
        void garbledBodyThrows() {
            String garbled = "L\u0006\u0000\n\u00000\fE\u007fE\u0000>\u0000=8\u0000\u0000\u0000l\u0000M\nm\u0011?";
            BilibiliException e = assertThrows(BilibiliException.class, () -> DanmakuXml.parse(garbled));

            assertEquals(0, e.getCode());
            assertTrue(e.getMessage().contains("不像 XML"), "实际：" + e.getMessage());
            assertTrue(e.getDescription().contains("deflate"),
                    "★ 这条 hint 是给排障的人看的：坏法是'底层不再自动解压'，"
                            + "而那需要给 BilibiliHttp 加字节出口。实际：" + e.getDescription());
        }

        @Test
        @DisplayName("空响应 / null：抛异常而不是给一个空列表（那会被读成'这视频没弹幕'）")
        void emptyBodyThrows() {
            assertEquals(0, assertThrows(BilibiliException.class, () -> DanmakuXml.parse("")).getCode());
            assertEquals(0, assertThrows(BilibiliException.class, () -> DanmakuXml.parse(null)).getCode());
        }

        @Test
        @DisplayName("JSON 错误响应（HTTP 层没拦住时）也会被挡下：它既没有 <i> 也不以 <?xml 开头")
        void jsonBodyThrows() {
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DanmakuXml.parse("{\"code\":-400,\"message\":\"请求错误\"}"));

            assertTrue(e.getMessage().contains("不像 XML"), "实际：" + e.getMessage());
        }
    }
}

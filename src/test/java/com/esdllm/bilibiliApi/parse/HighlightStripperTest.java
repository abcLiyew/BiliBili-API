package com.esdllm.bilibiliApi.parse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HighlightStripper} 测试。
 *
 * <p>它守的是一个很隐蔽的坑：搜索接口把命中的关键词用 {@code <em class="keyword">} 包起来返回，
 * 于是"看起来一样的标题"其实不相等 —— 用原始串做去重、比较、或直接展示都会出问题。
 *
 * <p><b>样本取自真实夹具</b>（{@code search-type-video.json}，2026-09-21 现场 dump），
 * 不是手编的 string：手编的只能证明"我实现了我以为的规则"。
 */
@DisplayName("HighlightStripper：剥离搜索高亮")
class HighlightStripperTest {

    /** 真实夹具里的第一条视频标题（逐字来自 dump） */
    private static final String REAL_TITLE =
            "【2026最新】B站最全最细的软件<em class=\"keyword\">测试</em>教程，"
                    + "7天从零基础小白到精通软件<em class=\"keyword\">测试</em>，学完即上岗！";

    private static final String REAL_TITLE_CLEAN =
            "【2026最新】B站最全最细的软件测试教程，7天从零基础小白到精通软件测试，学完即上岗！";

    @Nested
    @DisplayName("基本剥离")
    class StripTest {

        @Test
        @DisplayName("真实夹具样本：两处高亮都剥掉，其余逐字不变")
        void realSample() {
            assertEquals(REAL_TITLE_CLEAN, HighlightStripper.strip(REAL_TITLE));
        }

        @Test
        @DisplayName("夹具文件里的标题确实带高亮 —— 上面的常量与它同源，不是我自己编的")
        void fixtureStillHasHighlight() throws Exception {
            JSONObject root = JSON.parseObject(
                    Files.readString(Path.of("src/test/resources/fixtures/search-type-video.json")));
            String title = root.getJSONObject("data").getJSONArray("result")
                    .getJSONObject(0).getString("title");
            assertTrue(title.contains("<em class=\"keyword\">"),
                    "夹具第一条的标题必须带高亮标签，否则本类的用例失去样本意义：" + title);
            assertFalse(HighlightStripper.strip(title).contains("<em"),
                    "剥完不该还有标签：" + HighlightStripper.strip(title));
            assertEquals(HighlightStripper.strip(title), HighlightStripper.strip(REAL_TITLE));
        }

        @Test
        @DisplayName("其它形态的标签也一并去掉（B 站目前只产出 em，但不必赌它永远不变）")
        void otherTags() {
            assertEquals("粗体", HighlightStripper.strip("<b>粗体</b>"));
            assertEquals("外层内层", HighlightStripper.strip("<span class=\"x\">外层<b>内层</b></span>"));
            assertEquals("abc", HighlightStripper.strip("a<br/>b<br>c"));
        }

        @Test
        @DisplayName("没有标签的串原样返回（不做无谓改动）")
        void noTags() {
            assertEquals("影视飓风", HighlightStripper.strip("影视飓风"));
            assertEquals("a < b 是数学", HighlightStripper.strip("a < b 是数学"));
        }
    }

    @Nested
    @DisplayName("HTML 实体反转义")
    class EntityTest {

        @Test
        @DisplayName("常见实体都能还原")
        void entities() {
            assertEquals("A&B", HighlightStripper.strip("A&amp;B"));
            assertEquals("1<2", HighlightStripper.strip("1&lt;2"));
            assertEquals("2>1", HighlightStripper.strip("2&gt;1"));
            assertEquals("\"引号\"", HighlightStripper.strip("&quot;引号&quot;"));
            assertEquals("it's", HighlightStripper.strip("it&#39;s"));
            assertEquals("it's", HighlightStripper.strip("it&apos;s"));
            assertEquals("a b", HighlightStripper.strip("a&nbsp;b"));
        }

        @Test
        @DisplayName("🔴 &amp; 必须最后处理：&amp;lt; 只能还原成 &lt;，不能变成 <")
        void ampersandIsLast() {
            // 若先替换 &amp;，会得到 "&lt;"，再被 &lt; 规则吃掉变成 "<" —— 那是双重解码，改坏了原文
            assertEquals("&lt;", HighlightStripper.strip("&amp;lt;"));
        }

        @Test
        @DisplayName("标签与实体同时出现：先剥标签再还原实体（顺序反了会把实体变成真标签又剥掉）")
        void tagThenEntity() {
            assertEquals("A&B", HighlightStripper.strip("<em>A&amp;B</em>"));
            // 这个样本最能说明顺序：若先还原实体，会得到 "<em>A<B</em>" 这种坏结构
            assertEquals("A<B", HighlightStripper.strip("<em>A&lt;B</em>"));
        }
    }

    @Nested
    @DisplayName("边界值")
    class EdgeTest {

        @Test
        @DisplayName("null 仍是 null（保持\"没有值\"与\"空串\"的区别）")
        void nullStaysNull() {
            assertNull(HighlightStripper.strip(null));
        }

        @Test
        @DisplayName("空串仍是空串")
        void emptyStaysEmpty() {
            assertEquals("", HighlightStripper.strip(""));
        }

        @Test
        @DisplayName("只有标签的串 → 空串（不抛）")
        void onlyTags() {
            assertEquals("", HighlightStripper.strip("<em class=\"keyword\"></em>"));
        }
    }
}

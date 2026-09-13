package com.esdllm.bilibiliApi.http;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserAgentPool} 单测。
 *
 * <p>锁住两条不变量（都是"改了就静默破坏兼容"的类型）：
 * <ol>
 *   <li>{@code at(0)} 必须与 {@link BilibiliEndpoint#userAgent} <b>逐字相同</b> ——
 *       这保证"不轮换时（第 1 代身份）行为与改造前完全一致"；
 *       （P3 起常量单一来源是 {@code BilibiliEndpoint}，{@code BilibiliConfig} 已退场）</li>
 *   <li>{@code at(index)} 对<b>任意整数</b>都不越界（用 {@code Math.floorMod}，
 *       负数也要安全），因为轮换代数是外部传入的。</li>
 * </ol>
 */
class UserAgentPoolTest {

    @Test
    @DisplayName("池大小 ≥ 1（否则轮换无意义），且实测为 4")
    void size() {
        assertTrue(UserAgentPool.size() >= 1, "池不能为空");
        assertEquals(4, UserAgentPool.size(), "当前池是 4 个真实浏览器 UA");
    }

    @Test
    @DisplayName("defaultAgent() 与 BilibiliEndpoint.userAgent 逐字相同（兼容不变量）")
    void defaultAgent与配置一致() {
        assertNotNull(BilibiliEndpoint.userAgent);
        assertEquals(BilibiliEndpoint.userAgent, UserAgentPool.defaultAgent(),
                "第 0 个 UA 必须是原 UA，否则'不轮换时行为不变'这条兼容声明就破了");
        assertEquals(BilibiliEndpoint.userAgent, UserAgentPool.at(0),
                "at(0) 与 defaultAgent() 必须是同一个");
    }

    @Test
    @DisplayName("index 取模：at(size) 回到 at(0)")
    void 取模() {
        int n = UserAgentPool.size();
        assertEquals(UserAgentPool.at(0), UserAgentPool.at(n));
        assertEquals(UserAgentPool.at(1), UserAgentPool.at(n + 1));
        assertEquals(UserAgentPool.at(0), UserAgentPool.at(2 * n));
    }

    @Test
    @DisplayName("负数索引不越界（floorMod 语义）")
    void 负数索引() {
        int n = UserAgentPool.size();
        // floorMod(-1, 4) == 3
        assertEquals(UserAgentPool.at(n - 1), UserAgentPool.at(-1));
        assertEquals(UserAgentPool.at(0), UserAgentPool.at(-n));
        assertEquals(UserAgentPool.at(0), UserAgentPool.at(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("极端索引不越界")
    void 极端索引() {
        assertNotNull(UserAgentPool.at(Integer.MAX_VALUE));
        assertNotNull(UserAgentPool.at(99999));
        // 不臆断具体取到第几个 —— 只要求"不抛异常且落在池内"
        String got = UserAgentPool.at(Integer.MAX_VALUE);
        boolean inPool = false;
        for (int i = 0; i < UserAgentPool.size(); i++) {
            if (UserAgentPool.at(i).equals(got)) {
                inPool = true;
                break;
            }
        }
        assertTrue(inPool, "极端索引也必须落在池内，实际取到：" + got);
    }

    @Test
    @DisplayName("池内 UA 互不相同（否则'换 UA'等于没换）")
    void UA不重复() {
        Set<String> uniq = new HashSet<>();
        for (int i = 0; i < UserAgentPool.size(); i++) {
            String ua = UserAgentPool.at(i);
            assertNotNull(ua);
            assertFalse(ua.isBlank(), "第 " + i + " 个 UA 不能为空白");
            assertTrue(ua.startsWith("Mozilla/5.0"), "应是浏览器 UA，实际：" + ua);
            uniq.add(ua);
        }
        assertEquals(UserAgentPool.size(), uniq.size(), "池内 UA 必须互不相同");
    }

    @Test
    @DisplayName("返回的字符串是池内常量本身（无拷贝、无拼接副作用）")
    void 返回常量本身() {
        assertSame(UserAgentPool.at(0), UserAgentPool.at(0),
                "同一索引应返回同一个字符串常量引用");
    }
}
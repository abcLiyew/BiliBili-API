package com.esdllm.bilibiliApi.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnonymousSession} 的离线可测部分。
 *
 * <p><b>为什么只测到这里</b>：{@code current()} / {@code rotate()} 会真去打
 * {@code x/frontend/finger/spi} 领取指纹（且该请求经 {@code Unirest} 直发，
 * 不经过 {@code BilibiliHttp}，因此<b>无法被 {@code MockBiliServer} 拦截</b>）。
 * 所以本类只覆盖两件离线确定的事：
 * <ol>
 *   <li>{@link AnonymousSession.Identity} 的 {@code hasCookie()} 语义（纯数据）；</li>
 *   <li><b>"失败不阻塞"的设计承诺</b> —— 拿不到指纹时给出无 Cookie 身份而不是抛异常。
 *       用 {@code isAvailable()} 观察即可，无论网络通断该断言都成立。</li>
 * </ol>
 *
 * <p>身份轮换（{@code rotate()} 递增代数）的网络路径由
 * {@code smoke/HttpRetrySmokeTest} 与真实冒烟覆盖。
 */
class AnonymousSessionTest {

    @Test
    @DisplayName("Identity.hasCookie：非空 cookie → true")
    void hasCookie为真() {
        AnonymousSession.Identity id = new AnonymousSession.Identity(
                "buvid3=ABC123; buvid4=DEF456", "UA", 1, 1700000000L);
        assertTrue(id.hasCookie());
    }

    @Test
    @DisplayName("Identity.hasCookie：null / 空串 → false（领取失败的形态）")
    void hasCookie为假() {
        assertFalse(new AnonymousSession.Identity(null, "UA", 1, 0L).hasCookie(),
                "null 表示没领到");
        assertFalse(new AnonymousSession.Identity("", "UA", 1, 0L).hasCookie(),
                "空串表示没领到（obtain 失败时的返回值）");
    }

    @Test
    @DisplayName("Identity record 的四个字段原样保留")
    void 字段完整() {
        AnonymousSession.Identity id = new AnonymousSession.Identity(
                "buvid3=x", "MyAgent/1.0", 7, 1700000123456L);
        assertEquals("buvid3=x", id.cookie());
        assertEquals("MyAgent/1.0", id.userAgent(), "UA 必须与这一代指纹一起存下来，否则会出现新指纹配旧 UA 的矛盾组合");
        assertEquals(7, id.generation());
        assertEquals(1700000123456L, id.createdAt());
    }

    @Test
    @DisplayName("record 的 equals/hashCode 按值比较")
    void record相等语义() {
        AnonymousSession.Identity a = new AnonymousSession.Identity("c", "u", 1, 100L);
        AnonymousSession.Identity b = new AnonymousSession.Identity("c", "u", 1, 100L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("失败不阻塞：无网络时 current() 仍返回身份、不抛异常（可退化为无 Cookie）")
    void 失败不阻塞() {
        AnonymousSession.Identity id = AnonymousSession.current();
        assertNotNull(id, "设计承诺：拿不到指纹也要给一个身份，绝不因指纹接口不可用而整体不可用");
        assertNotNull(id.userAgent(), "UA 永远有值（来自 UserAgentPool，不依赖网络）");
        assertTrue(id.generation() >= 1, "代数从 1 开始");
        // 不硬断言 hasCookie() —— 有网络时会真领到，离线时为 false，两者都是正确行为
    }

    @Test
    @DisplayName("generation() 与 current().generation() 一致")
    void generation一致() {
        assertEquals(AnonymousSession.generation(), AnonymousSession.current().generation());
    }

    @Test
    @DisplayName("userAgent()/cookieHeader() 与 current() 的字段一致")
    void 派生方法与current一致() {
        AnonymousSession.Identity id = AnonymousSession.current();
        assertEquals(id.userAgent(), AnonymousSession.userAgent());
        assertEquals(id.cookie(), AnonymousSession.cookieHeader());
        assertEquals(id.hasCookie(), AnonymousSession.isAvailable());
    }

    @Test
    @DisplayName("current() 幂等：连续调用返回同一代身份（不重复领取）")
    void current幂等() {
        int first = AnonymousSession.generation();
        int second = AnonymousSession.current().generation();
        int third = AnonymousSession.generation();
        assertEquals(first, second, "current() 必须走缓存，不能每次都重新领指纹");
        assertEquals(second, third);
    }
}
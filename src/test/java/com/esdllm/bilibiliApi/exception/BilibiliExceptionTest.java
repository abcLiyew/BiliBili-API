package com.esdllm.bilibiliApi.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>BilibiliException 构造器回归测试</b>。
 *
 * <p>本类历史上翻过车（5 个构造器存在但调用方误用了不存在的 {@code (String, Throwable)}，
 * 见 2026-09-13 P1.5 修复记录），因此单测目的：
 * <ol>
 *   <li>锁定 5 个构造器的<b>字段映射</b>（code/message/description 三者赋值正确）；</li>
 *   <li>覆盖"看起来像构造器但实际不存在"的常见误用，避免下次重蹈覆辙。</li>
 * </ol>
 */
class BilibiliExceptionTest {

    @Test
    void ctor_stringMessage() {
        BilibiliException e = new BilibiliException("hello");
        assertEquals(0, e.getCode());
        assertEquals("hello", e.getMessage());
        assertEquals("", e.getDescription());
    }

    @Test
    void ctor_exception_only() {
        IllegalStateException raw = new IllegalStateException("inner");
        BilibiliException e = new BilibiliException(raw);
        assertEquals(-1, e.getCode());
        assertEquals("inner", e.getMessage());
        assertEquals("", e.getDescription());
        // getCause() 应该就是原异常（这是 RuntimeException 自带能力）
        assertSame(raw, e.getCause());
    }

    @Test
    void ctor_exception_withDescription() {
        IllegalStateException raw = new IllegalStateException("inner");
        BilibiliException e = new BilibiliException(raw, "获取动态列表失败：");
        assertEquals(-1, e.getCode());
        // 双参构造器的语义：message 来自 cause.getMessage()，description 是给开发者看的前缀
        assertEquals("inner", e.getMessage());
        assertEquals("获取动态列表失败：", e.getDescription());
        assertSame(raw, e.getCause());
    }

    @Test
    void ctor_code_message_description() {
        BilibiliException e = new BilibiliException(-352, "风控", "请重试");
        assertEquals(-352, e.getCode());
        assertEquals("风控", e.getMessage());
        assertEquals("请重试", e.getDescription());
        assertNull(e.getCause());
    }

    @Test
    void ctor_code_message_description_cause() {
        Throwable raw = new RuntimeException("inner");
        BilibiliException e = new BilibiliException(-352, "风控", "请重试", raw);
        assertEquals(-352, e.getCode());
        assertEquals("风控", e.getMessage());
        assertEquals("请重试", e.getDescription());
        assertSame(raw, e.getCause());
    }

    /**
     * 防回归：{@code new BilibiliException(String, Throwable)} 这种"看起来像"的构造器<b>不存在</b>。
     * 若有人未来加上这个构造器（破坏 message/description 语义），本测试应作为信号。
     *
     * <p>验证方式：尝试反射获取名为 {@code <init>}、参数 {@code (String, Throwable)} 的构造器；
     * 若存在则说明本约定已被破坏，测试失败。
     */
    @Test
    void no_stringThrowableConstructor() throws NoSuchMethodException {
        boolean exists;
        try {
            BilibiliException.class.getDeclaredConstructor(String.class, Throwable.class);
            exists = true;
        } catch (NoSuchMethodException e) {
            exists = false;
        }
        assertTrue(!exists, "BilibiliException 不应有 (String, Throwable) 构造器——会与 (Exception, String) 双参的语义冲突");
    }
}
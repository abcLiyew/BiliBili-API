package com.esdllm.bilibiliApi.model.data.pojo.login;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GeeTestValidation} 单测。
 *
 * <p>这个类是"浏览器侧过验结果"的入口，它只有一条业务规则（{@code seccode = validate + "|jordan"}），
 * 但规则写错的代价很高：服务端只会回一句"验证极验服务出错"，看不出是参数问题。
 * 所以规则写成工厂方法、并用测试钉死。
 */
class GeeTestValidationTest {

    private static final String VALIDATE = "7f8e9d0c1b2a3948aabbccdd";

    @Test
    @DisplayName("只给 validate → seccode 自动补极验的 |jordan 后缀")
    void derivesSeccode() {
        GeeTestValidation validation = GeeTestValidation.of(VALIDATE);

        assertEquals(VALIDATE, validation.getValidate());
        assertEquals(VALIDATE + "|jordan", validation.getSeccode());
        assertEquals("|jordan", GeeTestValidation.SECCODE_SUFFIX);
    }

    @Test
    @DisplayName("显式给两个值 → 原样保留（浏览器回什么就传什么，最稳）")
    void explicitSeccode() {
        GeeTestValidation validation = GeeTestValidation.of(VALIDATE, "custom-seccode");

        assertEquals(VALIDATE, validation.getValidate());
        assertEquals("custom-seccode", validation.getSeccode());
    }

    @Test
    @DisplayName("没指定 challenge → hasChallenge 为 false（调用方改用申请到的那个）")
    void challengeUnset() {
        assertFalse(GeeTestValidation.of(VALIDATE).hasChallenge());
        assertNull(GeeTestValidation.of(VALIDATE).getChallenge());
    }

    @Test
    @DisplayName("withChallenge 返回新对象（不可变），不影响原有实例")
    void withChallengeDoesNotMutate() {
        GeeTestValidation original = GeeTestValidation.of(VALIDATE);

        GeeTestValidation withChallenge = original.withChallenge("9f9e8d7c6b5a43210fedcba987654321");

        assertTrue(withChallenge.hasChallenge());
        assertEquals("9f9e8d7c6b5a43210fedcba987654321", withChallenge.getChallenge());
        assertEquals(original.getValidate(), withChallenge.getValidate());
        assertEquals(original.getSeccode(), withChallenge.getSeccode());
        assertFalse(original.hasChallenge(), "原对象不该被改动");
        assertSame(original, original.withChallenge("  "), "空白 challenge 应被忽略");
        assertSame(original, original.withChallenge(null));
    }

    @Test
    @DisplayName("validate 为空 → 明确告诉调用方'先去浏览器过验'，而不是让它变成一个静默的空参数")
    void blankValidateRejected() {
        assertThrows(IllegalArgumentException.class, () -> GeeTestValidation.of(""));
        assertThrows(IllegalArgumentException.class, () -> GeeTestValidation.of("   "));
        assertThrows(IllegalArgumentException.class, () -> GeeTestValidation.of(null));
        assertThrows(IllegalArgumentException.class, () -> GeeTestValidation.of(VALIDATE, " "));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GeeTestValidation.of(""));
        assertTrue(e.getMessage().contains("浏览器"), "消息里应说明该怎么修：实际 " + e.getMessage());
    }

    @Test
    @DisplayName("★ 回归：toString 不得泄露 validate（它能换一次登录提交）")
    void toStringDoesNotLeak() {
        String text = GeeTestValidation.of(VALIDATE).toString();

        assertFalse(text.contains(VALIDATE), "实际：" + text);
        assertTrue(text.contains(String.valueOf(VALIDATE.length())), "长度可以出，便于排障：" + text);
    }

    @Test
    @DisplayName("首尾空白会被去掉（从页面复制粘贴常常带空白）")
    void trimsWhitespace() {
        GeeTestValidation validation = GeeTestValidation.of("  " + VALIDATE + "  ");

        assertEquals(VALIDATE, validation.getValidate());
        assertEquals(VALIDATE + "|jordan", validation.getSeccode());
    }
}

package com.esdllm.bilibiliApi.contract;

import com.esdllm.bilibiliApi.bilibiliApi.*;
import com.esdllm.bilibiliApi.common.ShotChainInfo;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门面冻结契约测试。
 *
 * <p>下游消费方 XatiiBot 以 {@code com.esdllm:bilibili-api} 为依赖、只依赖「零改动」这一条约定，
 * 因此本库的每一次重构都必须保证下面这些签名逐字不变。本测试用**反射**把它们固化下来，
 * 让"重构顺手改了签名"在 {@code mvn package} 阶段就变成红灯，而不是等 XatiiBot 编译失败才发现。
 *
 * <p>约束范围严格对应 {@code REFACTOR_PLAN.md} §2（已对 XatiiBot 全量 grep 核对）：
 * <ul>
 *   <li>§2.1 门面类名 / 包名 / public 方法签名（参数类型 + 返回类型）</li>
 *   <li>§2.2 冻结模型：{@code BilibiliDynamicResp} 嵌套结构、{@code Dynamic.DynamicInfo} 静态内部类</li>
 *   <li>§2.3 <b>只有 {@code throws IOException} 是编译红线</b>（XatiiBot 有三处 {@code catch (IOException e)}）。
 *       {@code throws InterruptedException} 不是红线 —— Java 只禁止 catch 不可能抛出的受检异常，
 *       而"声明"一个永不抛出的受检异常永远合法，所以本测试**刻意不断言它**，
 *       以免为一条并不存在的约束加断言。</li>
 * </ul>
 *
 * <p>另有两条**语义红线**无法用反射断言，只能靠代码评审守（见 {@code REFACTOR_PLAN.md} §2.4 / §4.4）：
 * <ol>
 *   <li>{@code DynamicInfo.time} 必须承载**相对时间文案**（下游判断 {@code startsWith("刚刚")} 来触发推送）；
 *       相对文案只存在于**列表**端点，详情端点给的是绝对时间，不能混用。</li>
 *   <li>{@code DynamicInfo.dynamicId} 与 {@code shareDynamicId} 互斥：转发动态时 {@code dynamicId} 必须为 null。</li>
 * </ol>
 */
@DisplayName("门面冻结契约（XatiiBot 零改动红线）")
class FacadeContractTest {

    /** 5 个门面所在的包，必须不变 */
    private static final String FACADE_PACKAGE = "com.esdllm.bilibiliApi.bilibiliApi";

    // ================================================================
    // 断言工具
    // ================================================================

    private static Method locate(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail(String.format("门面契约被破坏：%s#%s(%s) 不存在。%n"
                            + "该方法是下游 XatiiBot 直接调用的，不允许改名/改参数/删除（见 REFACTOR_PLAN.md §2.1）",
                    owner.getName(), name, paramNames(params)));
            // fail() 声明返回 void，这里显式抛出以满足编译器的"必有返回值"检查
            throw new AssertionError(e);
        }
    }

    private static String paramNames(Class<?>[] params) {
        if (params.length == 0) {
            return "";
        }
        return String.join(", ", Arrays.stream(params).map(Class::getSimpleName).toList());
    }

    /** 断言方法存在、public、非 static，且**返回类型逐字一致** */
    private static void assertSignature(Class<?> owner, String name, Class<?> expectedReturn, Class<?>... params) {
        Method m = locate(owner, name, params);
        assertEquals(expectedReturn, m.getReturnType(),
                () -> String.format("%s#%s(%s) 返回类型变了，下游按旧类型接收会编译失败",
                        owner.getName(), name, paramNames(params)));
        assertTrue(Modifier.isPublic(m.getModifiers()),
                () -> String.format("%s#%s 必须是 public", owner.getName(), name));
        assertFalse(Modifier.isStatic(m.getModifiers()),
                () -> String.format("%s#%s 原本是实例方法，改成 static 会破坏下游调用方", owner.getName(), name));
    }

    /** 断言方法在 throws 里声明了指定受检异常（§2.3：只有 IOException 是编译红线） */
    private static void assertDeclares(Class<?> owner, String name, Class<?> exception, Class<?>... params) {
        Method m = locate(owner, name, params);
        assertTrue(Arrays.asList(m.getExceptionTypes()).contains(exception),
                () -> String.format("%s#%s 的 throws 里必须保留 %s%n"
                                + "下游 XatiiBot 有 catch (IOException e) 包住该方法，去掉声明会直接编译失败（REFACTOR_PLAN.md §2.3）",
                        owner.getName(), name, exception.getName()));
    }

    private static void assertPublicNoArgCtor(Class<?> owner) {
        try {
            assertTrue(Modifier.isPublic(owner.getConstructor().getModifiers()),
                    () -> owner.getName() + " 的无参构造器必须是 public（下游直接 new）");
        } catch (NoSuchMethodException e) {
            fail(owner.getName() + " 的无参构造器不见了（下游直接 new）");
        }
    }

    private static void assertClassInFacadePackage(Class<?> owner) {
        assertEquals(FACADE_PACKAGE, owner.getPackageName(),
                () -> owner.getSimpleName() + " 的包名变了，下游 import 会编译失败");
        assertTrue(Modifier.isPublic(owner.getModifiers()),
                () -> owner.getSimpleName() + " 必须是 public class");
    }

    /** 断言 {@code List<某类型>} 的泛型实参没变（反射只能这样校验泛型） */
    private static void assertListOf(Class<?> owner, String name, Class<?> elementType, Class<?>... params) {
        Method m = locate(owner, name, params);
        Type generic = m.getGenericReturnType();
        assertInstanceOf(ParameterizedType.class, generic, () -> owner.getName() + "#" + name + " 应返回 List<...>");
        ParameterizedType pt = (ParameterizedType) generic;
        assertEquals(List.class, pt.getRawType(), "原始类型应是 java.util.List");
        assertEquals(elementType, pt.getActualTypeArguments()[0],
                () -> owner.getName() + "#" + name + " 的 List 元素类型变了，下游 for-each 取值会编译失败");
    }

    /** 断言字段存在、private、非 static，且类型一致（JSON 契约 + Lombok getter 的基础） */
    private static void assertField(Class<?> owner, String name, Class<?> type) {
        Field f;
        try {
            f = owner.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            fail(String.format("%s.%s 字段不存在。下游通过 Lombok 生成的 getter 取它，删字段等于删 getter",
                    owner.getName(), name));
            return;
        }
        assertEquals(type, f.getType(),
                () -> String.format("%s.%s 类型变了", owner.getName(), name));
    }

    // ================================================================
    // §2.1 五个门面
    // ================================================================

    @Nested
    @DisplayName("Dynamic 门面")
    class DynamicFacade {

        @Test
        @DisplayName("getDynamicDetail(String) -> Card，且必须声明 throws IOException")
        void getDynamicDetail() {
            assertClassInFacadePackage(Dynamic.class);
            assertPublicNoArgCtor(Dynamic.class);
            assertSignature(Dynamic.class, "getDynamicDetail", BilibiliDynamicResp.Data.Card.class, String.class);
            // ★ 唯一的真编译红线
            assertDeclares(Dynamic.class, "getDynamicDetail", IOException.class, String.class);
        }

        @Test
        @DisplayName("getDynamicImg(String) -> BufferedImage（长图，签名不变）")
        void getDynamicImg() {
            assertSignature(Dynamic.class, "getDynamicImg", BufferedImage.class, String.class);
        }

        @Test
        @DisplayName("getDynamicInfoList(String) -> List<Dynamic.DynamicInfo>")
        void getDynamicInfoList() {
            assertListOf(Dynamic.class, "getDynamicInfoList", Dynamic.DynamicInfo.class, String.class);
        }
    }

    @Nested
    @DisplayName("Live 门面")
    class LiveFacade {

        @Test
        @DisplayName("Live 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Live.class);
            assertPublicNoArgCtor(Live.class);

            assertSignature(Live.class, "getLiveRoom", LiveRoom.class, Long.class);
            assertSignature(Live.class, "getLiveStatus", int.class, Long.class);
            assertSignature(Live.class, "getLiveUrl", String.class, Long.class);
            assertSignature(Live.class, "getLiveTitle", String.class, Long.class);
            assertSignature(Live.class, "getImageUrl", String.class, Long.class);
            assertSignature(Live.class, "getUid", Long.class, Long.class);
            assertSignature(Live.class, "getLiveArea", String.class, Long.class);
            assertSignature(Live.class, "getLiveTime", String.class, Long.class);
        }

        @Test
        @DisplayName("getLiveStatus/getLiveTitle/getImageUrl 必须声明 throws IOException")
        void throwsIOException() {
            assertDeclares(Live.class, "getLiveStatus", IOException.class, Long.class);
            assertDeclares(Live.class, "getLiveTitle", IOException.class, Long.class);
            assertDeclares(Live.class, "getImageUrl", IOException.class, Long.class);
        }
    }

    @Nested
    @DisplayName("CardInfo 门面")
    class CardInfoFacade {

        @Test
        @DisplayName("CardInfo 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(CardInfo.class);
            assertPublicNoArgCtor(CardInfo.class);
            assertSignature(CardInfo.class, "getUserName", String.class, Long.class);
        }
    }

    @Nested
    @DisplayName("BilibiliClient 门面")
    class BilibiliClientFacade {

        @Test
        @DisplayName("BilibiliClient 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(BilibiliClient.class);
            assertPublicNoArgCtor(BilibiliClient.class);

            assertSignature(BilibiliClient.class, "getVideoInfo", VideoInfo.class, String.class);
            assertSignature(BilibiliClient.class, "getVideoInfo", VideoInfo.class, Long.class);
            assertSignature(BilibiliClient.class, "getVideoCoverUrl", String.class, String.class);
            assertSignature(BilibiliClient.class, "getVideoAv", Long.class, String.class);
            assertSignature(BilibiliClient.class, "getVideoTitle", String.class, String.class);
            assertSignature(BilibiliClient.class, "getVideoDesc", String.class, String.class);
        }

        @Test
        @DisplayName("getVideoInfo(String/Long) 必须声明 throws IOException")
        void throwsIOException() {
            assertDeclares(BilibiliClient.class, "getVideoInfo", IOException.class, String.class);
            assertDeclares(BilibiliClient.class, "getVideoInfo", IOException.class, Long.class);
        }
    }

    @Nested
    @DisplayName("ShortChain 门面")
    class ShortChainFacade {

        @Test
        @DisplayName("ShortChain 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(ShortChain.class);
            assertPublicNoArgCtor(ShortChain.class);

            // 构造器 ShortChain(String url)
            try {
                assertTrue(Modifier.isPublic(ShortChain.class.getConstructor(String.class).getModifiers()),
                        "ShortChain(String) 必须是 public");
            } catch (NoSuchMethodException e) {
                fail("ShortChain(String) 构造器不见了（下游用它解析短链）");
            }

            assertSignature(ShortChain.class, "getShotChainInfo", ShotChainInfo.class);
            assertSignature(ShortChain.class, "getLiveRoom", LiveRoom.class);
            assertSignature(ShortChain.class, "getVideoInfo", VideoInfo.class);
            assertSignature(ShortChain.class, "getDynamicCard", BilibiliDynamicResp.Data.Card.class);
        }
    }

    // ================================================================
    // §2.1.6 第 6 个门面：Login（2026-09-16 新增）
    // ================================================================

    @Nested
    @DisplayName("Login 门面")
    class LoginFacade {

        @Test
        @DisplayName("Login 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Login.class);
            assertPublicNoArgCtor(Login.class);

            // 取得凭据：扫码
            assertSignature(Login.class, "getLoginQrCode", QrCodeLogin.class);
            assertSignature(Login.class, "getLoginStatus", QrLoginStatus.class, String.class);
            assertSignature(Login.class, "waitForLogin", LoginCredential.class, String.class, long.class);

            // 取得凭据：密码 / 短信
            assertSignature(Login.class, "getCaptcha", LoginCaptcha.class);
            assertSignature(Login.class, "getRsaKey", RsaKeyInfo.class);
            assertSignature(Login.class, "loginByPassword", LoginCredential.class,
                    String.class, String.class, LoginCaptcha.class, GeeTestValidation.class);
            assertSignature(Login.class, "sendSmsCode", SmsSendResult.class,
                    String.class, LoginCaptcha.class, GeeTestValidation.class);
            assertSignature(Login.class, "sendSmsCode", SmsSendResult.class,
                    String.class, String.class, LoginCaptcha.class, GeeTestValidation.class);
            assertSignature(Login.class, "loginBySms", LoginCredential.class,
                    String.class, String.class, String.class);
            assertSignature(Login.class, "loginBySms", LoginCredential.class,
                    String.class, String.class, String.class, String.class);

            // 校验凭据（2026-09-16 新增）：问服务端"这枚还活着吗"
            assertSignature(Login.class, "getCredentialStatus", CredentialStatus.class);
        }

        @Test
        @DisplayName("Login 门面所有网络方法必须声明 throws IOException")
        void declares() {
            for (String name : List.of("getLoginQrCode", "getCaptcha", "getRsaKey", "getCredentialStatus")) {
                assertDeclares(Login.class, name, IOException.class);
            }
            assertDeclares(Login.class, "getLoginStatus", IOException.class, String.class);
            assertDeclares(Login.class, "waitForLogin", IOException.class, String.class, long.class);
            assertDeclares(Login.class, "loginByPassword", IOException.class,
                    String.class, String.class, LoginCaptcha.class, GeeTestValidation.class);
            assertDeclares(Login.class, "sendSmsCode", IOException.class,
                    String.class, LoginCaptcha.class, GeeTestValidation.class);
            assertDeclares(Login.class, "sendSmsCode", IOException.class,
                    String.class, String.class, LoginCaptcha.class, GeeTestValidation.class);
            assertDeclares(Login.class, "loginBySms", IOException.class,
                    String.class, String.class, String.class);
            assertDeclares(Login.class, "loginBySms", IOException.class,
                    String.class, String.class, String.class, String.class);
        }
    }

    // ================================================================
    // §2.2 冻结模型
    // ================================================================

    @Nested
    @DisplayName("冻结模型 Dynamic.DynamicInfo")
    class DynamicInfoModel {

        @Test
        @DisplayName("必须是 Dynamic 的 public static 内部类（下游按 Dynamic.DynamicInfo 引用）")
        void nestedTypes() {
            Class<?> info = Dynamic.DynamicInfo.class;
            assertTrue(Modifier.isPublic(info.getModifiers()), "DynamicInfo 必须 public");
            assertTrue(Modifier.isStatic(info.getModifiers()),
                    "DynamicInfo 必须 static —— 它没有隐式持有外部类实例，下游可独立引用");
            assertEquals(Dynamic.class, info.getDeclaringClass(), "DynamicInfo 必须仍是 Dynamic 的内部类");
        }

        @Test
        @DisplayName("字段名/类型不变（Lombok getter 依赖它们）")
        void fields() {
            Class<?> info = Dynamic.DynamicInfo.class;
            assertField(info, "dynamicId", String.class);
            assertField(info, "tag", String.class);
            assertField(info, "time", String.class);
            assertField(info, "title", String.class);
            assertField(info, "desc", String.class);
            assertField(info, "imageUrl", List.class);
            assertField(info, "bvid", String.class);
            assertField(info, "shareDynamicId", String.class);
            // 2026-09-14 新增的两个附加字段（关注流 feed/all 需要：一个用来归到订阅，一个用来省掉名片请求）
            assertField(info, "uid", String.class);
            assertField(info, "userName", String.class);
        }

        @Test
        @DisplayName("下游实际调用的 4 个 getter 必须在且返回 String")
        void gettersUsedByDownstream() {
            Class<?> info = Dynamic.DynamicInfo.class;
            assertSignature(info, "getTime", String.class);
            assertSignature(info, "getBvid", String.class);
            assertSignature(info, "getDynamicId", String.class);
            assertSignature(info, "getShareDynamicId", String.class);
        }
    }

    @Nested
    @DisplayName("冻结模型 BilibiliDynamicResp")
    class DynamicRespModel {

        @Test
        @DisplayName("嵌套链路 Data.Card.Desc.UserProfile.Info 全部保留")
        void nestedStructure() {
            assertNotNull(BilibiliDynamicResp.Data.class);
            assertNotNull(BilibiliDynamicResp.Data.Card.class);
            assertNotNull(BilibiliDynamicResp.Data.Card.Desc.class);
            assertNotNull(BilibiliDynamicResp.Data.Card.Desc.UserProfile.class);
            assertNotNull(BilibiliDynamicResp.Data.Card.Desc.UserProfile.Info.class);

            assertEquals(BilibiliDynamicResp.class, BilibiliDynamicResp.Data.class.getDeclaringClass());
            assertEquals(BilibiliDynamicResp.Data.class, BilibiliDynamicResp.Data.Card.class.getDeclaringClass());
            assertEquals(BilibiliDynamicResp.Data.Card.class, BilibiliDynamicResp.Data.Card.Desc.class.getDeclaringClass());
            assertEquals(BilibiliDynamicResp.Data.Card.Desc.class,
                    BilibiliDynamicResp.Data.Card.Desc.UserProfile.class.getDeclaringClass());
            assertEquals(BilibiliDynamicResp.Data.Card.Desc.UserProfile.class,
                    BilibiliDynamicResp.Data.Card.Desc.UserProfile.Info.class.getDeclaringClass());
        }

        @Test
        @DisplayName("下游实际读取的两条路径：desc.dynamic_id_str 与 desc.user_profile.info.uname")
        void pathsUsedByDownstream() {
            Class<?> card = BilibiliDynamicResp.Data.Card.class;
            assertSignature(card, "getDesc", BilibiliDynamicResp.Data.Card.Desc.class);

            Class<?> desc = BilibiliDynamicResp.Data.Card.Desc.class;
            assertSignature(desc, "getDynamic_id_str", String.class);
            assertSignature(desc, "getUser_profile", BilibiliDynamicResp.Data.Card.Desc.UserProfile.class);

            Class<?> info = BilibiliDynamicResp.Data.Card.Desc.UserProfile.Info.class;
            assertSignature(info, "getUname", String.class);
        }
    }

    // ================================================================
    // 稳定性：门面必须都是 public class，且都在同一个包下
    // ================================================================

    @Test
    @DisplayName("6 个门面都必须在 com.esdllm.bilibiliApi.bilibiliApi 下")
    void facadePackageNamesUnchanged() {
        List<Class<?>> facades = List.of(Dynamic.class, Live.class, CardInfo.class,
                BilibiliClient.class, ShortChain.class, Login.class);
        for (Class<?> facade : facades) {
            assertClassInFacadePackage(facade);
        }
        assertEquals(6, facades.stream().filter(Objects::nonNull).count(), "门面数量不应变化");
    }
}

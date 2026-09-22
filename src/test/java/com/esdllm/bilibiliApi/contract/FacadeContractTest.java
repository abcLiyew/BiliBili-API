package com.esdllm.bilibiliApi.contract;

import com.esdllm.bilibiliApi.bilibiliApi.*;
import com.esdllm.bilibiliApi.common.ShotChainInfo;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.model.data.VideoInfo;
import com.esdllm.bilibiliApi.model.data.pojo.LiveRoom;
import com.esdllm.bilibiliApi.model.data.pojo.login.*;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchAllResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchTypeResult;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchUser;
import com.esdllm.bilibiliApi.model.data.pojo.search.SearchVideo;
import com.esdllm.bilibiliApi.model.data.pojo.content.ArticleInfo;
import com.esdllm.bilibiliApi.model.data.pojo.content.FavFolderList;
import com.esdllm.bilibiliApi.model.data.pojo.content.HistoryCursor;
import com.esdllm.bilibiliApi.model.data.pojo.content.ToViewList;
import com.esdllm.bilibiliApi.model.data.pojo.comment.CommentPage;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuItem;
import com.esdllm.bilibiliApi.model.data.pojo.danmaku.DanmakuXml;
import com.esdllm.bilibiliApi.model.data.pojo.live.LiveStream;
import com.esdllm.bilibiliApi.model.data.pojo.live.MasterInfo;
import com.esdllm.bilibiliApi.model.data.pojo.user.AccInfo;
import com.esdllm.bilibiliApi.model.data.pojo.user.ArchiveSearchResult;
import com.esdllm.bilibiliApi.model.data.pojo.user.RelationList;
import com.esdllm.bilibiliApi.model.data.pojo.user.RelationStat;
import com.esdllm.bilibiliApi.model.data.pojo.user.SeasonsArchives;
import com.esdllm.bilibiliApi.model.data.pojo.user.UpStat;
import com.esdllm.bilibiliApi.model.data.pojo.video.AiSummary;
import com.esdllm.bilibiliApi.model.data.pojo.video.OnlineTotal;
import com.esdllm.bilibiliApi.model.data.pojo.video.PlayUrl;
import com.esdllm.bilibiliApi.model.data.pojo.video.PopularList;
import com.esdllm.bilibiliApi.model.data.pojo.video.RankingList;
import com.esdllm.bilibiliApi.model.data.pojo.video.ViewDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    /** 门面所在的包，必须不变 */
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

    /**
     * 断言返回类型是 {@code 某泛型类<实参>}。
     *
     * <p>存在的理由：反射看到的返回类型是<b>擦除后</b>的原始类型，
     * 泛型实参写错（例如 {@code SearchTypeResult<SearchUser>} 写成
     * {@code SearchTypeResult<SearchVideo>}）在本测试里会"看起来通过"，
     * 而调用方 for-each 取元素时才在编译期炸。所以泛型实参必须单独断言。
     */
    private static void assertGenericReturn(Class<?> owner, String name, Class<?> rawType,
                                            Class<?> typeArgument, Class<?>... params) {
        Method m = locate(owner, name, params);
        Type generic = m.getGenericReturnType();
        assertInstanceOf(ParameterizedType.class, generic,
                () -> owner.getName() + "#" + name + " 应返回 " + rawType.getSimpleName() + "<...>");
        ParameterizedType pt = (ParameterizedType) generic;
        assertEquals(rawType, pt.getRawType(),
                () -> owner.getName() + "#" + name + " 的原始返回类型变了");
        assertEquals(typeArgument, pt.getActualTypeArguments()[0],
                () -> owner.getName() + "#" + name + " 的泛型实参变了，下游 for-each 取值会编译失败");
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
    // §2.1 门面（前 6 个 = 冻结红线；第 7~9 个 = 2026-09-21 WBI 批新增，纯增量）
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
    // §2.1.7 ~ §2.1.9 第 7~9 个门面：Search / UserSpace / VideoExtra（2026-09-21 WBI 批）
    //
    // 与 Login 同理：新增类 + 新增方法，**不触碰前 6 个门面的任何一行**。
    // 这三块的作用不是"防止本次改动破坏了什么"（它们是新写的，没有历史契约），
    // 而是**把本次的公开面固化下来** —— 从交付这一刻起，它们就进入同一套冻结纪律，
    // 后续批次（B1 的 VideoExtra/Comment/UserSpace 扩容等）只能加、不能改。
    // ================================================================

    @Nested
    @DisplayName("Search 门面（第 7 个）")
    class SearchFacade {

        @Test
        @DisplayName("Search 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Search.class);
            assertPublicNoArgCtor(Search.class);

            assertSignature(Search.class, "searchAll", SearchAllResult.class, String.class, int.class);
            assertSignature(Search.class, "searchVideos",
                    SearchTypeResult.class, String.class, int.class);
            assertSignature(Search.class, "searchUsers",
                    SearchTypeResult.class, String.class, int.class);
        }

        @Test
        @DisplayName("三个搜索方法都必须声明 throws IOException（门面边界统一口径）")
        void declares() {
            assertDeclares(Search.class, "searchAll", IOException.class, String.class, int.class);
            assertDeclares(Search.class, "searchVideos", IOException.class, String.class, int.class);
            assertDeclares(Search.class, "searchUsers", IOException.class, String.class, int.class);
        }

        /**
         * 分类搜索的返回类型是<b>泛型</b> {@code SearchTypeResult<T>}，
         * 而反射只能看到擦除后的 {@code SearchTypeResult} —— 泛型实参错了编译期不报错、
         * 调用方 for-each 时才炸。这里单独断言泛型实参。
         */
        @Test
        @DisplayName("searchVideos/searchUsers 的泛型实参必须是 SearchVideo/SearchUser")
        void genericArguments() {
            assertGenericReturn(Search.class, "searchVideos", SearchTypeResult.class, SearchVideo.class,
                    String.class, int.class);
            assertGenericReturn(Search.class, "searchUsers", SearchTypeResult.class, SearchUser.class,
                    String.class, int.class);
        }
    }

    @Nested
    @DisplayName("UserSpace 门面（第 8 个）")
    class UserSpaceFacade {

        @Test
        @DisplayName("UserSpace 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(UserSpace.class);
            assertPublicNoArgCtor(UserSpace.class);

            assertSignature(UserSpace.class, "getAccInfo", AccInfo.class, long.class);
            assertSignature(UserSpace.class, "getArchives", ArchiveSearchResult.class,
                    long.class, int.class, int.class);
            assertSignature(UserSpace.class, "getArchives", ArchiveSearchResult.class,
                    long.class, int.class, int.class, String.class);
            assertSignature(UserSpace.class, "getSeasonArchives", SeasonsArchives.class,
                    long.class, long.class, int.class, int.class);
            // 可能返回 null（首页投稿里没有合集稿件），因此是包装类型 Long
            assertSignature(UserSpace.class, "findSeasonId", Long.class, long.class);

            // 2026-09-22 B3.5 扩容（凭据解锁）：三项实测都是「真需登录」那一档。
            // 注意缺凭据时它们**不是同一种错**：getUpStat 回 code=0 + 空 data（最阴），
            // 而 followers/followings 回 -101 —— 门面契约只钉签名，门槛差异记在 UserSpace 的类注释里。
            assertSignature(UserSpace.class, "getUpStat", UpStat.class, long.class);
            assertSignature(UserSpace.class, "getFollowers", RelationList.class,
                    long.class, int.class, int.class);
            assertSignature(UserSpace.class, "getFollowings", RelationList.class,
                    long.class, int.class, int.class);

            // 2026-09-22 B1：关系数。⚠️ 它是本门面**唯一不需要凭据**的方法，
            // 且查任意用户都行；与上面的名单端点（-101、只限本人）同域、同参数名（vmid）、
            // 名字也像 —— 最容易被人"顺手合并"的一对，所以两处都单独钉住。
            assertSignature(UserSpace.class, "getRelationStat", RelationStat.class, long.class);
        }

        @Test
        @DisplayName("UserSpace 门面所有网络方法必须声明 throws IOException")
        void declares() {
            assertDeclares(UserSpace.class, "getAccInfo", IOException.class, long.class);
            assertDeclares(UserSpace.class, "getArchives", IOException.class, long.class, int.class, int.class);
            assertDeclares(UserSpace.class, "getArchives", IOException.class,
                    long.class, int.class, int.class, String.class);
            assertDeclares(UserSpace.class, "getSeasonArchives", IOException.class,
                    long.class, long.class, int.class, int.class);
            assertDeclares(UserSpace.class, "findSeasonId", IOException.class, long.class);
            assertDeclares(UserSpace.class, "getUpStat", IOException.class, long.class);
            assertDeclares(UserSpace.class, "getFollowers", IOException.class, long.class, int.class, int.class);
            assertDeclares(UserSpace.class, "getFollowings", IOException.class, long.class, int.class, int.class);
            assertDeclares(UserSpace.class, "getRelationStat", IOException.class, long.class);
        }
    }

    @Nested
    @DisplayName("VideoExtra 门面（第 9 个）")
    class VideoExtraFacade {

        @Test
        @DisplayName("VideoExtra 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(VideoExtra.class);
            assertPublicNoArgCtor(VideoExtra.class);

            assertSignature(VideoExtra.class, "getAiSummary", AiSummary.class, String.class);
            assertSignature(VideoExtra.class, "getAiSummary", AiSummary.class, String.class, Long.class);

            // 2026-09-22 B3.5：播放地址。本批唯一「匿名也能用」的一项 ——
            // 凭据买到的是**更高清晰度**（MP4 封顶 720P，DASH 到 1080P），不是"能不能用"。
            assertSignature(VideoExtra.class, "getPlayUrl", PlayUrl.class, String.class, Long.class);
            assertSignature(VideoExtra.class, "getPlayUrl", PlayUrl.class,
                    String.class, Long.class, Integer.class, Integer.class);

            // 2026-09-22 B1：两项**真·零门槛**。加进来之后本门面的门槛表才完整 ——
            // 在此之前它四项里三项都"可能失败"（签名/凭据/出口信誉），容易让人以为整个类都难用。
            assertSignature(VideoExtra.class, "getViewDetail", ViewDetail.class, String.class);
            assertSignature(VideoExtra.class, "getOnlineTotal", OnlineTotal.class, String.class, Long.class);
        }

        @Test
        @DisplayName("六个方法（含 B1 新增的两个）都必须声明 throws IOException")
        void declares() {
            assertDeclares(VideoExtra.class, "getAiSummary", IOException.class, String.class);
            assertDeclares(VideoExtra.class, "getAiSummary", IOException.class, String.class, Long.class);
            assertDeclares(VideoExtra.class, "getPlayUrl", IOException.class, String.class, Long.class);
            assertDeclares(VideoExtra.class, "getPlayUrl", IOException.class,
                    String.class, Long.class, Integer.class, Integer.class);
            assertDeclares(VideoExtra.class, "getViewDetail", IOException.class, String.class);
            assertDeclares(VideoExtra.class, "getOnlineTotal", IOException.class, String.class, Long.class);
        }
    }

    // ================================================================
    // §2.1.10 第 10 个门面：Wbi（2026-09-21）
    //
    // 它是本库第一块**不是数据接口**的公开面：不发请求、只算签名，用来支持本库尚未覆盖的
    // WBI 端点。纳入冻结集的理由与前面三块同理 —— 交付即承诺，后续只能加、不能改。
    // ================================================================

    @Nested
    @DisplayName("Wbi 门面（第 10 个）")
    class WbiFacade {

        @Test
        @DisplayName("Wbi 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Wbi.class);
            assertPublicNoArgCtor(Wbi.class);

            assertSignature(Wbi.class, "signQuery", String.class, Map.class);
            assertSignature(Wbi.class, "signQuery", String.class, Map.class, long.class);
            assertSignature(Wbi.class, "signQuery", String.class, Map.class,
                    String.class, String.class, long.class);
            assertSignature(Wbi.class, "signedUrl", String.class, String.class, Map.class);
            assertSignature(Wbi.class, "invalidateKeys", void.class);
        }

        @Test
        @DisplayName("联网方法必须声明 throws IOException；离线重载刻意不声明")
        void declares() {
            assertDeclares(Wbi.class, "signQuery", IOException.class, Map.class);
            assertDeclares(Wbi.class, "signQuery", IOException.class, Map.class, long.class);
            assertDeclares(Wbi.class, "signedUrl", IOException.class, String.class, Map.class);

            // 四参重载不联网。逼调用方 catch 一个永不抛出的受检异常纯属噪音 ——
            // 所以这里反向断言：它**不该**声明 IOException，免得后人"为了统一"把它加上。
            Method offline = locate(Wbi.class, "signQuery",
                    Map.class, String.class, String.class, long.class);
            assertFalse(Arrays.asList(offline.getExceptionTypes()).contains(IOException.class),
                    "离线重载不产生 I/O，不应声明 IOException");
        }
    }

    // ================================================================
    // §2.1.11 第 11 个门面：Content（2026-09-22 B3.5 批）
    //
    // B3.5 一共 6 项能力，为什么只多出**一个**门面（而不是 6 个）：见 INTERFACE_PLAN.md §7-Q1
    // 决策 (d)「混合：高频域独立 + 低频域合并」—— 观看历史 / 稍后再看 / 收藏夹目录彼此无关、
    // 频率低、又都属于"我自己的内容"，硬拆成三个类只会抬高调用方的认知成本。
    //
    // ⚠️ 这个门面全是 **GET 只读**：往稍后再看里增删、清空历史、收藏/取消收藏都不在库里。
    // 那条边界（写操作需 csrf 且会改动账号）必须靠评审守，反射断言只能钉住"现在没有写方法"。
    //
    // 🆕 2026-09-22 B4 批：补进第 5 个方法 getArticleInfo(long)（专栏信息 x/article/viewinfo）。
    //    它是本门面**唯一免凭据**的方法 —— 前四项（历史 / 稍后再看 / 收藏夹）不带 SESSDATA
    //    一律失败，而专栏信息匿名就能拿到 23 个字段。这正好解释了这个门面为什么名义上叫
    //    "我的内容"却容得下一个公开端点：它解决的是**同一个下游问题**（"我这一侧与内容的关系"），
    //    不是同一个鉴权域。签名与其余四项保持一致（同抛 IOException），不额外分裂返回风格。
    //    同时它也是**只加方法、不加类**的范例：门面计数仍是 15，第 11 个。
    // ================================================================

    @Nested
    @DisplayName("Content 门面（第 11 个）")
    class ContentFacade {

        @Test
        @DisplayName("Content 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Content.class);
            assertPublicNoArgCtor(Content.class);

            assertSignature(Content.class, "getWatchHistory", HistoryCursor.class, int.class);
            assertSignature(Content.class, "getWatchHistory", HistoryCursor.class,
                    Integer.class, Long.class, Long.class, String.class);
            assertSignature(Content.class, "getToView", ToViewList.class);
            assertSignature(Content.class, "getFavoriteFolders", FavFolderList.class, long.class);
            assertSignature(Content.class, "getArticleInfo", ArticleInfo.class, long.class);
        }

        @Test
        @DisplayName("五个方法都必须声明 throws IOException（前四项依赖凭据；专栏信息是唯一免凭据的，"
                + "但网络失败同样是常态，所以一视同仁）")
        void declares() {
            assertDeclares(Content.class, "getWatchHistory", IOException.class, int.class);
            assertDeclares(Content.class, "getWatchHistory", IOException.class,
                    Integer.class, Long.class, Long.class, String.class);
            assertDeclares(Content.class, "getToView", IOException.class);
            assertDeclares(Content.class, "getFavoriteFolders", IOException.class, long.class);
            assertDeclares(Content.class, "getArticleInfo", IOException.class, long.class);
        }

        /**
         * 反射只能证明"当前没有写方法"，不能阻止以后加 —— 但把这条断言写在契约里，
         * 至少让"往后门面里塞写操作"变成一个需要显式删掉本用例的动作。
         */
        @Test
        @DisplayName("★ 必须保持只读：不存在 set/add/delete/create/remove/clear 之类的方法")
        void staysReadOnly() {
            List<String> verbPrefixes = List.of("set", "add", "delete", "remove", "create", "clear", "update");
            for (Method m : Content.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                assertFalse(verbPrefixes.stream().anyMatch(p -> m.getName().startsWith(p)),
                        () -> "Content 是只读门面，不该出现像写操作的方法：" + m.getName()
                                + "（写操作需 csrf 且会改动账号，若要做必须另立门面）");
            }
        }
    }

    // ================================================================
    // §2.1.12 ~ §2.1.14 第 12~14 个门面：Comment / LiveExtra / Ranking（2026-09-22 B1 批）
    //
    // B1 一共 11 项能力，为什么只多出**三个**门面（而不是 11 个）：见 INTERFACE_PLAN.md
    // §7-Q1 决策 (d)「混合：高频域独立 + 低频域合并」——
    //   · Comment / Ranking 属高频域 ⇒ 独立成类；
    //   · 直播域的两项（拉流地址 / 主播信息）低频但同域 ⇒ 合并进 LiveExtra；
    //   · 其余四项（标签 / 相关推荐 / 状态数）**根本不是新端点**，而是复用 view/detail
    //     与既有 Live/UserSpace 门面的方法扩容 ⇒ 只加方法、不加类。
    //
    // 与之前每一批同理：新增类 + 新增方法，**不触碰前 11 个门面的任何一行**。
    // 这三块的作用不是"防止本次改动破坏了什么"，而是**把本次的公开面固化下来** ——
    // 从交付这一刻起，它们进入同一套冻结纪律，后续只能加、不能改。
    // ================================================================

    @Nested
    @DisplayName("Comment 门面（第 12 个）")
    class CommentFacade {

        @Test
        @DisplayName("Comment 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Comment.class);
            assertPublicNoArgCtor(Comment.class);

            // 收 aid 的两条：已知 aid 时用，0 次额外请求
            assertSignature(Comment.class, "getReplies", CommentPage.class, long.class, int.class, int.class);
            assertSignature(Comment.class, "getReplies", CommentPage.class,
                    long.class, int.class, int.class, int.class);
            // 收 bvid 的一条：内部先打一次 view 换 aid（多花一次请求，但不会静默拿空列表）
            assertSignature(Comment.class, "getRepliesByBvid", CommentPage.class,
                    String.class, int.class, int.class);
        }

        @Test
        @DisplayName("三个方法都必须声明 throws IOException")
        void declares() {
            assertDeclares(Comment.class, "getReplies", IOException.class, long.class, int.class, int.class);
            assertDeclares(Comment.class, "getReplies", IOException.class,
                    long.class, int.class, int.class, int.class);
            assertDeclares(Comment.class, "getRepliesByBvid", IOException.class,
                    String.class, int.class, int.class);
        }

        /**
         * 与 {@code Content} 的同名反向断言同一思路：反射只能证明"当前没有写方法"，
         * 但把它写进契约，至少让"往评论门面里塞发表/删除"变成一个需要显式删用例的动作。
         */
        @Test
        @DisplayName("★ 必须保持只读：不存在 set/add/delete/remove/create/clear 之类的方法")
        void staysReadOnly() {
            List<String> verbPrefixes = List.of("set", "add", "delete", "remove", "create", "clear", "update");
            for (Method m : Comment.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                assertFalse(verbPrefixes.stream().anyMatch(p -> m.getName().startsWith(p)),
                        () -> "Comment 是只读门面，不该出现像写操作的方法：" + m.getName()
                                + "（发表/删除评论需 csrf 且会改动账号，本库不做）");
            }
        }
    }

    @Nested
    @DisplayName("LiveExtra 门面（第 13 个）")
    class LiveExtraFacade {

        @Test
        @DisplayName("LiveExtra 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(LiveExtra.class);
            assertPublicNoArgCtor(LiveExtra.class);

            // ⚠️ 两个方法的参数语义不同：流地址收**房间号**、主播信息收**主播 uid**。
            // 两者都是 Long，反射上"看起来一样"，所以只能靠这条注释与各自的测试守。
            assertSignature(LiveExtra.class, "getLiveStream", LiveStream.class, Long.class);
            assertSignature(LiveExtra.class, "getLiveStream", LiveStream.class, Long.class, Integer.class);
            assertSignature(LiveExtra.class, "getMasterInfo", MasterInfo.class, Long.class);
        }

        @Test
        @DisplayName("三个方法都必须声明 throws IOException")
        void declares() {
            assertDeclares(LiveExtra.class, "getLiveStream", IOException.class, Long.class);
            assertDeclares(LiveExtra.class, "getLiveStream", IOException.class, Long.class, Integer.class);
            assertDeclares(LiveExtra.class, "getMasterInfo", IOException.class, Long.class);
        }

        /**
         * 上游 {@code Live} 门面是冻结红线（XatiiBot 直接依赖），新能力必须走新类。
         * 这条断言把"新能力没被塞进 Live"也钉住 —— 否则一次"顺手加个方法"就会
         * 悄悄改变一个下游正在依赖的类的形状。
         */
        @Test
        @DisplayName("★ 新能力不许塞进冻结的 Live 门面：Live 上不能出现 getLiveStream/getMasterInfo")
        void newCapabilityStaysOutOfFrozenLive() {
            for (String name : List.of("getLiveStream", "getMasterInfo")) {
                boolean present = Arrays.stream(Live.class.getMethods())
                        .anyMatch(m -> m.getName().equals(name));
                assertFalse(present, "Live 门面是下游逐字依赖的冻结契约，新能力一律走 LiveExtra：" + name);
            }
        }
    }

    @Nested
    @DisplayName("Ranking 门面（第 14 个）")
    class RankingFacade {

        @Test
        @DisplayName("Ranking 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Ranking.class);
            assertPublicNoArgCtor(Ranking.class);

            assertSignature(Ranking.class, "getRanking", RankingList.class, int.class);
            assertSignature(Ranking.class, "getRanking", RankingList.class, int.class, String.class);
            assertSignature(Ranking.class, "getPopular", PopularList.class, int.class, int.class);
        }

        @Test
        @DisplayName("三个方法都必须声明 throws IOException")
        void declares() {
            assertDeclares(Ranking.class, "getRanking", IOException.class, int.class);
            assertDeclares(Ranking.class, "getRanking", IOException.class, int.class, String.class);
            assertDeclares(Ranking.class, "getPopular", IOException.class, int.class, int.class);
        }

        /**
         * 榜单与热门<b>返回类型不同</b>（外层容器不一样：一个多 {@code note}、一个多 {@code no_more}），
         * 元素形状虽然都是 {@code VideoBrief}，但不能互相接收。
         * 这条断言把两个外层类型钉住，避免有人为了"统一"把它们合并成一个类。
         */
        @Test
        @DisplayName("★ 榜单与热门的返回类型必须分开：RankingList / PopularList 不能合并")
        void twoContainerTypes() {
            assertSignature(Ranking.class, "getRanking", RankingList.class, int.class);
            assertSignature(Ranking.class, "getPopular", PopularList.class, int.class, int.class);
            assertNotEquals(RankingList.class, PopularList.class);
        }
    }

    // ================================================================
    // §2.1.15 第 15 个门面：Danmaku（2026-09-22 B2 批）
    //
    // 为什么单独立类而不是塞进 Comment：弹幕挂 **cid**、评论挂 **aid**，两者的 id 体系都不同，
    // 合进一个类只会制造"到底该传哪个 id"的混淆。见 INTERFACE_PLAN.md §7-Q1 决策 (d)。
    //
    // ⚠️ 本门面是全库唯一**返回 XML** 的公开面（其它全是 JSON），所以它不遵循
    // "code/data 外层"那套惯例 —— 这是它必须被写进契约文档、让后人看得见的原因。
    // ================================================================

    @Nested
    @DisplayName("Danmaku 门面（第 15 个）")
    class DanmakuFacade {

        @Test
        @DisplayName("Danmaku 门面的公开方法签名与无参构造器")
        void methodSignatures() {
            assertClassInFacadePackage(Danmaku.class);
            assertPublicNoArgCtor(Danmaku.class);

            // 收 cid 的返回 XML 包装（带 maxlimit，用于判断有没有被截断）
            assertSignature(Danmaku.class, "getDanmaku", DanmakuXml.class, long.class);
            // 只要文本列表的便捷重载
            assertListOf(Danmaku.class, "getDanmakuList", DanmakuItem.class, long.class);
        }

        @Test
        @DisplayName("两个方法都必须声明 throws IOException（门面边界统一口径）")
        void declares() {
            assertDeclares(Danmaku.class, "getDanmaku", IOException.class, long.class);
            assertDeclares(Danmaku.class, "getDanmakuList", IOException.class, long.class);
        }

        /**
         * 与 {@code Comment} / {@code Content} 的同名反向断言同一思路：反射只能证明
         * "当前没有写方法"，但把它写进契约，至少让"往弹幕门面里塞发/删弹幕"变成一个
         * 需要显式删用例的动作。
         */
        @Test
        @DisplayName("★ 必须保持只读：不存在 set/add/delete/remove/create/clear 之类的方法")
        void staysReadOnly() {
            List<String> verbPrefixes = List.of("set", "add", "delete", "remove", "create", "clear", "update");
            for (Method m : Danmaku.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                assertFalse(verbPrefixes.stream().anyMatch(p -> m.getName().startsWith(p)),
                        () -> "Danmaku 是只读门面，不该出现像写操作的方法：" + m.getName()
                                + "（发/删弹幕需 csrf 且会改动账号，本库不做）");
            }
        }

        /**
         * 弹幕与评论的 id 体系不同（{@code cid} vs {@code aid}），
         * 所以 {@code Comment} 里不该出现弹幕方法、{@code Danmaku} 里也不该出现评论方法。
         * 两个类都在同一包下、名字都像，最容易被人"顺手合并"。
         */
        @Test
        @DisplayName("★ 弹幕不许塞进 Comment 门面：Comment 上不能出现 getDanmaku 之类")
        void danmakuStaysOutOfFrozenComment() {
            for (String name : List.of("getDanmaku", "getDanmakuList")) {
                boolean present = Arrays.stream(Comment.class.getMethods())
                        .anyMatch(m -> m.getName().equals(name));
                assertFalse(present,
                        "Comment 门面收 aid、Danmaku 收 cid，两者的 id 体系不同，不要合并：" + name);
            }
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
    @DisplayName("15 个门面都必须在 com.esdllm.bilibiliApi.bilibiliApi 下")
    void facadePackageNamesUnchanged() {
        List<Class<?>> facades = List.of(Dynamic.class, Live.class, CardInfo.class,
                BilibiliClient.class, ShortChain.class, Login.class,
                Search.class, UserSpace.class, VideoExtra.class, Wbi.class, Content.class,
                Comment.class, LiveExtra.class, Ranking.class, Danmaku.class);
        for (Class<?> facade : facades) {
            assertClassInFacadePackage(facade);
        }
        assertEquals(15, facades.stream().filter(Objects::nonNull).count(), "门面数量不应变化");
    }
}

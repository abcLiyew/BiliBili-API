package com.esdllm.bilibiliApi.service;

import com.esdllm.bilibiliApi.bilibiliApi.Dynamic;
import com.esdllm.bilibiliApi.exception.BilibiliException;
import com.esdllm.bilibiliApi.http.AnonymousSession;
import com.esdllm.bilibiliApi.http.HttpPolicy;
import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.render.HttpImageFetcher;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DynamicService} 直接单测 —— 本库最复杂的一个 Service。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li><b>三条链路</b>（详情 / 长图 / 列表）的正常映射与失败路径；</li>
 *   <li><b>异常语义的分界</b>：参数校验抛 {@link BilibiliException}（runtime），
 *       取数失败抛 {@link IOException}（受检）—— 混了会让下游 catch 兜不住；</li>
 *   <li><b>desktop feed 的两种失败形态</b>（2026-09-13 实测发现）：
 *       {@code code=-352} 抛异常；{@code code=0 + items=[]} 是"静默空"
 *       —— 2026-09-13 起改为<b>换一副匿名身份再取一次</b>（见 {@link EmptyFeedRetry} 套件），
 *       仍为空才按"该 UP 没发动态"返回空列表。</li>
 * </ol>
 *
 * <h2>⚠️ 已知的异常类型不一致（本测试锁定现状，未修）</h2>
 * <ul>
 *   <li>{@code getDetail} / {@code getImg}：失败 → <b>IOException</b>（catch BilibiliException 后包成受检异常）；</li>
 *   <li>{@code getInfoList}：声明了 {@code throws IOException}，但<b>所有失败分支实际都抛
 *       BilibiliException</b>（runtime）。</li>
 * </ul>
 * 功能无碍（门面 {@code Dynamic.getDynamicInfoList} 同时 catch 了 IOException 与
 * BilibiliException；下游 XatiiBot 用 {@code catch (Exception)}），但语义不统一，
 * 属可选的后续清理项。
 */
class DynamicServiceTest {

    private static final String DETAIL_PATH = "/x/polymer/web-dynamic/v1/detail";
    private static final String FEED_PATH = "/x/polymer/web-dynamic/v1/feed/space";

    /** 关注流路径（{@code feed/all}，2026-09-14 新增的替代数据源） */
    private static final String FOLLOW_PATH = "/x/polymer/web-dynamic/v1/feed/all";
    private static final String LEGACY_FIXTURE = "src/test/resources/fixtures/dynamic-detail-legacy.json";
    private static final String FEED_FIXTURE = "src/test/resources/fixtures/dynamic-feed-desktop.json";

    /** 匿名指纹接口路径（{@code AnonymousSession} 内部常量的字面量对齐） */
    private static final String FINGER_PATH = "/x/frontend/finger/spi";

    /**
     * 一份合法的指纹响应。
     * <p>有了它，{@code AnonymousSession.rotate()} 完全走本机 mock ——
     * 否则每次"空列表换身份重试"都会真的去打 {@code api.bilibili.com}（既慢又不可控）。
     */
    private static final String FINGER_BODY =
            "{\"code\":0,\"message\":\"0\",\"data\":{\"b_3\":\"MOCK-BUVID3-0000\",\"b_4\":\"MOCK-BUVID4-0000\"}}";

    /** 静默风控形态：业务码 0，但 items 为空 */
    private static final String EMPTY_FEED =
            "{\"code\":0,\"message\":\"0\",\"data\":{\"has_more\":false,\"items\":[]}}";

    private MockBiliServer mock;

    @BeforeEach
    void setUp() {
        HttpImageFetcher.setEnabled(false);
        mock = MockBiliServer.start()
                .register(FINGER_PATH, FINGER_BODY);
    }

    @AfterEach
    void tearDown() {
        HttpImageFetcher.setEnabled(true);
        mock.close();
    }

    // ================================================================
    // getDetail
    // ================================================================

    @Nested
    @DisplayName("getDetail：详情链路")
    class GetDetail {

        @Test
        @DisplayName("正常路径：LEGACY schema → 冻结 Card 的两条下游路径都有值")
        void happyPath() throws IOException {
            mock.register(DETAIL_PATH + "?id=", Files.readString(Path.of(LEGACY_FIXTURE)));

            BilibiliDynamicResp.Data.Card card = DynamicService.INSTANCE.getDetail("1247016318199136288");
            assertNotNull(card);
            // 与 XatiiBot BilibiliAnalysisImpl 的取值路径逐字一致
            assertEquals("1247016318199136288", card.getDesc().getDynamic_id_str());
            assertEquals("影视飓风", card.getDesc().getUser_profile().getInfo().getUname());
            assertEquals(946974L, card.getDesc().getUser_profile().getInfo().getUid());
            assertEquals("BV1xVY26dEbz", card.getDesc().getBvid());
            assertEquals(10106L, card.getDesc().getLike());
        }

        @Test
        @DisplayName("dynamicId 为 null/空 → 抛 BilibiliException（runtime，不是 IOException）")
        void argumentValidation() {
            assertThrows(BilibiliException.class, () -> DynamicService.INSTANCE.getDetail(null));
            assertThrows(BilibiliException.class, () -> DynamicService.INSTANCE.getDetail(""));
        }

        @Test
        @DisplayName("业务码非 0 → 抛 IOException（受检，下游 catch(IOException) 能兜住）")
        void nonZeroBusinessCode() {
            mock.register(DETAIL_PATH + "?id=",
                    "{\"code\":4101105,\"message\":\"啥都木有\",\"data\":null}");
            IOException e = assertThrows(IOException.class,
                    () -> DynamicService.INSTANCE.getDetail("1"));
            assertTrue(e.getMessage().contains("4101105"),
                    "异常消息应带 B 站错误码，实际：" + e.getMessage());
        }

        @Test
        @DisplayName("HTTP 412 风控 → 抛 IOException 且消息点明风控")
        void riskControl412() {
            mock.register(DETAIL_PATH + "?id=", "<html>blocked</html>");
            // mock 返回 200 + HTML，会走到"响应不是合法 JSON"分支；也属 IOException
            IOException e = assertThrows(IOException.class,
                    () -> DynamicService.INSTANCE.getDetail("1"));
            assertNotNull(e.getMessage());
        }

        @Test
        @DisplayName("data 里没有 item → 抛 IOException")
        void missingItem() {
            mock.register(DETAIL_PATH + "?id=", "{\"code\":0,\"message\":\"0\",\"data\":{}}");
            IOException e = assertThrows(IOException.class,
                    () -> DynamicService.INSTANCE.getDetail("1"));
            assertTrue(e.getMessage().contains("item"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("响应不是合法 JSON → 抛 IOException（消息含前 120 字便于排障）")
        void invalidJson() {
            mock.register(DETAIL_PATH + "?id=", "totally-not-json");
            assertThrows(IOException.class, () -> DynamicService.INSTANCE.getDetail("1"));
        }
    }

    // ================================================================
    // getInfoList（含 Bug 2 的两种失败形态）
    // ================================================================

    @Nested
    @DisplayName("getInfoList：列表链路")
    class GetInfoList {

        @Test
        @DisplayName("正常路径：解析出 2 条，time 来自 pub_time（含 pub_action 拼接）")
        void happyPath() throws IOException {
            mock.register(FEED_PATH + "?host_mid=", Files.readString(Path.of(FEED_FIXTURE)));

            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");
            assertEquals(2, list.size());

            Dynamic.DynamicInfo draw = list.get(0);
            assertEquals("1247440317376888835", draw.getDynamicId());
            assertEquals("7小时前", draw.getTime(), "pub_action 为空 → time 就是 pub_time");
            assertEquals(1, draw.getImageUrl().size());

            Dynamic.DynamicInfo av = list.get(1);
            assertEquals("昨天 11:00 · 投稿了视频", av.getTime(),
                    "pub_action 非空 → time = pub_time + ' · ' + pub_action");
            assertEquals("BV1xVY26dEbz", av.getBvid());
        }

        @Test
        @DisplayName("uid 为 null/空 → 抛 BilibiliException")
        void argumentValidation() {
            assertThrows(BilibiliException.class, () -> DynamicService.INSTANCE.getInfoList(null));
            assertThrows(BilibiliException.class, () -> DynamicService.INSTANCE.getInfoList(""));
        }

        @Test
        @DisplayName("【失败形态 A】code=-352 风控 → 抛 BilibiliException（注意：runtime，非 IOException）")
        void failureModeA_riskControl() {
            mock.register(FEED_PATH + "?host_mid=", "{\"code\":-352,\"message\":\"-352\",\"ttl\":1}");
            // ⚠️ 实测的异常语义：getInfoList 声明了 throws IOException，但**实际抛的是
            // BilibiliException**（方法内的所有失败分支都 throw BilibiliException）。
            // 与 getDetail/getImg（真的包成 IOException）不一致。下游都能兜住
            // （门面 Dynamic.getDynamicInfoList 两个 catch 都写了；XatiiBot catch Exception），
            // 所以功能无碍，但语义不统一 —— 见类尾注释。这里锁定现状。
            BilibiliException e = assertThrows(BilibiliException.class,
                    () -> DynamicService.INSTANCE.getInfoList("946974"));
            assertTrue(e.getMessage().contains("-352"), "实际：" + e.getMessage());
        }

        @Test
        @DisplayName("【失败形态 B】code=0 但 items 为空 → 换身份重试一次后仍空 → 返回空列表")
        void failureModeB_silentEmpty() throws IOException {
            mock.register(FEED_PATH + "?host_mid=", EMPTY_FEED);

            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");
            assertNotNull(list);
            assertTrue(list.isEmpty(),
                    "『code=0 但 items=[]』无法与『该 UP 真没发动态』区分。"
                            + "当前策略：换一副身份重试一次（见 EmptyFeedRetry 套件）；"
                            + "重试后仍空则按『没有动态』返回空列表 —— 此时仍可能漏报，属已知残留风险。");
            assertEquals(2, mock.hitCount(FEED_PATH + "?host_mid="),
                    "空列表必须触发一次换身份重试（底层被调 2 次）");
        }

        @Test
        @DisplayName("data 为 null → 返回空列表（不抛异常）")
        void nullData() throws IOException {
            mock.register(FEED_PATH + "?host_mid=", "{\"code\":0,\"message\":\"0\",\"data\":null}");
            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");
            assertNotNull(list);
            assertTrue(list.isEmpty());
        }

        @Test
        @DisplayName("items 键缺失 → 返回空列表")
        void missingItemsKey() throws IOException {
            mock.register(FEED_PATH + "?host_mid=", "{\"code\":0,\"message\":\"0\",\"data\":{}}");
            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");
            assertTrue(list.isEmpty());
        }

        @Test
        @DisplayName("响应不是合法 JSON → 抛 BilibiliException（同 getInfoList 的其它失败分支）")
        void invalidJson() {
            mock.register(FEED_PATH + "?host_mid=", "<html>风控页</html>");
            assertThrows(BilibiliException.class,
                    () -> DynamicService.INSTANCE.getInfoList("1"));
        }

        @Test
        @DisplayName("含无 module_dynamic 的条目 → 该条被跳过，其余正常返回（不是整列丢弃）")
        void skipsItemsWithoutModule() throws IOException {
            String mixed = "{\"code\":0,\"data\":{\"items\":["
                    // 第一条：无 modules → 跳过
                    + "{\"id_str\":\"1\",\"type\":\"DYNAMIC_TYPE_LIVE_RCMD\"},"
                    // 第二条：合法
                    + "{\"id_str\":\"2\",\"type\":\"DYNAMIC_TYPE_WORD\",\"modules\":{"
                    + "\"module_author\":{\"mid\":1,\"name\":\"n\",\"pub_time\":\"刚刚\",\"pub_action\":\"\"},"
                    + "\"module_dynamic\":{\"desc\":{\"text\":\"正文\"}}}}"
                    + "]}}";
            mock.register(FEED_PATH + "?host_mid=", mixed);

            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("1");
            assertEquals(1, list.size(), "'少几条但都对'，不是整列抛掉");
            assertEquals("2", list.get(0).getDynamicId());
            assertEquals("刚刚", list.get(0).getTime());
        }
    }

    // ================================================================
    // 空 items 换身份重试（2026-09-13 新增行为）
    // ================================================================

    @Nested
    @DisplayName("getInfoList：空 items 换身份重试")
    class EmptyFeedRetry {

        @Test
        @DisplayName("第一次空、第二次有内容 → 返回第二次结果；底层被调 2 次；身份代数递增")
        void emptyThenRetrySucceeds() throws IOException {
            mock.registerSequence(FEED_PATH + "?host_mid=", EMPTY_FEED,
                    Files.readString(Path.of(FEED_FIXTURE)));

            int generationBefore = AnonymousSession.generation();
            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");

            assertEquals(2, list.size(), "应返回第二次（换身份后）的结果");
            assertEquals(2, mock.hitCount(FEED_PATH + "?host_mid="),
                    "空列表必须触发一次重试 —— 期望底层被调 2 次");
            assertTrue(AnonymousSession.generation() > generationBefore,
                    "重试前必须换一副身份（rotate），否则等于拿被标记的指纹硬撞");
        }

        @Test
        @DisplayName("两次都空 → 返回空列表，且只重试一次（不循环）")
        void retriesOnlyOnceWhenStillEmpty() throws IOException {
            // 给 3 个 body：若实现会循环，第 3 次就会被消费
            mock.registerSequence(FEED_PATH + "?host_mid=", EMPTY_FEED, EMPTY_FEED, EMPTY_FEED);

            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");

            assertTrue(list.isEmpty());
            assertEquals(2, mock.hitCount(FEED_PATH + "?host_mid="),
                    "必须只重试一次 —— 第 3 个 body 绝不能被消费");
        }

        @Test
        @DisplayName("关掉开关（rotateOnEmptyFeed=false）→ 不重试，底层只被调 1 次")
        void noRetryWhenDisabled() throws IOException {
            HttpPolicy.setRotateOnEmptyFeed(false);
            try {
                mock.registerSequence(FEED_PATH + "?host_mid=", EMPTY_FEED,
                        Files.readString(Path.of(FEED_FIXTURE)));

                List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");

                assertTrue(list.isEmpty(), "开关关闭时不得换身份重试");
                assertEquals(1, mock.hitCount(FEED_PATH + "?host_mid="));
            } finally {
                HttpPolicy.setRotateOnEmptyFeed(true);
            }
        }

        @Test
        @DisplayName("第一次就非空 → 不轮换身份（不能白耗指纹）")
        void noRotationWhenNotEmpty() throws IOException {
            mock.register(FEED_PATH + "?host_mid=", Files.readString(Path.of(FEED_FIXTURE)));

            int generationBefore = AnonymousSession.generation();
            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getInfoList("946974");

            assertEquals(2, list.size());
            assertEquals(1, mock.hitCount(FEED_PATH + "?host_mid="));
            assertEquals(generationBefore, AnonymousSession.generation(),
                    "正常返回时不该轮换身份");
        }

        @Test
        @DisplayName("风控码 -352 → 抛 BilibiliException，且列表层不再叠加重试（走的是另一条分支）")
        void riskControlDoesNotTriggerEmptyRetry() {
            // 关掉 HTTP 层的风控轮换，才能把"列表层是否额外重试"单独隔离出来观察；
            // 否则 -352 会让 BilibiliHttp 自己轮换重试一次，命中数变成 2，断言说不清是谁干的。
            HttpPolicy.setRotateOnRiskControl(false);
            try {
                mock.register(FEED_PATH + "?host_mid=", "{\"code\":-352,\"message\":\"-352\",\"ttl\":1}");

                assertThrows(BilibiliException.class,
                        () -> DynamicService.INSTANCE.getInfoList("946974"));
                assertEquals(1, mock.hitCount(FEED_PATH + "?host_mid="),
                        "风控码应原样抛出（由 HTTP 层负责），列表层不得再叠一层重试");
            } finally {
                HttpPolicy.setRotateOnRiskControl(true);
            }
        }
    }

    // ================================================================
    // getImg
    // ================================================================

    @Nested
    @DisplayName("getImg：长图链路")
    class GetImg {

        @Test
        @DisplayName("dynamicId 为 null/空 → 抛 BilibiliException")
        void argumentValidation() {
            assertThrows(BilibiliException.class, () -> DynamicService.INSTANCE.getImg(null));
            assertThrows(BilibiliException.class, () -> DynamicService.INSTANCE.getImg(""));
        }

        @Test
        @DisplayName("端点全部不可用 → 抛 IOException（不返回空白图）")
        void fetchFailure() {
            // 未注册任何动态端点 → opus 与 v1/detail 都 404
            IOException e = assertThrows(IOException.class,
                    () -> DynamicService.INSTANCE.getImg("9999999999"));
            assertNotNull(e.getMessage());
        }
    }

    // ================================================================
    // getFollowFeed（2026-09-14 新增：feed/space 被 -412 封禁时的替代数据源）
    // ================================================================

    @Nested
    @DisplayName("getFollowFeed：关注流链路")
    class GetFollowFeed {

        @Test
        @DisplayName("正常路径：一次请求解析多条，且每条都带 uid / userName（供调用方按订阅过滤）")
        void happyPath() throws IOException {
            // 形态取自 2026-09-14 真机实测的 feed/all 响应（items[].modules.module_author.{mid,name,pub_time}）
            mock.register(FOLLOW_PATH,
                    "{\"code\":0,\"data\":{\"has_more\":true,\"items\":["
                            + "{\"id_str\":\"1247591251054690304\",\"type\":\"DYNAMIC_TYPE_DRAW\","
                            + "\"modules\":{\"module_author\":{\"mid\":\"497078180\",\"name\":\"可可小绒猫\","
                            + "\"pub_time\":\"刚刚\"},"
                            + "\"module_dynamic\":{\"desc\":{\"text\":\"今天也要加油\"},"
                            + "\"dyn_draw\":{\"items\":[{\"src\":\"//i0.hdslb.com/bfs/a.jpg\"}]}}}},"
                            + "{\"id_str\":\"1247591251054690305\",\"type\":\"DYNAMIC_TYPE_AV\","
                            + "\"modules\":{\"module_author\":{\"mid\":\"3546774476163227\",\"name\":\"小雨绒Candy\","
                            + "\"pub_time\":\"3分钟前\",\"pub_action\":\"投稿了视频\"},"
                            + "\"module_dynamic\":{\"dyn_archive\":{\"title\":\"标题\",\"bvid\":\"BV1xVY26dEbz\"}}}}"
                            + "]}}");

            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getFollowFeed();

            assertEquals(2, list.size());
            Dynamic.DynamicInfo first = list.get(0);
            assertEquals("497078180", first.getUid(), "必须带发布者 uid，否则调用方无法归到订阅");
            assertEquals("可可小绒猫", first.getUserName(), "带上昵称，省掉一次名片接口请求");
            assertEquals("刚刚", first.getTime());
            assertEquals(1, first.getImageUrl().size());

            Dynamic.DynamicInfo second = list.get(1);
            assertEquals("3546774476163227", second.getUid());
            assertEquals("3分钟前 · 投稿了视频", second.getTime(), "pub_action 非空时按前端习惯拼接");
            assertEquals("BV1xVY26dEbz", second.getBvid());

            assertEquals(1, mock.hitCount(FOLLOW_PATH), "关注流一轮只该发 1 次请求");
        }

        @Test
        @DisplayName("含推荐项（LIVE_RCMD 无 module_dynamic）→ 仍会解析出条目，过滤是调用方的责任")
        void recommendationsFilteredByCaller() throws IOException {
            mock.register(FOLLOW_PATH,
                    "{\"code\":0,\"data\":{\"items\":[{\"id_str\":\"1\",\"type\":\"DYNAMIC_TYPE_LIVE_RCMD\","
                            + "\"modules\":{\"module_author\":{\"mid\":\"999\",\"name\":\"直播推荐\"}}},"
                            + "{\"id_str\":\"2\",\"type\":\"DYNAMIC_TYPE_DRAW\",\"modules\":{\"module_author\":"
                            + "{\"mid\":\"111\",\"name\":\"up\",\"pub_time\":\"5分钟前\"}}}]}}");

            List<Dynamic.DynamicInfo> list = DynamicService.INSTANCE.getFollowFeed();

            // ★ 关键契约：推荐项**不会**被过滤掉（它有 uid/id，只是没正文）。
            //   关注流会大量混入这类内容，所以调用方必须按 uid 过滤 —— XatiiBot 就是这么做的。
            assertEquals(2, list.size(), "推荐项也会解析出来，不能指望本层替你过滤");
            assertEquals("999", list.get(0).getUid(), "推荐项的 uid 不属于任何订阅 → 调用方据此丢弃");
            assertEquals("111", list.get(1).getUid());
            assertEquals("5分钟前", list.get(1).getTime());
        }

        @Test
        @DisplayName("-412 request was banned → 抛异常（这是 feed/space 被封的真实形态）")
        void banned() {
            mock.register(FOLLOW_PATH, "{\"code\":-412,\"message\":\"request was banned\",\"ttl\":1}");

            BilibiliException e = assertThrows(BilibiliException.class,
                    DynamicService.INSTANCE::getFollowFeed);
            assertTrue(e.getMessage().contains("-412"), "实际：" + e.getMessage());
        }
    }
}
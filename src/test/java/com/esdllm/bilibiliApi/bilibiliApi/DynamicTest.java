package com.esdllm.bilibiliApi.bilibiliApi;

import com.esdllm.bilibiliApi.http.MockBiliServer;
import com.esdllm.bilibiliApi.model.BilibiliDynamicResp;
import com.esdllm.bilibiliApi.render.HttpImageFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Dynamic 门面回归测试</b>（P1.5 改造后重写）。
 *
 * <p>4 个用例对应门面 3 个公开方法 + 1 个并发路径：
 * <ol>
 *   <li>{@link Dynamic#getDynamicDetail}：mock legacy fixture 走 v1/detail 路径；</li>
 *   <li>{@link Dynamic#getDynamicImg}：mock 双端点不返回有效内容 → 验证失败语义
 *       （旧实现的"失败包 RuntimeException"行为保留）；</li>
 *   <li>{@link Dynamic#getDynamicInfoList}：mock desktop fixture 走 feed/space 路径；</li>
 *   <li>多线程并发跑 detail + infoList，验证非线程安全的"单槽缓存"在单线程内使用且不崩。</li>
 * </ol>
 *
 * <p>端到端长图渲染（联网抓图、Java2D 出图）由 {@code smoke/WiredDynamicImgSmokeTest} 覆盖，
 * 本类不重复——{@code HttpImageFetcher} 需要真实网络才能拿到图字节流。
 */
class DynamicTest {

    private static final String LEGACY_DETAIL_PATH = "/x/polymer/web-dynamic/v1/detail";
    private static final String OPUS_DETAIL_PATH = "/x/polymer/web-dynamic/v1/opus/detail";
    private static final String FEED_PATH = "/x/polymer/web-dynamic/v1/feed/space";
    private static final String LEGACY_FIXTURE = "src/test/resources/fixtures/dynamic-detail-legacy.json";
    private static final String DESKTOP_FIXTURE = "src/test/resources/fixtures/dynamic-detail-desktop.json";
    private static final String FEED_FIXTURE = "src/test/resources/fixtures/dynamic-feed-desktop.json";

    private MockBiliServer mock;
    private Dynamic dynamic;

    @BeforeEach
    void setUp() throws IOException {
        // 离线模式：所有抓图请求直接返回 null（避免 fixture 测试触发真实 CDN 请求）
        HttpImageFetcher.setEnabled(false);
        mock = MockBiliServer.start();
        dynamic = new Dynamic();
    }

    @AfterEach
    void tearDown() {
        HttpImageFetcher.setEnabled(true);
        mock.close();
    }

    // —— 1. getDynamicDetail 走 v1/detail (LEGACY schema) ——

    @Test
    void getDynamicDetail_mapsLegacyFields() throws IOException {
        String body = Files.readString(Path.of(LEGACY_FIXTURE));
        mock.register(LEGACY_DETAIL_PATH + "?id=", body);

        BilibiliDynamicResp.Data.Card card = dynamic.getDynamicDetail("1247016318199136288");
        assertNotNull(card);
        // LEGACY schema 的 dynamicId 拍平到 desc.dynamic_id_str
        assertEquals("1247016318199136288", card.getDesc().getDynamic_id_str());
        // 时间来自 module_author.pub_ts（1789182012）
        assertEquals(Long.valueOf(1789182012L), card.getDesc().getTimestamp());
        // 作者信息：LEGACY schema 下 author 本身 = user，所以 name 在 desc.user_profile.info.uname
        assertEquals("影视飓风", card.getDesc().getUser_profile().getInfo().getUname());
        assertEquals(Long.valueOf(946974L), card.getDesc().getUser_profile().getInfo().getUid());
        // 视频 bvid 来自 major.archive.bvid（LEGACY schema 走 archiveNode 回退）
        assertEquals("BV1xVY26dEbz", card.getDesc().getBvid());
        // 互动数：stat.like.count = 10106
        assertEquals(Long.valueOf(10106L), card.getDesc().getLike());
    }

    // —— 2. getDynamicImg 失败语义（端到端渲染由 smoke 测试覆盖） ——

    @Test
    void getDynamicImg_unavailableFixture_wrapsAsRuntimeException() {
        // mock 不注册任何动态端点 → fetchItem 两次（opus + v1/detail）都拿到 HTTP 404 + code=-404
        // → ResponseParserSupport.unwrap 抛 BilibiliException → RenderModelLoader 抛 IOException
        // → DynamicService.getImg 抛 IOException → Dynamic.getDynamicImg 包成 RuntimeException
        // 验证：失败行为与旧 Selenium 实现"逐字一致"
        assertThrows(RuntimeException.class, () -> dynamic.getDynamicImg("9999999999999"));
    }

    // —— 3. getDynamicInfoList 走 desktop feed ——

    @Test
    void getDynamicInfoList_mapsDesktopFields() throws Exception {
        String body = Files.readString(Path.of(FEED_FIXTURE));
        mock.register(FEED_PATH + "?host_mid=", body);

        List<Dynamic.DynamicInfo> list = dynamic.getDynamicInfoList("946974");
        assertNotNull(list);
        assertEquals(2, list.size());

        // —— item[0]：DRAW，pub_action 为空 → time 就是 pub_time ——
        Dynamic.DynamicInfo draw = list.get(0);
        assertEquals("1247440317376888835", draw.getDynamicId());
        assertEquals("7小时前", draw.getTime(),
                "time 必须取自 module_author.pub_time（真实响应无 pub_text 字段）");
        assertNotNull(draw.getImageUrl());
        assertEquals(1, draw.getImageUrl().size());
        assertTrue(draw.getImageUrl().get(0).startsWith("https://i0.hdslb.com/bfs/new_dyn/"));
        assertNull(draw.getBvid());
        assertEquals("或许你的童年照片里，也有这座游乐园。", draw.getDesc());

        // —— item[1]：AV，pub_action 非空 → time = pub_time + " · " + pub_action ——
        Dynamic.DynamicInfo av = list.get(1);
        assertEquals("1247016318199136288", av.getDynamicId());
        assertEquals("昨天 11:00 · 投稿了视频", av.getTime(),
                "time 应为 pub_time + ' · ' + pub_action（与前端同款文案）");
        assertEquals("BV1xVY26dEbz", av.getBvid());
        assertEquals("去了一趟山西。", av.getTitle());
    }

    /**
     * <b>回归锁定</b>：{@code DynamicInfo.time} 是 XatiiBot 的推送触发字段
     * （{@code PushInfoServiceImpl.java:196} 判 {@code startsWith("刚刚")}），
     * 必须来自真实存在的 {@code pub_time}。
     *
     * <p>历史教训：曾误用 {@code module_author.pub_text} —— 该字段在真实响应里
     * <b>不存在</b>（恒 null），导致 time 恒为 null、推送永不触发。本用例把"取错字段"
     * 这件事变成红灯。
     */
    @Test
    void getDynamicInfoList_timeComesFromPubTime_notPubText() throws Exception {
        // 构造一条只有 pub_time、没有 pub_text 的动态 —— 与真实响应一致
        String feedJson = "{\"code\":0,\"data\":{\"items\":[{"
                + "\"id_str\":\"999\",\"type\":\"DYNAMIC_TYPE_WORD\","
                + "\"modules\":{\"module_author\":{"
                + "\"mid\":1,\"name\":\"n\",\"pub_time\":\"刚刚\",\"pub_action\":\"\","
                + "\"pub_ts\":1700000000,\"is_top\":false},"
                + "\"module_dynamic\":{\"desc\":{\"text\":\"d\"}}"
                + "}}]}}";
        mock.register(FEED_PATH + "?host_mid=", feedJson);

        List<Dynamic.DynamicInfo> list = dynamic.getDynamicInfoList("1");
        assertEquals(1, list.size());
        assertEquals("刚刚", list.get(0).getTime(),
                "time 必须来自 pub_time；若取 pub_text 会得到 null，推送触发条件 startsWith(\"刚刚\") 永不成立");
    }

    // —— 4. 多线程并发不崩（非线程安全"单槽缓存"的合理使用边界） ——

    @Test
    void getDynamicDetailAndInfoList_concurrently_noCrash() throws Exception {
        String legacyBody = Files.readString(Path.of(LEGACY_FIXTURE));
        String feedBody = Files.readString(Path.of(FEED_FIXTURE));
        mock.register(LEGACY_DETAIL_PATH + "?id=", legacyBody);
        mock.register(FEED_PATH + "?host_mid=", feedBody);

        // 注：本测试只验证"调用链能跑通"；"单槽缓存"由各自门面独立实例持有，
        // 多线程并发同一 Dynamic 实例的理论风险不在本测试覆盖范围（门面方法本身被设计为单线程内复用）。
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);
        Thread t1 = new Thread(() -> {
            try {
                dynamic.getDynamicDetail("1247016318199136288");
            } catch (Throwable e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        }, "detail");
        Thread t2 = new Thread(() -> {
            try {
                dynamic.getDynamicInfoList("946974");
            } catch (Throwable e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        }, "infoList");
        t1.start();
        t2.start();
        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发 10s 内应完成");
        // 任一线程崩了即失败
        if (err.get() != null) {
            throw new AssertionError("并发调用失败", err.get());
        }
    }
}
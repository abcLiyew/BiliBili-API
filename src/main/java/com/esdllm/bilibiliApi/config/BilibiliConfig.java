package com.esdllm.bilibiliApi.config;

import com.esdllm.bilibiliApi.endpoint.BilibiliEndpoint;

/**
 * 请求配置相关信息。
 *
 * @deprecated 已迁移到 {@link BilibiliEndpoint}；本类保留为 {@code @Deprecated} 转调以保证
 *         历史引用方（包含 5 个门面 + http/* + render/* 共 ~10 个文件）零改动即可继续工作。
 *         P1 阶段不删；待 {@code service/} 上线后由 P3 阶段删除。
 */
@Deprecated
public class BilibiliConfig {

    /** @deprecated see {@link BilibiliEndpoint#userAgent} */
    @Deprecated
    public static final String userAgent = BilibiliEndpoint.userAgent;

    /** @deprecated see {@link BilibiliEndpoint#accept} */
    @Deprecated
    public static final String accept = BilibiliEndpoint.accept;

    /** @deprecated see {@link BilibiliEndpoint#referer} */
    @Deprecated
    public static final String referer = BilibiliEndpoint.referer;

    /** @deprecated see {@link BilibiliEndpoint#videoBaseUrl} */
    @Deprecated
    public static final String videoBaseUrl = BilibiliEndpoint.videoBaseUrl;

    /** @deprecated see {@link BilibiliEndpoint#videoAvBaseUrl} */
    @Deprecated
    public static final String videoAvBaseUrl = BilibiliEndpoint.videoAvBaseUrl;

    /** @deprecated see {@link BilibiliEndpoint#cardBaseUrl} */
    @Deprecated
    public static final String cardBaseUrl = BilibiliEndpoint.cardBaseUrl;

    /** @deprecated see {@link BilibiliEndpoint#dynamicBaseUrl} */
    @Deprecated
    public static final String dynamicBaseUrl = BilibiliEndpoint.dynamicBaseUrl;

    /** @deprecated see {@link BilibiliEndpoint#dynamicDetailUrl} */
    @Deprecated
    public static final String dynamicDetailUrl = BilibiliEndpoint.dynamicDetailUrl;

    /** @deprecated see {@link BilibiliEndpoint#opusDetailUrl} */
    @Deprecated
    public static final String opusDetailUrl = BilibiliEndpoint.opusDetailUrl;

    /** @deprecated see {@link BilibiliEndpoint#liveBaseUrl} */
    @Deprecated
    public static final String liveBaseUrl = BilibiliEndpoint.liveBaseUrl;

    /** @deprecated see {@link BilibiliEndpoint#dynamicFeedUrl} */
    @Deprecated
    public static final String dynamicFeedUrl = BilibiliEndpoint.dynamicFeedUrl;

    private BilibiliConfig() {
        throw new AssertionError("config constants holder; do not instantiate");
    }
}

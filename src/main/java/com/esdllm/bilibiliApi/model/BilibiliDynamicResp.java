package com.esdllm.bilibiliApi.model;

import lombok.Data;

@Data
public class BilibiliDynamicResp {
    private int code;
    private String message;
    private int ttl;
    private String msg;
    private Data data;

    @lombok.Data
    /**
     * 动态信息本体
     * @param card
     */
    public static class Data {
        private Card card;
        private Long result;
        private Long _gt_;

        /**
         * 动态卡片信息
         */
        @lombok.Data
        public static class Card {
            //该条动态参与的活动
            private Object activity_infos;
            //动态详细信息
            private String card;
            //动态描述信息
            private Desc desc;
            //动态展示信息
            private Object display;
            //动态扩展项
            private String extend_json;

            @lombok.Data
            public static class Desc{
                //动态发布者uid
                private Long uid;
                //动态类型
                private Integer type;
                //动态关联的rid
                private Long rid;
                private Long acl;
                //动态被浏览次数
                private Long view;
                private Long repost;
                private Integer comment;
                private Long like;
                private Integer is_liked;
                private Long dynamic_id;
                private Long timestamp;
                private Integer pre_dy_id;
                private Integer orig_dy_id;
                private Integer orig_type;
                private UserProfile user_profile;
                private Integer spec_type;
                private Integer uid_type;
                private Integer stype;
                private Integer r_type;
                private Integer inner_id;
                private Integer status;
                private String dynamic_id_str;
                private String pre_dy_id_str;
                private String orig_dy_id_str;
                private String rid_str;
                private Object origin;
                private String bvid;
                private Object previous;

                @lombok.Data
                public static class UserProfile{
                    private Info info;
                    private Object card;
                    private Object vip;
                    private Object official_verify;
                    private Object pendant;
                    private String rank;
                    private String sign;
                    private Object level_info;

                    @lombok.Data
                    public static class Info{
                        private Long uid;
                        private String uname;
                        private String face;
                    }
                }

            }
        }
    }
}

package com.esdllm.bilibiliApi.model.data.pojo.card;

import lombok.Data;

@Data
public class Label {
    private String path;
    private String text;
    private String label_theme;
    private String text_color;
    private Integer bg_style;
    private String bg_color;
    private String border_color;
    private Boolean use_img_label;
    private String img_label_uri_hans;
    private String img_label_uri_hant;
    private String img_label_uri_hans_static;
    private String img_label_uri_hant_static;

}

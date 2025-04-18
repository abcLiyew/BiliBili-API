package com.esdllm.bilibiliApi.model.data.pojo;

import com.esdllm.bilibiliApi.model.data.pojo.card.*;
import lombok.Data;

import java.util.List;

@Data
public class Card {
    private String mid;
    private Boolean approve;
    private String name;
    private String sex;
    private String face;
    private String DisplayRank;
    private Integer regtime;
    private Integer spacesta;
    private String birthday;
    private String place;
    private String description;
    private Integer article;
    private List<Object> attentions;
    private Integer fans;
    private Integer friend;
    private Integer attention;
    private String sign;
    private LevelInfo level_info;
    private Pendant pendant;
    private Nameplate nameplate;
    private Official official;
    private Object official_verify;
    private Vip vip;
    private Space space;
}

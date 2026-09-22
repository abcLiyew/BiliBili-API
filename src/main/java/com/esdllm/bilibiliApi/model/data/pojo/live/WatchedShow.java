package com.esdllm.bilibiliApi.model.data.pojo.live;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 直播间的"观看/追番"挂件（直播接口的 {@code watched_show}）。
 *
 * <p>⚠️ 本类<b>在库内目前没有任何调用方</b>（{@code UserLiveRoom.watched_show} 是裸
 * {@code JSONObject}，没走这个类），保留它是为给调用方一个现成的形状。
 *
 * @author 饿死的流浪猫
 */
@Data
public class WatchedShow {

    /**
     * 是否已看过。
     *
     * <p>🔴 <b>字段名反常规（首字母大写）且必须显式写 {@code @JSONField(name = "switch")}</b>：
     * JSON 键是 {@code switch}，而 {@code switch} 是 Java 保留字，不能做字段名
     * ⇒ 只能借 {@code Switch} 这个名字。
     *
     * <p>🔴 这条映射原先靠 <b>fastjson 1.x 的大小写不敏感匹配</b>接上；而
     * <b>fastjson2 没有名字宽容度</b>（实测 2.0.56：只认同名，大小写/下划线一律不宽容）
     * ⇒ 没有这个注解就会<b>静默变 null</b>。同型问题见 {@code HotSearch.goTo}。
     * ⚠️ 此处<b>刻意不改字段名</b>（改成 {@code switchFlag} 会让 Lombok 生成的
     * {@code getSwitch()} 消失，属破坏性变更）。
     */
    @JSONField(name = "switch")
    private Boolean Switch;

    private Integer num;
    private String text_small;
    private String text_large;
    private String icon;
    private String icon_location;
    private String icon_web;
}

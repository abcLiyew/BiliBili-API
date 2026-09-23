package com.esdllm.bilibiliApi.model.data.pojo.video;

import lombok.Data;

/**
 * <b>笔记入口禁令</b> —— {@code x/note/is_forbid} 的 {@code data}（B5 批，2026-09-23）。
 *
 * <p>实测响应只有一个键：
 * <pre>
 * {"code":0,"message":"OK","data":{"forbid_note_entrance":false}}
 * </pre>
 * 字段名与 JSON 键<b>逐字对应</b>（fastjson2 只认精确同名，见 {@code KeyNameMappingGuardTest}）。
 *
 * <p>🔴 <b>本端点的坑不在字段上，在"它不校验参数"上</b>：{@code aid} 传一个不存在的稿件
 * （实测 {@code aid=1}）照样 {@code code=0} 并给出一个布尔 ⇒ <b>它的成功不能证明 id 有效</b>。
 * 详见 {@code VideoExtra#isNoteForbidden(long)}。
 *
 * @author 饿死的流浪猫
 */
@Data
public class NoteForbid {

    /** 笔记入口是否被禁（实测匿名与带凭据都给值，不给 {@code null}） */
    private Boolean forbid_note_entrance;
}

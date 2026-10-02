package com.esdllm.bilibiliApi.model.data.pojo.search;

import lombok.Data;

/**
 * <b>默认搜索词</b> —— {@code x/web-interface/search/default} 的 {@code data}（C2 批）。
 *
 * <p>实测响应（2026-10-02，匿名，8 个键）：
 * <pre>
 * {"id": 2797749738467414230, "name": "边狱巴士", "seid": "16451640188548591644",
 *  "show_name": "边狱巴士", "type": 0, "goto_type": 0, "goto_value": "",
 *  "url": "https://search.bilibili.com/all?keyword=\u8fb9\u72f1\u5df4\u58eb"}
 * </pre>
 *
 * <p>✅ <b>完全不需要凭据、也不需要签名</b>（2026-10-02 匿名 / 凭据两格实测均 {@code code=0}）。
 *
 * <p>⚠️ <b>样本是快照，会过时</b>：{@code name} / {@code id} / {@code url} 描述的是
 * <b>当下</b>的默认（热门）词，隔一段时间就换（实测 2026-10-02 上午为 {@code 边狱巴士}，
 * 同日下午已变成别的词）。而 {@link #seid} 更是<b>每次请求都不同</b>。
 * ⇒ 本类<b>没有任何一个字段适合当"稳定主键"</b>；要用它做缓存键请慎重。
 *
 * <p>🔴 <b>{@link #seid} 必须是 {@link String}，不能是 {@code long}</b>：它是 19~20 位的随机数字串，
 * 实测<b>大多数时候超过 {@code Long.MAX_VALUE}</b>（约 9.22e18；最长 {@code 16451640188548591644}），
 * 按数值反序列化会溢出/变负数。⚠️ 注意它<b>偶尔也会落在范围内</b> ⇒ <b>"这次 parseLong 没炸"不能
 * 反推"用 long 也行"</b>。这与 {@code HotSearch.Trending#trackid} 是<b>同一类坑</b>
 * —— 本库第二次遇到同形状的字段（超长数字 id 以字符串下发）。
 *
 * <p>⚠️ 字段名与 JSON 键<b>逐字相同</b>（{@code show_name} / {@code goto_type} / {@code goto_value}）
 * —— 这是本库的既有约定：fastjson2 <b>没有</b>名字宽容度（1.x 的大小写不敏感 + 忽略下划线
 * 在 2.x 上完全没有），改名字就会静默变 {@code null}。
 *
 * @author 饿死的流浪猫
 */
@Data
public class DefaultSearchWord {

    /** 词条 id（实测 {@code 2797749738467414230}，在 {@code long} 范围内） */
    private Long id;

    /** 词本身（实测 {@code 边狱巴士}；随热点变化） */
    private String name;

    /**
     * 搜索会话 id —— <b>19~20 位随机数字串，必须留在 {@link String} 里</b>。
     *
     * <p>🔴 实测<b>大多数时候超出 {@code long} 范围</b>（最长 {@code 16451640188548591644}
     * ≈ 1.65e19），<b>但偶尔会落在范围内</b> ⇒ <b>"能不能 parseLong"不能用来判断该用什么类型</b>，
     * 更不该写进断言（那样会得到一条时而绿时而红的检查）。类型就一句话：<b>永远是 String</b>。
     *
     * <p>🔴 <b>它每次请求都不同</b>（2026-10-02 三连跑得到三个不同值），是<b>会话 id</b>，
     * 不是"这个词的稳定 id" ⇒ <b>不要缓存、不要跨请求复用、不要拿它做去重或判等</b>。
     * 要判断"默认词是不是变了"，比 {@link #name}（或 {@link #id}）。
     */
    private String seid;

    /** 展示用词（实测与 {@link #name} 相同；运营改词的场景下可能不同） */
    private String show_name;

    /** 词条类型（实测 {@code 0}） */
    private Long type;

    /** 跳转类型（实测 {@code 0}） */
    private Long goto_type;

    /** 跳转参数（实测空串） */
    private String goto_value;

    /** 直达搜索页的 URL（实测 {@code https://search.bilibili.com/all?keyword=…}） */
    private String url;
}

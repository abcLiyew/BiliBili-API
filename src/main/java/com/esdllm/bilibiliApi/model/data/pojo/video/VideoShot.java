package com.esdllm.bilibiliApi.model.data.pojo.video;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.List;

/**
 * <b>视频缩略图（进度条预览用）</b> —— {@code x/player/videoshot} 的 {@code data}（C1 批，2026-09-24）。
 *
 * <p>它给的<b>不是</b>"一串缩略图"，而是<b>一张雪碧图 + 一张取图坐标表</b>。
 * 要拿到"第 n 个时间点的预览图"，得自己按下面两步拼：
 * <pre>
 * 第 n 个时间点 → 网格格号 = index.get(n)
 * 格号 → 雪碧图裁剪框 = image.get(0) 上，左上角 ((格号 % img_x_len) * img_x_size,
 *                                            (格号 / img_x_len) * img_y_size)
 *                        尺寸 img_x_size × img_y_size
 * </pre>
 *
 * <p>实测形状（2026-09-24）：
 * <table border="1">
 *   <caption>{@code bvid=BV1BqhB6nEdN&index=1}</caption>
 *   <tr><th>字段</th><th>实测值</th><th>含义</th></tr>
 *   <tr><td>{@link #img_x_len} / {@link #img_y_len}</td><td>{@code 10} / {@code 10}</td>
 *       <td>雪碧图的网格列数 / 行数</td></tr>
 *   <tr><td>{@link #img_x_size} / {@link #img_y_size}</td><td>{@code 480} / {@code 270}</td>
 *       <td>单格像素尺寸</td></tr>
 *   <tr><td>{@link #image}</td><td>1 个地址</td><td>雪碧图（进度条预览图本体）</td></tr>
 *   <tr><td>{@link #index}</td><td><b>27 个值</b></td><td>时间点 → 格号</td></tr>
 *   <tr><td>{@link #pvdata} / {@link #video_shots} / {@link #indexs}</td>
 *       <td>1 个地址 / 空对象 / 空对象</td><td>官方客户端的另一套布局，本库只原样透出</td></tr>
 * </table>
 *
 * <p>🔴 <b>{@link #index} 与 {@link #indexs} 是"换了个字母"的两个键，别取错</b>：
 * {@link #index} 是<b>数组</b>（上面那张坐标表，本库实际用的就是它），
 * {@link #indexs} 是<b>对象</b>（实测恒空）。取错那个会拿到一个 {@code JSONObject} 而不是列表。
 *
 * <p>⚠️ {@link #image} 实测是 <b>{@code //} 开头的协议相对地址</b>
 * （{@code //bimp.hdslb.com/...}）—— 直接当 URL 用会失败，要么补 {@code https:}，
 * 要么交给本库既有的地址归一化处理。
 *
 * <p>⚠️ 本类的 {@link #index} 数组长度<b>取决于请求里的 {@code index} 参数</b>，
 * 而库内固定传 {@code 1}（原因见 {@code BilibiliEndpoint#playerVideoShotUrl} 的实测表：
 * 传 2/3/99 会静默拿到 <b>P1 的图</b> + 空坐标表）。
 *
 * @author 饿死的流浪猫
 */
@Data
public class VideoShot {

    /** 另一套布局用的数据文件地址（实测有值） */
    private String pvdata;

    /** 雪碧图网格<b>列数</b>（实测 10） */
    private Integer img_x_len;

    /** 雪碧图网格<b>行数</b>（实测 10） */
    private Integer img_y_len;

    /** 单格宽度（像素，实测 480） */
    private Integer img_x_size;

    /** 单格高度（像素，实测 270） */
    private Integer img_y_size;

    /** 雪碧图地址列表（实测 1 个）。⚠️ 协议相对地址（{@code //…} 开头） */
    private List<String> image;

    /**
     * <b>时间点 → 雪碧图格号</b>的坐标表（实测 27 个值）。
     *
     * <p>⚠️ 与 {@link #indexs} 只差一个字母，那个是<b>对象</b>且恒空。
     */
    private List<Integer> index;

    /** 官方客户端的另一套布局（实测空对象）—— 原样透出，不做解释 */
    private JSONObject video_shots;

    /** 同上（实测空对象）。⚠️ 它不是 {@link #index} */
    private JSONObject indexs;
}

package com.esdllm.bilibiliApi.render;

import com.esdllm.bilibiliApi.exception.BilibiliException;

import java.awt.image.BufferedImage;

/**
 * 动态长图渲染器。
 *
 * <p>抽成接口是为了让"怎么画"可替换：默认实现是 {@link Java2DImageRenderer}（纯 Java2D，
 * 无浏览器依赖）；将来若要做别的观感（比如接入模板引擎）只需换实现，不动上层。
 */
public interface DynamicImageRenderer {

    /**
     * 把视图模型画成一张长图。
     *
     * <p>🔴 <b>契约写在接口上</b>（调用方依赖的是接口，不是某个实现）：{@code model} 为
     * {@code null} 时必须抛 {@link BilibiliException} —— 那是本库对"入参非法"的唯一约定类型。
     * 实现要**显式判空**，不要靠"内部解引用抛 NPE"：那会把异常类型押在实现细节上，
     * 换个实现就变（本库已因同类问题收窄过 3 处宽断言）。
     *
     * @param model 视图模型
     * @return 长图（BufferedImage，类型为 TYPE_INT_RGB）
     * @throws BilibiliException {@code model} 为 {@code null}（调用方的编程错误，但类型仍走库内约定）
     */
    BufferedImage render(RenderModel model);
}

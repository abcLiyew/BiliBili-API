package com.esdllm.bilibiliApi.render;

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
     * @param model 视图模型
     * @return 长图（BufferedImage，类型为 TYPE_INT_RGB）
     */
    BufferedImage render(RenderModel model);
}

package com.aid.media.reference;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.media.reference.impl.DefaultImageReferenceRenderStrategy;

import lombok.extern.slf4j.Slf4j;

/**
 * 图片参考渲染策略路由器：按 {@code modelConfig.providerCode} 选择具体。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class ImageReferenceRenderPlanner
{
    /** Spring 自动注入全部策略实现（含 Default）。 */
    @Autowired
    private List<ImageReferenceRenderStrategy> strategies;

    /** 兜底策略：未命中专用策略时使用。 */
    @Autowired
    private DefaultImageReferenceRenderStrategy defaultStrategy;

    /**
     * 路由并渲染。
     *
     * @param ctx 渲染上下文
     * @return 渲染产物
     */
    public ImageReferenceRenderPlan plan(ImageReferenceRenderContext ctx)
    {
        String providerCode = ctx.getProviderCode();
        ImageReferenceRenderStrategy chosen = defaultStrategy;
        for (ImageReferenceRenderStrategy s : strategies)
        {
            // 跳过默认策略本身（它对任何 providerCode 都返回 false，仅作兜底）
            if (s == defaultStrategy)
            {
                continue;
            }
            if (s.supportsProviderCode(providerCode))
            {
                if (chosen != defaultStrategy)
                {
                    // 兜底守卫：多个策略认领同一 providerCode（未来误配）→ 保留首个命中、warn 提示，避免行为不确定
                    log.warn("图片参考渲染策略冲突: providerCode={} 同时被 {} 与 {} 认领, 采用首个",
                            providerCode, chosen.getClass().getSimpleName(), s.getClass().getSimpleName());
                    break;
                }
                chosen = s;
            }
        }
        log.info("图片参考渲染策略路由: providerCode={}, strategy={}", providerCode, chosen.getClass().getSimpleName());
        return chosen.render(ctx);
    }
}

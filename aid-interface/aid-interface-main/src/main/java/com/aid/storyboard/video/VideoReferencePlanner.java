package com.aid.storyboard.video;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜视频参考装配策略路由器：按模型 {@code provider_code} 路由到唯一匹配策略，未命中则用兜底策略。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class VideoReferencePlanner
{
    private final List<VideoReferenceStrategy> strategies;

    public VideoReferencePlanner(List<VideoReferenceStrategy> strategies)
    {
        this.strategies = strategies;
    }

    /**
     * 按模型配置选定策略并装配参考方案。
     *
     * @param ctx 装配上下文（其中 modelConfig.providerCode 决定路由）
     * @return 装配产物（最终 prompt / referenceImages / 首帧）
     */
    public VideoReferencePlan plan(VideoReferenceContext ctx)
    {
        VideoReferenceStrategy strategy = resolveStrategy(ctx.getModelConfig());
        return strategy.assemble(ctx);
    }

    /**
     * provider_code 强路由：唯一命中专用策略 → 命中多个抛错（配置/实现冲突）→ 0 个回退默认策略。
     */
    private VideoReferenceStrategy resolveStrategy(AiModelConfigVo modelConfig)
    {
        String providerCode = modelConfig == null ? null : modelConfig.getProviderCode();
        if (StrUtil.isNotBlank(providerCode))
        {
            List<VideoReferenceStrategy> hit = strategies.stream()
                    .filter(s -> s.supportsProviderCode(providerCode))
                    .toList();
            if (hit.size() == 1)
            {
                return hit.get(0);
            }
            if (hit.size() > 1)
            {
                log.error("视频参考策略 providerCode 命中多个: providerCode={}, count={}", providerCode, hit.size());
                throw new ServiceException("系统繁忙");
            }
            log.info("视频参考策略 providerCode 未命中专用策略, 回退默认: providerCode={}", providerCode);
        }
        return resolveDefault();
    }

    /** 取唯一默认策略；缺失 / 多个都属于实现错误。 */
    private VideoReferenceStrategy resolveDefault()
    {
        List<VideoReferenceStrategy> defaults = strategies.stream()
                .filter(VideoReferenceStrategy::isDefault)
                .toList();
        if (defaults.size() == 1)
        {
            return defaults.get(0);
        }
        log.error("视频参考默认策略数量异常: count={}", defaults.size());
        throw new ServiceException("系统繁忙");
    }
}

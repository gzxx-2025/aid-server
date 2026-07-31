package com.aid.common.moderation;

import java.util.Objects;

import com.aid.common.moderation.properties.ImageModerationProperties;

import cn.hutool.core.util.StrUtil;

/**
 * 图片审查命中决策工具
 * - 将厂商返回的审查结果（suggestion/error）映射为统一的处置决策
 * - 规则：Block→BLOCK（写死，永不放行）；Review→按 blockOnSuggestionReview 决定 BLOCK 或 REVIEW；其它→PASS；异常→ERROR
 *
 * @author 视觉AID
 */
public final class ModerationDecider
{
    /**
     * 建议：拦截
     */
    private static final String SUGGESTION_BLOCK = "Block";

    /**
     * 建议：复审
     */
    private static final String SUGGESTION_REVIEW = "Review";

    /**
     * 工具类禁止实例化
     */
    private ModerationDecider()
    {
    }

    /**
     * 根据审查结果与配置计算处置决策
     *
     * @param result 审查结果（可空）
     * @param props  图片审查配置（可空）
     * @return 处置决策
     */
    public static ModerationDecision decide(ModerationResult result, ImageModerationProperties props)
    {
        // 结果为空或审查异常，统一按 ERROR 处理
        if (Objects.isNull(result) || result.isError())
        {
            return ModerationDecision.ERROR;
        }

        String suggestion = result.getSuggestion();
        // Block：写死拦截，永不放行
        if (StrUtil.equalsIgnoreCase(suggestion, SUGGESTION_BLOCK))
        {
            return ModerationDecision.BLOCK;
        }

        // Review：按配置决定是否拦截
        if (StrUtil.equalsIgnoreCase(suggestion, SUGGESTION_REVIEW))
        {
            boolean blockReview = Objects.nonNull(props) && props.isBlockOnSuggestionReview();
            return blockReview ? ModerationDecision.BLOCK : ModerationDecision.REVIEW;
        }

        // 其它（Pass 或未知）放行
        return ModerationDecision.PASS;
    }
}

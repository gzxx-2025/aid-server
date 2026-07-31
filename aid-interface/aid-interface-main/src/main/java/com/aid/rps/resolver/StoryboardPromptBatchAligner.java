package com.aid.rps.resolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 按分镜身份键校验并归位批量视频提示词结果。
 *
 * @author 视觉AID
 */
public final class StoryboardPromptBatchAligner
{
    public static final String FIELD_SHOT_KEY = "shotKey";

    private static final String SHOT_KEY_PREFIX = "SB-";

    private StoryboardPromptBatchAligner()
    {
    }

    /**
     * 为分镜生成稳定的批量对齐键。
     *
     * @param storyboardId 分镜主键
     * @return 不会与业务镜号混淆的对齐键
     */
    public static String buildShotKey(Long storyboardId)
    {
        if (Objects.isNull(storyboardId) || storyboardId <= 0)
        {
            throw new IllegalArgumentException("storyboardId must be positive");
        }
        return SHOT_KEY_PREFIX + storyboardId;
    }

    /**
     * 校验模型输出与目标分镜是否构成完整双射，并按目标顺序归位。
     *
     * @param elements          模型输出元素
     * @param targets           本批目标分镜
     * @param businessNoField   业务编号字段名
     * @return 对齐结果；任何身份契约违约都会返回失败且不携带部分结果
     */
    public static AlignmentResult align(List<JsonNode> elements, List<Target> targets, String businessNoField)
    {
        if (targets == null || targets.isEmpty())
        {
            return AlignmentResult.failure("empty_targets");
        }
        int actualSize = elements == null ? 0 : elements.size();
        if (actualSize != targets.size())
        {
            return AlignmentResult.failure("count_mismatch: expected=" + targets.size() + ", actual=" + actualSize);
        }
        if (businessNoField == null || businessNoField.isBlank())
        {
            return AlignmentResult.failure("business_field_missing");
        }

        Map<String, Integer> keyToIndex = new HashMap<>(targets.size());
        List<String> expectedBusinessNumbers = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++)
        {
            Target target = targets.get(index);
            if (Objects.isNull(target) || Objects.isNull(target.storyboardId()))
            {
                return AlignmentResult.failure("invalid_target: index=" + index);
            }
            String key = buildShotKey(target.storyboardId());
            if (Objects.nonNull(keyToIndex.put(key, index)))
            {
                return AlignmentResult.failure("duplicate_target_key: key=" + key);
            }
            expectedBusinessNumbers.add(normalizeBusinessNumber(target.businessNumber()));
        }

        List<JsonNode> aligned = new ArrayList<>(java.util.Collections.nCopies(targets.size(), null));
        for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++)
        {
            JsonNode element = elements.get(elementIndex);
            if (Objects.isNull(element) || !element.isObject())
            {
                return AlignmentResult.failure("invalid_element: index=" + elementIndex);
            }
            String key = normalizeShotKey(element.path(FIELD_SHOT_KEY).asText(""));
            if (key.isBlank())
            {
                return AlignmentResult.failure("missing_shot_key: index=" + elementIndex);
            }
            Integer targetIndex = keyToIndex.get(key);
            if (Objects.isNull(targetIndex))
            {
                return AlignmentResult.failure("unknown_shot_key: key=" + key);
            }
            if (Objects.nonNull(aligned.get(targetIndex)))
            {
                return AlignmentResult.failure("duplicate_shot_key: key=" + key);
            }
            if (!element.has(businessNoField))
            {
                return AlignmentResult.failure("missing_business_number: key=" + key);
            }
            String expectedBusinessNumber = expectedBusinessNumbers.get(targetIndex);
            String returnedBusinessNumber = normalizeBusinessNumber(element.path(businessNoField).asText(""));
            if (!Objects.equals(expectedBusinessNumber, returnedBusinessNumber))
            {
                return AlignmentResult.failure("business_number_mismatch: key=" + key);
            }
            aligned.set(targetIndex, element);
        }

        if (aligned.stream().anyMatch(Objects::isNull))
        {
            return AlignmentResult.failure("incomplete_alignment");
        }
        return AlignmentResult.success(aligned);
    }

    private static String normalizeShotKey(String raw)
    {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeBusinessNumber(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return "";
        }
        return raw.trim().replaceFirst("^0+(?=.)", "").toUpperCase(Locale.ROOT);
    }

    /** 目标分镜身份。 */
    public record Target(Long storyboardId, String businessNumber)
    {
    }

    /** 批量对齐结果。 */
    public record AlignmentResult(boolean valid, List<JsonNode> elements, String reason)
    {
        private static AlignmentResult success(List<JsonNode> elements)
        {
            return new AlignmentResult(true, List.copyOf(elements), "");
        }

        private static AlignmentResult failure(String reason)
        {
            return new AlignmentResult(false, List.of(), reason);
        }
    }
}

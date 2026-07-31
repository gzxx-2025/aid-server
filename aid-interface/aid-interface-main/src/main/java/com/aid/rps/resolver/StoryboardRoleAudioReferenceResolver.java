package com.aid.rps.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.rps.resolver.StoryboardAudioReferenceResolver.DialogueSegment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 统一解析和校准分镜角色参考音频。
 *
 * @author 视觉AID
 */
@Component
public class StoryboardRoleAudioReferenceResolver
{
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String STATUS_ENABLED = "0";
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String AUDIO_NAME_PREFIX = "音频-";

    /** 引用信息中的音频分区；截至下一个媒体分区或文本结尾。 */
    private static final Pattern AUDIO_SECTION = Pattern.compile(
            "音频\\s*[:：]\\s*.*?(?=(?:场景|角色|道具|视频)\\s*[:：]|$)",
            Pattern.DOTALL);

    /** 兜底流程追加的角色音频映射行。 */
    private static final Pattern AUDIO_ROLE_MAPPING_SECTION = Pattern.compile(
            "(?m)^[\\t ]*音频角色映射\\s*[:：][^\\r\\n]*(?:\\R|$)");

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    @Autowired
    private StoryboardAudioReferenceResolver dialogueResolver;

    /**
     * 加载项目内具有启用音色绑定的角色级参考音频名称。
     *
     * @param projectId 项目 ID
     * @param episodeId 剧集 ID
     * @param userId 用户 ID
     * @return {@code 音频-角色名} 列表，按角色主键升序
     */
    public List<String> loadAvailableReferenceNames(Long projectId, Long episodeId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return Collections.emptyList();
        }
        // 防漏字段：角色级音频名称只依赖 id/name；新增匹配维度时必须同步扩展 select。
        List<AidRolePropScene> characters = rolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropScene::getId));
        return loadAvailableReferenceNames(characters, projectId, episodeId, userId);
    }

    /**
     * 使用调用方已加载的角色列表构建角色级参考音频名称，避免分镜编剧阶段重复查询角色表。
     */
    public List<String> loadAvailableReferenceNames(List<AidRolePropScene> characters,
            Long projectId, Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(characters) || Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return Collections.emptyList();
        }
        List<Long> assetIds = characters.stream()
                .map(AidRolePropScene::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (CollectionUtil.isEmpty(assetIds))
        {
            return Collections.emptyList();
        }
        // 防漏字段：绑定选择依赖 id/assetId/episodeId，引用可用性依赖两类音频 URL。
        List<AidRoleVoiceBinding> bindings = roleVoiceBindingService.list(
                Wrappers.<AidRoleVoiceBinding>lambdaQuery()
                        .select(AidRoleVoiceBinding::getId,
                                AidRoleVoiceBinding::getAssetId,
                                AidRoleVoiceBinding::getEpisodeId,
                                AidRoleVoiceBinding::getSampleUrl,
                                AidRoleVoiceBinding::getReferenceAudioUrl)
                        .in(AidRoleVoiceBinding::getAssetId, assetIds)
                        .eq(AidRoleVoiceBinding::getProjectId, projectId)
                        .eq(AidRoleVoiceBinding::getUserId, userId)
                        .and(wrapper -> {
                            if (Objects.nonNull(episodeId))
                            {
                                wrapper.eq(AidRoleVoiceBinding::getEpisodeId, episodeId)
                                        .or().isNull(AidRoleVoiceBinding::getEpisodeId)
                                        .or().eq(AidRoleVoiceBinding::getEpisodeId, 0L);
                            }
                            else
                            {
                                wrapper.isNull(AidRoleVoiceBinding::getEpisodeId)
                                        .or().eq(AidRoleVoiceBinding::getEpisodeId, 0L);
                            }
                        })
                        .eq(AidRoleVoiceBinding::getStatus, STATUS_ENABLED)
                        .eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(bindings))
        {
            return Collections.emptyList();
        }
        Map<Long, AidRoleVoiceBinding> bindingByAssetId = chooseEffectiveBindings(bindings, episodeId);
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AidRolePropScene character : characters)
        {
            if (Objects.isNull(character) || Objects.isNull(character.getId())
                    || !bindingByAssetId.containsKey(character.getId()) || StrUtil.isBlank(character.getName()))
            {
                continue;
            }
            String referenceName = AUDIO_NAME_PREFIX + character.getName().trim();
            if (seen.add(referenceName))
            {
                result.add(referenceName);
            }
        }
        return result;
    }

    /**
     * 从台词中按发言顺序选出具有有效绑定的角色级音频引用。
     */
    public List<String> resolveSpeakerReferenceNames(String dialogueText, List<String> availableReferenceNames)
    {
        if (CollectionUtil.isEmpty(availableReferenceNames))
        {
            return Collections.emptyList();
        }
        List<String> candidates = availableReferenceNames.stream()
                .filter(StrUtil::isNotBlank)
                .filter(name -> name.startsWith(AUDIO_NAME_PREFIX))
                .map(String::trim)
                .distinct()
                .toList();
        if (CollectionUtil.isEmpty(candidates))
        {
            return Collections.emptyList();
        }
        List<String> speakerReferences = extractSpeakerReferenceNames(dialogueText);
        List<String> result = new ArrayList<>();
        Set<String> seenRoles = new LinkedHashSet<>();
        for (String speakerReference : speakerReferences)
        {
            String roleName = StrUtil.removePrefix(speakerReference, AUDIO_NAME_PREFIX);
            String matched = matchAvailableReference(roleName, candidates);
            if (StrUtil.isBlank(matched))
            {
                continue;
            }
            // 无论候选是否来自历史形态级名称，输出一律归一成角色主名。
            String canonical = AUDIO_NAME_PREFIX + roleName.trim();
            String roleKey = normalizeRoleName(canonical);
            if (seenRoles.add(roleKey))
            {
                result.add(canonical);
            }
        }
        return result;
    }

    /**
     * 仅按台词结构提取发言角色级音频名，不访问数据库。
     * 出片前可先补齐全部发言角色，再交既有参考音频解析器按绑定可用性过滤。
     */
    public List<String> extractSpeakerReferenceNames(String dialogueText)
    {
        if (StrUtil.isBlank(dialogueText))
        {
            return Collections.emptyList();
        }
        List<DialogueSegment> segments = dialogueResolver.parse(dialogueText);
        if (CollectionUtil.isEmpty(segments))
        {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        Set<String> seenRoles = new LinkedHashSet<>();
        for (DialogueSegment segment : segments)
        {
            String roleName = Objects.isNull(segment) ? null : segment.getRoleName();
            if (StrUtil.isBlank(roleName))
            {
                continue;
            }
            String canonical = AUDIO_NAME_PREFIX + roleName.trim();
            if (seenRoles.add(normalizeRoleName(canonical)))
            {
                result.add(canonical);
            }
        }
        return result;
    }

    /**
     * 在分镜脚本的引用信息中确定性写入实际发言角色音频。
     * 发现历史形态级音频分区时整体替换为角色级分区，避免同一绑定重复占位。
     */
    public String ensureReferenceInfo(String referenceInfo, String dialogueText,
            List<String> availableReferenceNames)
    {
        List<String> speakerReferences = resolveSpeakerReferenceNames(dialogueText, availableReferenceNames);
        if (CollectionUtil.isEmpty(speakerReferences))
        {
            return referenceInfo;
        }
        String audioSection = "音频：" + speakerReferences.stream()
                .map(name -> "[" + name + "]")
                .collect(java.util.stream.Collectors.joining("、"));
        String source = StrUtil.nullToEmpty(referenceInfo).strip();
        if (StrUtil.isBlank(source))
        {
            return audioSection;
        }
        Matcher matcher = AUDIO_SECTION.matcher(source);
        if (matcher.find())
        {
            return matcher.replaceFirst(Matcher.quoteReplacement(audioSection)).strip();
        }
        String separator = source.contains("\n") ? "\n" : "；";
        return source + separator + audioSection;
    }

    /** 校验并修复自动生成的视频提示词音频占位。 */
    public String normalizeGeneratedVideoPrompt(String prompt, String dialogueText,
            List<String> availableReferenceNames)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        List<String> speakerReferences = resolveSpeakerReferenceNames(dialogueText, availableReferenceNames);
        return reconcilePromptAudioReferences(prompt, speakerReferences);
    }

    /** 按实际可下发的角色音频列表同步提示词占位。 */
    public String alignPromptToResolvedRoleReferences(String prompt, String dialogueText,
            List<String> resolvedRoleReferenceNames)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        List<String> speakerReferences = resolveSpeakerReferenceNames(
                dialogueText, resolvedRoleReferenceNames);
        return reconcilePromptAudioReferences(prompt, speakerReferences);
    }

    /** 出片前校验并修复库存提示词音频占位。 */
    public String normalizeStoredVideoPrompt(String prompt, String dialogueText)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        List<String> speakerReferences = extractSpeakerReferenceNames(dialogueText);
        if (CollectionUtil.isEmpty(speakerReferences))
        {
            return prompt;
        }
        return reconcilePromptAudioReferences(prompt, speakerReferences);
    }

    /**
     * 正确占位直接原样返回；错误占位按角色名在原位置纠正，仅对缺失角色追加兜底映射。
     */
    private String reconcilePromptAudioReferences(String prompt, List<String> speakerReferences)
    {
        if (hasExactAudioReferences(prompt, speakerReferences))
        {
            return prompt;
        }

        String source = AUDIO_ROLE_MAPPING_SECTION.matcher(prompt).replaceAll("");
        Matcher matcher = StoryboardAudioPlaceholders.pattern().matcher(source);
        StringBuffer rewritten = new StringBuffer();
        Set<Integer> placedIndexes = new LinkedHashSet<>();
        while (matcher.find())
        {
            String originalName = StrUtil.trimToEmpty(matcher.group(2));
            int expectedIndex = findExpectedReferenceIndex(originalName, speakerReferences);
            String replacement;
            if (expectedIndex >= 0)
            {
                String canonicalName = speakerReferences.get(expectedIndex);
                replacement = buildAudioPlaceholder(expectedIndex, canonicalName);
                placedIndexes.add(expectedIndex);
            }
            else
            {
                replacement = readableAudioName(originalName);
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);

        String repaired = rewritten.toString().stripTrailing();
        List<String> missingMappings = new ArrayList<>();
        for (int i = 0; i < speakerReferences.size(); i++)
        {
            if (!placedIndexes.contains(i))
            {
                String referenceName = speakerReferences.get(i);
                String roleName = StrUtil.removePrefix(referenceName, AUDIO_NAME_PREFIX);
                missingMappings.add(roleName + "声音参考" + buildAudioPlaceholder(i, referenceName));
            }
        }
        if (CollectionUtil.isEmpty(missingMappings))
        {
            return repaired;
        }
        return repaired + "\n音频角色映射：" + String.join("；", missingMappings) + "。";
    }

    /** 判断提示词的编号、名称和数量是否与实际发言角色完全一致。 */
    private boolean hasExactAudioReferences(String prompt, List<String> speakerReferences)
    {
        StoryboardAudioPlaceholders.PlaceholderResult placeholders = StoryboardAudioPlaceholders.parse(prompt);
        Map<Integer, String> names = placeholders.getNames();
        if (placeholders.isConflicted() || names.size() != speakerReferences.size())
        {
            return false;
        }
        for (int i = 0; i < speakerReferences.size(); i++)
        {
            if (!Objects.equals(speakerReferences.get(i), StrUtil.trim(names.get(i + 1))))
            {
                return false;
            }
        }
        return true;
    }

    /** 按角色语义查找引用序号，兼容历史形态级音频名称。 */
    private int findExpectedReferenceIndex(String referenceName, List<String> speakerReferences)
    {
        String normalizedReference = normalizeRoleName(referenceName);
        if (StrUtil.isBlank(normalizedReference))
        {
            return -1;
        }
        int legacyMatchIndex = -1;
        int legacyMatchLength = -1;
        for (int i = 0; i < speakerReferences.size(); i++)
        {
            String normalizedExpected = normalizeRoleName(speakerReferences.get(i));
            if (Objects.equals(normalizedReference, normalizedExpected))
            {
                return i;
            }
            if (normalizedReference.startsWith(normalizedExpected + "_")
                    && normalizedExpected.length() > legacyMatchLength)
            {
                legacyMatchIndex = i;
                legacyMatchLength = normalizedExpected.length();
            }
        }
        return legacyMatchIndex;
    }

    /** 构建与请求体下标一致的音频占位。 */
    private String buildAudioPlaceholder(int zeroBasedIndex, String referenceName)
    {
        return "@音频" + (zeroBasedIndex + 1) + "[" + referenceName + "]";
    }

    /** 将不可用占位降级为可读角色名。 */
    private String readableAudioName(String referenceName)
    {
        return StrUtil.removePrefix(StrUtil.trimToEmpty(referenceName), AUDIO_NAME_PREFIX);
    }

    private Map<Long, AidRoleVoiceBinding> chooseEffectiveBindings(List<AidRoleVoiceBinding> bindings,
            Long episodeId)
    {
        Map<Long, AidRoleVoiceBinding> result = new HashMap<>();
        for (AidRoleVoiceBinding candidate : bindings)
        {
            if (Objects.isNull(candidate) || Objects.isNull(candidate.getAssetId())
                    || (StrUtil.isBlank(candidate.getReferenceAudioUrl())
                            && StrUtil.isBlank(candidate.getSampleUrl())))
            {
                continue;
            }
            AidRoleVoiceBinding existing = result.get(candidate.getAssetId());
            if (Objects.isNull(existing) || shouldPrefer(candidate, existing, episodeId))
            {
                result.put(candidate.getAssetId(), candidate);
            }
        }
        return result;
    }

    private boolean shouldPrefer(AidRoleVoiceBinding candidate, AidRoleVoiceBinding existing, Long episodeId)
    {
        boolean candidateExact = Objects.nonNull(episodeId)
                && Objects.equals(candidate.getEpisodeId(), episodeId);
        boolean existingExact = Objects.nonNull(episodeId)
                && Objects.equals(existing.getEpisodeId(), episodeId);
        return candidateExact && !existingExact;
    }

    private String matchAvailableReference(String roleName, List<String> candidates)
    {
        if (StrUtil.isBlank(roleName))
        {
            return null;
        }
        String normalizedRole = normalizeRoleName(roleName);
        Map<String, String> legacyMatches = new LinkedHashMap<>();
        for (String candidate : candidates)
        {
            String normalizedCandidate = normalizeRoleName(candidate);
            if (Objects.equals(normalizedRole, normalizedCandidate))
            {
                return candidate;
            }
            if (normalizedCandidate.startsWith(normalizedRole + "_"))
            {
                legacyMatches.putIfAbsent(normalizedCandidate, candidate);
            }
        }
        return legacyMatches.values().stream().findFirst().orElse(null);
    }

    private String normalizeRoleName(String name)
    {
        String value = StrUtil.removePrefix(StrUtil.trimToEmpty(name), AUDIO_NAME_PREFIX);
        return value.replace('*', '_').replace('-', '_').replace('－', '_')
                .replace('—', '_').replace('–', '_').toLowerCase(Locale.ROOT);
    }
}

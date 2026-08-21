package com.aid.rps.assembler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.common.utils.AssetNameNormalizer;
import com.aid.rps.model.StoryboardSceneContext;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜场景上下文装配器。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardSceneContextAssembler
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String SCENE_NAME_KEY = "场景名称";
    private static final String REFERENCE_INFO_KEY = "引用信息";
    private static final List<String> REFERENCE_SECTIONS = List.of("场景", "角色", "道具", "视频", "音频");
    private static final List<String> VIEW_LABELS = List.of("主视", "反打", "左立面", "右立面");
    private static final Pattern REFERENCE_SECTION_PATTERN =
            Pattern.compile("(场景|角色|道具|视频|音频)\\s*[:：]");
    private static final Pattern BRACKET_NAME_PATTERN = Pattern.compile("\\[([^\\]]+)]");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private IAidRolePropSceneFormImageService formImageService;

    /**
     * 批量装配分镜当前场景上下文。
     *
     * @param storyboards 分镜列表
     * @param projectId 项目ID
     * @param userId 用户ID
     * @return 分镜ID到场景上下文的映射
     */
    public Map<Long, StoryboardSceneContext> assemble(List<AidStoryboard> storyboards,
                                                       Long projectId, Long userId)
    {
        if (CollectionUtil.isEmpty(storyboards) || projectId == null || userId == null)
        {
            return Map.of();
        }

        Map<Long, Map<String, Object>> paramsByStoryboard = new LinkedHashMap<>();
        Map<Long, String> snapshotNameByStoryboard = new LinkedHashMap<>();
        Set<String> snapshotNameKeys = new LinkedHashSet<>();
        Set<Long> sourceSceneIds = new LinkedHashSet<>();
        for (AidStoryboard storyboard : storyboards)
        {
            Map<String, Object> params = parseScriptParams(storyboard.getScriptParams());
            paramsByStoryboard.put(storyboard.getId(), params);
            String snapshotName = StrUtil.trim(Convert.toStr(params.get(SCENE_NAME_KEY), ""));
            if (StrUtil.isNotBlank(snapshotName))
            {
                snapshotNameByStoryboard.put(storyboard.getId(), snapshotName);
                snapshotNameKeys.add(AssetNameNormalizer.normalize(snapshotName));
            }
            if (storyboard.getSourceSceneId() != null)
            {
                sourceSceneIds.add(storyboard.getSourceSceneId());
            }
        }

        Map<String, AidRolePropScene> sceneByNameKey = loadScenesByName(
                projectId, userId, snapshotNameKeys,
                new LinkedHashSet<>(snapshotNameByStoryboard.values()));
        Map<Long, AidRolePropScene> activeSceneById = loadScenesById(
                projectId, userId, sourceSceneIds);

        Map<Long, AidRolePropScene> sceneByStoryboard = new LinkedHashMap<>();
        Set<Long> resolvedSceneIds = new LinkedHashSet<>();
        for (AidStoryboard storyboard : storyboards)
        {
            String snapshotName = snapshotNameByStoryboard.get(storyboard.getId());
            AidRolePropScene scene = activeSceneById.get(storyboard.getSourceSceneId());
            if (scene == null && StrUtil.isNotBlank(snapshotName))
            {
                scene = sceneByNameKey.get(AssetNameNormalizer.normalize(snapshotName));
                if (scene == null)
                {
                    log.warn("event=storyboard_scene_context_unresolved reason=scene_name_not_found "
                                    + "projectId={} userId={} storyboardId={} sourceSceneId={} sceneName={}",
                            projectId, userId, storyboard.getId(), storyboard.getSourceSceneId(), snapshotName);
                }
            }
            else if (scene == null)
            {
                log.warn("event=storyboard_scene_context_unresolved reason=legacy_scene_missing "
                                + "projectId={} userId={} storyboardId={} sourceSceneId={}",
                        projectId, userId, storyboard.getId(), storyboard.getSourceSceneId());
            }
            if (scene != null)
            {
                sceneByStoryboard.put(storyboard.getId(), scene);
                resolvedSceneIds.add(scene.getId());
            }
        }

        Map<Long, List<AidRolePropSceneFormImage>> imagesByAsset = loadSceneImages(
                projectId, userId, resolvedSceneIds);
        Map<Long, StoryboardSceneContext> result = new LinkedHashMap<>();
        for (AidStoryboard storyboard : storyboards)
        {
            AidRolePropScene scene = sceneByStoryboard.get(storyboard.getId());
            String snapshotName = snapshotNameByStoryboard.get(storyboard.getId());
            if (scene == null)
            {
                result.put(storyboard.getId(), new StoryboardSceneContext(
                        storyboard.getId(), null, snapshotName, null, null));
                continue;
            }
            Map<String, Object> params = paramsByStoryboard.getOrDefault(storyboard.getId(), Map.of());
            List<AidRolePropSceneFormImage> images = imagesByAsset.getOrDefault(scene.getId(), List.of());
            AidRolePropSceneFormImage selected = selectReferenceImage(params, images);
            String description = StrUtil.blankToDefault(scene.getIntroduction(), scene.getSummary());
            result.put(storyboard.getId(), new StoryboardSceneContext(
                    storyboard.getId(), scene.getId(), scene.getName(), description,
                    selected == null ? null : selected.getName()));
        }
        return result;
    }

    /**
     * 用确定性场景上下文替换脚本参数中的场景引用分区。
     *
     * @param params 分镜脚本参数
     * @param context 场景上下文
     */
    public void applyReferenceInfo(Map<String, Object> params, StoryboardSceneContext context)
    {
        if (params == null || context == null)
        {
            return;
        }
        String current = Convert.toStr(params.get(REFERENCE_INFO_KEY), "");
        String merged = replaceSceneReferenceSection(current, context.referenceImageName());
        if (StrUtil.isBlank(merged))
        {
            params.remove(REFERENCE_INFO_KEY);
        }
        else
        {
            params.put(REFERENCE_INFO_KEY, merged);
        }
    }

    /**
     * 生成供视觉导演使用的场景上下文文本。
     *
     * @param context 场景上下文
     * @return 场景上下文文本
     */
    public String formatPromptContext(StoryboardSceneContext context)
    {
        if (context == null || StrUtil.isBlank(context.sceneName()))
        {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append("场景名称：").append(context.sceneName()).append('\n');
        if (StrUtil.isNotBlank(context.sceneDescription()))
        {
            text.append("场景描述：").append(context.sceneDescription()).append('\n');
        }
        if (StrUtil.isNotBlank(context.referenceImageName()))
        {
            text.append("场景参考图：").append(context.referenceImageName()).append('\n');
        }
        else
        {
            text.append("场景参考图：无可用图片，仅使用场景文字\n");
        }
        return text.toString();
    }

    private Map<String, AidRolePropScene> loadScenesByName(Long projectId, Long userId,
                                                           Set<String> nameKeys,
                                                           Set<String> displayNames)
    {
        if (CollectionUtil.isEmpty(nameKeys))
        {
            return Map.of();
        }
        List<AidRolePropScene> scenes = rolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName,
                                AidRolePropScene::getNameNormalized, AidRolePropScene::getIntroduction,
                                AidRolePropScene::getSummary)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_SCENE)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                        .and(wrapper -> wrapper.in(AidRolePropScene::getNameNormalized, nameKeys)
                                .or().in(AidRolePropScene::getName, displayNames)));
        return scenes.stream()
                .filter(scene -> nameKeys.contains(AssetNameNormalizer.normalize(scene.getName())))
                .collect(Collectors.toMap(
                scene -> AssetNameNormalizer.normalize(scene.getName()), scene -> scene, (left, right) -> left));
    }

    private Map<Long, AidRolePropScene> loadScenesById(Long projectId, Long userId, Set<Long> sceneIds)
    {
        if (CollectionUtil.isEmpty(sceneIds))
        {
            return Map.of();
        }
        List<AidRolePropScene> scenes = rolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName,
                                AidRolePropScene::getIntroduction, AidRolePropScene::getSummary)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, ASSET_TYPE_SCENE)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                        .in(AidRolePropScene::getId, sceneIds));
        return scenes.stream().collect(Collectors.toMap(AidRolePropScene::getId, scene -> scene));
    }

    private Map<Long, List<AidRolePropSceneFormImage>> loadSceneImages(Long projectId, Long userId,
                                                                       Set<Long> sceneIds)
    {
        if (CollectionUtil.isEmpty(sceneIds))
        {
            return Map.of();
        }
        List<AidRolePropSceneFormImage> images = formImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getAssetId,
                                AidRolePropSceneFormImage::getName, AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getSortOrder,
                                AidRolePropSceneFormImage::getIsSplitSource,
                                AidRolePropSceneFormImage::getIsSplitChild)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, 1)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, 0)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .isNotNull(AidRolePropSceneFormImage::getImageUrl)
                        .in(AidRolePropSceneFormImage::getAssetId, sceneIds)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder)
                        .orderByAsc(AidRolePropSceneFormImage::getId));
        return images.stream()
                .filter(image -> StrUtil.isNotBlank(image.getName()) && StrUtil.isNotBlank(image.getImageUrl()))
                .collect(Collectors.groupingBy(AidRolePropSceneFormImage::getAssetId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    private AidRolePropSceneFormImage selectReferenceImage(Map<String, Object> params,
                                                            List<AidRolePropSceneFormImage> images)
    {
        if (CollectionUtil.isEmpty(images))
        {
            return null;
        }
        List<String> requestedNames = extractBracketNames(
                extractReferenceSection(Convert.toStr(params.get(REFERENCE_INFO_KEY), ""), "场景"));
        for (String requestedName : requestedNames)
        {
            AidRolePropSceneFormImage exact = findMatchingImage(requestedName, images);
            if (exact != null)
            {
                return exact;
            }
        }

        List<AidRolePropSceneFormImage> splitChildren = images.stream()
                .filter(image -> Objects.equals(image.getIsSplitChild(), 1))
                .sorted(imageComparator())
                .collect(Collectors.toList());
        if (CollectionUtil.isNotEmpty(splitChildren))
        {
            String desiredView = inferView(params);
            AidRolePropSceneFormImage desired = findViewImage(splitChildren, desiredView);
            if (desired != null)
            {
                return desired;
            }
            AidRolePropSceneFormImage main = findViewImage(splitChildren, VIEW_LABELS.get(0));
            return main == null ? splitChildren.get(0) : main;
        }

        return images.stream()
                .filter(image -> !Objects.equals(image.getIsSplitChild(), 1))
                .sorted(imageComparator())
                .findFirst()
                .orElse(null);
    }

    private Comparator<AidRolePropSceneFormImage> imageComparator()
    {
        return Comparator.comparing(AidRolePropSceneFormImage::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AidRolePropSceneFormImage::getId,
                        Comparator.nullsLast(Long::compareTo));
    }

    private AidRolePropSceneFormImage findMatchingImage(String requestedName,
                                                         List<AidRolePropSceneFormImage> images)
    {
        List<String> requestedKeys = StoryboardImageReferenceResolver.candidateLookupKeys(requestedName);
        for (String requestedKey : requestedKeys)
        {
            for (AidRolePropSceneFormImage image : images)
            {
                String imageKey = StoryboardImageReferenceResolver.normalizeAssetRefName(image.getName());
                if (requestedKey.equals(imageKey))
                {
                    return image;
                }
            }
        }
        return null;
    }

    private AidRolePropSceneFormImage findViewImage(List<AidRolePropSceneFormImage> images, String view)
    {
        String suffix = "_" + view;
        return images.stream()
                .filter(image -> StrUtil.trimToEmpty(image.getName()).endsWith(suffix))
                .findFirst()
                .orElse(null);
    }

    private String inferView(Map<String, Object> params)
    {
        StringBuilder text = new StringBuilder();
        for (String key : List.of("拍摄角度", "镜头脚本", "画面说明", "画面描述", "镜头运动", "构图"))
        {
            text.append(Convert.toStr(params.get(key), "")).append(' ');
        }
        String value = text.toString();
        if (containsAny(value, "反打", "背面", "背后", "后方", "回头", "回望", "转身"))
        {
            return "反打";
        }
        if (containsAny(value, "左立面", "左侧立面", "向左", "左转", "朝左"))
        {
            return "左立面";
        }
        if (containsAny(value, "右立面", "右侧立面", "向右", "右转", "朝右"))
        {
            return "右立面";
        }
        return "主视";
    }

    private boolean containsAny(String value, String... candidates)
    {
        for (String candidate : candidates)
        {
            if (value.contains(candidate))
            {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> parseScriptParams(String scriptParams)
    {
        if (StrUtil.isBlank(scriptParams))
        {
            return new LinkedHashMap<>();
        }
        try
        {
            return OBJECT_MAPPER.readValue(scriptParams,
                    OBJECT_MAPPER.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Object.class));
        }
        catch (Exception e)
        {
            return new LinkedHashMap<>();
        }
    }

    private String replaceSceneReferenceSection(String referenceInfo, String sceneImageName)
    {
        Map<String, String> sections = parseReferenceSections(referenceInfo);
        if (StrUtil.isNotBlank(sceneImageName))
        {
            sections.put("场景", "[" + sceneImageName + "]");
        }
        else
        {
            sections.remove("场景");
        }
        List<String> parts = new ArrayList<>();
        for (String label : REFERENCE_SECTIONS)
        {
            String value = StrUtil.trim(sections.get(label));
            if (StrUtil.isNotBlank(value))
            {
                parts.add(label + ":" + value);
            }
        }
        return String.join(" ", parts);
    }

    private Map<String, String> parseReferenceSections(String referenceInfo)
    {
        Map<String, String> sections = new LinkedHashMap<>();
        if (StrUtil.isBlank(referenceInfo))
        {
            return sections;
        }
        Matcher matcher = REFERENCE_SECTION_PATTERN.matcher(referenceInfo);
        List<SectionPosition> positions = new ArrayList<>();
        while (matcher.find())
        {
            positions.add(new SectionPosition(matcher.group(1), matcher.end(), matcher.start()));
        }
        for (int i = 0; i < positions.size(); i++)
        {
            SectionPosition current = positions.get(i);
            int end = i + 1 < positions.size() ? positions.get(i + 1).labelStart() : referenceInfo.length();
            sections.putIfAbsent(current.label(), StrUtil.trim(referenceInfo.substring(current.valueStart(), end)));
        }
        return sections;
    }

    private String extractReferenceSection(String referenceInfo, String sectionName)
    {
        return parseReferenceSections(referenceInfo).getOrDefault(sectionName, "");
    }

    private List<String> extractBracketNames(String section)
    {
        List<String> names = new ArrayList<>();
        Matcher matcher = BRACKET_NAME_PATTERN.matcher(StrUtil.nullToEmpty(section));
        while (matcher.find())
        {
            String name = StrUtil.trim(matcher.group(1));
            if (StrUtil.isNotBlank(name))
            {
                names.add(name);
            }
        }
        return names;
    }

    private record SectionPosition(String label, int valueStart, int labelStart)
    {
    }
}

package com.aid.rps.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import cn.hutool.core.util.StrUtil;

/**
 * 已有资产库快照，用于提取过程中去重判断，支持跨切片增量构建。
 *
 * @author 视觉AID
 */
public class ExistingAssetLib
{
    private final Set<String> characterNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> propNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    /** 名称小写 → 名称+别名列表，用于交叉匹配 */
    private final Map<String, List<String>> characterAliasMap = new HashMap<>();
    private final Map<String, List<String>> propAliasMap = new HashMap<>();

    /** 场景去重映射 specificLocation（lower-case）→ aid_role_prop_scene.id（保留插入顺序） */
    private final Map<String, Long> sceneSpecificLocationMap = new LinkedHashMap<>();

    /** 已添加 scene 的展示用 specificLocation 原文（保留原始大小写），喂给 LLM 提示词 */
    private final Set<String> sceneSpecificLocations = new LinkedHashSetCaseInsensitive();
    /**
     * 添加角色到已有库（切片处理后调用，用于后续切片去重）
     *
     * @param name    角色名
     * @param aliases 别名列表
     */
    public void addCharacter(String name, List<String> aliases)
    {
        if (StrUtil.isNotBlank(name))
        {
            characterNames.add(name);
            if (aliases != null && !aliases.isEmpty())
            {
                characterAliasMap.put(name.toLowerCase(), aliases);
            }
        }
    }

    /**
     * 按 specificLocation 登记 scene（同一 location 仅记录首次 sceneId）。
     *
     * @param specificLocation 空间唯一名称（已 strip 时间后缀）
     * @param sceneId          aid_role_prop_scene.id
     */
    public void addScene(String specificLocation, Long sceneId)
    {
        if (StrUtil.isBlank(specificLocation) || Objects.isNull(sceneId))
        {
            return;
        }
        sceneSpecificLocationMap.putIfAbsent(specificLocation.toLowerCase(), sceneId);
        sceneSpecificLocations.add(specificLocation);
    }

    /**
     * 按 specificLocation 查 sceneId（lower-case 匹配）。
     *
     * @return 命中 → 返回 sceneId；不命中 → 返回 null
     */
    public Long findSceneIdByLocation(String specificLocation)
    {
        if (StrUtil.isBlank(specificLocation))
        {
            return null;
        }
        return sceneSpecificLocationMap.get(specificLocation.toLowerCase());
    }

    /**
     * 添加道具到已有库
     */
    public void addProp(String name, List<String> aliases)
    {
        if (StrUtil.isNotBlank(name))
        {
            propNames.add(name);
            if (aliases != null && !aliases.isEmpty())
            {
                propAliasMap.put(name.toLowerCase(), aliases);
            }
        }
    }
    public Set<String> getCharacterNames()
    {
        return characterNames;
    }

    /**
     * 场景层面"已有名称"语义为 specificLocation 集合（原始大小写，忽略大小写去重，保留插入顺序）。
     */
    public Set<String> getSceneNames()
    {
        return sceneSpecificLocations;
    }

    public Set<String> getPropNames()
    {
        return propNames;
    }

    public Map<String, List<String>> getCharacterAliasMap()
    {
        return characterAliasMap;
    }

    /** 场景去重不依赖别名匹配，返回空 Map，保留以兼容老调用点 */
    public Map<String, List<String>> getSceneAliasMap()
    {
        return Collections.emptyMap();
    }

    public Map<String, List<String>> getPropAliasMap()
    {
        return propAliasMap;
    }

    /** 返回 specificLocation → sceneId 映射的只读视图，供逐条遍历调用方使用 */
    public Map<String, Long> getSceneSpecificLocationMap()
    {
        return Collections.unmodifiableMap(sceneSpecificLocationMap);
    }
    public String getCharacterNamesJoined()
    {
        return characterNames.isEmpty() ? "无" : String.join(", ", characterNames);
    }

    /** 组装喂给场景提取智能体的 locations_lib_name（specificLocation 集合原文） */
    public String getSceneNamesJoined()
    {
        return sceneSpecificLocations.isEmpty() ? "无" : String.join(", ", sceneSpecificLocations);
    }

    public String getPropNamesJoined()
    {
        return propNames.isEmpty() ? "无" : String.join(", ", propNames);
    }

    /** 大小写不敏感的有序集合：内部用 lower-case 判重，对外返回插入顺序的原始字符串 */
    private static final class LinkedHashSetCaseInsensitive extends LinkedHashSet<String>
    {
        private final Set<String> lowerSeen = new HashSet<>();

        @Override
        public boolean add(String e)
        {
            if (StrUtil.isBlank(e))
            {
                return false;
            }
            String key = e.toLowerCase();
            if (!lowerSeen.add(key))
            {
                return false;
            }
            return super.add(e);
        }
    }
}

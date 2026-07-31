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

    /** 场景去重映射 canonicalName（地点_时间，lower-case）→ 主资产ID（保留插入顺序） */
    private final Map<String, Long> sceneNameMap = new LinkedHashMap<>();

    /** 已添加场景的权威名称原文（地点_时间），用于提示词去重。 */
    private final Set<String> sceneNames = new LinkedHashSetCaseInsensitive();
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
     * 按权威场景名称登记场景，同一地点的不同时段分别建档。
     *
     * @param sceneName 场景名称，格式为“地点_时间”
     * @param sceneId   aid_role_prop_scene.id
     */
    public void addScene(String sceneName, Long sceneId)
    {
        if (StrUtil.isBlank(sceneName) || Objects.isNull(sceneId))
        {
            return;
        }
        sceneNameMap.putIfAbsent(sceneName.toLowerCase(), sceneId);
        sceneNames.add(sceneName);
    }

    /**
     * 按“地点_时间”权威名称查询场景主资产ID（忽略大小写）。
     *
     * @return 命中 → 返回 sceneId；不命中 → 返回 null
     */
    public Long findSceneIdByName(String sceneName)
    {
        if (StrUtil.isBlank(sceneName))
        {
            return null;
        }
        return sceneNameMap.get(sceneName.toLowerCase());
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
     * 场景层面“已有名称”为“地点_时间”权威名称集合。
     */
    public Set<String> getSceneNames()
    {
        return sceneNames;
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

    /** 返回“地点_时间”→ sceneId 映射的只读视图。 */
    public Map<String, Long> getSceneNameMap()
    {
        return Collections.unmodifiableMap(sceneNameMap);
    }
    public String getCharacterNamesJoined()
    {
        return characterNames.isEmpty() ? "无" : String.join(", ", characterNames);
    }

    /** 组装喂给场景提取智能体的“地点_时间”权威名称目录。 */
    public String getSceneNamesJoined()
    {
        return sceneNames.isEmpty() ? "无" : String.join(", ", sceneNames);
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

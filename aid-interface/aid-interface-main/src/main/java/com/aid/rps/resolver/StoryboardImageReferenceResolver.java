package com.aid.rps.resolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.common.aid.oss.util.MediaUrlResolver;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜图脚本 image_prompt 中 {@code @图片N[name]} 参考图占位的解析器：按 N 顺序精确匹配 form_image 并返回参考图 URL，纯查询、可重入。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class StoryboardImageReferenceResolver
{
    /** 占位正则 {@code @图片N[name]}：组1=编号 N，组2=name。 */
    private static final Pattern REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");

    /**
     * 场景四视图拆分子图方位后缀，顺序固定：主视 / 反打 / 左立面 / 右立面。
     * 与 {@link com.aid.rps.service.impl.RpsFormImageBusinessServiceImpl} 的拆分命名严格对齐，不要单独维护。
     * 公开给引用名匹配的所有调用方（出图解析 / 引用即启用 / 画师输出消毒），保证回退口径一处定义。
     */
    public static final List<String> DIRECTION_LABELS = List.of("主视", "反打", "左立面", "右立面");

    /** 删除标志 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** is_use=1（用户主动设为引用） */
    private static final int IS_USE_YES = 1;

    /** is_split_source=0（拆完的源图自动剔除，不可参与引用） */
    private static final int IS_SPLIT_SOURCE_NO = 0;

    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;

    @Autowired
    private IAidRolePropSceneService rpsService;

    /** MediaUrlResolver：DB 存相对路径，下游需完整 URL。 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /**
     * 解析 image_prompt 中的 @图片N[name] 占位，按 N 顺序精确匹配 form_image。
     * 可引用域=项目+用户（不按集过滤）：项目级角色图与跨集复用资产图均可解析。
     *
     * @param imagePrompt 分镜画师生成的 image_prompt 文本
     * @param projectId   项目 ID（防越权）
     * @param userId      当前用户 ID（防越权）
     * @return 解析结果（含 referenceImageIds / referenceImageUrls / unresolvedNames）
     */
    public ResolveResult resolve(String imagePrompt, Long projectId, Long userId)
    {
        ResolveResult result = new ResolveResult();
        if (StrUtil.isBlank(imagePrompt) || Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return result;
        }

        Map<Integer, String> nameByN = new LinkedHashMap<>();
        Matcher m = REF_PATTERN.matcher(imagePrompt);
        while (m.find())
        {
            int n;
            try { n = Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { continue; }
            String name = StrUtil.trimToEmpty(m.group(2));
            if (StrUtil.isBlank(name)) { continue; }
            nameByN.putIfAbsent(n, name);
        }
        if (nameByN.isEmpty())
        {
            return result;
        }

        //    不按 name IN 过滤，便于对占位名做分隔符归一化后匹配。
        //    可引用域=项目+用户（不按集过滤）：剧集角色形态图归属项目级（episode_id=0）、
        //    跨集复用资产的图归属其它集，编剧字典本身按项目装配，按集过滤会漏配；
        //    电影模式项目下所有行 episode_id=0，结果集不变
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_SOURCE_NO)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));

        // 同 name 多图时按 sort_order 升序取首张 image_url 非空的；key 统一 trim + 分隔符归一化
        Map<String, AidRolePropSceneFormImage> byName = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(imgs))
        {
            for (AidRolePropSceneFormImage img : imgs)
            {
                if (StrUtil.isBlank(img.getImageUrl())) { continue; }
                byName.putIfAbsent(normalizeAssetRefName(img.getName()), img);
            }
        }

        List<Long> ids = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        List<ResolvedRefItem> items = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        // 排序后按 N 升序，避免 LLM 编号穿插导致 reference 顺序乱
        List<Integer> orderedNs = new ArrayList<>(nameByN.keySet());
        orderedNs.sort(Integer::compareTo);
        for (Integer n : orderedNs)
        {
            String name = nameByN.get(n);
            AidRolePropSceneFormImage hit = lookupByCandidates(byName, name);
            // 命中为空，或命中行 image_url 为空/不可用 → 都视为未匹配（不可用），不放进 urls 造成空位/错位
            if (Objects.isNull(hit) || StrUtil.isBlank(hit.getImageUrl()))
            {
                unresolved.add(name);
                continue;
            }
            // DB 存的是相对路径，下游需要完整 URL（与现有图生图链路保持一致）
            String fullUrl = mediaUrlResolver.toFullUrl(hit.getImageUrl());
            ids.add(hit.getId());
            urls.add(fullUrl);
            ResolvedRefItem item = new ResolvedRefItem();
            item.setN(n);
            item.setName(name);
            item.setImageId(hit.getId());
            item.setUrl(fullUrl);
            items.add(item);
        }

        result.setReferenceImageIds(ids);
        result.setReferenceImageUrls(urls);
        result.setReferences(items);
        result.setUnresolvedNames(unresolved);
        if (!unresolved.isEmpty())
        {
            log.warn("StoryboardImageReferenceResolver: 部分占位未匹配, projectId={}, userId={}, "
                            + "totalRefs={}, unresolvedCount={}, unresolvedNames={}",
                    projectId, userId, orderedNs.size(), unresolved.size(), unresolved);
        }
        return result;
    }

    /**
     * 富化解析：在 {@link #resolve} 基础上补充每个引用的资产类型与主资产名，并按是否有可用图分型（REFERENCE/DESCRIPTION），供厂商参考图渲染策略使用。批量查询无 N+1。
     * 可引用域=项目+用户（不按集过滤），口径与 {@link #resolve} 一致。
     *
     * @param imagePrompt 分镜画师生成的 image_prompt 文本
     * @param projectId   项目 ID（防越权）
     * @param userId      当前用户 ID（防越权）
     * @return 按 N 升序的富化引用列表；无 @图片N 占位返回空列表
     */
    public List<ResolvedImageReference> resolveRich(String imagePrompt, Long projectId, Long userId)
    {
        List<ResolvedImageReference> out = new ArrayList<>();
        if (StrUtil.isBlank(imagePrompt) || Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return out;
        }

        Map<Integer, String> nameByN = new LinkedHashMap<>();
        Matcher m = REF_PATTERN.matcher(imagePrompt);
        while (m.find())
        {
            int n;
            try { n = Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { continue; }
            String name = StrUtil.trimToEmpty(m.group(2));
            if (StrUtil.isBlank(name)) { continue; }
            nameByN.putIfAbsent(n, name);
        }
        if (nameByN.isEmpty())
        {
            return out;
        }

        // 可引用域=项目+用户（不按集过滤），口径与 resolve 一致：
        // 项目级角色图（episode_id=0）/ 跨集复用资产图按集过滤会漏配
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getAssetId,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_SOURCE_NO)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));

        // 同 name 取首张「image_url 非空」的（与 resolve / 白名单口径一致）；key 统一 trim + 分隔符归一化
        Map<String, AidRolePropSceneFormImage> byName = new LinkedHashMap<>();
        Set<Long> assetIds = new HashSet<>();
        if (CollectionUtil.isNotEmpty(imgs))
        {
            for (AidRolePropSceneFormImage img : imgs)
            {
                if (StrUtil.isBlank(img.getImageUrl())) { continue; }
                AidRolePropSceneFormImage prev = byName.putIfAbsent(normalizeAssetRefName(img.getName()), img);
                if (Objects.isNull(prev) && Objects.nonNull(img.getAssetId()))
                {
                    assetIds.add(img.getAssetId());
                }
            }
        }

        Map<Long, AidRolePropScene> assetById = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(assetIds))
        {
            List<AidRolePropScene> assets = rpsService.list(
                    Wrappers.<AidRolePropScene>lambdaQuery()
                            .select(AidRolePropScene::getId, AidRolePropScene::getName,
                                    AidRolePropScene::getAssetType)
                            .in(AidRolePropScene::getId, assetIds)
                            .eq(AidRolePropScene::getProjectId, projectId)
                            .eq(AidRolePropScene::getUserId, userId)
                            .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
            for (AidRolePropScene a : assets)
            {
                assetById.put(a.getId(), a);
            }
        }

        List<Integer> orderedNs = new ArrayList<>(nameByN.keySet());
        orderedNs.sort(Integer::compareTo);
        for (Integer n : orderedNs)
        {
            String name = nameByN.get(n);
            ResolvedImageReference ref = new ResolvedImageReference();
            ref.setN(n);
            ref.setName(name);
            AidRolePropSceneFormImage hit = lookupByCandidates(byName, name);
            if (Objects.nonNull(hit) && StrUtil.isNotBlank(hit.getImageUrl()))
            {
                // 命中可用图 → 引用类型
                ref.setType(RefType.REFERENCE);
                ref.setUrl(mediaUrlResolver.toFullUrl(hit.getImageUrl()));
                AidRolePropScene asset = Objects.nonNull(hit.getAssetId()) ? assetById.get(hit.getAssetId()) : null;
                if (Objects.nonNull(asset))
                {
                    ref.setAssetKind(asset.getAssetType());
                    ref.setAssetName(asset.getName());
                }
            }
            else
            {
                // 无可用图 → 描述类型（不进 image[]，由渲染策略文字化）
                ref.setType(RefType.DESCRIPTION);
                log.warn("StoryboardImageReferenceResolver.resolveRich: 引用未命中可用图(描述化), projectId={}, "
                        + "n={}, name={}", projectId, n, name);
            }
            out.add(ref);
        }
        return out;
    }

    /**
     * 资产引用名归一化：把结构分隔符的连字符/全角横线统一成下划线后再比对，消除 LLM 把 {@code _} 写成 {@code -} 导致的漏配。仅用于匹配比对。
     */
    public static String normalizeAssetRefName(String s)
    {
        if (s == null) { return ""; }
        String t = StrUtil.trim(s);
        return t.replace('-', '_').replace('－', '_').replace('‐', '_').replace('–', '_');
    }

    /**
     * 生成引用名的候选匹配键（均已归一化），按优先级排列：
     * <ol>
     *   <li>原名精确键；</li>
     *   <li>原名带方位后缀（{@code X_反打}）→ 追加整图基名 {@code X}（场景未拆分、编剧却引用了方位子图名的回退）；</li>
     *   <li>原名不带方位后缀 → 按固定方位顺序追加 {@code X_主视 … X_右立面}（引用了整图名、库里只有拆分子图的回退）。</li>
     * </ol>
     * 方位后缀是场景四视图拆分的封闭集合，不会与角色形态名（如 {@code _初始形象}）冲突，回退不会串资产。
     * 所有按名匹配 form_image 的调用方（出图解析 / 引用即启用 / 画师输出消毒 / 引用清洗）统一走本方法，保证口径一致。
     */
    public static List<String> candidateLookupKeys(String rawName)
    {
        List<String> keys = new ArrayList<>();
        String exact = normalizeAssetRefName(rawName);
        if (StrUtil.isBlank(exact))
        {
            return keys;
        }
        keys.add(exact);
        String label = directionLabelOf(exact);
        if (label != null)
        {
            String base = exact.substring(0, exact.length() - label.length() - 1);
            if (StrUtil.isNotBlank(base))
            {
                keys.add(base);
            }
        }
        else
        {
            for (String l : DIRECTION_LABELS)
            {
                keys.add(exact + "_" + l);
            }
        }
        return keys;
    }

    /** 提取归一化名末尾的方位后缀标签（{@code X_反打} → {@code 反打}）；无方位后缀返回 null。 */
    private static String directionLabelOf(String normalizedName)
    {
        for (String label : DIRECTION_LABELS)
        {
            if (normalizedName.endsWith("_" + label))
            {
                return label;
            }
        }
        return null;
    }

    /** 按候选键顺序在名字索引中查找，返回首个命中；全不中返回 null。 */
    private static AidRolePropSceneFormImage lookupByCandidates(
            Map<String, AidRolePropSceneFormImage> byName, String rawName)
    {
        for (String key : candidateLookupKeys(rawName))
        {
            AidRolePropSceneFormImage hit = byName.get(key);
            if (Objects.nonNull(hit))
            {
                return hit;
            }
        }
        return null;
    }

    /**
     * 引用分型：是否需要把真实参考图喂给模型。
     */
    public enum RefType
    {
        /** 引用类型：进 image[] 数组，需要参考图 + 厂商语法标注。 */
        REFERENCE,
        /** 描述类型：仅 prompt 文字命名，不进 image[]。 */
        DESCRIPTION
    }

    /**
     * 单个 @图片N[name] 引用的富化解析结果（供厂商参考图渲染策略使用）。
     */
    @Data
    public static class ResolvedImageReference
    {
        /** 占位编号 N（与 prompt 内 @图片N 对应）。 */
        private int n;
        /** 占位内名字（= form_image.name）。 */
        private String name;
        /** 资产类型：character/scene/prop；外部图或查不到主资产时为 null。 */
        private String assetKind;
        /** 主资产名（aid_role_prop_scene.name），用于渲染显示名；可空。 */
        private String assetName;
        /** 可用完整 URL；描述类型为 null。 */
        private String url;
        /** 分型：REFERENCE（进 image[]）/ DESCRIPTION（仅文字）。 */
        private RefType type;
    }

    /**
     * 单个解析成功的参考图条目（编号 + 占位名 + 图 ID + URL 对齐返回，供前端识别每张图归属）。
     */
    @Data
    public static class ResolvedRefItem
    {
        /** 占位编号 N（与 prompt 内 @图片N 对应）。 */
        private int n;

        /** 占位内名字（= form_image.name）。 */
        private String name;

        /** 命中的 form_image.id。 */
        private Long imageId;

        /** 完整 image URL。 */
        private String url;
    }

    /**
     * 解析结果。
     */
    @Data
    public static class ResolveResult
    {
        /** 解析成功的 form_image.id 列表，按 N 编号升序。 */
        private List<Long> referenceImageIds = Collections.emptyList();

        /** 解析成功的完整 image URL 列表，与 {@link #referenceImageIds} 同序同长，可直接喂给上游模型。 */
        private List<String> referenceImageUrls = Collections.emptyList();

        /** 解析成功的参考图明细（编号/占位名/图ID/URL 对齐），与 {@link #referenceImageIds} 同序同长。 */
        private List<ResolvedRefItem> references = Collections.emptyList();

        /** 占位提到但未匹配到可用 form_image 的 name 列表，调用方据此决定抛错或仅 warn。 */
        private List<String> unresolvedNames = Collections.emptyList();
    }
}

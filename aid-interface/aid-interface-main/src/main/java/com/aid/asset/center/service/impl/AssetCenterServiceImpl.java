package com.aid.asset.center.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidEpisodeEditor;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidEpisodeEditorService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.asset.center.dto.AssetCenterCategoryTreeRequest;
import com.aid.asset.center.dto.AssetCenterDetailRequest;
import com.aid.asset.center.dto.AssetCenterListRequest;
import com.aid.asset.center.service.IAssetCenterService;
import com.aid.asset.center.vo.AssetCenterCategoryVO;
import com.aid.asset.center.vo.AssetCenterDetailVO;
import com.aid.asset.center.vo.AssetCenterEpisodeVO;
import com.aid.asset.center.vo.AssetCenterItemVO;
import com.aid.asset.center.vo.AssetCenterProjectVO;
import com.aid.common.exception.ServiceException;
import com.aid.enums.AssetCenterCategoryEnum;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 资产中心业务 Service 实现。
 * 性能要点：分类树批量查剧集避免 N+1；个人资产列表单分类走 DB 分页、多分类 scatter-gather
 * 各类仅取前 (from+pageSize) 条再内存归并切片；明细按 id+userId 单条命中。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AssetCenterServiceImpl implements IAssetCenterService {

    /** 删除标志：存在（各资产表「存在」统一为 '0'） */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 项目类型：电影 */
    private static final String PROJECT_TYPE_MOVIE = "movie";
    /** 电影模式的虚拟剧集 ID */
    private static final Long MOVIE_EPISODE_ID = 0L;
    /** 电影模式的虚拟剧集名称 */
    private static final String MOVIE_EPISODE_NAME = "全剧集";

    /** 主资产类型：角色 */
    private static final String ASSET_TYPE_CHARACTER = "character";
    /** 主资产类型：场景 */
    private static final String ASSET_TYPE_SCENE = "scene";
    /** 主资产类型：道具 */
    private static final String ASSET_TYPE_PROP = "prop";

    /** 分镜脚本图的 genType 集合 */
    private static final List<String> GEN_IMAGE_TYPES = List.of("image", "grid");
    /** 分镜视频的 genType 集合（compose=配音合成视频、upload_video=用户上传，均归视频大类） */
    private static final List<String> GEN_VIDEO_TYPES = List.of("i2v", "multi", "edge", "upload_video", "compose");
    /** gen_record 成功状态 */
    private static final Integer GEN_STATUS_SUCCESS = 1;
    /** 配音业务成功状态 */
    private static final String AUDIO_STATUS_SUCCEEDED = "SUCCEEDED";

    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 列表默认分页大小 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 列表最大分页大小 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 多分类混合模式：单类最大候选扫描窗口（防御深翻页时单类拉取过多） */
    private static final int MIX_MAX_WINDOW = 2000;
    /** 主资产 ID 反查上限（防御 IN 列表过大） */
    private static final int MAIN_ID_LIMIT = 2000;
    /** 分类树项目层默认分页大小 */
    private static final int TREE_DEFAULT_PAGE_SIZE = 10;
    /** 分类树项目层最大分页大小 */
    private static final int TREE_MAX_PAGE_SIZE = 50;

    @Resource
    private IAidComicProjectService aidComicProjectService;
    @Resource
    private IAidComicEpisodeService aidComicEpisodeService;
    @Resource
    private IAidComicScriptService aidComicScriptService;
    @Resource
    private IAidRolePropSceneService aidRolePropSceneService;
    @Resource
    private IAidRolePropSceneFormService aidRolePropSceneFormService;
    @Resource
    private IAidRolePropSceneFormImageService aidRolePropSceneFormImageService;
    @Resource
    private IAidStoryboardService aidStoryboardService;
    @Resource
    private IAidGenRecordService aidGenRecordService;
    @Resource
    private IAidAudioRecordService aidAudioRecordService;
    @Resource
    private IAidEpisodeEditorService aidEpisodeEditorService;
    @Override
    public Map<String, Object> categoryTree(AssetCenterCategoryTreeRequest request, Long userId) {
        // 空请求体兜底
        if (Objects.isNull(request)) {
            request = new AssetCenterCategoryTreeRequest();
        }
        int pageNum = Objects.isNull(request.getPageNum()) || request.getPageNum() < 1
                ? DEFAULT_PAGE_NUM : request.getPageNum();
        int pageSize = Objects.isNull(request.getPageSize()) || request.getPageSize() < 1
                ? TREE_DEFAULT_PAGE_SIZE : Math.min(request.getPageSize(), TREE_MAX_PAGE_SIZE);

        // 仅查项目层必要字段（减少列返回）
        LambdaQueryWrapper<AidComicProject> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidComicProject::getId, AidComicProject::getProjectName, AidComicProject::getProjectType);
        wrapper.eq(AidComicProject::getUserId, userId);
        wrapper.eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL);
        String keyword = Objects.isNull(request.getKeyword()) ? null : request.getKeyword().trim();
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(AidComicProject::getProjectName, keyword);
        }
        wrapper.orderByDesc(AidComicProject::getCreateTime);

        Page<AidComicProject> page = aidComicProjectService.page(new Page<>(pageNum, pageSize), wrapper);
        List<AidComicProject> projects = page.getRecords();

        // 固定 15 个分类节点（只读清单，所有剧集复用同一引用）
        List<AssetCenterCategoryVO> fixedCategories = buildFixedCategories();

        // 批量查本页所有「剧集型」项目的剧集，按 projectId 分组，杜绝 N+1
        Map<Long, List<AidComicEpisode>> episodeMap = batchLoadEpisodes(projects, userId);

        List<AssetCenterProjectVO> projectList = new ArrayList<>();
        for (AidComicProject project : projects) {
            List<AssetCenterEpisodeVO> episodes = new ArrayList<>();
            if (Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType())) {
                // 电影：虚拟「全剧集(0)」节点
                episodes.add(AssetCenterEpisodeVO.builder()
                        .episodeId(MOVIE_EPISODE_ID)
                        .episodeName(MOVIE_EPISODE_NAME)
                        .categories(fixedCategories)
                        .build());
            } else {
                List<AidComicEpisode> eps = episodeMap.getOrDefault(project.getId(), new ArrayList<>());
                for (AidComicEpisode ep : eps) {
                    episodes.add(AssetCenterEpisodeVO.builder()
                            .episodeId(ep.getId())
                            .episodeName(ep.getComicTitle())
                            .categories(fixedCategories)
                            .build());
                }
            }
            projectList.add(AssetCenterProjectVO.builder()
                    .projectId(project.getId())
                    .projectName(project.getProjectName())
                    .projectType(project.getProjectType())
                    .episodes(episodes)
                    .build());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("total", page.getTotal());
        data.put("pageNum", page.getCurrent());
        data.put("pageSize", page.getSize());
        data.put("list", projectList);
        return data;
    }

    /**
     * 构建固定的 15 个分类节点。
     */
    private List<AssetCenterCategoryVO> buildFixedCategories() {
        List<AssetCenterCategoryVO> list = new ArrayList<>();
        for (AssetCenterCategoryEnum item : AssetCenterCategoryEnum.values()) {
            list.add(new AssetCenterCategoryVO(item.getCode(), item.getName()));
        }
        return list;
    }

    /**
     * 批量加载本页「剧集型」项目的剧集，一次 IN 查询后按 projectId 分组（避免逐项目查询的 N+1）。
     */
    private Map<Long, List<AidComicEpisode>> batchLoadEpisodes(List<AidComicProject> projects, Long userId) {
        Map<Long, List<AidComicEpisode>> map = new HashMap<>();
        // 收集剧集型项目ID
        List<Long> seriesProjectIds = new ArrayList<>();
        for (AidComicProject p : projects) {
            if (!Objects.equals(PROJECT_TYPE_MOVIE, p.getProjectType())) {
                seriesProjectIds.add(p.getId());
            }
        }
        if (CollectionUtil.isEmpty(seriesProjectIds)) {
            return map;
        }
        LambdaQueryWrapper<AidComicEpisode> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidComicEpisode::getId, AidComicEpisode::getComicTitle,
                AidComicEpisode::getEpisodeNo, AidComicEpisode::getProjectId);
        wrapper.in(AidComicEpisode::getProjectId, seriesProjectIds);
        wrapper.eq(AidComicEpisode::getUserId, userId);
        wrapper.eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.orderByAsc(AidComicEpisode::getEpisodeNo);
        List<AidComicEpisode> all = aidComicEpisodeService.list(wrapper);
        for (AidComicEpisode ep : all) {
            map.computeIfAbsent(ep.getProjectId(), k -> new ArrayList<>()).add(ep);
        }
        return map;
    }
    @Override
    public Map<String, Object> listAssets(AssetCenterListRequest request, Long userId) {
        if (Objects.isNull(request)) {
            request = new AssetCenterListRequest();
        }
        int pageNum = Objects.isNull(request.getPageNum()) || request.getPageNum() < 1
                ? DEFAULT_PAGE_NUM : request.getPageNum();
        int pageSize = Objects.isNull(request.getPageSize()) || request.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(request.getPageSize(), MAX_PAGE_SIZE);
        int from = (pageNum - 1) * pageSize;

        Long projectId = request.getProjectId();
        Long episodeId = request.getEpisodeId();
        String categoryCode = Objects.isNull(request.getCategoryCode()) ? null : request.getCategoryCode().trim();

        Map<String, Object> data = new HashMap<>();
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);

        if (StrUtil.isNotBlank(categoryCode)) {
            // 单分类：直接走 DB 分页（count + LIMIT/OFFSET），最常见、最高效
            if (!AssetCenterCategoryEnum.isValid(categoryCode)) {
                log.error("资产中心列表-分类编码非法: userId={}, categoryCode={}", userId, categoryCode);
                throw new ServiceException("分类不支持");
            }
            // 主资产ID缓存：同一请求内 character/scene/prop 主ID只反查一次，避免 count/query 重复子查询
            Map<String, List<Long>> mainIdCache = new HashMap<>();
            long total = countByCategory(categoryCode, projectId, episodeId, userId, mainIdCache);
            List<AssetCenterItemVO> list = (from >= total)
                    ? new ArrayList<>()
                    : queryByCategory(categoryCode, projectId, episodeId, userId, from, pageSize, mainIdCache);
            data.put("total", total);
            data.put("list", list);
            return data;
        }

        // 多分类混合：scatter-gather。各类只取前 (from+pageSize) 条候选（offset=0，不深扫），内存归并切片。
        int window = Math.min(from + pageSize, MIX_MAX_WINDOW);
        long total = 0L;
        List<AssetCenterItemVO> candidates = new ArrayList<>();
        // 主资产ID缓存：3 种主类型在多个分类间复用，避免重复反查
        Map<String, List<Long>> mainIdCache = new HashMap<>();
        for (String code : AssetCenterCategoryEnum.codes()) {
            long cnt = countByCategory(code, projectId, episodeId, userId, mainIdCache);
            total += cnt;
            // 该分类无数据则跳过候选查询，省一次 SELECT
            if (cnt > 0) {
                candidates.addAll(queryByCategory(code, projectId, episodeId, userId, 0, window, mainIdCache));
            }
        }
        // 统一按创建时间倒序，时间相同按 id 倒序
        candidates.sort(Comparator
                .comparing(AssetCenterItemVO::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AssetCenterItemVO::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());

        List<AssetCenterItemVO> list;
        if (from >= candidates.size()) {
            list = new ArrayList<>();
        } else {
            int to = Math.min(from + pageSize, candidates.size());
            list = new ArrayList<>(candidates.subList(from, to));
        }
        data.put("total", total);
        data.put("list", list);
        return data;
    }
    /**
     * 统计某分类在指定范围内的数量。
     */
    private long countByCategory(String code, Long projectId, Long episodeId, Long userId,
                                 Map<String, List<Long>> mainIdCache) {
        AssetCenterCategoryEnum cat = AssetCenterCategoryEnum.getByCode(code);
        if (Objects.isNull(cat)) {
            return 0L;
        }
        switch (cat) {
            case SCRIPT:
                return countScript(projectId, episodeId, userId);
            case ROLE:
                return countRps(ASSET_TYPE_CHARACTER, projectId, episodeId, userId);
            case SCENE:
                return countRps(ASSET_TYPE_SCENE, projectId, episodeId, userId);
            case PROP:
                return countRps(ASSET_TYPE_PROP, projectId, episodeId, userId);
            case ROLE_SETTING:
                return countForm(ASSET_TYPE_CHARACTER, projectId, episodeId, userId, mainIdCache);
            case SCENE_SETTING:
                return countForm(ASSET_TYPE_SCENE, projectId, episodeId, userId, mainIdCache);
            case PROP_SETTING:
                return countForm(ASSET_TYPE_PROP, projectId, episodeId, userId, mainIdCache);
            case ROLE_IMAGE:
                return countFormImage(ASSET_TYPE_CHARACTER, projectId, episodeId, userId, mainIdCache);
            case SCENE_IMAGE:
                return countFormImage(ASSET_TYPE_SCENE, projectId, episodeId, userId, mainIdCache);
            case PROP_IMAGE:
                return countFormImage(ASSET_TYPE_PROP, projectId, episodeId, userId, mainIdCache);
            case STORYBOARD_SCRIPT:
                return countStoryboard(projectId, episodeId, userId);
            case STORYBOARD_IMAGE:
                return countGen(GEN_IMAGE_TYPES, projectId, episodeId, userId);
            case STORYBOARD_VIDEO:
                return countGen(GEN_VIDEO_TYPES, projectId, episodeId, userId);
            case DUBBING:
                return countAudio(projectId, episodeId, userId);
            case PREVIEW_VIDEO:
                return countEditor(projectId, episodeId, userId);
            default:
                return 0L;
        }
    }

    /**
     * 按分类分页取数（offset/limit），返回精简列表项。
     */
    private List<AssetCenterItemVO> queryByCategory(String code, Long projectId, Long episodeId,
                                                    Long userId, int offset, int limit,
                                                    Map<String, List<Long>> mainIdCache) {
        AssetCenterCategoryEnum cat = AssetCenterCategoryEnum.getByCode(code);
        if (Objects.isNull(cat)) {
            return new ArrayList<>();
        }
        switch (cat) {
            case SCRIPT:
                return queryScript(cat, projectId, episodeId, userId, offset, limit);
            case ROLE:
                return queryRps(cat, ASSET_TYPE_CHARACTER, projectId, episodeId, userId, offset, limit);
            case SCENE:
                return queryRps(cat, ASSET_TYPE_SCENE, projectId, episodeId, userId, offset, limit);
            case PROP:
                return queryRps(cat, ASSET_TYPE_PROP, projectId, episodeId, userId, offset, limit);
            case ROLE_SETTING:
                return queryForm(cat, ASSET_TYPE_CHARACTER, projectId, episodeId, userId, offset, limit, mainIdCache);
            case SCENE_SETTING:
                return queryForm(cat, ASSET_TYPE_SCENE, projectId, episodeId, userId, offset, limit, mainIdCache);
            case PROP_SETTING:
                return queryForm(cat, ASSET_TYPE_PROP, projectId, episodeId, userId, offset, limit, mainIdCache);
            case ROLE_IMAGE:
                return queryFormImage(cat, ASSET_TYPE_CHARACTER, projectId, episodeId, userId, offset, limit, mainIdCache);
            case SCENE_IMAGE:
                return queryFormImage(cat, ASSET_TYPE_SCENE, projectId, episodeId, userId, offset, limit, mainIdCache);
            case PROP_IMAGE:
                return queryFormImage(cat, ASSET_TYPE_PROP, projectId, episodeId, userId, offset, limit, mainIdCache);
            case STORYBOARD_SCRIPT:
                return queryStoryboard(cat, projectId, episodeId, userId, offset, limit);
            case STORYBOARD_IMAGE:
                return queryGen(cat, GEN_IMAGE_TYPES, projectId, episodeId, userId, offset, limit);
            case STORYBOARD_VIDEO:
                return queryGen(cat, GEN_VIDEO_TYPES, projectId, episodeId, userId, offset, limit);
            case DUBBING:
                return queryAudio(cat, projectId, episodeId, userId, offset, limit);
            case PREVIEW_VIDEO:
                return queryEditor(cat, projectId, episodeId, userId, offset, limit);
            default:
                return new ArrayList<>();
        }
    }

    /**
     * 拼接 LIMIT/OFFSET（值均为整型，无注入风险）。
     */
    private String limitOffset(int offset, int limit) {
        return "LIMIT " + limit + " OFFSET " + offset;
    }

    /**
     * 反查指定类型主资产 ID 列表（角色/场景/道具），供「设定 / 设定图」按类型过滤。
     */
    private List<Long> mainAssetIds(String assetType, Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidRolePropScene::getId);
        wrapper.eq(AidRolePropScene::getUserId, userId);
        wrapper.eq(AidRolePropScene::getAssetType, assetType);
        wrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(Objects.nonNull(projectId), AidRolePropScene::getProjectId, projectId);
        // 按集过滤时项目级资产（剧集角色/复用资产，episodeId=0）恒可见
        wrapper.in(Objects.nonNull(episodeId) && episodeId > 0L,
                AidRolePropScene::getEpisodeId, 0L, episodeId);
        wrapper.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropScene::getEpisodeId, episodeId);
        // 稳定排序：超过窗口时保证返回行确定（无 ORDER BY 的 LIMIT 返回行不确定，count/分页会抖动）
        wrapper.orderByAsc(AidRolePropScene::getId);
        wrapper.last("LIMIT " + MAIN_ID_LIMIT);
        List<AidRolePropScene> list = aidRolePropSceneService.list(wrapper);
        List<Long> ids = new ArrayList<>();
        for (AidRolePropScene r : list) {
            ids.add(r.getId());
        }
        return ids;
    }

    /**
     * 解析主资产 ID 列表，单次请求内按 assetType 缓存（projectId/episodeId/userId 在一次请求中固定）。
     */
    private List<Long> resolveMainIds(String assetType, Long projectId, Long episodeId, Long userId,
                                      Map<String, List<Long>> cache) {
        if (Objects.isNull(cache)) {
            return mainAssetIds(assetType, projectId, episodeId, userId);
        }
        return cache.computeIfAbsent(assetType, k -> mainAssetIds(assetType, projectId, episodeId, userId));
    }

    // ---------- 剧本 ----------
    private long countScript(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidComicScript> w = Wrappers.lambdaQuery();
        w.eq(AidComicScript::getUserId, userId);
        w.eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidComicScript::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidComicScript::getEpisodeId, episodeId);
        return aidComicScriptService.count(w);
    }

    private List<AssetCenterItemVO> queryScript(AssetCenterCategoryEnum cat, Long projectId, Long episodeId,
                                                Long userId, int offset, int limit) {
        LambdaQueryWrapper<AidComicScript> w = Wrappers.lambdaQuery();
        w.select(AidComicScript::getId, AidComicScript::getComicVersion, AidComicScript::getProjectId,
                AidComicScript::getEpisodeId, AidComicScript::getCreateTime);
        w.eq(AidComicScript::getUserId, userId);
        w.eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidComicScript::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidComicScript::getEpisodeId, episodeId);
        w.orderByDesc(AidComicScript::getCreateTime).orderByDesc(AidComicScript::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidComicScript s : aidComicScriptService.list(w)) {
            String name = Objects.isNull(s.getComicVersion()) ? "剧本" : "剧本 v" + s.getComicVersion();
            result.add(item(cat, s.getId(), name, null, s.getProjectId(), s.getEpisodeId(), s.getCreateTime()));
        }
        return result;
    }

    // ---------- 角色/场景/道具（主表） ----------
    private long countRps(String assetType, Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidRolePropScene> w = Wrappers.lambdaQuery();
        w.eq(AidRolePropScene::getUserId, userId);
        w.eq(AidRolePropScene::getAssetType, assetType);
        w.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidRolePropScene::getProjectId, projectId);
        // 按集过滤时项目级资产（剧集角色/复用资产，episodeId=0）恒可见
        w.in(Objects.nonNull(episodeId) && episodeId > 0L, AidRolePropScene::getEpisodeId, 0L, episodeId);
        w.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropScene::getEpisodeId, episodeId);
        return aidRolePropSceneService.count(w);
    }

    private List<AssetCenterItemVO> queryRps(AssetCenterCategoryEnum cat, String assetType, Long projectId,
                                             Long episodeId, Long userId, int offset, int limit) {
        LambdaQueryWrapper<AidRolePropScene> w = Wrappers.lambdaQuery();
        w.select(AidRolePropScene::getId, AidRolePropScene::getName, AidRolePropScene::getProjectId,
                AidRolePropScene::getEpisodeId, AidRolePropScene::getCreateTime);
        w.eq(AidRolePropScene::getUserId, userId);
        w.eq(AidRolePropScene::getAssetType, assetType);
        w.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidRolePropScene::getProjectId, projectId);
        // 按集过滤时项目级资产（剧集角色/复用资产，episodeId=0）恒可见
        w.in(Objects.nonNull(episodeId) && episodeId > 0L, AidRolePropScene::getEpisodeId, 0L, episodeId);
        w.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropScene::getEpisodeId, episodeId);
        w.orderByDesc(AidRolePropScene::getCreateTime).orderByDesc(AidRolePropScene::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidRolePropScene r : aidRolePropSceneService.list(w)) {
            result.add(item(cat, r.getId(), r.getName(), null, r.getProjectId(), r.getEpisodeId(), r.getCreateTime()));
        }
        return result;
    }

    // ---------- 角色/场景/道具 设定（form） ----------
    private long countForm(String mainAssetType, Long projectId, Long episodeId, Long userId,
                           Map<String, List<Long>> mainIdCache) {
        List<Long> ids = resolveMainIds(mainAssetType, projectId, episodeId, userId, mainIdCache);
        if (CollectionUtil.isEmpty(ids)) {
            return 0L;
        }
        LambdaQueryWrapper<AidRolePropSceneForm> w = Wrappers.lambdaQuery();
        w.eq(AidRolePropSceneForm::getUserId, userId);
        w.in(AidRolePropSceneForm::getAssetId, ids);
        w.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidRolePropSceneForm::getProjectId, projectId);
        // 与主资产层口径一致：按集过滤时项目级形态（episodeId=0）恒可见
        w.in(Objects.nonNull(episodeId) && episodeId > 0L, AidRolePropSceneForm::getEpisodeId, 0L, episodeId);
        w.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropSceneForm::getEpisodeId, episodeId);
        return aidRolePropSceneFormService.count(w);
    }

    private List<AssetCenterItemVO> queryForm(AssetCenterCategoryEnum cat, String mainAssetType, Long projectId,
                                              Long episodeId, Long userId, int offset, int limit,
                                              Map<String, List<Long>> mainIdCache) {
        List<Long> ids = resolveMainIds(mainAssetType, projectId, episodeId, userId, mainIdCache);
        if (CollectionUtil.isEmpty(ids)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<AidRolePropSceneForm> w = Wrappers.lambdaQuery();
        w.select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getName, AidRolePropSceneForm::getProjectId,
                AidRolePropSceneForm::getEpisodeId, AidRolePropSceneForm::getCreateTime);
        w.eq(AidRolePropSceneForm::getUserId, userId);
        w.in(AidRolePropSceneForm::getAssetId, ids);
        w.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidRolePropSceneForm::getProjectId, projectId);
        // 与主资产层口径一致：按集过滤时项目级形态（episodeId=0）恒可见
        w.in(Objects.nonNull(episodeId) && episodeId > 0L, AidRolePropSceneForm::getEpisodeId, 0L, episodeId);
        w.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropSceneForm::getEpisodeId, episodeId);
        w.orderByDesc(AidRolePropSceneForm::getCreateTime).orderByDesc(AidRolePropSceneForm::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidRolePropSceneForm f : aidRolePropSceneFormService.list(w)) {
            result.add(item(cat, f.getId(), f.getName(), null, f.getProjectId(), f.getEpisodeId(), f.getCreateTime()));
        }
        return result;
    }

    // ---------- 角色/场景/道具 设定图（form_image） ----------
    private long countFormImage(String mainAssetType, Long projectId, Long episodeId, Long userId,
                                Map<String, List<Long>> mainIdCache) {
        List<Long> ids = resolveMainIds(mainAssetType, projectId, episodeId, userId, mainIdCache);
        if (CollectionUtil.isEmpty(ids)) {
            return 0L;
        }
        LambdaQueryWrapper<AidRolePropSceneFormImage> w = Wrappers.lambdaQuery();
        w.eq(AidRolePropSceneFormImage::getUserId, userId);
        w.in(AidRolePropSceneFormImage::getAssetId, ids);
        w.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidRolePropSceneFormImage::getProjectId, projectId);
        // 与主资产层口径一致：按集过滤时项目级形态图（episodeId=0）恒可见
        w.in(Objects.nonNull(episodeId) && episodeId > 0L, AidRolePropSceneFormImage::getEpisodeId, 0L, episodeId);
        w.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropSceneFormImage::getEpisodeId, episodeId);
        return aidRolePropSceneFormImageService.count(w);
    }

    private List<AssetCenterItemVO> queryFormImage(AssetCenterCategoryEnum cat, String mainAssetType, Long projectId,
                                                   Long episodeId, Long userId, int offset, int limit,
                                                   Map<String, List<Long>> mainIdCache) {
        List<Long> ids = resolveMainIds(mainAssetType, projectId, episodeId, userId, mainIdCache);
        if (CollectionUtil.isEmpty(ids)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<AidRolePropSceneFormImage> w = Wrappers.lambdaQuery();
        w.select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getName,
                AidRolePropSceneFormImage::getImageUrl, AidRolePropSceneFormImage::getProjectId,
                AidRolePropSceneFormImage::getEpisodeId, AidRolePropSceneFormImage::getCreateTime);
        w.eq(AidRolePropSceneFormImage::getUserId, userId);
        w.in(AidRolePropSceneFormImage::getAssetId, ids);
        w.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidRolePropSceneFormImage::getProjectId, projectId);
        // 与主资产层口径一致：按集过滤时项目级形态图（episodeId=0）恒可见
        w.in(Objects.nonNull(episodeId) && episodeId > 0L, AidRolePropSceneFormImage::getEpisodeId, 0L, episodeId);
        w.eq(Objects.nonNull(episodeId) && episodeId <= 0L, AidRolePropSceneFormImage::getEpisodeId, episodeId);
        w.orderByDesc(AidRolePropSceneFormImage::getCreateTime).orderByDesc(AidRolePropSceneFormImage::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidRolePropSceneFormImage img : aidRolePropSceneFormImageService.list(w)) {
            result.add(item(cat, img.getId(), img.getName(), img.getImageUrl(),
                    img.getProjectId(), img.getEpisodeId(), img.getCreateTime()));
        }
        return result;
    }

    // ---------- 分镜脚本（storyboard） ----------
    private long countStoryboard(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidStoryboard> w = Wrappers.lambdaQuery();
        w.eq(AidStoryboard::getUserId, userId);
        w.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidStoryboard::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidStoryboard::getEpisodeId, episodeId);
        return aidStoryboardService.count(w);
    }

    private List<AssetCenterItemVO> queryStoryboard(AssetCenterCategoryEnum cat, Long projectId, Long episodeId,
                                                    Long userId, int offset, int limit) {
        LambdaQueryWrapper<AidStoryboard> w = Wrappers.lambdaQuery();
        w.select(AidStoryboard::getId, AidStoryboard::getTitle, AidStoryboard::getSortOrder,
                AidStoryboard::getProjectId, AidStoryboard::getEpisodeId, AidStoryboard::getCreateTime);
        w.eq(AidStoryboard::getUserId, userId);
        w.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidStoryboard::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidStoryboard::getEpisodeId, episodeId);
        w.orderByDesc(AidStoryboard::getCreateTime).orderByDesc(AidStoryboard::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidStoryboard s : aidStoryboardService.list(w)) {
            String name = StrUtil.isNotBlank(s.getTitle()) ? s.getTitle()
                    : ("分镜" + (Objects.isNull(s.getSortOrder()) ? "" : s.getSortOrder()));
            result.add(item(cat, s.getId(), name, null, s.getProjectId(), s.getEpisodeId(), s.getCreateTime()));
        }
        return result;
    }

    // ---------- 分镜脚本图 / 分镜视频（gen_record） ----------
    private long countGen(List<String> genTypes, Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidGenRecord> w = Wrappers.lambdaQuery();
        w.eq(AidGenRecord::getUserId, userId);
        w.in(AidGenRecord::getGenType, genTypes);
        w.eq(AidGenRecord::getStatus, GEN_STATUS_SUCCESS);
        w.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidGenRecord::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidGenRecord::getEpisodeId, episodeId);
        return aidGenRecordService.count(w);
    }

    private List<AssetCenterItemVO> queryGen(AssetCenterCategoryEnum cat, List<String> genTypes, Long projectId,
                                             Long episodeId, Long userId, int offset, int limit) {
        boolean isVideo = GEN_VIDEO_TYPES.equals(genTypes);
        LambdaQueryWrapper<AidGenRecord> w = Wrappers.lambdaQuery();
        w.select(AidGenRecord::getId, AidGenRecord::getFileUrl, AidGenRecord::getProjectId,
                AidGenRecord::getEpisodeId, AidGenRecord::getCreateTime);
        w.eq(AidGenRecord::getUserId, userId);
        w.in(AidGenRecord::getGenType, genTypes);
        w.eq(AidGenRecord::getStatus, GEN_STATUS_SUCCESS);
        w.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidGenRecord::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidGenRecord::getEpisodeId, episodeId);
        w.orderByDesc(AidGenRecord::getCreateTime).orderByDesc(AidGenRecord::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidGenRecord g : aidGenRecordService.list(w)) {
            String name = (isVideo ? "分镜视频#" : "分镜图#") + g.getId();
            result.add(item(cat, g.getId(), name, g.getFileUrl(), g.getProjectId(), g.getEpisodeId(), g.getCreateTime()));
        }
        return result;
    }

    // ---------- 配音（audio_record） ----------
    private long countAudio(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidAudioRecord> w = Wrappers.lambdaQuery();
        w.eq(AidAudioRecord::getUserId, userId);
        w.eq(AidAudioRecord::getStatus, AUDIO_STATUS_SUCCEEDED);
        w.eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidAudioRecord::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidAudioRecord::getEpisodeId, episodeId);
        return aidAudioRecordService.count(w);
    }

    private List<AssetCenterItemVO> queryAudio(AssetCenterCategoryEnum cat, Long projectId, Long episodeId,
                                               Long userId, int offset, int limit) {
        LambdaQueryWrapper<AidAudioRecord> w = Wrappers.lambdaQuery();
        w.select(AidAudioRecord::getId, AidAudioRecord::getAudioUrl, AidAudioRecord::getProjectId,
                AidAudioRecord::getEpisodeId, AidAudioRecord::getCreateTime);
        w.eq(AidAudioRecord::getUserId, userId);
        w.eq(AidAudioRecord::getStatus, AUDIO_STATUS_SUCCEEDED);
        w.eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(Objects.nonNull(projectId), AidAudioRecord::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidAudioRecord::getEpisodeId, episodeId);
        w.orderByDesc(AidAudioRecord::getCreateTime).orderByDesc(AidAudioRecord::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidAudioRecord a : aidAudioRecordService.list(w)) {
            result.add(item(cat, a.getId(), "配音#" + a.getId(), a.getAudioUrl(),
                    a.getProjectId(), a.getEpisodeId(), a.getCreateTime()));
        }
        return result;
    }

    // ---------- 预览视频（episode_editor） ----------
    private long countEditor(Long projectId, Long episodeId, Long userId) {
        LambdaQueryWrapper<AidEpisodeEditor> w = Wrappers.lambdaQuery();
        w.eq(AidEpisodeEditor::getUserId, userId);
        w.eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL);
        w.isNotNull(AidEpisodeEditor::getFinalVideoUrl);
        w.ne(AidEpisodeEditor::getFinalVideoUrl, "");
        w.eq(Objects.nonNull(projectId), AidEpisodeEditor::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidEpisodeEditor::getEpisodeId, episodeId);
        return aidEpisodeEditorService.count(w);
    }

    private List<AssetCenterItemVO> queryEditor(AssetCenterCategoryEnum cat, Long projectId, Long episodeId,
                                                Long userId, int offset, int limit) {
        LambdaQueryWrapper<AidEpisodeEditor> w = Wrappers.lambdaQuery();
        w.select(AidEpisodeEditor::getId, AidEpisodeEditor::getFinalVideoUrl, AidEpisodeEditor::getProjectId,
                AidEpisodeEditor::getEpisodeId, AidEpisodeEditor::getCreateTime);
        w.eq(AidEpisodeEditor::getUserId, userId);
        w.eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL);
        w.isNotNull(AidEpisodeEditor::getFinalVideoUrl);
        w.ne(AidEpisodeEditor::getFinalVideoUrl, "");
        w.eq(Objects.nonNull(projectId), AidEpisodeEditor::getProjectId, projectId);
        w.eq(Objects.nonNull(episodeId), AidEpisodeEditor::getEpisodeId, episodeId);
        w.orderByDesc(AidEpisodeEditor::getCreateTime).orderByDesc(AidEpisodeEditor::getId);
        w.last(limitOffset(offset, limit));
        List<AssetCenterItemVO> result = new ArrayList<>();
        for (AidEpisodeEditor e : aidEpisodeEditorService.list(w)) {
            result.add(item(cat, e.getId(), "预览视频", e.getFinalVideoUrl(),
                    e.getProjectId(), e.getEpisodeId(), e.getCreateTime()));
        }
        return result;
    }

    /**
     * 统一组装列表项 VO。
     */
    private AssetCenterItemVO item(AssetCenterCategoryEnum cat, Long id, String name, String mediaUrl,
                                   Long projectId, Long episodeId, Date createTime) {
        return AssetCenterItemVO.builder()
                .id(id)
                .categoryCode(cat.getCode())
                .categoryName(cat.getName())
                .name(name)
                .mediaUrl(mediaUrl)
                .projectId(projectId)
                .episodeId(episodeId)
                .createTime(createTime)
                .build();
    }
    @Override
    public AssetCenterDetailVO detail(AssetCenterDetailRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId()) || StrUtil.isBlank(request.getCategoryCode())) {
            log.error("资产中心明细-参数缺失: userId={}, request={}", userId, request);
            throw new ServiceException("参数错误");
        }
        String code = request.getCategoryCode().trim();
        AssetCenterCategoryEnum cat = AssetCenterCategoryEnum.getByCode(code);
        if (Objects.isNull(cat)) {
            log.error("资产中心明细-分类编码非法: userId={}, categoryCode={}", userId, code);
            throw new ServiceException("分类不支持");
        }
        Long id = request.getId();
        switch (cat) {
            case SCRIPT:
                return detailScript(cat, id, userId);
            case ROLE:
            case SCENE:
            case PROP:
                return detailRps(cat, id, userId);
            case ROLE_SETTING:
            case SCENE_SETTING:
            case PROP_SETTING:
                return detailForm(cat, id, userId);
            case ROLE_IMAGE:
            case SCENE_IMAGE:
            case PROP_IMAGE:
                return detailFormImage(cat, id, userId);
            case STORYBOARD_SCRIPT:
                return detailStoryboard(cat, id, userId);
            case STORYBOARD_IMAGE:
                return detailGen(cat, GEN_IMAGE_TYPES, false, id, userId);
            case STORYBOARD_VIDEO:
                return detailGen(cat, GEN_VIDEO_TYPES, true, id, userId);
            case DUBBING:
                return detailAudio(cat, id, userId);
            case PREVIEW_VIDEO:
                return detailEditor(cat, id, userId);
            default:
                throw new ServiceException("分类不支持");
        }
    }

    private AssetCenterDetailVO detailScript(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidComicScript s = aidComicScriptService.getOne(Wrappers.<AidComicScript>lambdaQuery()
                .eq(AidComicScript::getId, id)
                .eq(AidComicScript::getUserId, userId)
                .eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(s, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("originalText", s.getOriginalText());
        content.put("simplifiedText", s.getSimplifiedText());
        content.put("comicVersion", s.getComicVersion());
        content.put("status", s.getStatus());
        content.put("isExtracted", s.getIsExtracted());
        String name = Objects.isNull(s.getComicVersion()) ? "剧本" : "剧本 v" + s.getComicVersion();
        return baseDetail(cat, s.getId(), name, s.getProjectId(), s.getEpisodeId(), s.getCreateTime())
                .content(content).build();
    }

    private AssetCenterDetailVO detailRps(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidRolePropScene r = aidRolePropSceneService.getOne(Wrappers.<AidRolePropScene>lambdaQuery()
                .eq(AidRolePropScene::getId, id)
                .eq(AidRolePropScene::getUserId, userId)
                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(r, cat, id, userId);
        // 校验 id 真实类型与请求分类一致，避免传错分类把场景按角色返回
        String expectType = expectedRpsType(cat);
        if (!Objects.equals(expectType, r.getAssetType())) {
            log.error("资产中心明细-类型不匹配: userId={}, category={}, realType={}, id={}",
                    userId, cat.getCode(), r.getAssetType(), id);
            throw new ServiceException("数据不存在");
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("assetType", r.getAssetType());
        content.put("aliasesName", r.getAliasesName());
        content.put("introduction", r.getIntroduction());
        content.put("gender", r.getGender());
        content.put("ageRange", r.getAgeRange());
        content.put("roleLevel", r.getRoleLevel());
        content.put("profileData", r.getProfileData());
        content.put("expectedAppearances", r.getExpectedAppearances());
        content.put("summary", r.getSummary());
        content.put("availableSlots", r.getAvailableSlots());
        content.put("hasCrowd", r.getHasCrowd());
        content.put("crowdDescription", r.getCrowdDescription());
        content.put("firstSceneCode", r.getFirstSceneCode());
        return baseDetail(cat, r.getId(), r.getName(), r.getProjectId(), r.getEpisodeId(), r.getCreateTime())
                .content(content).build();
    }

    private AssetCenterDetailVO detailForm(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidRolePropSceneForm f = aidRolePropSceneFormService.getOne(Wrappers.<AidRolePropSceneForm>lambdaQuery()
                .eq(AidRolePropSceneForm::getId, id)
                .eq(AidRolePropSceneForm::getUserId, userId)
                .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(f, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("assetId", f.getAssetId());
        content.put("changeReason", f.getChangeReason());
        content.put("promptText", f.getPromptText());
        content.put("visualDescStatus", f.getVisualDescStatus());
        content.put("createSource", f.getCreateSource());
        return baseDetail(cat, f.getId(), f.getName(), f.getProjectId(), f.getEpisodeId(), f.getCreateTime())
                .content(content).build();
    }

    private AssetCenterDetailVO detailFormImage(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidRolePropSceneFormImage img = aidRolePropSceneFormImageService.getOne(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .eq(AidRolePropSceneFormImage::getId, id)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"));
        ensureExist(img, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("formId", img.getFormId());
        content.put("assetId", img.getAssetId());
        content.put("sourceType", img.getSourceType());
        content.put("promptSnapshot", img.getPromptSnapshot());
        content.put("referenceImages", img.getReferenceImages());
        content.put("isUse", img.getIsUse());
        content.put("imageStatus", img.getImageStatus());
        return baseDetail(cat, img.getId(), img.getName(), img.getProjectId(), img.getEpisodeId(), img.getCreateTime())
                .imageUrl(img.getImageUrl())
                .content(content).build();
    }

    private AssetCenterDetailVO detailStoryboard(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidStoryboard s = aidStoryboardService.getOne(Wrappers.<AidStoryboard>lambdaQuery()
                .eq(AidStoryboard::getId, id)
                .eq(AidStoryboard::getUserId, userId)
                .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(s, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("sortOrder", s.getSortOrder());
        content.put("storyScript", s.getStoryScript());
        content.put("dialogueText", s.getDialogueText());
        content.put("scriptParams", s.getScriptParams());
        content.put("imagePrompt", s.getImagePrompt());
        content.put("videoPrompt", s.getVideoPrompt());
        content.put("videoPromptImage", s.getVideoPromptImage());
        content.put("finalImageId", s.getFinalImageId());
        content.put("finalVideoId", s.getFinalVideoId());
        content.put("finalAudioId", s.getFinalAudioId());
        String name = StrUtil.isNotBlank(s.getTitle()) ? s.getTitle()
                : ("分镜" + (Objects.isNull(s.getSortOrder()) ? "" : s.getSortOrder()));
        return baseDetail(cat, s.getId(), name, s.getProjectId(), s.getEpisodeId(), s.getCreateTime())
                .content(content).build();
    }

    private AssetCenterDetailVO detailGen(AssetCenterCategoryEnum cat, List<String> genTypes, boolean isVideo,
                                          Long id, Long userId) {
        AidGenRecord g = aidGenRecordService.getOne(Wrappers.<AidGenRecord>lambdaQuery()
                .eq(AidGenRecord::getId, id)
                .eq(AidGenRecord::getUserId, userId)
                .in(AidGenRecord::getGenType, genTypes)
                .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(g, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("genType", g.getGenType());
        content.put("storyboardId", g.getStoryboardId());
        content.put("promptText", g.getPromptText());
        content.put("userInputText", g.getUserInputText());
        content.put("genParams", g.getGenParams());
        content.put("modelId", g.getModelId());
        content.put("videoDuration", g.getVideoDuration());
        content.put("costCredits", g.getCostCredits());
        content.put("isSelected", g.getIsSelected());
        content.put("status", g.getStatus());
        String name = (isVideo ? "分镜视频#" : "分镜图#") + g.getId();
        AssetCenterDetailVO.AssetCenterDetailVOBuilder builder =
                baseDetail(cat, g.getId(), name, g.getProjectId(), g.getEpisodeId(), g.getCreateTime()).content(content);
        // 图片落 imageUrl，视频落 videoUrl
        if (isVideo) {
            builder.videoUrl(g.getFileUrl());
        } else {
            builder.imageUrl(g.getFileUrl());
        }
        return builder.build();
    }

    private AssetCenterDetailVO detailAudio(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidAudioRecord a = aidAudioRecordService.getOne(Wrappers.<AidAudioRecord>lambdaQuery()
                .eq(AidAudioRecord::getId, id)
                .eq(AidAudioRecord::getUserId, userId)
                .eq(AidAudioRecord::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(a, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("storyboardId", a.getStoryboardId());
        content.put("audioSource", a.getAudioSource());
        content.put("ttsText", a.getTtsText());
        content.put("durationMs", a.getDurationMs());
        content.put("enableLipSync", a.getEnableLipSync());
        content.put("status", a.getStatus());
        return baseDetail(cat, a.getId(), "配音#" + a.getId(), a.getProjectId(), a.getEpisodeId(), a.getCreateTime())
                .audioUrl(a.getAudioUrl())
                .videoUrl(a.getSyncVideoUrl())
                .content(content).build();
    }

    private AssetCenterDetailVO detailEditor(AssetCenterCategoryEnum cat, Long id, Long userId) {
        AidEpisodeEditor e = aidEpisodeEditorService.getOne(Wrappers.<AidEpisodeEditor>lambdaQuery()
                .eq(AidEpisodeEditor::getId, id)
                .eq(AidEpisodeEditor::getUserId, userId)
                .eq(AidEpisodeEditor::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"));
        ensureExist(e, cat, id, userId);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("timelineJson", e.getTimelineJson());
        content.put("exportStatus", e.getExportStatus());
        content.put("exportProgress", e.getExportProgress());
        return baseDetail(cat, e.getId(), "预览视频", e.getProjectId(), e.getEpisodeId(), e.getCreateTime())
                .videoUrl(e.getFinalVideoUrl())
                .coverUrl(e.getCoverUrl())
                .content(content).build();
    }

    /**
     * 角色/场景/道具分类对应的主资产类型，用于明细类型一致性校验。
     */
    private String expectedRpsType(AssetCenterCategoryEnum cat) {
        switch (cat) {
            case ROLE:
                return ASSET_TYPE_CHARACTER;
            case SCENE:
                return ASSET_TYPE_SCENE;
            case PROP:
                return ASSET_TYPE_PROP;
            default:
                return null;
        }
    }

    /**
     * 实体不存在（或不属于当前用户）时统一抛错。
     */
    private void ensureExist(Object entity, AssetCenterCategoryEnum cat, Long id, Long userId) {
        if (Objects.isNull(entity)) {
            log.error("资产中心明细-不存在或越权: userId={}, category={}, id={}", userId, cat.getCode(), id);
            throw new ServiceException("数据不存在");
        }
    }

    /**
     * 组装明细 VO 头部公共字段。
     */
    private AssetCenterDetailVO.AssetCenterDetailVOBuilder baseDetail(AssetCenterCategoryEnum cat, Long id, String name,
                                                                     Long projectId, Long episodeId, Date createTime) {
        return AssetCenterDetailVO.builder()
                .id(id)
                .categoryCode(cat.getCode())
                .categoryName(cat.getName())
                .name(name)
                .projectId(projectId)
                .episodeId(episodeId)
                .createTime(createTime);
    }
}

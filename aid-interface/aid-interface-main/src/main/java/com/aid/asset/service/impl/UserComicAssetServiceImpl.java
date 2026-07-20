package com.aid.asset.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.transaction.annotation.Transactional;
import com.aid.aid.domain.AidUserComicAsset;
import com.aid.aid.domain.AidComicAsset;
import com.aid.aid.service.IAidUserComicAssetService;
import com.aid.aid.service.IAidComicAssetService;
import com.aid.asset.constants.UserAssetTypeConstants;
import com.aid.asset.dto.MergedAssetPageRequest;
import com.aid.asset.dto.UserComicAssetCreateRequest;
import com.aid.asset.dto.UserComicAssetDeleteRequest;
import com.aid.asset.dto.UserComicAssetDetailRequest;
import com.aid.asset.dto.UserComicAssetListRequest;
import com.aid.asset.dto.UserComicAssetUpdateRequest;
import com.aid.asset.service.IUserComicAssetService;
import com.aid.asset.vo.MergedAssetVO;
import com.aid.asset.vo.UserComicAssetTypeVO;
import com.aid.asset.vo.UserComicAssetVO;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.media.cleanup.IMediaOssCleanupService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * C端用户自定义参考资产业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserComicAssetServiceImpl implements IUserComicAssetService {

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 状态：正常 */
    private static final String STATUS_NORMAL = "0";
    /** 来源类型：用户创建 */
    private static final String SOURCE_TYPE_USER = "USER";
    /** 资产类型：风格（该类型提示词必填） */
    private static final String ASSET_TYPE_STYLE = "style";
    /** 资产名称最大长度 */
    private static final int ASSET_NAME_MAX_LENGTH = 100;
    /** 图片URL最大长度（对齐 aid_user_comic_asset.image_url VARCHAR(500)） */
    private static final int IMAGE_URL_MAX_LENGTH = 500;
    /** 备注最大长度（对齐 aid_user_comic_asset.remark VARCHAR(500)） */
    private static final int REMARK_MAX_LENGTH = 500;
    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;
    /** 默认分页大小 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 最大分页大小（防止大分页拖垮查询） */
    private static final int MAX_PAGE_SIZE = 100;

    /** C端允许的资产类型白名单（引用共享常量） */
    private static final Set<String> ALLOWED_ASSET_TYPES = UserAssetTypeConstants.ALLOWED_ASSET_TYPES;

    /** C端允许的资产类型字典（有序） */
    private static final List<UserComicAssetTypeVO> ALLOWED_TYPE_DICT;

    static {
        List<UserComicAssetTypeVO> list = new ArrayList<>();
        list.add(new UserComicAssetTypeVO("reference_character", "人物参考图", "用户上传的人物参考图，不是真正角色"));
        list.add(new UserComicAssetTypeVO("reference_scene", "场景参考图", "用户上传的场景参考图，不是真正场景"));
        list.add(new UserComicAssetTypeVO("reference_prop", "道具参考图", "用户上传的道具参考图，不是真正道具"));
        list.add(new UserComicAssetTypeVO("style", "风格", "用户自定义风格参考"));
        list.add(new UserComicAssetTypeVO("pose", "姿势", "用户自定义姿势参考"));
        list.add(new UserComicAssetTypeVO("expression", "表情", "用户自定义表情参考"));
        list.add(new UserComicAssetTypeVO("effect", "特效", "用户自定义特效参考"));
        list.add(new UserComicAssetTypeVO("file", "文件", "用户通用参考文件"));
        list.add(new UserComicAssetTypeVO("mood", "情绪神态", "用户自定义情绪或神态参考"));
        list.add(new UserComicAssetTypeVO("camera", "摄影参数", "用户自定义摄影参数"));
        ALLOWED_TYPE_DICT = Collections.unmodifiableList(list);
    }

    @Resource
    private IAidUserComicAssetService aidUserComicAssetService;

    /** 官方素材服务（aid_comic_asset），合并查询时只读取官方数据 */
    @Resource
    private IAidComicAssetService aidComicAssetService;

    /** OSS 文件清理服务：硬删参考资产前先删其 OSS 图片 */
    @Resource
    private IMediaOssCleanupService mediaOssCleanupService;

    /** 媒体URL统一解析器：本站校验 + 相对路径规范化 */
    @Resource
    private MediaUrlResolver mediaUrlResolver;

    @Override
    public Long createAsset(UserComicAssetCreateRequest request, Long userId) {
        if (Objects.isNull(request)) {
            log.error("C端参考资产创建失败-请求体为空: userId={}", userId);
            throw new ServiceException("参数错误");
        }
        String assetType = Objects.isNull(request.getAssetType()) ? null : request.getAssetType().trim();
        if (StrUtil.isBlank(assetType) || !ALLOWED_ASSET_TYPES.contains(assetType)) {
            log.error("C端参考资产创建失败-类型非法: userId={}, assetType={}", userId, assetType);
            throw new ServiceException("类型错误");
        }
        String assetName = Objects.isNull(request.getAssetName()) ? null : request.getAssetName().trim();
        if (StrUtil.isBlank(assetName)) {
            log.error("C端参考资产创建失败-名称为空: userId={}", userId);
            throw new ServiceException("名称不能为空");
        }
        if (assetName.length() > ASSET_NAME_MAX_LENGTH) {
            log.error("C端参考资产创建失败-名称过长: userId={}, length={}", userId, assetName.length());
            throw new ServiceException("名称过长");
        }
        // 风格资产必须填写提示词。
        if (Objects.equals(ASSET_TYPE_STYLE, assetType) && StrUtil.isBlank(request.getPromptText())) {
            log.error("C端参考资产创建失败-风格提示词为空: userId={}", userId);
            throw new ServiceException("提示词不能为空");
        }
        String imageUrl = normalizeImageUrl(request.getImageUrl());
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            if (!mediaUrlResolver.isSiteImageUrl(imageUrl)) {
                log.error("C端参考资产创建失败-图片URL非本站资源: userId={}, imageUrl={}", userId, request.getImageUrl());
                throw new ServiceException("图片格式有误");
            }
            // 统一剥域名入库，DB 只存相对路径
            imageUrl = mediaUrlResolver.toRelativePath(imageUrl);
        }
        if (StrUtil.isNotBlank(imageUrl) && imageUrl.length() > IMAGE_URL_MAX_LENGTH) {
            log.error("C端参考资产创建失败-图片URL过长: userId={}, length={}", userId, imageUrl.length());
            throw new ServiceException("图片地址过长");
        }
        if (Objects.nonNull(request.getRemark()) && request.getRemark().length() > REMARK_MAX_LENGTH) {
            log.error("C端参考资产创建失败-备注过长: userId={}, length={}", userId, request.getRemark().length());
            throw new ServiceException("备注过长");
        }

        AidUserComicAsset entity = new AidUserComicAsset();
        entity.setUserId(userId);
        entity.setAssetType(assetType);
        entity.setAssetName(assetName);
        entity.setPersonalityDesc(request.getPersonalityDesc());
        entity.setPromptText(request.getPromptText());
        entity.setImageUrl(StrUtil.isBlank(imageUrl) ? null : imageUrl);
        entity.setSourceType(SOURCE_TYPE_USER);
        entity.setStatus(STATUS_NORMAL);
        entity.setDelFlag(DEL_FLAG_NORMAL);
        entity.setRemark(request.getRemark());
        Date now = DateUtils.getNowDate();
        entity.setCreateTime(now);
        entity.setCreateBy(String.valueOf(userId));

        boolean ok = aidUserComicAssetService.save(entity);
        if (!ok || Objects.isNull(entity.getId())) {
            log.error("C端参考资产创建失败-入库异常: userId={}", userId);
            throw new ServiceException("保存失败，请重试");
        }
        return entity.getId();
    }

    @Override
    public Map<String, Object> listAsset(UserComicAssetListRequest request, Long userId) {
        if (Objects.isNull(request)) {
            request = new UserComicAssetListRequest();
        }
        String assetType = request.getAssetType();
        if (StrUtil.isNotBlank(assetType) && !ALLOWED_ASSET_TYPES.contains(assetType)) {
            log.error("C端参考资产列表-类型非法: userId={}, assetType={}", userId, assetType);
            throw new ServiceException("类型错误");
        }

        int pageNum = Objects.isNull(request.getPageNum()) || request.getPageNum() <= 0
                ? DEFAULT_PAGE_NUM : request.getPageNum();
        int pageSize = Objects.isNull(request.getPageSize()) || request.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE : request.getPageSize();
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        Page<AidUserComicAsset> page = new Page<>(pageNum, pageSize);

        // 注意：新增返回字段时请同步调整 .select(...)，避免列表字段缺失
        LambdaQueryWrapper<AidUserComicAsset> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidUserComicAsset::getId,
                AidUserComicAsset::getAssetType,
                AidUserComicAsset::getAssetName,
                AidUserComicAsset::getPersonalityDesc,
                AidUserComicAsset::getPromptText,
                AidUserComicAsset::getImageUrl,
                AidUserComicAsset::getSourceType,
                AidUserComicAsset::getSortOrder,
                AidUserComicAsset::getRemark,
                AidUserComicAsset::getCreateTime
        );
        wrapper.eq(AidUserComicAsset::getUserId, userId);
        wrapper.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.eq(AidUserComicAsset::getStatus, STATUS_NORMAL);
        if (StrUtil.isNotBlank(assetType)) {
            wrapper.eq(AidUserComicAsset::getAssetType, assetType);
        } else {
            // 仅允许C端白名单类型
            wrapper.in(AidUserComicAsset::getAssetType, ALLOWED_ASSET_TYPES);
        }
        String keyword = Objects.isNull(request.getKeyword()) ? null : request.getKeyword().trim();
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(AidUserComicAsset::getAssetName, keyword);
        }
        wrapper.orderByAsc(AidUserComicAsset::getSortOrder);
        wrapper.orderByDesc(AidUserComicAsset::getCreateTime);

        Page<AidUserComicAsset> result = aidUserComicAssetService.page(page, wrapper);

        List<UserComicAssetVO> list;
        if (CollectionUtil.isEmpty(result.getRecords())) {
            list = new ArrayList<>();
        } else {
            list = result.getRecords().stream().map(this::toListVO).toList();
        }

        Map<String, Object> data = new HashMap<>();
        data.put("total", result.getTotal());
        data.put("list", list);
        return data;
    }

    @Override
    public Map<String, Object> pageMergedAssets(MergedAssetPageRequest request, Long userId) {
        if (Objects.isNull(request)) {
            request = new MergedAssetPageRequest();
        }
        String assetType = Objects.isNull(request.getAssetType()) ? null : request.getAssetType().trim();
        if (StrUtil.isNotBlank(assetType) && !ALLOWED_ASSET_TYPES.contains(assetType)) {
            log.error("合并资产查询-类型非法: userId={}, assetType={}", userId, assetType);
            throw new ServiceException("类型错误");
        }
        String keyword = Objects.isNull(request.getKeyword()) ? null : request.getKeyword().trim();

        int pageNum = Objects.isNull(request.getPageNum()) || request.getPageNum() <= 0
                ? DEFAULT_PAGE_NUM : request.getPageNum();
        int pageSize = Objects.isNull(request.getPageSize()) || request.getPageSize() <= 0
                ? DEFAULT_PAGE_SIZE : Math.min(request.getPageSize(), MAX_PAGE_SIZE);
        int from = (pageNum - 1) * pageSize;

        long personalCount = aidUserComicAssetService.count(buildPersonalCountWrapper(userId, assetType, keyword));
        long officialCount = aidComicAssetService.count(buildOfficialCountWrapper(assetType, keyword));
        long total = personalCount + officialCount;

        List<MergedAssetVO> list = new ArrayList<>();
        if (from < personalCount) {
            // 当前页从个人资产段开始。
            int personalLimit = (int) Math.min(pageSize, personalCount - from);
            list.addAll(fetchPersonalSlice(userId, assetType, keyword, from, personalLimit));
            int remaining = pageSize - personalLimit;
            if (remaining > 0) {
                // 个人段不足填满本页，官方段从头补齐
                list.addAll(fetchOfficialSlice(assetType, keyword, 0, remaining));
            }
        } else {
            // 当前页完全落在官方资产段。
            int officialOffset = (int) (from - personalCount);
            list.addAll(fetchOfficialSlice(assetType, keyword, officialOffset, pageSize));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        data.put("list", list);
        return data;
    }

    /**
     * 个人资产统计条件（aid_user_comic_asset）。
     */
    private LambdaQueryWrapper<AidUserComicAsset> buildPersonalCountWrapper(Long userId, String assetType, String keyword) {
        LambdaQueryWrapper<AidUserComicAsset> w = Wrappers.lambdaQuery();
        w.eq(AidUserComicAsset::getUserId, userId);
        w.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        w.eq(AidUserComicAsset::getStatus, STATUS_NORMAL);
        if (StrUtil.isNotBlank(assetType)) {
            w.eq(AidUserComicAsset::getAssetType, assetType);
        } else {
            w.in(AidUserComicAsset::getAssetType, ALLOWED_ASSET_TYPES);
        }
        if (StrUtil.isNotBlank(keyword)) {
            w.like(AidUserComicAsset::getAssetName, keyword);
        }
        return w;
    }

    /**
     * 官方素材统计条件（aid_comic_asset）。
     */
    private LambdaQueryWrapper<AidComicAsset> buildOfficialCountWrapper(String assetType, String keyword) {
        LambdaQueryWrapper<AidComicAsset> w = Wrappers.lambdaQuery();
        w.eq(AidComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        if (StrUtil.isNotBlank(assetType)) {
            w.eq(AidComicAsset::getAssetType, assetType);
        } else {
            w.in(AidComicAsset::getAssetType, ALLOWED_ASSET_TYPES);
        }
        if (StrUtil.isNotBlank(keyword)) {
            w.like(AidComicAsset::getAssetName, keyword);
        }
        return w;
    }

    /**
     * 取个人资产切片（sourceFlag=custom）。
     */
    private List<MergedAssetVO> fetchPersonalSlice(Long userId, String assetType, String keyword, int offset, int limit) {
        LambdaQueryWrapper<AidUserComicAsset> w = buildPersonalCountWrapper(userId, assetType, keyword);
        w.select(AidUserComicAsset::getId, AidUserComicAsset::getAssetType, AidUserComicAsset::getAssetName,
                AidUserComicAsset::getPromptText, AidUserComicAsset::getImageUrl);
        w.orderByAsc(AidUserComicAsset::getSortOrder).orderByDesc(AidUserComicAsset::getCreateTime).orderByDesc(AidUserComicAsset::getId);
        w.last("LIMIT " + limit + " OFFSET " + offset);
        List<MergedAssetVO> result = new ArrayList<>();
        for (AidUserComicAsset a : aidUserComicAssetService.list(w)) {
            result.add(MergedAssetVO.builder()
                    .id(a.getId())
                    .sourceFlag("custom")
                    .assetType(a.getAssetType())
                    .assetName(a.getAssetName())
                    .promptText(a.getPromptText())
                    .imageUrl(a.getImageUrl())
                    .build());
        }
        return result;
    }

    /**
     * 取官方素材切片（sourceFlag=official）。
     */
    private List<MergedAssetVO> fetchOfficialSlice(String assetType, String keyword, int offset, int limit) {
        LambdaQueryWrapper<AidComicAsset> w = buildOfficialCountWrapper(assetType, keyword);
        w.select(AidComicAsset::getId, AidComicAsset::getAssetType, AidComicAsset::getAssetName,
                AidComicAsset::getPromptText, AidComicAsset::getImageUrl);
        w.orderByDesc(AidComicAsset::getCreateTime).orderByDesc(AidComicAsset::getId);
        w.last("LIMIT " + limit + " OFFSET " + offset);
        List<MergedAssetVO> result = new ArrayList<>();
        for (AidComicAsset a : aidComicAssetService.list(w)) {
            result.add(MergedAssetVO.builder()
                    .id(a.getId())
                    .sourceFlag("official")
                    .assetType(a.getAssetType())
                    .assetName(a.getAssetName())
                    .promptText(a.getPromptText())
                    .imageUrl(a.getImageUrl())
                    .build());
        }
        return result;
    }

    @Override
    public UserComicAssetVO detailAsset(UserComicAssetDetailRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId())) {
            log.error("C端参考资产详情-ID为空: userId={}", userId);
            throw new ServiceException("数据不存在");
        }
        // 注意：新增返回字段时请同步调整 .select(...)
        LambdaQueryWrapper<AidUserComicAsset> wrapper = Wrappers.lambdaQuery();
        wrapper.select(
                AidUserComicAsset::getId,
                AidUserComicAsset::getUserId,
                AidUserComicAsset::getAssetType,
                AidUserComicAsset::getAssetName,
                AidUserComicAsset::getPersonalityDesc,
                AidUserComicAsset::getPromptText,
                AidUserComicAsset::getImageUrl,
                AidUserComicAsset::getSourceType,
                AidUserComicAsset::getSortOrder,
                AidUserComicAsset::getStatus,
                AidUserComicAsset::getRemark,
                AidUserComicAsset::getCreateTime
        );
        wrapper.eq(AidUserComicAsset::getId, request.getId());
        wrapper.eq(AidUserComicAsset::getUserId, userId);
        wrapper.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        // 仅允许查看正常状态资产（后台停用后对C端不可见）
        wrapper.eq(AidUserComicAsset::getStatus, STATUS_NORMAL);
        wrapper.last("LIMIT 1");
        AidUserComicAsset entity = aidUserComicAssetService.getOne(wrapper);
        if (Objects.isNull(entity)) {
            log.error("C端参考资产详情-不存在: userId={}, id={}", userId, request.getId());
            throw new ServiceException("数据不存在");
        }
        UserComicAssetVO vo = new UserComicAssetVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    @Override
    public void updateAsset(UserComicAssetUpdateRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId())) {
            log.error("C端参考资产修改-ID为空: userId={}", userId);
            throw new ServiceException("数据不存在");
        }

        LambdaQueryWrapper<AidUserComicAsset> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.select(AidUserComicAsset::getId);
        queryWrapper.eq(AidUserComicAsset::getId, request.getId());
        queryWrapper.eq(AidUserComicAsset::getUserId, userId);
        queryWrapper.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        queryWrapper.eq(AidUserComicAsset::getStatus, STATUS_NORMAL);
        queryWrapper.last("LIMIT 1");
        AidUserComicAsset existed = aidUserComicAssetService.getOne(queryWrapper);
        if (Objects.isNull(existed)) {
            log.error("C端参考资产修改-不存在: userId={}, id={}", userId, request.getId());
            throw new ServiceException("数据不存在");
        }

        String trimmedName = null;
        if (Objects.nonNull(request.getAssetName())) {
            trimmedName = request.getAssetName().trim();
            if (StrUtil.isBlank(trimmedName)) {
                log.error("C端参考资产修改-名称为空: userId={}, id={}", userId, request.getId());
                throw new ServiceException("名称不能为空");
            }
            if (trimmedName.length() > ASSET_NAME_MAX_LENGTH) {
                log.error("C端参考资产修改-名称过长: userId={}, id={}", userId, request.getId());
                throw new ServiceException("名称过长");
            }
        }
        String normalizedImageUrl = null;
        if (Objects.nonNull(request.getImageUrl()) && StrUtil.isNotBlank(request.getImageUrl())) {
            normalizedImageUrl = normalizeImageUrl(request.getImageUrl());
            if (!mediaUrlResolver.isSiteImageUrl(normalizedImageUrl)) {
                log.error("C端参考资产修改-图片URL非本站资源: userId={}, imageUrl={}", userId, request.getImageUrl());
                throw new ServiceException("图片格式有误");
            }
            // 统一剥域名入库，DB 只存相对路径
            normalizedImageUrl = mediaUrlResolver.toRelativePath(normalizedImageUrl);
            if (normalizedImageUrl.length() > IMAGE_URL_MAX_LENGTH) {
                log.error("C端参考资产修改-图片URL过长: userId={}, length={}", userId, normalizedImageUrl.length());
                throw new ServiceException("图片地址过长");
            }
        }
        if (Objects.nonNull(request.getRemark()) && request.getRemark().length() > REMARK_MAX_LENGTH) {
            log.error("C端参考资产修改-备注过长: userId={}, length={}", userId, request.getRemark().length());
            throw new ServiceException("备注过长");
        }

        LambdaUpdateWrapper<AidUserComicAsset> update = Wrappers.lambdaUpdate();
        update.eq(AidUserComicAsset::getId, request.getId());
        update.eq(AidUserComicAsset::getUserId, userId);
        update.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        update.eq(AidUserComicAsset::getStatus, STATUS_NORMAL);
        if (Objects.nonNull(request.getAssetName())) {
            update.set(AidUserComicAsset::getAssetName, trimmedName);
        }
        if (Objects.nonNull(request.getPersonalityDesc())) {
            update.set(AidUserComicAsset::getPersonalityDesc, request.getPersonalityDesc());
        }
        if (Objects.nonNull(request.getPromptText())) {
            update.set(AidUserComicAsset::getPromptText, request.getPromptText());
        }
        // 图片URL：仅在非空白时更新，避免传 "   " 时意外清空
        if (StrUtil.isNotBlank(normalizedImageUrl)) {
            update.set(AidUserComicAsset::getImageUrl, normalizedImageUrl);
        }
        if (Objects.nonNull(request.getRemark())) {
            update.set(AidUserComicAsset::getRemark, request.getRemark());
        }
        update.set(AidUserComicAsset::getUpdateTime, DateUtils.getNowDate());
        update.set(AidUserComicAsset::getUpdateBy, String.valueOf(userId));

        boolean ok = aidUserComicAssetService.update(update);
        if (!ok) {
            log.error("C端参考资产修改-更新失败: userId={}, id={}", userId, request.getId());
            throw new ServiceException("保存失败，请重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAsset(UserComicAssetDeleteRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId())) {
            log.error("C端参考资产删除-ID为空: userId={}", userId);
            throw new ServiceException("数据不存在");
        }

        LambdaQueryWrapper<AidUserComicAsset> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.select(AidUserComicAsset::getId, AidUserComicAsset::getImageUrl);
        queryWrapper.eq(AidUserComicAsset::getId, request.getId());
        queryWrapper.eq(AidUserComicAsset::getUserId, userId);
        queryWrapper.eq(AidUserComicAsset::getDelFlag, DEL_FLAG_NORMAL);
        queryWrapper.eq(AidUserComicAsset::getStatus, STATUS_NORMAL);
        queryWrapper.last("LIMIT 1");
        AidUserComicAsset existed = aidUserComicAssetService.getOne(queryWrapper);
        if (Objects.isNull(existed)) {
            log.error("C端参考资产删除-不存在: userId={}, id={}", userId, request.getId());
            throw new ServiceException("数据不存在");
        }

        boolean ok = aidUserComicAssetService.getBaseMapper().delete(Wrappers.<AidUserComicAsset>lambdaQuery()
                .eq(AidUserComicAsset::getId, request.getId())
                .eq(AidUserComicAsset::getUserId, userId)) > 0;
        if (!ok) {
            log.error("C端参考资产删除-删除失败: userId={}, id={}", userId, request.getId());
            throw new ServiceException("删除失败，请重试");
        }

        mediaOssCleanupService.cleanupFiles(Collections.singletonList(existed.getImageUrl()));
    }

    @Override
    public List<UserComicAssetTypeVO> listAllowedTypes() {
        return ALLOWED_TYPE_DICT;
    }

    /**
     * 规范化图片URL：去除前后空白
     */
    private String normalizeImageUrl(String url) {
        if (Objects.isNull(url)) {
            return null;
        }
        return url.trim();
    }

    /**
     * 列表项VO转换（不包含 userId / status）
     */
    private UserComicAssetVO toListVO(AidUserComicAsset entity) {
        UserComicAssetVO vo = new UserComicAssetVO();
        vo.setId(entity.getId());
        vo.setAssetType(entity.getAssetType());
        vo.setAssetName(entity.getAssetName());
        vo.setPersonalityDesc(entity.getPersonalityDesc());
        vo.setPromptText(entity.getPromptText());
        vo.setImageUrl(entity.getImageUrl());
        vo.setSourceType(entity.getSourceType());
        vo.setSortOrder(entity.getSortOrder());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}

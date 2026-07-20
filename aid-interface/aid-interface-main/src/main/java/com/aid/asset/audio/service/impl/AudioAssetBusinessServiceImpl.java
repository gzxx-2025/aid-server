package com.aid.asset.audio.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aid.aid.domain.AidAudioAsset;
import com.aid.aid.service.IAidAudioAssetService;
import com.aid.asset.audio.dto.AudioAssetDeleteRequest;
import com.aid.asset.audio.dto.AudioAssetListRequest;
import com.aid.asset.audio.dto.AudioAssetRenameRequest;
import com.aid.asset.audio.service.IAudioAssetBusinessService;
import com.aid.asset.audio.vo.AudioAssetVO;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 音频资产业务实现
 * C 端：按 userId 硬过滤并做归属校验；后台：不做归属校验。
 * 查询显式 {@code .select(...)} 防止未来字段新增漏查。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AudioAssetBusinessServiceImpl implements IAudioAssetBusinessService {

    /** 未删除 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 已删除（软删） */
    private static final String DEL_FLAG_DELETED = "2";

    /** 默认分页页码 */
    private static final int DEFAULT_PAGE_NUM = 1;

    /** 默认分页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 分页上限 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 资产标题最大长度 */
    private static final int ASSET_TITLE_MAX_LEN = 200;

    @Resource
    private IAidAudioAssetService aidAudioAssetService;
    @Override
    public IPage<AudioAssetVO> listForClient(AudioAssetListRequest request, Long userId) {
        if (Objects.isNull(userId) || userId <= 0) {
            log.info("listForClient 用户ID无效, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        return listInternal(request, userId, true);
    }

    @Override
    public IPage<AudioAssetVO> listForAdmin(AudioAssetListRequest request) {
        return listInternal(request, null, false);
    }

    @Override
    public AudioAssetVO getDetailForClient(Long id, Long userId) {
        if (Objects.isNull(id) || id <= 0 || Objects.isNull(userId)) {
            log.info("getDetailForClient 参数无效, id={}, userId={}", id, userId);
            throw new ServiceException("参数错误");
        }
        AidAudioAsset entity = aidAudioAssetService.selectAidAudioAssetById(id);
        if (Objects.isNull(entity) || Objects.equals(DEL_FLAG_DELETED, entity.getDelFlag())) {
            log.info("getDetailForClient 记录不存在, id={}", id);
            throw new ServiceException("数据不存在");
        }
        if (!Objects.equals(userId, entity.getUserId())) {
            log.info("getDetailForClient 无权访问, id={}, owner={}, user={}",
                    id, entity.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        return toVO(entity, true);
    }

    @Override
    public AudioAssetVO getDetailForAdmin(Long id) {
        if (Objects.isNull(id) || id <= 0) {
            log.info("getDetailForAdmin 参数无效, id={}", id);
            throw new ServiceException("参数错误");
        }
        AidAudioAsset entity = aidAudioAssetService.selectAidAudioAssetById(id);
        if (Objects.isNull(entity)) {
            log.info("getDetailForAdmin 记录不存在, id={}", id);
            throw new ServiceException("数据不存在");
        }
        return toVO(entity, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameForClient(AudioAssetRenameRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId()) || Objects.isNull(userId)) {
            log.info("renameForClient 参数无效, request={}, userId={}", request, userId);
            throw new ServiceException("参数错误");
        }
        String title = StrUtil.trim(request.getAssetTitle());
        if (StrUtil.isBlank(title)) {
            log.info("renameForClient 标题为空");
            throw new ServiceException("标题格式有误");
        }
        if (title.length() > ASSET_TITLE_MAX_LEN) {
            log.info("renameForClient 标题过长, len={}", title.length());
            throw new ServiceException("标题过长");
        }
        AidAudioAsset entity = aidAudioAssetService.selectAidAudioAssetById(request.getId());
        if (Objects.isNull(entity) || Objects.equals(DEL_FLAG_DELETED, entity.getDelFlag())) {
            log.info("renameForClient 记录不存在, id={}", request.getId());
            throw new ServiceException("数据不存在");
        }
        if (!Objects.equals(userId, entity.getUserId())) {
            log.info("renameForClient 无权访问, id={}, owner={}, user={}",
                    request.getId(), entity.getUserId(), userId);
            throw new ServiceException("无权访问");
        }

        AidAudioAsset update = new AidAudioAsset();
        update.setId(entity.getId());
        update.setAssetTitle(title);
        update.setUpdateBy(String.valueOf(userId));
        update.setUpdateTime(new Date());
        int rows = aidAudioAssetService.updateAidAudioAsset(update);
        if (rows <= 0) {
            log.error("renameForClient 更新失败, id={}", request.getId());
            throw new ServiceException("保存失败，请重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteForClient(AudioAssetDeleteRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId()) || Objects.isNull(userId)) {
            log.info("deleteForClient 参数无效, request={}, userId={}", request, userId);
            throw new ServiceException("参数错误");
        }
        AidAudioAsset entity = aidAudioAssetService.selectAidAudioAssetById(request.getId());
        if (Objects.isNull(entity) || Objects.equals(DEL_FLAG_DELETED, entity.getDelFlag())) {
            log.info("deleteForClient 记录不存在, id={}", request.getId());
            throw new ServiceException("数据不存在");
        }
        if (!Objects.equals(userId, entity.getUserId())) {
            log.info("deleteForClient 无权访问, id={}, owner={}, user={}",
                    request.getId(), entity.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        AidAudioAsset update = new AidAudioAsset();
        update.setId(entity.getId());
        update.setDelFlag(DEL_FLAG_DELETED);
        update.setUpdateBy(String.valueOf(userId));
        update.setUpdateTime(new Date());
        aidAudioAssetService.updateAidAudioAsset(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteForAdmin(Long[] ids) {
        if (ids == null || ids.length == 0) {
            log.info("deleteForAdmin ids 为空");
            throw new ServiceException("参数错误");
        }
        for (Long id : ids) {
            if (Objects.isNull(id) || id <= 0) {
                log.info("deleteForAdmin 非法 id={}", id);
                throw new ServiceException("参数错误");
            }
        }
        String operator = SecurityUtils.getUsername();
        Date now = new Date();
        for (Long id : ids) {
            AidAudioAsset update = new AidAudioAsset();
            update.setId(id);
            update.setDelFlag(DEL_FLAG_DELETED);
            update.setUpdateBy(operator);
            update.setUpdateTime(now);
            aidAudioAssetService.updateAidAudioAsset(update);
        }
    }
    /**
     * 列表内部实现：clientMode=true 时硬过滤 userId 并裁剪敏感字段。
     */
    private IPage<AudioAssetVO> listInternal(AudioAssetListRequest request, Long userId, boolean clientMode) {
        AudioAssetListRequest req = Objects.isNull(request) ? new AudioAssetListRequest() : request;
        int pageNum = Objects.isNull(req.getPageNum()) || req.getPageNum() < 1 ? DEFAULT_PAGE_NUM : req.getPageNum();
        int pageSize = normalizePageSize(req.getPageSize());

        LambdaQueryWrapper<AidAudioAsset> wrapper = buildQueryWrapper(req, userId, clientMode, true);

        Page<AidAudioAsset> page = new Page<>(pageNum, pageSize);
        IPage<AidAudioAsset> result = aidAudioAssetService.page(page, wrapper);

        List<AudioAssetVO> voList = new ArrayList<>(result.getRecords().size());
        for (AidAudioAsset entity : result.getRecords()) {
            voList.add(toVO(entity, clientMode));
        }
        Page<AudioAssetVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    public List<AidAudioAsset> listEntitiesForExport(AudioAssetListRequest request) {
        AudioAssetListRequest req = Objects.isNull(request) ? new AudioAssetListRequest() : request;
        // 后台导出：clientMode=false 不限用户；withSelect=false 取所有字段方便 Excel 落表
        LambdaQueryWrapper<AidAudioAsset> wrapper = buildQueryWrapper(req, null, false, false);
        return aidAudioAssetService.list(wrapper);
    }

    /**
     * 统一构造查询 wrapper：列表 / 导出共用一套过滤条件。
     *
     * @param req         入参
     * @param userId      C 端当前用户
     * @param clientMode  true 时硬过滤 userId
     * @param withSelect  true 时只取列表需要的字段；false 时取全字段（用于导出）
     */
    private LambdaQueryWrapper<AidAudioAsset> buildQueryWrapper(AudioAssetListRequest req,
                                                                Long userId,
                                                                boolean clientMode,
                                                                boolean withSelect) {
        LambdaQueryWrapper<AidAudioAsset> wrapper = Wrappers.lambdaQuery();
        if (withSelect) {
            // 显式 select：新增字段需同步更新
            wrapper.select(AidAudioAsset::getId,
                    AidAudioAsset::getUserId, AidAudioAsset::getProjectId,
                    AidAudioAsset::getEpisodeId, AidAudioAsset::getStoryboardId,
                    AidAudioAsset::getAudioRecordId, AidAudioAsset::getMediaTaskId,
                    AidAudioAsset::getAudioUrl,
                    AidAudioAsset::getFileSize, AidAudioAsset::getAudioFormat,
                    AidAudioAsset::getSampleRate,
                    AidAudioAsset::getTtsText, AidAudioAsset::getVoiceLibraryId,
                    AidAudioAsset::getVoiceModelId, AidAudioAsset::getVoiceCode,
                    AidAudioAsset::getVoiceName, AidAudioAsset::getEmotion,
                    AidAudioAsset::getSpeechRate, AidAudioAsset::getLoudnessRate,
                    AidAudioAsset::getPitch,
                    AidAudioAsset::getAudioSource, AidAudioAsset::getAssetTitle,
                    AidAudioAsset::getRemark, AidAudioAsset::getDelFlag,
                    AidAudioAsset::getCreateTime, AidAudioAsset::getUpdateTime);
        }
        if (clientMode && Objects.nonNull(userId)) {
            wrapper.eq(AidAudioAsset::getUserId, userId);
        }
        if (clientMode) {
            wrapper.eq(AidAudioAsset::getDelFlag, DEL_FLAG_NORMAL);
        } else if (StrUtil.isNotBlank(req.getDelFlag())) {
            wrapper.eq(AidAudioAsset::getDelFlag, req.getDelFlag());
        }
        if (Objects.nonNull(req.getProjectId())) {
            wrapper.eq(AidAudioAsset::getProjectId, req.getProjectId());
        }
        if (Objects.nonNull(req.getEpisodeId())) {
            wrapper.eq(AidAudioAsset::getEpisodeId, req.getEpisodeId());
        }
        if (Objects.nonNull(req.getStoryboardId())) {
            wrapper.eq(AidAudioAsset::getStoryboardId, req.getStoryboardId());
        }
        if (Objects.nonNull(req.getVoiceLibraryId())) {
            wrapper.eq(AidAudioAsset::getVoiceLibraryId, req.getVoiceLibraryId());
        }
        if (StrUtil.isNotBlank(req.getVoiceName())) {
            wrapper.like(AidAudioAsset::getVoiceName, req.getVoiceName());
        }
        if (StrUtil.isNotBlank(req.getAssetTitle())) {
            wrapper.like(AidAudioAsset::getAssetTitle, req.getAssetTitle());
        }
        if (StrUtil.isNotBlank(req.getEmotion())) {
            wrapper.eq(AidAudioAsset::getEmotion, req.getEmotion());
        }
        if (Objects.nonNull(req.getAudioSource())) {
            wrapper.eq(AidAudioAsset::getAudioSource, req.getAudioSource());
        }
        wrapper.orderByDesc(AidAudioAsset::getId);
        return wrapper;
    }

    /**
     * 实体 → VO；clientMode=true 时裁剪 updateTime 等字段。
     */
    private AudioAssetVO toVO(AidAudioAsset entity, boolean clientMode) {
        AudioAssetVO vo = new AudioAssetVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setProjectId(entity.getProjectId());
        vo.setEpisodeId(entity.getEpisodeId());
        vo.setStoryboardId(entity.getStoryboardId());
        vo.setAudioRecordId(entity.getAudioRecordId());
        vo.setMediaTaskId(entity.getMediaTaskId());
        vo.setAudioUrl(entity.getAudioUrl());
        vo.setFileSize(entity.getFileSize());
        vo.setAudioFormat(entity.getAudioFormat());
        vo.setSampleRate(entity.getSampleRate());
        vo.setTtsText(entity.getTtsText());
        vo.setVoiceLibraryId(entity.getVoiceLibraryId());
        vo.setVoiceModelId(entity.getVoiceModelId());
        vo.setVoiceCode(entity.getVoiceCode());
        vo.setVoiceName(entity.getVoiceName());
        vo.setEmotion(entity.getEmotion());
        vo.setSpeechRate(entity.getSpeechRate());
        vo.setLoudnessRate(entity.getLoudnessRate());
        vo.setPitch(entity.getPitch());
        vo.setAudioSource(entity.getAudioSource());
        vo.setAssetTitle(entity.getAssetTitle());
        vo.setCreateTime(entity.getCreateTime());
        // 后台返回 updateTime / remark，C 端裁剪
        if (!clientMode) {
            vo.setRemark(entity.getRemark());
            vo.setDelFlag(entity.getDelFlag());
            vo.setUpdateTime(entity.getUpdateTime());
        }
        return vo;
    }

    /**
     * 校验并规整 pageSize。
     */
    private int normalizePageSize(Integer pageSize) {
        if (Objects.isNull(pageSize) || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}

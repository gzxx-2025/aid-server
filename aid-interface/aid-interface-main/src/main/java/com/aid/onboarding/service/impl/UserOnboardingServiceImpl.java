package com.aid.onboarding.service.impl;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidOnboardingTourConfig;
import com.aid.aid.domain.AidUserOnboardingProfile;
import com.aid.aid.domain.AidUserOnboardingTour;
import com.aid.aid.mapper.UserOnboardingTourMapper;
import com.aid.aid.service.IOnboardingTourConfigService;
import com.aid.aid.service.IUserOnboardingProfileService;
import com.aid.aid.service.IUserOnboardingTourService;
import com.aid.common.constant.OnboardingConstants;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.StringUtils;
import com.aid.enums.OnboardingTourStatus;
import com.aid.onboarding.dto.*;
import com.aid.onboarding.service.IUserOnboardingService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户引导进度业务 Service 实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class UserOnboardingServiceImpl implements IUserOnboardingService {

    @Autowired
    private IUserOnboardingProfileService profileService;

    @Autowired
    private IUserOnboardingTourService tourService;

    @Autowired
    private UserOnboardingTourMapper tourMapper;

    @Autowired
    private IOnboardingTourConfigService tourConfigService;

    @Autowired
    private RedisCache redisCache;

    /** 线程安全的时间格式化器（日志展示用） */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 批量同步单次引导条数上限（防超大列表在事务内循环加锁写库） */
    private static final int MAX_SYNC_TOURS = 50;

    // ── 公开接口 ──────────────────────────────────────────────

    @Override
    public OnboardingProgressVO getProgress(Long userId) {
        AidUserOnboardingProfile profile = getOrCreateProfile(userId);
        List<AidUserOnboardingTour> tours = listUserTours(userId);
        return buildProgressVO(profile, tours);
    }

    @Override
    @Transactional
    public OnboardingReportResultVO report(Long userId, OnboardingProgressReportReq req) {
        validateReportReq(req);
        getOrCreateProfile(userId);
        boolean applied = upsertTour(userId, req);
        return new OnboardingReportResultVO(true, applied);
    }

    @Override
    @Transactional
    public OnboardingProgressVO sync(Long userId, OnboardingProgressSyncReq req) {
        // 集合大小前置校验：空列表无意义，超大列表在事务内循环加锁写库有拖库风险
        if (req.getTours() == null || req.getTours().isEmpty()) {
            log.info("用户引导-sync-列表为空, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        if (req.getTours().size() > MAX_SYNC_TOURS) {
            log.info("用户引导-sync-列表超上限, userId={}, size={}, max={}", userId, req.getTours().size(), MAX_SYNC_TOURS);
            throw new ServiceException("同步条数过多");
        }
        getOrCreateProfile(userId);
        for (OnboardingProgressReportReq tourReq : req.getTours()) {
            validateReportReq(tourReq);
            upsertTour(userId, tourReq);
        }
        return getProgress(userId);
    }

    @Override
    @Transactional
    public OnboardingProgressVO reset(Long userId, OnboardingProgressResetReq req) {
        List<String> tourIds = (req != null) ? req.getTourIds() : null;

        if (tourIds == null || tourIds.isEmpty()) {
            // 重置全部
            LambdaQueryWrapper<AidUserOnboardingTour> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(AidUserOnboardingTour::getUserId, userId);
            tourService.getBaseMapper().delete(wrapper);
            log.info("用户引导-重置全部: userId={}", userId);
        } else {
            // 校验白名单
            for (String tourId : tourIds) {
                if (!isValidTourId(tourId)) {
                    log.error("用户引导-重置失败-非法tourId: userId={}, tourId={}", userId, tourId);
                    throw new ServiceException("参数错误");
                }
            }
            // 删除指定 Tour
            LambdaQueryWrapper<AidUserOnboardingTour> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(AidUserOnboardingTour::getUserId, userId);
            wrapper.in(AidUserOnboardingTour::getTourId, tourIds);
            tourService.getBaseMapper().delete(wrapper);
            log.info("用户引导-重置指定: userId={}, tourIds={}", userId, tourIds);
        }

        return getProgress(userId);
    }

    @Override
    @Transactional
    public OnboardingProgressVO dismiss(Long userId, OnboardingProgressDismissReq req) {
        AidUserOnboardingProfile profile = getOrCreateProfile(userId);
        if (Boolean.TRUE.equals(req.getDismissed())) {
            profile.setGlobalDismissed(1);
            profile.setDismissedAt(DateUtils.getNowDate());
        } else {
            profile.setGlobalDismissed(0);
            profile.setDismissedAt(null);
        }
        // 审计字段：更新时填充更新时间/更新者
        profile.setUpdateTime(DateUtils.getNowDate());
        profile.setUpdateBy(String.valueOf(userId));
        profileService.updateById(profile);
        log.info("用户引导-dismiss: userId={}, dismissed={}", userId, req.getDismissed());
        return getProgress(userId);
    }

    // ── 核心合并逻辑 ──────────────────────────────────────────

    /**
     * 单条 Tour 进度 UPSERT（带冲突合并）
     *
     * @return true=已写入, false=因冲突而忽略
     */
    private boolean upsertTour(Long userId, OnboardingProgressReportReq req) {
        // SELECT ... FOR UPDATE 锁定行
        AidUserOnboardingTour existing = tourMapper.selectByUserIdAndTourIdForUpdate(userId, req.getTourId());
        Date clientTime = DateUtils.parseDate(req.getClientUpdatedAt());

        if (existing == null) {
            // 无记录 → INSERT
            AidUserOnboardingTour tour = buildTour(userId, req, clientTime);
            tourService.save(tour);
            log.info("用户引导-report-新增: userId={}, tourId={}, status={}", userId, req.getTourId(), req.getStatus());
            return true;
        }

        // 有记录 → 冲突合并
        int cmp = clientTime.compareTo(existing.getClientUpdatedAt());
        if (cmp > 0) {
            // 客户端时间更新 → 覆盖
            updateTour(existing, req, clientTime);
            tourService.updateById(existing);
            log.info("用户引导-report-覆盖(时间更新): userId={}, tourId={}, status={}", userId, req.getTourId(), req.getStatus());
            return true;
        } else if (cmp == 0) {
            // 时间相同 → 按状态优先级
            OnboardingTourStatus newStatus = OnboardingTourStatus.fromCode(req.getStatus());
            OnboardingTourStatus oldStatus = OnboardingTourStatus.fromCode(existing.getStatus());
            if (newStatus != null && oldStatus != null && newStatus.getPriority() > oldStatus.getPriority()) {
                updateTour(existing, req, clientTime);
                tourService.updateById(existing);
                log.info("用户引导-report-覆盖(优先级): userId={}, tourId={}, {} -> {}", userId, req.getTourId(), oldStatus.getCode(), newStatus.getCode());
                return true;
            }
        }

        // cmp < 0 或同时间旧状态优先级更高 → 忽略
        log.info("用户引导-report-忽略: userId={}, tourId={}, clientTime={}, dbTime={}", userId, req.getTourId(),
                req.getClientUpdatedAt(), DATE_FORMAT.format(existing.getClientUpdatedAt().toInstant()));
        return false;
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private AidUserOnboardingTour buildTour(Long userId, OnboardingProgressReportReq req, Date clientTime) {
        AidUserOnboardingTour tour = new AidUserOnboardingTour();
        tour.setUserId(userId);
        tour.setTourId(req.getTourId());
        tour.setStatus(req.getStatus());
        tour.setTourVersion(req.getTourVersion());
        tour.setLastStepId(req.getLastStepId());
        tour.setClientUpdatedAt(clientTime);
        return tour;
    }

    private void updateTour(AidUserOnboardingTour existing, OnboardingProgressReportReq req, Date clientTime) {
        existing.setStatus(req.getStatus());
        existing.setTourVersion(req.getTourVersion());
        existing.setLastStepId(req.getLastStepId());
        existing.setClientUpdatedAt(clientTime);
    }

    private AidUserOnboardingProfile getOrCreateProfile(Long userId) {
        LambdaQueryWrapper<AidUserOnboardingProfile> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidUserOnboardingProfile::getUserId, userId);
        AidUserOnboardingProfile profile = profileService.getOne(wrapper);
        if (profile == null) {
            profile = new AidUserOnboardingProfile();
            profile.setUserId(userId);
            profile.setGlobalDismissed(0);
            profile.setSchemaVersion(OnboardingConstants.ONBOARDING_SCHEMA_VERSION);
            // 审计字段：创建时填充删除标志 + 创建时间/创建者 + 更新时间/更新者
            profile.setDelFlag("0");
            Date now = DateUtils.getNowDate();
            profile.setCreateTime(now);
            profile.setCreateBy(String.valueOf(userId));
            profile.setUpdateTime(now);
            profile.setUpdateBy(String.valueOf(userId));
            profileService.save(profile);
        }
        return profile;
    }

    private List<AidUserOnboardingTour> listUserTours(Long userId) {
        LambdaQueryWrapper<AidUserOnboardingTour> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidUserOnboardingTour::getUserId, userId);
        return tourService.list(wrapper);
    }

    private OnboardingProgressVO buildProgressVO(AidUserOnboardingProfile profile, List<AidUserOnboardingTour> tours) {
        List<OnboardingTourVO> tourVOs = tours.stream()
                .map(t -> OnboardingTourVO.builder()
                        .tourId(t.getTourId())
                        .status(t.getStatus())
                        .tourVersion(t.getTourVersion())
                        .lastStepId(t.getLastStepId())
                        .updatedAt(t.getClientUpdatedAt())
                        .build())
                .collect(Collectors.toList());

        Date maxUpdatedAt = tours.stream()
                .map(AidUserOnboardingTour::getClientUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(profile.getUpdateTime());

        return OnboardingProgressVO.builder()
                .schemaVersion(OnboardingConstants.ONBOARDING_SCHEMA_VERSION)
                .globalDismissed(profile.getGlobalDismissed() != null && profile.getGlobalDismissed() == 1)
                .updatedAt(maxUpdatedAt)
                .tours(tourVOs)
                .build();
    }

    // ── 校验 ──────────────────────────────────────────────────

    private void validateReportReq(OnboardingProgressReportReq req) {
        if (!isValidTourId(req.getTourId())) {
            throw new ServiceException("参数错误");
        }
        if (OnboardingTourStatus.fromCode(req.getStatus()) == null) {
            throw new ServiceException("引导状态异常");
        }
        if (req.getTourVersion() == null || req.getTourVersion() < 1) {
            throw new ServiceException("参数错误");
        }
        if (StringUtils.isEmpty(req.getClientUpdatedAt())) {
            throw new ServiceException("客户端时间有误");
        }
        if (DateUtils.parseDate(req.getClientUpdatedAt()) == null) {
            throw new ServiceException("客户端时间有误");
        }
    }

    /**
     * 从 Redis 缓存校验 tourId 白名单
     */
    private boolean isValidTourId(String tourId) {
        if (StringUtils.isEmpty(tourId)) {
            return false;
        }
        Set<String> cacheSet = redisCache.getCacheSet(OnboardingConstants.TOUR_CONFIG_CACHE_KEY);
        if (cacheSet == null || cacheSet.isEmpty()) {
            // 缓存未命中，从 DB 加载
            refreshTourIdCache();
            cacheSet = redisCache.getCacheSet(OnboardingConstants.TOUR_CONFIG_CACHE_KEY);
        }
        return cacheSet != null && cacheSet.contains(tourId);
    }

    /**
     * 从数据库加载已启用的 tour_id 到 Redis 缓存
     */
    private void refreshTourIdCache() {
        LambdaQueryWrapper<AidOnboardingTourConfig> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidOnboardingTourConfig::getIsEnabled, 1);
        List<AidOnboardingTourConfig> configs = tourConfigService.list(wrapper);
        Set<String> tourIds = configs.stream()
                .map(AidOnboardingTourConfig::getTourId)
                .collect(Collectors.toSet());
        redisCache.deleteObject(OnboardingConstants.TOUR_CONFIG_CACHE_KEY);
        redisCache.setCacheSet(OnboardingConstants.TOUR_CONFIG_CACHE_KEY, tourIds);
        redisCache.expire(OnboardingConstants.TOUR_CONFIG_CACHE_KEY, OnboardingConstants.TOUR_CONFIG_CACHE_TTL);
        log.info("用户引导-刷新Tour白名单缓存: {} 个", tourIds.size());
    }
}

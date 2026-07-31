package com.aid.asset.audio.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.AidReferenceAudio;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidConfigService;
import com.aid.aid.service.IAidReferenceAudioService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.asset.audio.dto.ReferenceAudioDeleteRequest;
import com.aid.asset.audio.dto.ReferenceAudioListRequest;
import com.aid.asset.audio.dto.ReferenceAudioRenameRequest;
import com.aid.asset.audio.dto.ReferenceAudioUploadRequest;
import com.aid.asset.audio.service.IReferenceAudioBusinessService;
import com.aid.asset.audio.vo.ReferenceAudioVO;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.enums.ProjectTypeEnum;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.util.AudioDurationProber;
import com.aid.media.util.MediaBytesFetcher;
import com.aid.media.util.MediaFormatResolver;
import com.aid.media.util.UploadedMediaPathValidator;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 参考音频业务实现。
 * 参考音频的第三条来源：用户自行上传的音频文件，按用户 + 项目硬隔离。
 * 上传期只做「能不能存」的硬边界校验（本站路径、可探测格式、时长硬边界、项目配额），
 * 「能不能用在这个模型上」由出片时的 ModelCapabilityValidator 单点负责——
 * 上传时还不知道音频会用于哪个模型，此处按模型卡死会误伤。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class ReferenceAudioBusinessServiceImpl implements IReferenceAudioBusinessService {

    /** 删除标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 删除标志：已删除（软删） */
    private static final String DEL_FLAG_DELETED = "2";

    /** 状态：启用 */
    private static final String STATUS_NORMAL = "0";

    /** 默认分页页码 */
    private static final int DEFAULT_PAGE_NUM = 1;

    /** 默认分页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 分页上限 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 音频名称最大长度（与 aid_reference_audio.audio_name 列宽一致） */
    private static final int AUDIO_NAME_MAX_LEN = 128;

    /** 电影项目的剧集占位值 */
    private static final long EPISODE_ID_NONE = 0L;

    /** aid_config 分类：参考音频上传约束 */
    private static final String CONFIG_CATEGORY = "referenceAudio";

    /** aid_config 键：单项目上传数量上限 */
    private static final String CFG_MAX_PER_PROJECT = "maxPerProject";

    /** aid_config 键：单条最短时长（秒） */
    private static final String CFG_MIN_DURATION_SECONDS = "minDurationSeconds";

    /** aid_config 键：单条最长时长（秒） */
    private static final String CFG_MAX_DURATION_SECONDS = "maxDurationSeconds";

    /** 单项目上传数量上限默认值 */
    private static final int DEFAULT_MAX_PER_PROJECT = 20;

    /** 单条最短时长默认值（秒） */
    private static final int DEFAULT_MIN_DURATION_SECONDS = 1;

    /** 单条最长时长默认值（秒） */
    private static final int DEFAULT_MAX_DURATION_SECONDS = 300;

    /** 毫秒每秒 */
    private static final long MILLIS_PER_SECOND = 1000L;

    /** mp3 最高码率对应的字节速率（字节/秒）：320kbps ÷ 8；无 Xing 头的 CBR 只能按字节数估时长，按此反推要读多少字节 */
    private static final int MAX_MP3_BYTES_PER_SECOND = 40_000;

    /** 探测下载上限的硬顶（字节）：时长配置被调大也不允许无限占内存 */
    private static final int MAX_PROBE_BYTES = 32 * 1024 * 1024;

    /** 探测下载上限的头部余量（字节）：容纳 ID3 标签等音频数据之外的部分 */
    private static final int PROBE_HEADER_SLACK_BYTES = 1024 * 1024;

    @Resource
    private IAidReferenceAudioService referenceAudioService;

    @Resource
    private IAidComicProjectService comicProjectService;

    @Resource
    private IAidComicEpisodeService comicEpisodeService;

    @Resource
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    @Resource
    private IAidConfigService aidConfigService;

    @Resource
    private MediaUrlResolver mediaUrlResolver;

    /**
     * 登记上传的参考音频。
     * 不加事务：时长探测需要下载音频，放进事务会让 HTTP 往返期间一直占着数据库连接；
     * 落库本身只有一条 INSERT，天然原子。
     */
    @Override
    public ReferenceAudioVO upload(ReferenceAudioUploadRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(userId) || userId <= 0) {
            log.info("参考音频登记参数无效, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        Long episodeId = resolveOwnedEpisodeId(request.getProjectId(), request.getEpisodeId(), userId);

        String audioName = StrUtil.trim(request.getAudioName());
        if (StrUtil.isBlank(audioName)) {
            log.info("参考音频登记失败，名称为空, userId={}, projectId={}", userId, request.getProjectId());
            throw new ServiceException("名称不能空");
        }
        if (audioName.length() > AUDIO_NAME_MAX_LEN) {
            log.info("参考音频登记失败，名称过长, userId={}, len={}", userId, audioName.length());
            throw new ServiceException("名称过长");
        }

        // 只接受本站已上传资源的规范相对路径：@MediaUrl 只剥离本站域名，站外链接会原样保留，
        // 不再判一次的话用户可传任意外链让服务端去拉取。
        String relativeUrl = StrUtil.trim(request.getAudioUrl());
        if (!UploadedMediaPathValidator.isLegalRelativePath(relativeUrl)) {
            log.error("参考音频路径非法(非本站相对路径/疑似穿越), userId={}, projectId={}, audioUrl={}",
                    userId, request.getProjectId(), relativeUrl);
            throw new ServiceException("音频格式有误");
        }

        // 只放行服务端能解析出时长的格式：探不出时长的音频进入出片链路后必被能力校验剔除，
        // 与其让用户白传一场，不如在入口就拦掉。
        String audioFormat = MediaFormatResolver.resolveFormat(relativeUrl);
        if (!ReferenceAudioLimiter.isProbeableFormat(audioFormat)) {
            log.info("参考音频格式不可解析时长, userId={}, audioUrl={}, format={}, probeable={}",
                    userId, relativeUrl, audioFormat, ReferenceAudioLimiter.probeableFormats());
            throw new ServiceException("格式不支持");
        }

        // 配额在回源下载之前判：额度已满的请求无论音频如何都存不下，先拦掉才不会白拉一次流量
        validateQuota(request.getProjectId(), userId);

        int minSeconds = readConfigInt(CFG_MIN_DURATION_SECONDS, DEFAULT_MIN_DURATION_SECONDS);
        int maxSeconds = readConfigInt(CFG_MAX_DURATION_SECONDS, DEFAULT_MAX_DURATION_SECONDS);
        String fullUrl = mediaUrlResolver.toFullUrl(relativeUrl);
        MediaBytesFetcher.Content content = MediaBytesFetcher.fetch(fullUrl, resolveProbeMaxBytes(maxSeconds));
        if (content.isEmpty()) {
            log.error("参考音频下载为空, userId={}, url={}", userId, fullUrl);
            throw new ServiceException("音频不可用");
        }
        Integer durationMs = AudioDurationProber.probeDurationMs(content.bytes(), content.truncated());
        if (Objects.isNull(durationMs) || durationMs <= 0) {
            log.error("参考音频时长探测失败, userId={}, url={}, format={}", userId, fullUrl, audioFormat);
            throw new ServiceException("音频不可用");
        }
        validateDuration(durationMs, minSeconds, maxSeconds, userId, fullUrl);

        Date now = DateUtils.getNowDate();
        String operator = String.valueOf(userId);
        AidReferenceAudio entity = new AidReferenceAudio();
        entity.setUserId(userId);
        entity.setProjectId(request.getProjectId());
        entity.setEpisodeId(episodeId);
        entity.setAudioName(audioName);
        entity.setAudioUrl(relativeUrl);
        entity.setDurationMs(durationMs);
        entity.setAudioFormat(audioFormat);
        // 文件大小由服务端按实际下载字节数认定；截断说明超出探测上限，真实大小未知不臆造
        entity.setFileSize(content.truncated() ? null : (long) content.bytes().length);
        entity.setStatus(STATUS_NORMAL);
        entity.setDelFlag(DEL_FLAG_NORMAL);
        entity.setCreateBy(operator);
        entity.setCreateTime(now);
        entity.setUpdateBy(operator);
        entity.setUpdateTime(now);

        boolean ok;
        try {
            ok = referenceAudioService.save(entity);
        } catch (Exception ex) {
            log.error("参考音频落库异常, userId={}, projectId={}, err={}",
                    userId, request.getProjectId(), ex.getMessage(), ex);
            throw new ServiceException("保存失败");
        }
        if (!ok) {
            log.error("参考音频落库失败, userId={}, projectId={}", userId, request.getProjectId());
            throw new ServiceException("保存失败");
        }
        log.info("参考音频登记成功, id={}, userId={}, projectId={}, episodeId={}, format={}, durationMs={}",
                entity.getId(), userId, request.getProjectId(), episodeId, audioFormat, durationMs);
        return toVO(entity);
    }

    @Override
    public IPage<ReferenceAudioVO> list(ReferenceAudioListRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(userId) || userId <= 0) {
            log.info("参考音频列表参数无效, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        Long episodeId = resolveOwnedEpisodeId(request.getProjectId(), request.getEpisodeId(), userId);

        int pageNum = Objects.isNull(request.getPageNum()) || request.getPageNum() < 1
                ? DEFAULT_PAGE_NUM : request.getPageNum();
        int pageSize = normalizePageSize(request.getPageSize());

        // 显式 select：新增字段需同步更新
        LambdaQueryWrapper<AidReferenceAudio> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidReferenceAudio::getId, AidReferenceAudio::getUserId,
                AidReferenceAudio::getProjectId, AidReferenceAudio::getEpisodeId,
                AidReferenceAudio::getAudioName, AidReferenceAudio::getAudioUrl,
                AidReferenceAudio::getDurationMs, AidReferenceAudio::getAudioFormat,
                AidReferenceAudio::getFileSize, AidReferenceAudio::getStatus,
                AidReferenceAudio::getCreateTime);
        wrapper.eq(AidReferenceAudio::getUserId, userId);
        wrapper.eq(AidReferenceAudio::getProjectId, request.getProjectId());
        // 剧集项目按「本集 + 全剧集通用」两档取，电影项目 episodeId 恒为 0，两个条件等价
        wrapper.and(inner -> inner.eq(AidReferenceAudio::getEpisodeId, episodeId)
                .or().eq(AidReferenceAudio::getEpisodeId, EPISODE_ID_NONE));
        if (StrUtil.isNotBlank(request.getAudioName())) {
            wrapper.like(AidReferenceAudio::getAudioName, StrUtil.trim(request.getAudioName()));
        }
        wrapper.eq(AidReferenceAudio::getStatus, STATUS_NORMAL);
        wrapper.eq(AidReferenceAudio::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.orderByDesc(AidReferenceAudio::getId);

        IPage<AidReferenceAudio> result = referenceAudioService.page(new Page<>(pageNum, pageSize), wrapper);
        List<ReferenceAudioVO> voList = new ArrayList<>(result.getRecords().size());
        for (AidReferenceAudio entity : result.getRecords()) {
            voList.add(toVO(entity));
        }
        Page<ReferenceAudioVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rename(ReferenceAudioRenameRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId())
                || Objects.isNull(userId) || userId <= 0) {
            log.info("参考音频重命名参数无效, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        String audioName = StrUtil.trim(request.getAudioName());
        if (StrUtil.isBlank(audioName)) {
            log.info("参考音频重命名失败，名称为空, id={}, userId={}", request.getId(), userId);
            throw new ServiceException("名称不能空");
        }
        if (audioName.length() > AUDIO_NAME_MAX_LEN) {
            log.info("参考音频重命名失败，名称过长, id={}, len={}", request.getId(), audioName.length());
            throw new ServiceException("名称过长");
        }
        AidReferenceAudio entity = loadOwned(request.getId(), userId);

        Date now = DateUtils.getNowDate();
        AidReferenceAudio update = new AidReferenceAudio();
        update.setId(entity.getId());
        update.setAudioName(audioName);
        update.setUpdateBy(String.valueOf(userId));
        update.setUpdateTime(now);
        if (!referenceAudioService.updateById(update)) {
            log.error("参考音频重命名写库失败, id={}, userId={}", request.getId(), userId);
            throw new ServiceException("保存失败");
        }
        log.info("参考音频重命名成功, id={}, userId={}", entity.getId(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(ReferenceAudioDeleteRequest request, Long userId) {
        if (Objects.isNull(request) || Objects.isNull(request.getId())
                || Objects.isNull(userId) || userId <= 0) {
            log.info("参考音频删除参数无效, userId={}", userId);
            throw new ServiceException("参数错误");
        }
        AidReferenceAudio entity = loadOwned(request.getId(), userId);

        Date now = DateUtils.getNowDate();
        String operator = String.valueOf(userId);
        AidReferenceAudio update = new AidReferenceAudio();
        update.setId(entity.getId());
        update.setDelFlag(DEL_FLAG_DELETED);
        update.setUpdateBy(operator);
        update.setUpdateTime(now);
        if (!referenceAudioService.updateById(update)) {
            log.error("参考音频删除写库失败, id={}, userId={}", request.getId(), userId);
            throw new ServiceException("保存失败");
        }
        // 绑定表存的是 URL / 时长冗余快照，不清掉的话已删音频仍会被出片链路下发给厂商
        clearBindingReference(entity.getId(), userId, operator, now);
        log.info("参考音频删除成功, id={}, userId={}", entity.getId(), userId);
    }

    /**
     * 清空引用该参考音频的角色绑定冗余列，绑定自动回落音色库试听样音。
     *
     * @param referenceAudioId 参考音频ID
     * @param userId           归属用户ID
     * @param operator         操作人
     * @param now              操作时间
     */
    private void clearBindingReference(Long referenceAudioId, Long userId, String operator, Date now) {
        roleVoiceBindingService.update(Wrappers.<AidRoleVoiceBinding>lambdaUpdate()
                .eq(AidRoleVoiceBinding::getReferenceAudioId, referenceAudioId)
                .eq(AidRoleVoiceBinding::getUserId, userId)
                .set(AidRoleVoiceBinding::getReferenceAudioId, null)
                .set(AidRoleVoiceBinding::getReferenceAudioUrl, null)
                .set(AidRoleVoiceBinding::getReferenceAudioDurationMs, null)
                .set(AidRoleVoiceBinding::getUpdateBy, operator)
                .set(AidRoleVoiceBinding::getUpdateTime, now));
    }

    /**
     * 读取归属当前用户的参考音频；不存在 / 已删 / 非本人一律按不存在处理。
     *
     * @param id     参考音频ID
     * @param userId 当前用户ID
     * @return 参考音频实体
     */
    private AidReferenceAudio loadOwned(Long id, Long userId) {
        AidReferenceAudio entity = referenceAudioService.getById(id);
        if (Objects.isNull(entity) || Objects.equals(DEL_FLAG_DELETED, entity.getDelFlag())) {
            log.info("参考音频不存在, id={}, userId={}", id, userId);
            throw new ServiceException("数据不存在");
        }
        if (!Objects.equals(userId, entity.getUserId())) {
            log.info("参考音频无权访问, id={}, owner={}, userId={}", id, entity.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        return entity;
    }

    /**
     * 校验项目归属并归一化剧集ID：电影项目忽略 episodeId 统一返回 0，剧集项目必须命中本项目下的剧集。
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @param userId    当前用户ID
     * @return 归一化后的剧集ID
     */
    private Long resolveOwnedEpisodeId(Long projectId, Long episodeId, Long userId) {
        if (Objects.isNull(projectId) || projectId <= 0) {
            log.info("参考音频项目参数无效, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不能空");
        }
        AidComicProject project = comicProjectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getProjectType)
                .eq(AidComicProject::getId, projectId)
                .eq(AidComicProject::getUserId, userId)
                .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"), false);
        if (Objects.isNull(project)) {
            log.info("参考音频项目不存在或不属于当前用户, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        if (ProjectTypeEnum.MOVIE.getValue().equals(project.getProjectType())) {
            // 电影：无剧集维度，统一归一化为 0
            return EPISODE_ID_NONE;
        }
        if (Objects.isNull(episodeId) || episodeId <= 0) {
            log.info("参考音频剧集为空, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("剧集不能空");
        }
        AidComicEpisode episode = comicEpisodeService.getOne(Wrappers.<AidComicEpisode>lambdaQuery()
                .select(AidComicEpisode::getId)
                .eq(AidComicEpisode::getId, episodeId)
                .eq(AidComicEpisode::getProjectId, projectId)
                .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                .last("LIMIT 1"), false);
        if (Objects.isNull(episode)) {
            log.info("参考音频剧集不存在或不属于该项目, projectId={}, episodeId={}, userId={}",
                    projectId, episodeId, userId);
            throw new ServiceException("剧集不存在");
        }
        return episodeId;
    }

    /**
     * 按可接受的最长时长反推探测下载上限。
     * wav 的时长由容器头声明，读多读少都不影响；mp3 缺 Xing 头时只能按字节数估算 CBR 时长，
     * 字节被截断就只会判成「音频不可用」。上限跟着时长配置走，才不会出现
     * 「时长在允许区间内、却因为读不全而被拒」的错位。
     *
     * @param maxDurationSeconds 允许的最长时长（秒）
     * @return 本次探测的下载字节上限
     */
    private int resolveProbeMaxBytes(int maxDurationSeconds) {
        long audioBytes = (long) maxDurationSeconds * MAX_MP3_BYTES_PER_SECOND;
        return (int) Math.min(audioBytes + PROBE_HEADER_SLACK_BYTES, MAX_PROBE_BYTES);
    }

    /**
     * 时长硬边界校验：只卡与模型无关的宽松区间，按模型能力位的校验在出片时进行。
     *
     * @param durationMs 探测出的时长毫秒
     * @param minSeconds 允许的最短时长（秒）
     * @param maxSeconds 允许的最长时长（秒）
     * @param userId     当前用户ID
     * @param fullUrl    音频完整地址（日志定位用）
     */
    private void validateDuration(Integer durationMs, int minSeconds, int maxSeconds, Long userId, String fullUrl) {
        if (durationMs < minSeconds * MILLIS_PER_SECOND || durationMs > maxSeconds * MILLIS_PER_SECOND) {
            log.info("参考音频时长越界, userId={}, url={}, durationMs={}, min={}s, max={}s",
                    userId, fullUrl, durationMs, minSeconds, maxSeconds);
            throw new ServiceException("时长超限");
        }
    }

    /**
     * 项目配额校验：单项目可上传的参考音频条数上限。
     *
     * @param projectId 项目ID
     * @param userId    当前用户ID
     */
    private void validateQuota(Long projectId, Long userId) {
        int maxPerProject = readConfigInt(CFG_MAX_PER_PROJECT, DEFAULT_MAX_PER_PROJECT);
        long used = referenceAudioService.count(Wrappers.<AidReferenceAudio>lambdaQuery()
                .eq(AidReferenceAudio::getUserId, userId)
                .eq(AidReferenceAudio::getProjectId, projectId)
                .eq(AidReferenceAudio::getDelFlag, DEL_FLAG_NORMAL));
        if (used >= maxPerProject) {
            log.info("参考音频数量超限, userId={}, projectId={}, used={}, max={}",
                    userId, projectId, used, maxPerProject);
            throw new ServiceException("数量超限");
        }
    }

    /**
     * 读取 aid_config(category=referenceAudio) 下指定 config_name 的正整数值。
     *
     * @param configName   配置键
     * @param defaultValue 缺失 / 非法 / 非正数时的兜底值
     * @return 配置值
     */
    private int readConfigInt(String configName, int defaultValue) {
        try {
            AidConfig config = aidConfigService.getOne(Wrappers.<AidConfig>lambdaQuery()
                    .select(AidConfig::getConfigValue)
                    .eq(AidConfig::getCategory, CONFIG_CATEGORY)
                    .eq(AidConfig::getConfigName, configName)
                    .last("LIMIT 1"), false);
            if (Objects.nonNull(config) && StrUtil.isNotBlank(config.getConfigValue())) {
                int value = Integer.parseInt(StrUtil.trim(config.getConfigValue()));
                return value > 0 ? value : defaultValue;
            }
        } catch (Exception ex) {
            log.warn("读取参考音频配置失败, configName={}, 用默认值 {}: {}",
                    configName, defaultValue, ex.getMessage());
        }
        return defaultValue;
    }

    /**
     * 规整分页条数。
     *
     * @param pageSize 请求条数
     * @return 合法条数
     */
    private int normalizePageSize(Integer pageSize) {
        if (Objects.isNull(pageSize) || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 实体 → VO。
     *
     * @param entity 参考音频实体
     * @return VO
     */
    private ReferenceAudioVO toVO(AidReferenceAudio entity) {
        ReferenceAudioVO vo = new ReferenceAudioVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setProjectId(entity.getProjectId());
        vo.setEpisodeId(entity.getEpisodeId());
        vo.setAudioName(entity.getAudioName());
        vo.setAudioUrl(entity.getAudioUrl());
        vo.setDurationMs(entity.getDurationMs());
        vo.setAudioFormat(entity.getAudioFormat());
        vo.setFileSize(entity.getFileSize());
        vo.setStatus(entity.getStatus());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}

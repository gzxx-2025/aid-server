package com.aid.voice.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidAiVoiceTag;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.voice.constant.VoiceLibraryConstants;
import com.aid.voice.dto.VoiceLibraryListRequest;
import com.aid.voice.dto.VoiceLibraryStatusRequest;
import com.aid.voice.dto.VoiceLibraryUpsertRequest;
import com.aid.voice.service.IVoiceLibraryBusinessService;
import com.aid.voice.service.IVoiceTagBusinessService;
import com.aid.voice.util.VoiceEmotionCapability;
import com.aid.voice.vo.VoiceEnumItemVO;
import com.aid.voice.vo.VoiceLibraryVO;
import com.aid.voice.vo.VoiceTagBundleVO;
import com.aid.voice.vo.VoiceTagItemVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 音色库业务实现
 * 承载列表过滤（Admin / C 端双视角）、反查 provider、标签字典命中校验、C 端一次性标签打包。
 * 基础 CRUD 委托给 {@link IAidAiVoiceLibraryService}；校验写法遵循项目既有规范。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class VoiceLibraryBusinessServiceImpl implements IVoiceLibraryBusinessService
{
    /** 启用状态 */
    private static final String STATUS_ENABLED = "0";

    /** 停用状态 */
    private static final String STATUS_DISABLED = "1";

    /** 未删除 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 模型类型：音频（TTS） */
    private static final String MODEL_TYPE_AUDIO = "audio";

    /** 已删除 */
    private static final String DEL_FLAG_DELETED = "2";

    /** 默认分页 pageNum */
    private static final int DEFAULT_PAGE_NUM = 1;

    /** 默认分页 pageSize */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** URL 合法协议前缀 */
    private static final String URL_PREFIX_HTTP = "http://";
    private static final String URL_PREFIX_HTTPS = "https://";

    /** 永不下架兜底大值 */
    private static final String OFFLINE_TIME_NEVER_STR = "9999-12-31 00:00:00";
    private static final String OFFLINE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    /** 永不下架兜底 Date 实例（线程安全，只在首次访问时构造一次） */
    private static final Date OFFLINE_TIME_NEVER = cn.hutool.core.date.DateUtil.parse(OFFLINE_TIME_NEVER_STR, OFFLINE_TIME_PATTERN);

    @Resource
    private IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    @Resource
    private IAidAiModelService aidAiModelService;

    @Resource
    private IAidAiProviderService aidAiProviderService;

    @Resource
    private IVoiceTagBusinessService voiceTagBusinessService;
    @Override
    public IPage<VoiceLibraryVO> listForAdmin(VoiceLibraryListRequest request)
    {
        return listInternal(request, false);
    }

    @Override
    public IPage<VoiceLibraryVO> listForClient(VoiceLibraryListRequest request)
    {
        return listInternal(request, true);
    }

    @Override
    public VoiceLibraryVO getDetail(Long id)
    {
        if (Objects.isNull(id) || id <= 0)
        {
            log.info("getDetail id 无效, id={}", id);
            throw new ServiceException("参数错误");
        }
        AidAiVoiceLibrary entity = aidAiVoiceLibraryService.selectAidAiVoiceLibraryById(id);
        if (Objects.isNull(entity))
        {
            log.info("getDetail 未命中, id={}", id);
            throw new ServiceException("数据不存在");
        }
        VoiceLibraryVO vo = toVO(entity, false);
        enrichProviderModelName(java.util.Collections.singletonList(vo));
        return vo;
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createVoice(VoiceLibraryUpsertRequest request)
    {
        validateUpsertFields(request);

        // 反查模型，同步回填 provider_id；校验专用：仅取 id / provider_id / status / del_flag / capability_json
        AidAiModel model = selectModelForValidation(request.getModelId());
        // MiniMax 音色只允许通过"同步音色"按钮维护，禁止手动新增
        rejectIfSyncOnlyProvider(model);
        ensureUniqueVoiceCode(request.getModelId(), request.getVoiceCode(), null);
        // 标签字典命中校验（情感标签按所属模型的供应商声明白名单校验）
        validateTagArrays(request, model);

        AidAiVoiceLibrary entity = new AidAiVoiceLibrary();
        applyUpsert(entity, request);
        // 归属：provider 用反查结果，不信任前端
        entity.setProviderId(model.getProviderId());
        // 状态与软删（只接受合法枚举，默认启用）
        entity.setStatus(normalizeStatus(request.getStatus(), STATUS_ENABLED));
        entity.setDelFlag(DEL_FLAG_NORMAL);
        // 创建审计
        String operator = SecurityUtils.getUsername();
        entity.setCreateBy(operator);
        entity.setCreateTime(new Date());
        entity.setUpdateBy(operator);
        entity.setUpdateTime(new Date());

        int rows = aidAiVoiceLibraryService.insertAidAiVoiceLibrary(entity);
        if (rows <= 0)
        {
            log.warn("createVoice 数据库保存失败, request={}", request);
            throw new ServiceException("保存失败，请重试");
        }
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateVoice(VoiceLibraryUpsertRequest request)
    {
        if (Objects.isNull(request) || Objects.isNull(request.getId()))
        {
            log.info("updateVoice 缺少 id");
            throw new ServiceException("参数错误");
        }
        AidAiVoiceLibrary existing = aidAiVoiceLibraryService.selectAidAiVoiceLibraryById(request.getId());
        if (Objects.isNull(existing) || Objects.equals(DEL_FLAG_DELETED, existing.getDelFlag()))
        {
            log.info("updateVoice 未命中或已删除, id={}", request.getId());
            throw new ServiceException("数据不存在");
        }
        validateUpsertFields(request);
        AidAiModel model = selectModelForValidation(request.getModelId());
        // MiniMax 音色只允许通过"同步音色"按钮维护，禁止手动编辑
        rejectIfSyncOnlyProvider(model);
        ensureUniqueVoiceCode(request.getModelId(), request.getVoiceCode(), request.getId());
        validateTagArrays(request, model);

        AidAiVoiceLibrary update = new AidAiVoiceLibrary();
        update.setId(request.getId());
        applyUpsert(update, request);
        update.setProviderId(model.getProviderId());
        if (StrUtil.isNotBlank(request.getStatus()))
        {
            update.setStatus(normalizeStatus(request.getStatus(), existing.getStatus()));
        }
        // 更新审计
        update.setUpdateBy(SecurityUtils.getUsername());
        update.setUpdateTime(new Date());
        int rows = aidAiVoiceLibraryService.updateAidAiVoiceLibrary(update);
        if (rows <= 0)
        {
            log.warn("updateVoice 数据库保存失败, request={}", request);
            throw new ServiceException("保存失败，请重试");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateVoiceStatus(VoiceLibraryStatusRequest request)
    {
        if (Objects.isNull(request) || Objects.isNull(request.getId()))
        {
            log.info("updateVoiceStatus 缺少 id");
            throw new ServiceException("参数错误");
        }
        if (!Objects.equals(STATUS_ENABLED, request.getStatus())
                && !Objects.equals(STATUS_DISABLED, request.getStatus()))
        {
            log.info("updateVoiceStatus status 非法, status={}", request.getStatus());
            throw new ServiceException("状态不支持");
        }
        AidAiVoiceLibrary existing = aidAiVoiceLibraryService.selectAidAiVoiceLibraryById(request.getId());
        if (Objects.isNull(existing) || Objects.equals(DEL_FLAG_DELETED, existing.getDelFlag()))
        {
            log.info("updateVoiceStatus 未命中或已删除, id={}", request.getId());
            throw new ServiceException("数据不存在");
        }
        AidAiVoiceLibrary update = new AidAiVoiceLibrary();
        update.setId(request.getId());
        update.setStatus(request.getStatus());
        update.setUpdateBy(SecurityUtils.getUsername());
        update.setUpdateTime(new Date());
        aidAiVoiceLibraryService.updateAidAiVoiceLibrary(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVoices(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            log.info("deleteVoices ids 为空");
            throw new ServiceException("参数错误");
        }
        if (ids.length > VoiceLibraryConstants.DELETE_BATCH_MAX)
        {
            log.info("deleteVoices 超过批量上限, count={}", ids.length);
            throw new ServiceException("数量超出限制");
        }
        for (Long id : ids)
        {
            if (Objects.isNull(id) || id <= 0)
            {
                log.info("deleteVoices 非法 id={}", id);
                throw new ServiceException("参数错误");
            }
        }
        String operator = SecurityUtils.getUsername();
        Date now = new Date();
        for (Long id : ids)
        {
            AidAiVoiceLibrary entity = new AidAiVoiceLibrary();
            entity.setId(id);
            entity.setDelFlag(DEL_FLAG_DELETED);
            entity.setUpdateBy(operator);
            entity.setUpdateTime(now);
            aidAiVoiceLibraryService.updateById(entity);
        }
    }
    @Override
    public VoiceTagBundleVO buildTagBundle()
    {
        VoiceTagBundleVO bundle = new VoiceTagBundleVO();
        bundle.setCharacterTypes(toTagItems(
                voiceTagBusinessService.listActiveTagsByType(VoiceLibraryConstants.TAG_TYPE_CHARACTER)));
        bundle.setVoiceStyles(toTagItems(
                voiceTagBusinessService.listActiveTagsByType(VoiceLibraryConstants.TAG_TYPE_VOICE_STYLE)));
        bundle.setToneTags(toTagItems(
                voiceTagBusinessService.listActiveTagsByType(VoiceLibraryConstants.TAG_TYPE_TONE)));
        bundle.setEmotionTags(loadEmotionItems());
        bundle.setEnums(buildEnumsMap());
        return bundle;
    }
    private IPage<VoiceLibraryVO> listInternal(VoiceLibraryListRequest request, boolean clientMode)
    {
        VoiceLibraryListRequest req = Objects.isNull(request) ? new VoiceLibraryListRequest() : request;
        int pageNum = Objects.isNull(req.getPageNum()) || req.getPageNum() < 1 ? DEFAULT_PAGE_NUM : req.getPageNum();
        int pageSize = normalizePageSize(req.getPageSize());

        // 仅取必要字段；新增字段需同步更新
        LambdaQueryWrapper<AidAiVoiceLibrary> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAiVoiceLibrary::getId,
                AidAiVoiceLibrary::getProviderId, AidAiVoiceLibrary::getModelId,
                AidAiVoiceLibrary::getVoiceCode, AidAiVoiceLibrary::getVoiceName,
                AidAiVoiceLibrary::getAvatarUrl, AidAiVoiceLibrary::getSampleUrl,
                AidAiVoiceLibrary::getSampleText,
                AidAiVoiceLibrary::getLanguage, AidAiVoiceLibrary::getGender,
                AidAiVoiceLibrary::getAgeRange,
                AidAiVoiceLibrary::getCharacterTypes, AidAiVoiceLibrary::getVoiceStyles,
                AidAiVoiceLibrary::getToneTags, AidAiVoiceLibrary::getEmotionTags,
                AidAiVoiceLibrary::getSupportsEmotion, AidAiVoiceLibrary::getSupportsSpeed,
                AidAiVoiceLibrary::getSupportsPitch,
                AidAiVoiceLibrary::getDefaultSpeed, AidAiVoiceLibrary::getDefaultPitch,
                AidAiVoiceLibrary::getSampleRate, AidAiVoiceLibrary::getAudioFormat,
                AidAiVoiceLibrary::getSortOrder,
                AidAiVoiceLibrary::getStatus, AidAiVoiceLibrary::getRemark,
                AidAiVoiceLibrary::getDelFlag,
                AidAiVoiceLibrary::getOfflineTime,
                AidAiVoiceLibrary::getCreateBy, AidAiVoiceLibrary::getCreateTime,
                AidAiVoiceLibrary::getUpdateBy, AidAiVoiceLibrary::getUpdateTime);
        // C 端固定只返回启用记录，并强制过滤已下架音色；
        // 同时剔除所属模型 / 供应商已停用的音色（运营停用模型或供应商后，其下音色对 C 端立即不可见）
        if (clientMode)
        {
            wrapper.eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL);
            wrapper.eq(AidAiVoiceLibrary::getStatus, STATUS_ENABLED);
            wrapper.gt(AidAiVoiceLibrary::getOfflineTime, new Date());
            Set<Long> visibleModelIds = loadClientVisibleModelIds();
            if (CollectionUtil.isEmpty(visibleModelIds))
            {
                // 无任何"模型+供应商均启用"的模型 → C 端直接返回空页
                Page<VoiceLibraryVO> emptyPage = new Page<>(pageNum, pageSize, 0);
                emptyPage.setRecords(new ArrayList<>());
                return emptyPage;
            }
            wrapper.in(AidAiVoiceLibrary::getModelId, visibleModelIds);
        }
        else
        {
            if (StrUtil.isNotBlank(req.getDelFlag()))
            {
                wrapper.eq(AidAiVoiceLibrary::getDelFlag, req.getDelFlag());
            }
            if (StrUtil.isNotBlank(req.getStatus()))
            {
                wrapper.eq(AidAiVoiceLibrary::getStatus, req.getStatus());
            }
        }
        if (Objects.nonNull(req.getProviderId()))
        {
            wrapper.eq(AidAiVoiceLibrary::getProviderId, req.getProviderId());
        }
        if (Objects.nonNull(req.getModelId()))
        {
            wrapper.eq(AidAiVoiceLibrary::getModelId, req.getModelId());
        }
        if (StrUtil.isNotBlank(req.getLanguage()))
        {
            wrapper.eq(AidAiVoiceLibrary::getLanguage, req.getLanguage());
        }
        if (StrUtil.isNotBlank(req.getGender()))
        {
            wrapper.eq(AidAiVoiceLibrary::getGender, req.getGender());
        }
        if (StrUtil.isNotBlank(req.getAgeRange()))
        {
            wrapper.eq(AidAiVoiceLibrary::getAgeRange, req.getAgeRange());
        }
        if (StrUtil.isNotBlank(req.getVoiceName()))
        {
            wrapper.like(AidAiVoiceLibrary::getVoiceName, req.getVoiceName());
        }
        if (StrUtil.isNotBlank(req.getVoiceCode()))
        {
            wrapper.like(AidAiVoiceLibrary::getVoiceCode, req.getVoiceCode());
        }
        // JSON 数组包含匹配：用双引号包裹 value 才是 JSON 元素精确匹配（否则 "少女" 会误中 "少女学" 这样的包含项）
        if (StrUtil.isNotBlank(req.getCharacterType()))
        {
            wrapper.like(AidAiVoiceLibrary::getCharacterTypes, jsonArrayElementLike(req.getCharacterType()));
        }
        if (StrUtil.isNotBlank(req.getVoiceStyle()))
        {
            wrapper.like(AidAiVoiceLibrary::getVoiceStyles, jsonArrayElementLike(req.getVoiceStyle()));
        }
        if (StrUtil.isNotBlank(req.getToneTag()))
        {
            wrapper.like(AidAiVoiceLibrary::getToneTags, jsonArrayElementLike(req.getToneTag()));
        }
        if (StrUtil.isNotBlank(req.getEmotionTag()))
        {
            wrapper.like(AidAiVoiceLibrary::getEmotionTags, jsonArrayElementLike(req.getEmotionTag()));
        }
        // 稳定排序
        wrapper.orderByDesc(AidAiVoiceLibrary::getSortOrder).orderByDesc(AidAiVoiceLibrary::getId);

        Page<AidAiVoiceLibrary> page = new Page<>(pageNum, pageSize);
        IPage<AidAiVoiceLibrary> result = aidAiVoiceLibraryService.page(page, wrapper);

        List<VoiceLibraryVO> voList = new ArrayList<>();
        for (AidAiVoiceLibrary entity : result.getRecords())
        {
            voList.add(toVO(entity, clientMode));
        }
        if (!clientMode)
        {
            enrichProviderModelName(voList);
        }

        Page<VoiceLibraryVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }
    /**
     * C 端可见模型ID集合：模型启用未删 且 所属供应商启用未删。
     * 查询字段精简：模型仅取 id/provider_id，供应商仅取 id；后续扩展取数请同步增列。
     *
     * @return 可见模型ID集合；无可见模型返回空集合
     */
    private Set<Long> loadClientVisibleModelIds()
    {
        // 启用供应商ID集合
        LambdaQueryWrapper<AidAiProvider> pw = Wrappers.lambdaQuery();
        pw.select(AidAiProvider::getId);
        pw.eq(AidAiProvider::getStatus, STATUS_ENABLED);
        pw.eq(AidAiProvider::getDelFlag, DEL_FLAG_NORMAL);
        Set<Long> enabledProviderIds = new HashSet<>();
        for (AidAiProvider p : aidAiProviderService.list(pw))
        {
            enabledProviderIds.add(p.getId());
        }
        if (CollectionUtil.isEmpty(enabledProviderIds))
        {
            return new HashSet<>();
        }
        // 启用模型（且供应商启用）
        LambdaQueryWrapper<AidAiModel> mw = Wrappers.lambdaQuery();
        mw.select(AidAiModel::getId, AidAiModel::getProviderId);
        mw.eq(AidAiModel::getStatus, STATUS_ENABLED);
        mw.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        mw.in(AidAiModel::getProviderId, enabledProviderIds);
        Set<Long> modelIds = new HashSet<>();
        for (AidAiModel m : aidAiModelService.list(mw))
        {
            modelIds.add(m.getId());
        }
        return modelIds;
    }

    private void validateUpsertFields(VoiceLibraryUpsertRequest request)
    {
        if (Objects.isNull(request))
        {
            log.info("validateUpsertFields request 为空");
            throw new ServiceException("参数错误");
        }
        if (StrUtil.isBlank(request.getVoiceName())
                || request.getVoiceName().trim().length() > VoiceLibraryConstants.VOICE_NAME_MAX_LEN)
        {
            log.info("validateUpsertFields voiceName 非法");
            throw new ServiceException("名称格式有误");
        }
        if (StrUtil.isBlank(request.getVoiceCode())
                || request.getVoiceCode().length() > VoiceLibraryConstants.VOICE_CODE_MAX_LEN)
        {
            log.info("validateUpsertFields voiceCode 非法");
            throw new ServiceException("编码格式有误");
        }
        if (!VoiceLibraryConstants.LANGUAGES.contains(request.getLanguage()))
        {
            log.info("validateUpsertFields language 非法, language={}", request.getLanguage());
            throw new ServiceException("语言不支持");
        }
        if (!VoiceLibraryConstants.GENDERS.contains(request.getGender()))
        {
            log.info("validateUpsertFields gender 非法, gender={}", request.getGender());
            throw new ServiceException("性别不支持");
        }
        if (!VoiceLibraryConstants.AGE_RANGES.contains(request.getAgeRange()))
        {
            log.info("validateUpsertFields ageRange 非法, ageRange={}", request.getAgeRange());
            throw new ServiceException("年龄段不支持");
        }
        // URL 校验
        if (StrUtil.isNotBlank(request.getAvatarUrl())
                && !(request.getAvatarUrl().startsWith(URL_PREFIX_HTTP)
                || request.getAvatarUrl().startsWith(URL_PREFIX_HTTPS)))
        {
            log.info("validateUpsertFields avatarUrl 协议非法");
            throw new ServiceException("头像地址无效");
        }
        if (StrUtil.isNotBlank(request.getAvatarUrl())
                && request.getAvatarUrl().length() > VoiceLibraryConstants.URL_MAX_LEN)
        {
            log.info("validateUpsertFields avatarUrl 过长");
            throw new ServiceException("头像地址过长");
        }
        if (StrUtil.isNotBlank(request.getSampleUrl())
                && !(request.getSampleUrl().startsWith(URL_PREFIX_HTTP)
                || request.getSampleUrl().startsWith(URL_PREFIX_HTTPS)))
        {
            log.info("validateUpsertFields sampleUrl 协议非法");
            throw new ServiceException("试听地址无效");
        }
        if (StrUtil.isNotBlank(request.getSampleUrl())
                && request.getSampleUrl().length() > VoiceLibraryConstants.URL_MAX_LEN)
        {
            log.info("validateUpsertFields sampleUrl 过长");
            throw new ServiceException("试听地址过长");
        }
        if (StrUtil.isNotBlank(request.getSampleText())
                && request.getSampleText().length() > VoiceLibraryConstants.URL_MAX_LEN)
        {
            log.info("validateUpsertFields sampleText 过长");
            throw new ServiceException("示例文案过长");
        }
        // 能力字段范围
        if (Objects.nonNull(request.getDefaultSpeed()))
        {
            BigDecimal v = request.getDefaultSpeed();
            if (v.compareTo(VoiceLibraryConstants.SPEED_MIN) < 0
                    || v.compareTo(VoiceLibraryConstants.SPEED_MAX) > 0)
            {
                log.info("validateUpsertFields defaultSpeed 越界, value={}", v);
                throw new ServiceException("语速超出范围");
            }
        }
        if (Objects.nonNull(request.getDefaultPitch()))
        {
            BigDecimal v = request.getDefaultPitch();
            if (v.compareTo(VoiceLibraryConstants.PITCH_MIN) < 0
                    || v.compareTo(VoiceLibraryConstants.PITCH_MAX) > 0)
            {
                log.info("validateUpsertFields defaultPitch 越界, value={}", v);
                throw new ServiceException("音调超出范围");
            }
        }
        if (Objects.nonNull(request.getSampleRate())
                && !VoiceLibraryConstants.SAMPLE_RATES.contains(request.getSampleRate()))
        {
            log.info("validateUpsertFields sampleRate 非法, value={}", request.getSampleRate());
            throw new ServiceException("采样率不支持");
        }
        if (StrUtil.isNotBlank(request.getAudioFormat())
                && !VoiceLibraryConstants.AUDIO_FORMATS.contains(request.getAudioFormat()))
        {
            log.info("validateUpsertFields audioFormat 非法, value={}", request.getAudioFormat());
            throw new ServiceException("格式不支持");
        }
        // 标签数组大小 / 元素长度（存在性命中在 validateTagArrays 里做）
        checkTagArrayShape(request.getCharacterTypes(), "角色类型");
        checkTagArrayShape(request.getVoiceStyles(), "使用场景");
        checkTagArrayShape(request.getToneTags(), "音调");
        checkTagArrayShape(request.getEmotionTags(), "情感");
    }

    private void checkTagArrayShape(List<String> tags, String label)
    {
        if (CollectionUtil.isEmpty(tags))
        {
            return;
        }
        if (tags.size() > VoiceLibraryConstants.TAG_ARRAY_MAX_SIZE)
        {
            log.info("checkTagArrayShape {} 超过数量上限, size={}", label, tags.size());
            throw new ServiceException("标签数量超限");
        }
        for (String t : tags)
        {
            if (StrUtil.isBlank(t) || t.length() > VoiceLibraryConstants.TAG_ELEMENT_MAX_LEN)
            {
                log.info("checkTagArrayShape {} 非法元素 value={}", label, t);
                throw new ServiceException("标签格式有误");
            }
        }
    }

    /**
     * 模型有效性校验查询。
     * 校验专用：只取 id / provider_id / status / del_flag；新增字段需同步更新。
     */
    private AidAiModel selectModelForValidation(Long modelId)
    {
        if (Objects.isNull(modelId) || modelId <= 0)
        {
            log.info("selectModelForValidation modelId 非法");
            throw new ServiceException("模型参数有误");
        }
        LambdaQueryWrapper<AidAiModel> wrapper = Wrappers.lambdaQuery();
        // 校验专用：只取 id / provider_id / status / del_flag / capability_json（情感白名单校验用）
        wrapper.select(AidAiModel::getId, AidAiModel::getProviderId,
                AidAiModel::getStatus, AidAiModel::getDelFlag, AidAiModel::getCapabilityJson);
        wrapper.eq(AidAiModel::getId, modelId);
        wrapper.last("LIMIT 1");
        AidAiModel model = aidAiModelService.getOne(wrapper, false);
        if (Objects.isNull(model))
        {
            log.info("selectModelForValidation 模型不存在, modelId={}", modelId);
            throw new ServiceException("模型不存在");
        }
        if (!Objects.equals(STATUS_ENABLED, model.getStatus()))
        {
            log.info("selectModelForValidation 模型未启用, modelId={}, status={}", modelId, model.getStatus());
            throw new ServiceException("模型已停用");
        }
        if (Objects.equals("1", model.getDelFlag()) || Objects.equals("2", model.getDelFlag()))
        {
            log.info("selectModelForValidation 模型已删除, modelId={}, delFlag={}", modelId, model.getDelFlag());
            throw new ServiceException("模型不存在");
        }
        if (Objects.isNull(model.getProviderId()))
        {
            log.error("selectModelForValidation 模型 provider_id 为空, modelId={}", modelId);
            throw new ServiceException("模型不可用");
        }
        return model;
    }

    /**
     * 拒绝对"仅允许同步维护"的服务商进行手动新增/编辑。
     * 当前只有 MiniMax 走远程同步（{@code /v1/get_voice}），手动新增/编辑会和同步逻辑冲突
     * （同步会覆盖 name/remark、软删不存在的音色），所以在业务层直接拦截。
     * 判定条件：模型所属 provider 的 {@code provider_code} equalsIgnoreCase 'minimax'。
     */
    private void rejectIfSyncOnlyProvider(AidAiModel model)
    {
        if (Objects.isNull(model) || Objects.isNull(model.getProviderId()))
        {
            return;
        }
        // 查 provider_code
        com.aid.aid.domain.AidAiProvider provider = aidAiProviderService.getById(model.getProviderId());
        if (Objects.nonNull(provider) && StrUtil.isNotBlank(provider.getProviderCode())
                && "minimax".equalsIgnoreCase(provider.getProviderCode().trim()))
        {
            log.info("rejectIfSyncOnlyProvider 拒绝手动维护 MiniMax 音色: modelId={}, providerId={}",
                    model.getId(), model.getProviderId());
            throw new ServiceException("请使用同步方式");
        }
    }

    /**
     * (model_id, voice_code) 唯一性校验。
     * 校验专用：只取 id / model_id / voice_code / del_flag；新增字段需同步更新。
     */
    private void ensureUniqueVoiceCode(Long modelId, String voiceCode, Long selfId)
    {
        LambdaQueryWrapper<AidAiVoiceLibrary> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getModelId,
                AidAiVoiceLibrary::getVoiceCode, AidAiVoiceLibrary::getDelFlag);
        wrapper.eq(AidAiVoiceLibrary::getModelId, modelId);
        wrapper.eq(AidAiVoiceLibrary::getVoiceCode, voiceCode);
        wrapper.eq(AidAiVoiceLibrary::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.last("LIMIT 1");
        AidAiVoiceLibrary conflict = aidAiVoiceLibraryService.getOne(wrapper, false);
        if (Objects.nonNull(conflict) && !Objects.equals(conflict.getId(), selfId))
        {
            log.info("ensureUniqueVoiceCode 冲突, modelId={}, voiceCode={}, conflictId={}",
                    modelId, voiceCode, conflict.getId());
            throw new ServiceException("编码已存在");
        }
    }

    private void validateTagArrays(VoiceLibraryUpsertRequest request, AidAiModel model)
    {
        List<String> missing = voiceTagBusinessService.findMissingTagCodes(
                VoiceLibraryConstants.TAG_TYPE_CHARACTER, request.getCharacterTypes());
        if (CollectionUtil.isNotEmpty(missing))
        {
            log.info("validateTagArrays 角色类型未命中, missing={}", missing);
            throw new ServiceException("标签格式有误");
        }
        missing = voiceTagBusinessService.findMissingTagCodes(
                VoiceLibraryConstants.TAG_TYPE_VOICE_STYLE, request.getVoiceStyles());
        if (CollectionUtil.isNotEmpty(missing))
        {
            log.info("validateTagArrays 使用场景未命中, missing={}", missing);
            throw new ServiceException("标签格式有误");
        }
        missing = voiceTagBusinessService.findMissingTagCodes(
                VoiceLibraryConstants.TAG_TYPE_TONE, request.getToneTags());
        if (CollectionUtil.isNotEmpty(missing))
        {
            log.info("validateTagArrays 音调未命中, missing={}", missing);
            throw new ServiceException("标签格式有误");
        }
        // 擅长情感：以供应商声明为唯一标准——必须命中所属模型 capability_json.emotions 白名单；
        // 白名单为空 = 供应商未声明能力，拒绝设置情感标签（避免录入上游不识别的编码）
        if (CollectionUtil.isNotEmpty(request.getEmotionTags()))
        {
            List<String> supported = VoiceEmotionCapability.parseModelEmotions(
                    Objects.isNull(model) ? null : model.getCapabilityJson());
            if (CollectionUtil.isEmpty(supported))
            {
                log.info("validateTagArrays 模型未声明情感能力, modelId={}",
                        Objects.isNull(model) ? null : model.getId());
                throw new ServiceException("模型不支持情感");
            }
            for (String code : request.getEmotionTags())
            {
                if (!supported.contains(code))
                {
                    log.info("validateTagArrays 情感不在模型白名单, code={}, modelId={}, supported={}",
                            code, model.getId(), supported);
                    throw new ServiceException("情感不支持");
                }
            }
        }
    }
    private void applyUpsert(AidAiVoiceLibrary entity, VoiceLibraryUpsertRequest request)
    {
        entity.setModelId(request.getModelId());
        entity.setVoiceCode(StrUtil.trim(request.getVoiceCode()));
        entity.setVoiceName(request.getVoiceName().trim());
        entity.setAvatarUrl(blankToNull(request.getAvatarUrl()));
        entity.setSampleUrl(blankToNull(request.getSampleUrl()));
        entity.setSampleText(blankToNull(request.getSampleText()));
        entity.setLanguage(request.getLanguage());
        entity.setGender(request.getGender());
        entity.setAgeRange(request.getAgeRange());
        // 标签数组持久化为 JSON 字符串（即使为空也落 []，避免 null 与空数组语义混淆）
        entity.setCharacterTypes(toJsonArray(request.getCharacterTypes()));
        entity.setVoiceStyles(toJsonArray(request.getVoiceStyles()));
        entity.setToneTags(toJsonArray(request.getToneTags()));
        entity.setEmotionTags(toJsonArray(request.getEmotionTags()));
        entity.setSupportsEmotion(Objects.isNull(request.getSupportsEmotion()) ? Boolean.FALSE : request.getSupportsEmotion());
        entity.setSupportsSpeed(Objects.isNull(request.getSupportsSpeed()) ? Boolean.FALSE : request.getSupportsSpeed());
        entity.setSupportsPitch(Objects.isNull(request.getSupportsPitch()) ? Boolean.FALSE : request.getSupportsPitch());
        entity.setDefaultSpeed(Objects.isNull(request.getDefaultSpeed()) ? new BigDecimal("1.0") : request.getDefaultSpeed());
        entity.setDefaultPitch(Objects.isNull(request.getDefaultPitch()) ? new BigDecimal("0") : request.getDefaultPitch());
        entity.setSampleRate(request.getSampleRate());
        entity.setAudioFormat(blankToNull(request.getAudioFormat()));
        entity.setOfflineTime(parseOfflineTime(request.getOfflineTime()));
        entity.setSortOrder(Objects.isNull(request.getSortOrder()) ? 0 : request.getSortOrder());
        entity.setRemark(blankToNull(request.getRemark()));
    }

    private VoiceLibraryVO toVO(AidAiVoiceLibrary entity, boolean clientMode)
    {
        VoiceLibraryVO vo = new VoiceLibraryVO();
        vo.setId(entity.getId());
        vo.setProviderId(entity.getProviderId());
        vo.setModelId(entity.getModelId());
        vo.setVoiceCode(entity.getVoiceCode());
        vo.setVoiceName(entity.getVoiceName());
        vo.setAvatarUrl(entity.getAvatarUrl());
        vo.setSampleUrl(entity.getSampleUrl());
        vo.setSampleText(entity.getSampleText());
        vo.setLanguage(entity.getLanguage());
        vo.setGender(entity.getGender());
        vo.setAgeRange(entity.getAgeRange());
        vo.setCharacterTypes(parseJsonArray(entity.getCharacterTypes()));
        vo.setVoiceStyles(parseJsonArray(entity.getVoiceStyles()));
        vo.setToneTags(parseJsonArray(entity.getToneTags()));
        vo.setEmotionTags(parseJsonArray(entity.getEmotionTags()));
        vo.setSupportsEmotion(entity.getSupportsEmotion());
        vo.setSupportsSpeed(entity.getSupportsSpeed());
        vo.setSupportsPitch(entity.getSupportsPitch());
        vo.setDefaultSpeed(entity.getDefaultSpeed());
        vo.setDefaultPitch(entity.getDefaultPitch());
        vo.setSampleRate(entity.getSampleRate());
        vo.setAudioFormat(entity.getAudioFormat());
        vo.setOfflineTime(entity.getOfflineTime());
        vo.setSortOrder(entity.getSortOrder());
        if (!clientMode)
        {
            vo.setStatus(entity.getStatus());
            vo.setDelFlag(entity.getDelFlag());
            vo.setRemark(entity.getRemark());
            vo.setCreateTime(entity.getCreateTime());
            vo.setUpdateTime(entity.getUpdateTime());
        }
        return vo;
    }

    private void enrichProviderModelName(List<VoiceLibraryVO> voList)
    {
        if (CollectionUtil.isEmpty(voList))
        {
            return;
        }
        Set<Long> providerIds = new HashSet<>();
        Set<Long> modelIds = new HashSet<>();
        for (VoiceLibraryVO vo : voList)
        {
            if (Objects.nonNull(vo.getProviderId())) providerIds.add(vo.getProviderId());
            if (Objects.nonNull(vo.getModelId())) modelIds.add(vo.getModelId());
        }
        Map<Long, String> providerNameMap = new HashMap<>();
        Map<Long, String> modelNameMap = new HashMap<>();
        if (!providerIds.isEmpty())
        {
            LambdaQueryWrapper<AidAiProvider> pw = Wrappers.lambdaQuery();
            // 校验/展示专用：只取 id / provider_name
            pw.select(AidAiProvider::getId, AidAiProvider::getProviderName);
            pw.in(AidAiProvider::getId, providerIds);
            for (AidAiProvider p : aidAiProviderService.list(pw))
            {
                providerNameMap.put(p.getId(), p.getProviderName());
            }
        }
        if (!modelIds.isEmpty())
        {
            LambdaQueryWrapper<AidAiModel> mw = Wrappers.lambdaQuery();
            // 校验/展示专用：只取 id / model_name
            mw.select(AidAiModel::getId, AidAiModel::getModelName);
            mw.in(AidAiModel::getId, modelIds);
            for (AidAiModel m : aidAiModelService.list(mw))
            {
                modelNameMap.put(m.getId(), m.getModelName());
            }
        }
        for (VoiceLibraryVO vo : voList)
        {
            vo.setProviderName(providerNameMap.get(vo.getProviderId()));
            vo.setModelName(modelNameMap.get(vo.getModelId()));
        }
    }

    private List<VoiceTagItemVO> toTagItems(List<AidAiVoiceTag> tags)
    {
        List<VoiceTagItemVO> list = new ArrayList<>();
        if (CollectionUtil.isEmpty(tags))
        {
            return list;
        }
        for (AidAiVoiceTag tag : tags)
        {
            VoiceTagItemVO item = new VoiceTagItemVO();
            item.setTagCode(tag.getTagCode());
            item.setTagName(tag.getTagName());
            list.add(item);
        }
        return list;
    }

    /**
     * 情感候选：以供应商声明为唯一标准——聚合全部「启用未删」音频模型 capability_json.emotions 的并集
     * （保持声明顺序、跨模型去重），显示名由 {@link VoiceEmotionCapability#labelOf} 纯展示翻译。
     * 不再读取任何全局配置；供应商/模型未声明情感时该项自然不出现在候选里。
     * 查询字段精简：仅 select id / capability_json，后续扩展取数请同步增列。
     */
    private List<VoiceTagItemVO> loadEmotionItems()
    {
        LambdaQueryWrapper<AidAiModel> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAiModel::getId, AidAiModel::getCapabilityJson);
        wrapper.eq(AidAiModel::getModelType, MODEL_TYPE_AUDIO);
        wrapper.eq(AidAiModel::getStatus, STATUS_ENABLED);
        wrapper.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        List<AidAiModel> models = aidAiModelService.list(wrapper);

        List<VoiceTagItemVO> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AidAiModel model : models)
        {
            for (String code : VoiceEmotionCapability.parseModelEmotions(model.getCapabilityJson()))
            {
                if (seen.add(code))
                {
                    VoiceTagItemVO item = new VoiceTagItemVO();
                    item.setTagCode(code);
                    item.setTagName(VoiceEmotionCapability.labelOf(code));
                    list.add(item);
                }
            }
        }
        return list;
    }

    private Map<String, List<VoiceEnumItemVO>> buildEnumsMap()
    {
        Map<String, List<VoiceEnumItemVO>> map = new LinkedHashMap<>();
        map.put("language", toEnumItems(VoiceLibraryConstants.LANGUAGE_LABELS));
        map.put("gender", toEnumItems(VoiceLibraryConstants.GENDER_LABELS));
        map.put("ageRange", toEnumItems(VoiceLibraryConstants.AGE_RANGE_LABELS));
        return map;
    }

    private List<VoiceEnumItemVO> toEnumItems(Map<String, String> labels)
    {
        List<VoiceEnumItemVO> list = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet())
        {
            list.add(new VoiceEnumItemVO(e.getKey(), e.getValue()));
        }
        return list;
    }

    private String toJsonArray(List<String> list)
    {
        if (CollectionUtil.isEmpty(list))
        {
            return "[]";
        }
        JSONArray arr = new JSONArray();
        for (String item : list)
        {
            if (StrUtil.isNotBlank(item))
            {
                arr.add(item);
            }
        }
        return arr.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        try
        {
            Object obj = JSONUtil.parse(json);
            if (obj instanceof JSONArray)
            {
                for (Object item : (JSONArray) obj)
                {
                    if (item != null)
                    {
                        list.add(item.toString());
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.error("parseJsonArray 解析失败, json={}, err={}", json, e.getMessage());
        }
        return list;
    }

    private int normalizePageSize(Integer pageSize)
    {
        if (Objects.isNull(pageSize) || pageSize < VoiceLibraryConstants.PAGE_SIZE_MIN)
        {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize > VoiceLibraryConstants.PAGE_SIZE_MAX)
        {
            return VoiceLibraryConstants.PAGE_SIZE_MAX;
        }
        return pageSize;
    }

    private String blankToNull(String value)
    {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    /**
     * 归一化下架时间：
     *
     *   - 空值 / 空串 → 永不下架 {@code 9999-12-31 00:00:00}
     *   - 合法格式 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd → 按 GMT+8 解析
     *   - 解析失败 → 落日志，降级为永不下架（不阻断主流程，避免前端误填卡单）
     *
     */
    private Date parseOfflineTime(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return OFFLINE_TIME_NEVER;
        }
        String value = raw.trim();
        try
        {
            // 纯日期补零
            if (value.length() == 10)
            {
                return cn.hutool.core.date.DateUtil.parse(value + " 00:00:00", OFFLINE_TIME_PATTERN);
            }
            return cn.hutool.core.date.DateUtil.parse(value, OFFLINE_TIME_PATTERN);
        }
        catch (Exception e)
        {
            log.info("parseOfflineTime 解析失败, value={}, fallbackNever={}", value, OFFLINE_TIME_NEVER_STR);
            return OFFLINE_TIME_NEVER;
        }
    }

    private String normalizeStatus(String status, String fallback)
    {
        if (Objects.equals(STATUS_ENABLED, status) || Objects.equals(STATUS_DISABLED, status))
        {
            return status;
        }
        log.info("normalizeStatus 非法状态, value={}, fallback={}", status, fallback);
        return fallback;
    }

    /**
     * 把业务侧 value 封装成 MySQL JSON 字段的 like 片段：匹配 JSON 数组中的精确元素。
     * 例如 value="少女" 返回 "\"少女\""，使 LIKE 只能命中 ["少女", ...] 中的完整元素，
     * 不会误匹配到 ["少女学"]。同时转义 value 中的双引号与反斜杠，防止 JSON 结构被破坏。
     */
    private String jsonArrayElementLike(String value)
    {
        String safe = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }
}

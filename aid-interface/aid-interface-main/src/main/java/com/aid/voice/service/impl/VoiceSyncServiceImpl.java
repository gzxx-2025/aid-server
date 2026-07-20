package com.aid.voice.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxTtsConstants;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.service.IAiModelConfigService;
import com.aid.voice.service.IVoiceSyncService;
import com.aid.voice.vo.VoiceSyncResultVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 音色远程同步 Service 实现（当前覆盖 MiniMax）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class VoiceSyncServiceImpl implements IVoiceSyncService
{
    /** 删除标志：存在 */
    private static final String DEL_FLAG_NORMAL = "0";
    /** 删除标志：删除 */
    private static final String DEL_FLAG_DELETED = "2";
    /** 启用状态 */
    private static final String STATUS_NORMAL = "0";
    /** 音频模型类型 */
    private static final String MODEL_TYPE_AUDIO = "audio";
    /** "永不下架"兜底时间 */
    private static final Date OFFLINE_NEVER = DateUtils.parseDate("9999-12-31 00:00:00");

    @Resource
    private IAidAiModelService aidAiModelService;

    @Resource
    private IAiModelConfigService aiModelConfigService;

    @Resource
    private IAidAiVoiceLibraryService voiceLibraryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VoiceSyncResultVO syncByModel(Long modelId, String operator)
    {
        if (Objects.isNull(modelId))
        {
            log.info("音色同步失败，modelId 为空");
            throw new RuntimeException("模型无效");
        }
        long startTs = System.currentTimeMillis();

        AidAiModel model = aidAiModelService.getById(modelId);
        if (Objects.isNull(model) || !Objects.equals(DEL_FLAG_NORMAL, model.getDelFlag()))
        {
            log.info("音色同步失败，模型不存在: modelId={}", modelId);
            throw new RuntimeException("模型不存在");
        }
        if (!Objects.equals(MODEL_TYPE_AUDIO, model.getModelType()))
        {
            log.info("音色同步失败，非音频模型: modelId={}, type={}", modelId, model.getModelType());
            throw new RuntimeException("模型不符");
        }

        AiModelConfigVo config = aiModelConfigService.selectByModelId(modelId);
        if (Objects.isNull(config) || StrUtil.isBlank(config.getApiKey())
                || StrUtil.isBlank(config.getBaseUrl()))
        {
            log.info("音色同步失败，模型配置不完整: modelId={}", modelId);
            throw new RuntimeException("鉴权缺失");
        }
        // 仅 MiniMax 支持远程同步；豆包系列不提供 /get_voice 等价接口
        if (!isMinimax(config))
        {
            log.info("音色同步失败，非 MiniMax 模型: modelId={}, providerCode={}",
                    modelId, config.getProviderCode());
            throw new RuntimeException("暂不支持");
        }

        List<RemoteVoice> remoteList = fetchMinimaxVoices(config);
        if (Objects.isNull(remoteList))
        {
            // null 和 empty 语义区分：null = 请求失败（网络 / 非 0 base_resp）
            log.error("音色同步失败，远程接口异常: modelId={}", modelId);
            throw new RuntimeException(MinimaxTtsConstants.ERR_VOICE_SYNC);
        }
        // 空列表保护：上游可能偶发返回"三组都空"（账号未购 / 接口异常等），
        // 继续跑 diff 会把本地活跃音色全部软删，故按失败拒绝执行。
        if (remoteList.isEmpty())
        {
            log.error("音色同步失败，远程返回空列表拒绝执行以防止全量软删: modelId={}", modelId);
            throw new RuntimeException(MinimaxTtsConstants.ERR_VOICE_SYNC);
        }

        // 本地已有（含软删历史）：远程重新上架某个已下架的 voice_code 时，
        // 必须直接把软删旧行 del_flag 置回 '0'，不能走 INSERT 路径（会踩唯一键
        // uk_model_voice(model_id, voice_code)）。
        LambdaQueryWrapper<AidAiVoiceLibrary> localWrapper = Wrappers.lambdaQuery();
        localWrapper.eq(AidAiVoiceLibrary::getModelId, modelId);
        List<AidAiVoiceLibrary> localList = voiceLibraryService.list(localWrapper);
        Map<String, AidAiVoiceLibrary> localIndex = new HashMap<>(localList.size());
        for (AidAiVoiceLibrary v : localList)
        {
            if (StrUtil.isNotBlank(v.getVoiceCode()))
            {
                localIndex.put(v.getVoiceCode(), v);
            }
        }
        // 活跃子集——仅这部分参与"远程无 → 软删"逻辑；软删历史行不再参与软删 diff，
        // 避免对已下架的行再次 update
        List<AidAiVoiceLibrary> activeLocalList = new ArrayList<>();
        for (AidAiVoiceLibrary v : localList)
        {
            if (Objects.equals(DEL_FLAG_NORMAL, v.getDelFlag()))
            {
                activeLocalList.add(v);
            }
        }

        int inserted = 0;
        int updated = 0;
        int restored = 0;
        Set<String> remoteCodes = new HashSet<>(remoteList.size());
        Date now = DateUtils.getNowDate();
        String by = StrUtil.isNotBlank(operator) ? operator : "system";

        List<AidAiVoiceLibrary> toInsert = new ArrayList<>();
        // 纯"字段覆盖"的更新 → toUpdate；"del_flag 置回 0"的恢复 → toRestore；
        // 两个桶分别入库，避免 updated 计数把 restored 也算进去重复计数。
        List<AidAiVoiceLibrary> toUpdate = new ArrayList<>();
        List<AidAiVoiceLibrary> toRestore = new ArrayList<>();

        for (RemoteVoice rv : remoteList)
        {
            if (StrUtil.isBlank(rv.voiceCode))
            {
                continue;
            }
            remoteCodes.add(rv.voiceCode);
            AidAiVoiceLibrary local = localIndex.get(rv.voiceCode);
            if (Objects.isNull(local))
            {
                // 本地从未有过这条 voice_code → 纯新增（填充 MiniMax 通用默认值）
                AidAiVoiceLibrary ent = new AidAiVoiceLibrary();
                ent.setProviderId(config.getProviderId());
                ent.setModelId(modelId);
                ent.setVoiceCode(rv.voiceCode);
                ent.setVoiceName(StrUtil.isNotBlank(rv.voiceName) ? rv.voiceName : rv.voiceCode);
                ent.setRemark(rv.description);
                ent.setSupportsSpeed(Boolean.TRUE);
                ent.setSupportsPitch(Boolean.TRUE);
                ent.setSupportsEmotion(Boolean.TRUE);
                ent.setDefaultSpeed(new java.math.BigDecimal("1.0"));
                ent.setDefaultPitch(new java.math.BigDecimal("0"));
                ent.setSampleRate(32000);
                ent.setAudioFormat("mp3");
                ent.setOfflineTime(OFFLINE_NEVER);
                ent.setStatus(STATUS_NORMAL);
                ent.setDelFlag(DEL_FLAG_NORMAL);
                ent.setSortOrder(0);
                ent.setCreateBy(by);
                ent.setCreateTime(now);
                ent.setUpdateBy(by);
                ent.setUpdateTime(now);
                toInsert.add(ent);
            }
            else if (Objects.equals(DEL_FLAG_DELETED, local.getDelFlag()))
            {
                // 恢复软删：已软删的同 voice_code 被上游重新返回，直接把 del_flag 置回 '0'；
                // 不能走 INSERT（唯一键 uk_model_voice 会冲突），也不能忽略（会永远同步不到）。
                // 同时用远程最新数据覆盖 voice_name / remark，并把 offlineTime 重置为 OFFLINE_NEVER，
                // 避免旧行曾被设置过期导致"显示恢复但 C 端仍不可用"。
                AidAiVoiceLibrary restore = new AidAiVoiceLibrary();
                restore.setId(local.getId());
                restore.setDelFlag(DEL_FLAG_NORMAL);
                restore.setStatus(STATUS_NORMAL);
                restore.setOfflineTime(OFFLINE_NEVER);
                if (StrUtil.isNotBlank(rv.voiceName))
                {
                    restore.setVoiceName(rv.voiceName);
                }
                if (StrUtil.isNotBlank(rv.description))
                {
                    restore.setRemark(rv.description);
                }
                restore.setUpdateBy(by);
                restore.setUpdateTime(now);
                toRestore.add(restore);
                restored++;
            }
            else
            {
                // 字段覆盖：name / remark 用远程；头像 / 试听 / 标签 / 能力字段由运营本地维护，不覆盖
                AidAiVoiceLibrary upd = new AidAiVoiceLibrary();
                upd.setId(local.getId());
                if (StrUtil.isNotBlank(rv.voiceName) && !Objects.equals(local.getVoiceName(), rv.voiceName))
                {
                    upd.setVoiceName(rv.voiceName);
                }
                if (StrUtil.isNotBlank(rv.description) && !Objects.equals(local.getRemark(), rv.description))
                {
                    upd.setRemark(rv.description);
                }
                upd.setUpdateBy(by);
                upd.setUpdateTime(now);
                toUpdate.add(upd);
            }
        }

        int deleted = 0;
        List<Long> toDeleteIds = new ArrayList<>();
        for (AidAiVoiceLibrary local : activeLocalList)
        {
            if (!remoteCodes.contains(local.getVoiceCode()))
            {
                toDeleteIds.add(local.getId());
            }
        }

        if (CollectionUtil.isNotEmpty(toInsert))
        {
            voiceLibraryService.saveBatch(toInsert);
            inserted = toInsert.size();
        }
        if (CollectionUtil.isNotEmpty(toUpdate))
        {
            voiceLibraryService.updateBatchById(toUpdate);
            updated = toUpdate.size();
        }
        if (CollectionUtil.isNotEmpty(toRestore))
        {
            voiceLibraryService.updateBatchById(toRestore);
            // restored 在 diff 阶段已经计数，这里只负责写库
        }
        if (CollectionUtil.isNotEmpty(toDeleteIds))
        {
            voiceLibraryService.removeByIds(toDeleteIds);
            deleted = toDeleteIds.size();
        }

        VoiceSyncResultVO vo = new VoiceSyncResultVO();
        vo.setModelId(modelId);
        vo.setModelCode(model.getModelCode());
        vo.setRemoteCount(remoteList.size());
        vo.setInserted(inserted);
        vo.setUpdated(updated);
        vo.setRestored(restored);
        vo.setSoftDeleted(deleted);
        vo.setCostMs(System.currentTimeMillis() - startTs);
        vo.setSyncTime(now);

        log.info("音色同步完成: modelId={}, modelCode={}, remote={}, inserted={}, updated={}, restored={}, deleted={}, cost={}ms",
                modelId, model.getModelCode(), remoteList.size(),
                inserted, updated, restored, deleted, vo.getCostMs());
        return vo;
    }
    private boolean isMinimax(AiModelConfigVo config)
    {
        if (Objects.isNull(config))
        {
            return false;
        }
        String providerCode = StrUtil.trimToEmpty(config.getProviderCode()).toLowerCase();
        if (providerCode.contains("minimax"))
        {
            return true;
        }
        // 兜底：按 base_url 识别
        String base = StrUtil.trimToEmpty(config.getBaseUrl()).toLowerCase();
        return base.contains("minimaxi.com");
    }

    /**
     * 调 MiniMax {@code POST /v1/get_voice} 拉全量音色。
     * 把三组（system_voice / voice_cloning / voice_generation）拍扁到一个 {@code RemoteVoice} 列表；
     * 网络异常 / base_resp.status_code 非 0 返回 null（对外表示同步失败）。
     */
    private List<RemoteVoice> fetchMinimaxVoices(AiModelConfigVo config)
    {
        String url = joinUrl(config.getBaseUrl(), MinimaxTtsConstants.VOICE_LIST_PATH);
        Map<String, Object> body = new HashMap<>();
        body.put("voice_type", "all");
        String json = JSONUtil.toJsonStr(body);

        String raw;
        try (HttpResponse response = HttpRequest.post(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .body(json)
                .timeout(MinimaxTtsConstants.HTTP_TIMEOUT_MS)
                .execute())
        {
            raw = response.body();
        }
        catch (Exception ex)
        {
            log.error("MinimaxTts /get_voice 网络异常, providerId={}", config.getProviderId(), ex);
            return null;
        }
        JsonNode root = ProviderResponseHelper.readTree(raw);
        Integer code = ProviderResponseHelper.readInt(root, MinimaxTtsConstants.RESP_BASE_STATUS_CODE);
        if (Objects.isNull(code) || code != MinimaxTtsConstants.VENDOR_CODE_OK)
        {
            log.error("MinimaxTts /get_voice 非成功, code={}, raw={}",
                    code, StrUtil.brief(raw, MinimaxTtsConstants.LOG_TEXT_ABBREV_MAX));
            return null;
        }

        List<RemoteVoice> all = new ArrayList<>();
        collectVoiceArray(root.get("system_voice"), all);
        collectVoiceArray(root.get("voice_cloning"), all);
        collectVoiceArray(root.get("voice_generation"), all);
        // 去重：上游同一个 voice_id 可能出现在多个组里（如系统音色同时被克隆过），
        // 不去重会导致 toInsert 重复 → 唯一键冲突 → 整次同步回滚。
        java.util.Map<String, RemoteVoice> dedup = new java.util.LinkedHashMap<>();
        for (RemoteVoice rv : all) {
            if (StrUtil.isNotBlank(rv.voiceCode) && !dedup.containsKey(rv.voiceCode)) {
                dedup.put(rv.voiceCode, rv);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private void collectVoiceArray(JsonNode arr, List<RemoteVoice> target)
    {
        if (Objects.isNull(arr) || !arr.isArray())
        {
            return;
        }
        for (JsonNode item : arr)
        {
            if (Objects.isNull(item) || !item.isObject())
            {
                continue;
            }
            String voiceId = textOrNull(item.get("voice_id"));
            if (StrUtil.isBlank(voiceId))
            {
                continue;
            }
            RemoteVoice rv = new RemoteVoice();
            rv.voiceCode = voiceId;
            rv.voiceName = textOrNull(item.get("voice_name"));
            rv.description = joinDescription(item.get("description"));
            target.add(rv);
        }
    }

    private String textOrNull(JsonNode node)
    {
        if (Objects.isNull(node) || node.isNull() || node.isMissingNode())
        {
            return null;
        }
        return node.asText();
    }

    private String joinDescription(JsonNode desc)
    {
        if (Objects.isNull(desc) || !desc.isArray() || desc.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode d : desc)
        {
            if (Objects.nonNull(d) && !d.isNull())
            {
                if (sb.length() > 0)
                {
                    sb.append("；");
                }
                sb.append(d.asText());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String joinUrl(String base, String path)
    {
        String trimmed = StrUtil.trimToEmpty(base);
        if (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + path;
    }

    /** 内部远程音色 DTO */
    private static class RemoteVoice
    {
        String voiceCode;
        String voiceName;
        String description;
    }
    @Override
    public com.aid.voice.vo.RemoteVoiceFetchResultVO fetchRemoteWithLocalStatus(Long modelId)
    {
        if (Objects.isNull(modelId))
        {
            throw new RuntimeException("模型无效");
        }
        AidAiModel model = aidAiModelService.getById(modelId);
        if (Objects.isNull(model) || !Objects.equals(DEL_FLAG_NORMAL, model.getDelFlag()))
        {
            throw new RuntimeException("模型不存在");
        }
        AiModelConfigVo config = aiModelConfigService.selectByModelId(modelId);
        if (Objects.isNull(config) || StrUtil.isBlank(config.getApiKey()))
        {
            throw new RuntimeException("鉴权缺失");
        }
        if (!isMinimax(config))
        {
            throw new RuntimeException("暂不支持");
        }

        // 拉远程
        List<RemoteVoice> remoteList = fetchMinimaxVoices(config);
        if (Objects.isNull(remoteList))
        {
            throw new RuntimeException(MinimaxTtsConstants.ERR_VOICE_SYNC);
        }

        // 本地已入库
        LambdaQueryWrapper<AidAiVoiceLibrary> localWrapper = Wrappers.lambdaQuery();
        localWrapper.select(AidAiVoiceLibrary::getId, AidAiVoiceLibrary::getVoiceCode);
        localWrapper.eq(AidAiVoiceLibrary::getModelId, modelId);
        List<AidAiVoiceLibrary> localList = voiceLibraryService.list(localWrapper);
        java.util.Set<String> localCodes = new java.util.HashSet<>();
        for (AidAiVoiceLibrary v : localList)
        {
            if (StrUtil.isNotBlank(v.getVoiceCode()))
            {
                localCodes.add(v.getVoiceCode());
            }
        }

        // 组装 VO
        List<com.aid.voice.vo.RemoteVoiceVO> voList = new ArrayList<>();
        for (RemoteVoice rv : remoteList)
        {
            if (StrUtil.isBlank(rv.voiceCode))
            {
                continue;
            }
            com.aid.voice.vo.RemoteVoiceVO vo = new com.aid.voice.vo.RemoteVoiceVO();
            vo.setVoiceCode(rv.voiceCode);
            vo.setVoiceName(rv.voiceName);
            vo.setDescription(rv.description);
            vo.setExists(localCodes.contains(rv.voiceCode));
            voList.add(vo);
        }

        com.aid.voice.vo.RemoteVoiceFetchResultVO result = new com.aid.voice.vo.RemoteVoiceFetchResultVO();
        result.setVoices(voList);
        result.setRemoteCount(voList.size());
        result.setLocalCount(localCodes.size());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VoiceSyncResultVO applySelectedSync(com.aid.voice.dto.VoiceSyncApplyRequest request, String operator)
    {
        if (Objects.isNull(request) || Objects.isNull(request.getModelId()))
        {
            throw new RuntimeException("模型无效");
        }
        Long modelId = request.getModelId();
        AidAiModel model = aidAiModelService.getById(modelId);
        if (Objects.isNull(model) || !Objects.equals(DEL_FLAG_NORMAL, model.getDelFlag()))
        {
            throw new RuntimeException("模型不存在");
        }
        AiModelConfigVo config = aiModelConfigService.selectByModelId(modelId);
        if (Objects.isNull(config))
        {
            throw new RuntimeException("鉴权缺失");
        }
        if (!isMinimax(config))
        {
            throw new RuntimeException("暂不支持");
        }

        long startTs = System.currentTimeMillis();
        Date now = DateUtils.getNowDate();
        String by = StrUtil.isNotBlank(operator) ? operator : "system";
        int inserted = 0;

        // 本地已有
        LambdaQueryWrapper<AidAiVoiceLibrary> localWrapper = Wrappers.lambdaQuery();
        localWrapper.eq(AidAiVoiceLibrary::getModelId, modelId);
        List<AidAiVoiceLibrary> localList = voiceLibraryService.list(localWrapper);
        Map<String, AidAiVoiceLibrary> localIndex = new HashMap<>(localList.size());
        for (AidAiVoiceLibrary v : localList)
        {
            if (StrUtil.isNotBlank(v.getVoiceCode()))
            {
                localIndex.put(v.getVoiceCode(), v);
            }
        }

        List<String> selected = request.getSelectedVoiceCodes();
        if (CollectionUtil.isNotEmpty(selected))
        {
            // 拉远程拿到 name/description（用于新增时填充）
            List<RemoteVoice> remoteList = fetchMinimaxVoices(config);
            Map<String, RemoteVoice> remoteIndex = new HashMap<>();
            if (CollectionUtil.isNotEmpty(remoteList))
            {
                for (RemoteVoice rv : remoteList)
                {
                    if (StrUtil.isNotBlank(rv.voiceCode))
                    {
                        remoteIndex.put(rv.voiceCode, rv);
                    }
                }
            }

            for (String code : selected)
            {
                if (StrUtil.isBlank(code))
                {
                    continue;
                }
                String trimmed = code.trim();
                AidAiVoiceLibrary local = localIndex.get(trimmed);
                if (Objects.isNull(local))
                {
                    // 纯新增：填充 MiniMax 模型通用默认值
                    RemoteVoice rv = remoteIndex.get(trimmed);
                    AidAiVoiceLibrary ent = new AidAiVoiceLibrary();
                    ent.setProviderId(config.getProviderId());
                    ent.setModelId(modelId);
                    ent.setVoiceCode(trimmed);
                    ent.setVoiceName(rv != null && StrUtil.isNotBlank(rv.voiceName) ? rv.voiceName : trimmed);
                    ent.setRemark(rv != null ? rv.description : null);
                    ent.setSupportsSpeed(Boolean.TRUE);
                    ent.setSupportsPitch(Boolean.TRUE);
                    ent.setSupportsEmotion(Boolean.TRUE);
                    ent.setDefaultSpeed(new java.math.BigDecimal("1.0"));
                    ent.setDefaultPitch(new java.math.BigDecimal("0"));
                    ent.setSampleRate(32000);
                    ent.setAudioFormat("mp3");
                    ent.setOfflineTime(OFFLINE_NEVER);
                    ent.setStatus(STATUS_NORMAL);
                    ent.setDelFlag(DEL_FLAG_NORMAL);
                    ent.setSortOrder(0);
                    ent.setCreateBy(by);
                    ent.setCreateTime(now);
                    ent.setUpdateBy(by);
                    ent.setUpdateTime(now);
                    voiceLibraryService.save(ent);
                    inserted++;
                }
                else
                {
                    // 已存在：补充能力默认值（仅当本地值为 null/false/0 时才填，不覆盖已有配置）
                    boolean needUpdate = false;
                    AidAiVoiceLibrary patch = new AidAiVoiceLibrary();
                    patch.setId(local.getId());
                    if (!Boolean.TRUE.equals(local.getSupportsSpeed()))
                    {
                        patch.setSupportsSpeed(Boolean.TRUE);
                        needUpdate = true;
                    }
                    if (!Boolean.TRUE.equals(local.getSupportsPitch()))
                    {
                        patch.setSupportsPitch(Boolean.TRUE);
                        needUpdate = true;
                    }
                    if (!Boolean.TRUE.equals(local.getSupportsEmotion()))
                    {
                        patch.setSupportsEmotion(Boolean.TRUE);
                        needUpdate = true;
                    }
                    if (Objects.isNull(local.getDefaultSpeed()) || local.getDefaultSpeed().compareTo(java.math.BigDecimal.ZERO) == 0)
                    {
                        patch.setDefaultSpeed(new java.math.BigDecimal("1.0"));
                        needUpdate = true;
                    }
                    if (Objects.isNull(local.getSampleRate()) || local.getSampleRate() == 0)
                    {
                        patch.setSampleRate(32000);
                        needUpdate = true;
                    }
                    if (StrUtil.isBlank(local.getAudioFormat()))
                    {
                        patch.setAudioFormat("mp3");
                        needUpdate = true;
                    }
                    if (needUpdate)
                    {
                        patch.setUpdateBy(by);
                        patch.setUpdateTime(now);
                        voiceLibraryService.updateById(patch);
                    }
                }
            }
        }

        List<String> removed = request.getRemovedVoiceCodes();
        int deleted = 0;
        if (CollectionUtil.isNotEmpty(removed))
        {
            for (String code : removed)
            {
                if (StrUtil.isBlank(code))
                {
                    continue;
                }
                AidAiVoiceLibrary local = localIndex.get(code.trim());
                if (Objects.nonNull(local))
                {
                    voiceLibraryService.removeById(local.getId());
                    deleted++;
                }
            }
        }

        VoiceSyncResultVO vo = new VoiceSyncResultVO();
        vo.setModelId(modelId);
        vo.setModelCode(model.getModelCode());
        vo.setInserted(inserted);
        vo.setSoftDeleted(deleted);
        vo.setCostMs(System.currentTimeMillis() - startTs);
        vo.setSyncTime(now);
        log.info("音色选择同步完成: modelId={}, inserted={}, deleted={}, cost={}ms",
                modelId, inserted, deleted, vo.getCostMs());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredVoices(String operator)
    {
        Date now = DateUtils.getNowDate();

        LambdaQueryWrapper<AidAiVoiceLibrary> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidAiVoiceLibrary::getId);
        wrapper.le(AidAiVoiceLibrary::getOfflineTime, now);
        List<AidAiVoiceLibrary> expired = voiceLibraryService.list(wrapper);
        if (CollectionUtil.isEmpty(expired))
        {
            log.info("cleanExpiredVoices 无过期音色");
            return 0;
        }
        // 物理删除
        List<Long> ids = new ArrayList<>();
        for (AidAiVoiceLibrary v : expired)
        {
            ids.add(v.getId());
        }
        voiceLibraryService.removeByIds(ids);
        log.info("cleanExpiredVoices 物理删除过期音色: count={}", ids.size());
        return ids.size();
    }
}

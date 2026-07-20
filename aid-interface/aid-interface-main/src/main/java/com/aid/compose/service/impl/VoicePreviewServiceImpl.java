package com.aid.compose.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.aid.common.core.redis.RedisCache;
import com.aid.common.utils.SecurityUtils;
import com.aid.compose.dto.VoicePreviewRequest;
import com.aid.compose.dto.VoicePreviewResult;
import com.aid.compose.service.VoicePreviewService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.service.IAiModelConfigService;
import com.aid.voice.service.VoicePreviewLimitService;
import com.aid.voice.util.VoiceEmotionCapability;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 试听服务实现（同步、免费、不落库、不进 aid_media_task）。
 * minimax 提交后短轮询即返回；豆包提交后短轮询（有上限）。两条路径均不写任何业务表/流水。
 * 试听文本最大字数 = aid_config（voice / voice_preview_max_seconds）秒数 × 4.5 字/秒，配置缺失走 15 字兜底。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoicePreviewServiceImpl implements VoicePreviewService {

    /** 短轮询最大次数（有上限，避免阻塞请求线程过久） */
    private static final int MAX_POLL_TIMES = 15;

    /** 短轮询间隔（毫秒） */
    private static final long POLL_INTERVAL_MS = 1000L;

    /** 试听频控 key 前缀（用户级滑动窗口计数） */
    private static final String PREVIEW_RATE_KEY_PREFIX = "voice:preview:rate:";

    /** 试听频控窗口（秒） */
    private static final long PREVIEW_RATE_WINDOW_SECONDS = 60L;

    /** 试听频控窗口内上限次数：免费同步调用消耗上游 TTS 费用，防脚本无限刷 */
    private static final int PREVIEW_RATE_MAX_PER_WINDOW = 10;

    /** 模型配置服务 */
    private final IAiModelConfigService aiModelConfigService;

    /** 试听限制服务：统一秒数配置与最大字数换算口径 */
    private final VoicePreviewLimitService voicePreviewLimitService;

    /** 音频（TTS）provider 列表 */
    private final List<AudioProviderClient> audioProviderClients;

    /** Redis 缓存：试听用户级频控计数 */
    private final RedisCache redisCache;

    @Override
    public VoicePreviewResult preview(VoicePreviewRequest request) {
        if (Objects.isNull(request) || StrUtil.isBlank(request.getText())) {
            log.info("试听入参为空");
            throw new RuntimeException("参数有误");
        }
        // 用户级频控：免费试听消耗平台 TTS 费用，滑动窗口限次防刷
        assertPreviewRateAllowed();
        String text = request.getText().trim();
        // 最大字数由 aid_config 试听秒数折算（秒数 × 4.5 字/秒），运营可配；配置缺失/非法走兜底
        int maxTextLength = voicePreviewLimitService.getLimit().estimatedMaxChars();
        if (text.length() > maxTextLength) {
            log.info("试听文本超长, textLen={}, max={}", text.length(), maxTextLength);
            throw new RuntimeException("字数超限");
        }
        AiModelConfigVo modelConfig = resolveModel(request.getVoiceModelId());
        AudioProviderClient client = resolveAudioClient(modelConfig);

        MediaAudioGenerateRequest req = new MediaAudioGenerateRequest();
        req.setModelName(modelConfig.getModelCode());
        req.setTtsText(text);
        req.setVoiceCode(request.getTimbreCode());
        // 情感透传：编码校验通过后随请求下发，试听效果与正式配音同一口径
        applyEmotion(req, request, modelConfig);
        // 试听模式：provider 同步合成后直接回 base64、不上传对象存储（试听不落库）
        req.setPreviewMode(true);

        ProviderSubmitResult submitResult = client.submit(modelConfig, req);
        if (Objects.isNull(submitResult)) {
            log.error("试听提交失败, 无响应, modelCode={}", modelConfig.getModelCode());
            throw new RuntimeException("试听失败");
        }
        if (StrUtil.isNotBlank(submitResult.getAudioBase64())) {
            return base64Result(submitResult.getAudioBase64(), submitResult.getAudioDurationMs());
        }
        if (StrUtil.isNotBlank(submitResult.getDirectUrl())) {
            return urlResult(submitResult.getDirectUrl());
        }
        if (StrUtil.isNotBlank(submitResult.getOssUrl())) {
            return urlResult(submitResult.getOssUrl());
        }
        String providerTaskId = submitResult.getProviderTaskId();
        if (StrUtil.isBlank(providerTaskId)) {
            log.error("试听提交未返回 providerTaskId, modelCode={}", modelConfig.getModelCode());
            throw new RuntimeException("试听失败");
        }
        return pollResult(client, modelConfig, providerTaskId);
    }

    /**
     * 情感入参校验与透传（与单条配音同一口径）。
     * 以模型 {@code capability_json.emotions} 供应商声明为唯一标准：白名单非空且未命中直接拒绝；
     * 白名单缺失视为"供应商未声明能力"不拦截。情感强度仅在情感生效时随请求透传。
     *
     * @param req         下发 provider 的合成请求
     * @param request     试听入参
     * @param modelConfig 模型配置
     */
    private void applyEmotion(MediaAudioGenerateRequest req, VoicePreviewRequest request,
                              AiModelConfigVo modelConfig) {
        String emotion = StrUtil.trimToEmpty(request.getEmotion());
        if (StrUtil.isBlank(emotion)) {
            return;
        }
        List<String> supportedEmotions = VoiceEmotionCapability.parseModelEmotions(modelConfig.getCapabilityJson());
        if (CollectionUtil.isNotEmpty(supportedEmotions) && !supportedEmotions.contains(emotion)) {
            log.info("试听情感不在模型白名单, modelCode={}, emotion={}, supported={}",
                    modelConfig.getModelCode(), emotion, supportedEmotions);
            throw new RuntimeException("情感不支持");
        }
        req.setEmotion(emotion);
        req.setEmotionScale(request.getEmotionScale());
    }

    /**
     * 试听用户级频控：窗口内计数自增（首个请求设置过期），超限拒绝。
     * Redis / 登录上下文异常时放行（频控为增强防护，不阻断主功能）。
     */
    private void assertPreviewRateAllowed() {
        Long count = null;
        Long userId = null;
        try {
            userId = SecurityUtils.getUserId();
            String key = PREVIEW_RATE_KEY_PREFIX + userId;
            count = redisCache.redisTemplate.opsForValue().increment(key);
            if (Objects.nonNull(count) && count == 1L) {
                redisCache.expire(key, PREVIEW_RATE_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("试听频控判定异常，放行本次请求, msg={}", e.getMessage());
            return;
        }
        if (Objects.nonNull(count) && count > PREVIEW_RATE_MAX_PER_WINDOW) {
            log.info("试听频控拦截, userId={}, count={}, max={}", userId, count, PREVIEW_RATE_MAX_PER_WINDOW);
            throw new RuntimeException("试听过于频繁");
        }
    }

    /**
     * 短轮询查询试听结果（有上限）。
     *
     * @param client         音频 provider
     * @param modelConfig    模型配置
     * @param providerTaskId 上游任务ID
     * @return 试听结果
     */
    private VoicePreviewResult pollResult(AudioProviderClient client, AiModelConfigVo modelConfig,
                                          String providerTaskId) {
        for (int i = 0; i < MAX_POLL_TIMES; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("试听轮询被中断, providerTaskId={}", providerTaskId);
                throw new RuntimeException("试听失败");
            }
            ProviderTaskResult result = client.query(modelConfig, providerTaskId);
            if (Objects.isNull(result)) {
                continue;
            }
            if (MediaTaskStatus.SUCCEEDED.name().equals(result.getStatus())
                    && StrUtil.isNotBlank(result.getResultUrl())) {
                return urlResult(result.getResultUrl());
            }
            if (MediaTaskStatus.FAILED.name().equals(result.getStatus())) {
                log.info("试听上游失败, providerTaskId={}, err={}", providerTaskId, result.getErrorMessage());
                throw new RuntimeException("试听失败");
            }
        }
        log.info("试听轮询超时, providerTaskId={}", providerTaskId);
        throw new RuntimeException("试听超时");
    }

    /**
     * 构建 URL 结果。
     *
     * @param url 音频 URL
     * @return 试听结果
     */
    private VoicePreviewResult urlResult(String url) {
        VoicePreviewResult result = new VoicePreviewResult();
        result.setAudioUrl(url);
        return result;
    }

    /**
     * 构建 base64 结果（试听不落库直出）。
     *
     * @param audioBase64 音频 base64
     * @param durationMs  音频时长（毫秒，厂商未返回时为 null）
     * @return 试听结果
     */
    private VoicePreviewResult base64Result(String audioBase64, Long durationMs) {
        VoicePreviewResult result = new VoicePreviewResult();
        result.setAudioBase64(audioBase64);
        // 试听时长回传：前端可据此展示「准确的语调时长」（MiniMax 有值，豆包暂无返回为 null）
        if (Objects.nonNull(durationMs) && durationMs > 0) {
            result.setDurationMs(durationMs.intValue());
        }
        return result;
    }

    /**
     * 解析 TTS 模型配置。
     *
     * @param voiceModelId 模型ID
     * @return 模型配置
     */
    private AiModelConfigVo resolveModel(Long voiceModelId) {
        if (Objects.isNull(voiceModelId)) {
            log.info("试听模型ID为空");
            throw new RuntimeException("参数有误");
        }
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelId(voiceModelId);
        if (Objects.isNull(modelConfig) || StrUtil.isBlank(modelConfig.getModelCode())) {
            log.error("试听模型不存在, voiceModelId={}", voiceModelId);
            throw new RuntimeException("模型异常");
        }
        return modelConfig;
    }

    /**
     * 解析音频 provider：优先 providerCode 强路由，其次 modelCode 弱匹配。
     *
     * @param modelConfig 模型配置
     * @return 音频 provider
     */
    private AudioProviderClient resolveAudioClient(AiModelConfigVo modelConfig) {
        String providerCode = modelConfig.getProviderCode();
        if (StrUtil.isNotBlank(providerCode)) {
            List<AudioProviderClient> byCode = audioProviderClients.stream()
                    .filter(it -> it.supportsProviderCode(providerCode))
                    .toList();
            if (byCode.size() == 1) {
                return byCode.get(0);
            }
        }
        List<AudioProviderClient> byModel = audioProviderClients.stream()
                .filter(it -> it.supportsModel(modelConfig.getModelCode()))
                .toList();
        if (byModel.size() == 1) {
            return byModel.get(0);
        }
        log.error("试听未命中唯一音频 provider, providerCode={}, modelCode={}",
                providerCode, modelConfig.getModelCode());
        throw new RuntimeException("模型异常");
    }
}

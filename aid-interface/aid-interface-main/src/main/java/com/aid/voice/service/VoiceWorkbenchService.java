package com.aid.voice.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.PreparedMediaBillingInput;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaBillingQuotePreparer;
import com.aid.media.util.TrustedMediaProbe;
import com.aid.service.IAiModelConfigService;
import com.aid.tokendance.provider.common.TokenDanceTransport;
import com.aid.voice.dto.VoiceLibraryUpsertRequest;
import com.aid.voice.dto.VoiceWorkbenchPublishRequest;
import com.aid.voice.dto.VoiceWorkbenchRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 后台音色制作沿用统一媒体任务、费用快照和音色库。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceWorkbenchService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PREFIX = "aid:voice-workbench:";
    private static final String CLONE = "tokendance:minimax:voice_clone";
    private static final String MIMO_PROTOCOL = "tokendance:openai:chat-completions";
    private static final String MIMO_CLONE = "mimo-v2.5-tts-voiceclone";
    private static final String MIMO_DESIGN = "mimo-v2.5-tts-voicedesign";
    private static final Set<String> TTS = Set.of("tokendance:minimax:t2a_v2", "tokendance:ark:tts",
            "tokendance:minimax:t2a_v2_ws", "tokendance:ark:tts_ws");
    private static final int MAX_SAMPLE_BYTES = 20 * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final IAidAiModelService models;
    private final IAidAiProviderService providers;
    private final IAiModelConfigService configs;
    private final MediaBillingQuotePreparer preparer;
    private final BillingPreHoldCalculationService calculation;
    private final IMediaGenerationService media;
    private final AidMediaTaskMapper tasks;
    private final StringRedisTemplate redis;
    private final TokenDanceTransport transport;
    private final TrustedMediaProbe probe;
    private final IAidAiVoiceLibraryService voices;
    private final IVoiceLibraryBusinessService voiceBusiness;
    private final VoiceReferenceSampleService referenceSamples;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelDefinitionService modelDefinitions;

    public Map<String, Object> capabilities(Long modelId) {
        AidAiModel model = requireModel(modelId, false);
        AidAiModel synthesis = modelDefinitions.project(model, "audio");
        AidAiModel registration = modelDefinitions.project(model, "voice_clone");
        String protocol = StrUtil.blankToDefault(synthesis == null ? model.getProtocol() : synthesis.getProtocol(), "");
        boolean mimo = MIMO_PROTOCOL.equals(protocol);
        List<Map<String, Object>> operations = List.of(
                operation("SYNTHESIZE", TTS.contains(protocol) || mimo && "mimo-v2.5-tts".equals(model.getRealModelCode()), 9999, false, mimo),
                operation("REGISTER_CLONE", registration != null && CLONE.equals(registration.getProtocol()) || CLONE.equals(protocol), 1000, true, false),
                operation("REFERENCE_CLONE", mimo && MIMO_CLONE.equals(model.getRealModelCode()), 9999, true, true),
                operation("DESIGN", mimo && MIMO_DESIGN.equals(model.getRealModelCode()), 9999, false, true));
        String targetRealModel = mimo && (MIMO_DESIGN.equals(model.getRealModelCode()) || MIMO_CLONE.equals(model.getRealModelCode()))
                ? MIMO_CLONE : model.getRealModelCode();
        Set<String> targetProtocols = mimo ? Set.of(MIMO_PROTOCOL) : TTS;
        List<Long> publishTargets = models.list(Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getProviderId, model.getProviderId())
                .eq(AidAiModel::getRealModelCode, targetRealModel)
                .eq(AidAiModel::getModelType, "AUDIO").eq(AidAiModel::getStatus, "0").eq(AidAiModel::getDelFlag, "0"))
                .stream().map(candidate -> modelDefinitions.definitions(candidate.getId()).isEmpty() ? candidate : modelDefinitions.project(candidate, "audio"))
                .filter(candidate -> candidate != null && targetProtocols.contains(candidate.getProtocol()))
                .map(AidAiModel::getId).toList();
        operations.forEach(item -> item.put("publishTargetModelIds", Boolean.TRUE.equals(item.get("canPublish")) ? publishTargets : List.of()));
        for (Map<String, Object> item : operations) {
            if (!Boolean.TRUE.equals(item.get("supported")) || !"SYNTHESIZE".equals(item.get("operation"))) continue;
            if (protocol.startsWith("tokendance:ark:")) {
                item.put("audioFormats", List.of("mp3", "wav", "pcm", "ogg_opus"));
                item.put("sampleRates", List.of(8000, 16000, 22050, 24000, 32000, 44100, 48000));
                item.put("optionalFields", List.of("audioFormat", "sampleRate", "speechRate", "loudnessRate", "pitch"));
            } else if (protocol.startsWith("tokendance:minimax:")) {
                item.put("audioFormats", protocol.endsWith("_ws") ? List.of("mp3", "pcm", "flac") : List.of("mp3", "wav", "flac"));
                item.put("sampleRates", List.of(8000, 16000, 22050, 24000, 32000, 44100));
            }
        }
        return Map.of("modelId", modelId, "providerId", model.getProviderId(), "realModelCode", StrUtil.blankToDefault(model.getRealModelCode(), ""),
                "payer", "SITE_OWNER", "operations", operations,
                "notice", "费用由站长在供应商账户承担，不扣网站用户积分");
    }

    public Map<String, Object> quote(VoiceWorkbenchRequest request, Long adminId) {
        AidAiModel model = requireModel(request.getModelId(), true);
        MediaAudioGenerateRequest audio = audio(request, model, adminId);
        PreparedMediaBillingInput prepared = preparer.prepareAudioBilling(audio);
        var result = calculation.calculate(prepared.modelConfig(), prepared.billingInput());
        if (!result.isMatched() || result.getSnapshot() == null || result.getSnapshot().getBaseAmount() == null) {
            return Map.of("pricingStatus", "MISSING", "payer", "SITE_OWNER", "currency", "CNY", "breakdown", List.of());
        }
        BigDecimal cost = result.getSnapshot().getBaseAmount();
        String ref = UUID.randomUUID().toString();
        ObjectNode saved = JSON.createObjectNode();
        saved.put("adminId", adminId);
        saved.put("modelId", model.getId());
        saved.put("configVersion", model.getConfigVersion() == null ? 0 : model.getConfigVersion());
        saved.put("configHash", configurationHash(prepared.modelConfig()));
        saved.put("requestHash", hash(audio));
        saved.put("cost", cost);
        redis.opsForValue().set(PREFIX + "quote:" + ref, saved.toString(), Duration.ofMinutes(10));
        return Map.of("quoteRef", ref, "pricingStatus", cost.signum() == 0 ? "FREE" : "READY", "currency", "CNY",
                "totalCost", cost, "payer", "SITE_OWNER", "expiresAt", LocalDateTime.now().plusMinutes(10).format(TIME),
                "breakdown", List.of(Map.of("label", "模型调用成本", "amount", cost)));
    }

    public Map<String, Object> create(VoiceWorkbenchRequest request, Long adminId) {
        requireAdmin(adminId);
        if (!request.isChargeConfirmed() || !request.isRightsConfirmed()) throw new ServiceException("请确认授权与费用");
        if (request.getIdempotencyKey() == null || !request.getIdempotencyKey().matches("[A-Za-z0-9_-]{16,128}")) {
            throw new ServiceException("请求标识无效");
        }
        String actionKey = PREFIX + "create:" + adminId + ":" + request.getIdempotencyKey();
        String fingerprint = hashRequest(request);
        String prior = redis.opsForValue().get(actionKey);
        if (prior != null) return existingCreate(prior, fingerprint, adminId);
        AidAiModel model = requireModel(request.getModelId(), true);
        MediaAudioGenerateRequest audio = audio(request, model, adminId);
        PreparedMediaBillingInput prepared = preparer.prepareAudioBilling(audio);
        JsonNode quote = read(redis.opsForValue().get(PREFIX + "quote:" + StrUtil.blankToDefault(request.getQuoteRef(), "")));
        if (quote.path("adminId").asLong() != adminId || quote.path("modelId").asLong() != model.getId()
                || quote.path("configVersion").asLong(-1) != (model.getConfigVersion() == null ? 0 : model.getConfigVersion())
                || !hash(audio).equals(quote.path("requestHash").asText())) throw new ServiceException("请重新确认报价");
        if (!configurationHash(prepared.modelConfig()).equals(quote.path("configHash").asText())) throw new ServiceException("配置已变请重报价");
        audio.setExpectedModelConfigurationHash(quote.path("configHash").asText());
        // 报价和幂等标识均只能消费一次；未知结果保持 STARTED，不重新提交注册请求。
        ObjectNode state = JSON.createObjectNode().put("fingerprint", fingerprint).put("state", "STARTED");
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(actionKey, state.toString(), Duration.ofDays(7)))) {
            return existingCreate(redis.opsForValue().get(actionKey), fingerprint, adminId);
        }
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + "consumed:" + request.getQuoteRef(), actionKey, Duration.ofDays(7)))) {
            throw new ServiceException("报价已使用");
        }
        MediaTaskResponse task = media.generateOperatorAudio(audio, adminId);
        state.put("state", "CREATED").put("taskId", task.getTaskId());
        redis.opsForValue().set(actionKey, state.toString(), Duration.ofDays(7));
        return task(task.getTaskId(), adminId);
    }

    public Map<String, Object> task(Long taskId, Long adminId) {
        AidMediaTask task = requireTask(taskId, adminId);
        JsonNode input = read(task.getRequestJson());
        JsonNode billing = read(task.getBillingSnapshotJson());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        String realModel = read(task.getProviderRouteSnapshotJson()).path("realModelCode").asText();
        result.put("operation", CLONE.equals(task.getProtocol()) ? "REGISTER_CLONE"
                : MIMO_DESIGN.equals(realModel) ? "DESIGN" : MIMO_CLONE.equals(realModel) ? "REFERENCE_CLONE" : "SYNTHESIZE");
        result.put("status", task.getStatus());
        result.put("progress", "SUCCEEDED".equals(task.getStatus()) ? 100 : 0);
        result.put("result", Map.of("voiceCode", input.path("voiceCode").asText(), "audioUrl", StrUtil.blankToDefault(task.getOssUrl(), "")));
        result.put("quotedCost", billing.path("providerEstimatedCostCny").isNumber() ? billing.path("providerEstimatedCostCny").decimalValue() : null);
        result.put("finalCost", billing.path("providerActualCostCny").isNumber() ? billing.path("providerActualCostCny").decimalValue() : null);
        result.put("failureReason", StrUtil.blankToDefault(task.getErrorMessage(), ""));
        result.put("payer", "SITE_OWNER");
        return result;
    }

    public Map<String, Object> upload(Long modelId, MultipartFile file, boolean rightsConfirmed, Long adminId) {
        requireAdmin(adminId);
        AidAiModel model = requireModel(modelId, true);
        if (!modelDefinitions.definitions(model.getId()).isEmpty()) model = operationModel(model,
                MIMO_PROTOCOL.equals(model.getProtocol()) ? "REFERENCE_CLONE" : "REGISTER_CLONE");
        boolean mimo = MIMO_PROTOCOL.equals(model.getProtocol()) && MIMO_CLONE.equals(model.getRealModelCode());
        if (!rightsConfirmed || !CLONE.equals(model.getProtocol()) && !mimo) throw new ServiceException("请确认样本授权");
        if (file == null || file.isEmpty() || file.getSize() > MAX_SAMPLE_BYTES) throw new ServiceException("样本大小超限");
        if (mimo && file.getSize() > VoiceReferenceSampleService.MAX_SAMPLE_BYTES) throw new ServiceException("样本编码大小超限");
        try {
            byte[] bytes = file.getBytes();
            TrustedMediaProbe.Metadata metadata = probe.audio(bytes);
            String format = metadata.format();
            String extension = format.contains("mp3") ? "mp3" : format.equals("wav") ? "wav" : format.contains("mov") ? "m4a" : null;
            if (extension == null || mimo && "m4a".equals(extension)
                    || !mimo && (metadata.durationMs() < 10000 || metadata.durationMs() > 300000
                    || metadata.channels() != 1 || metadata.sampleRate() < 16000)) throw new ServiceException("样本规格不支持");
            AiModelConfigVo config = configs.selectByModelId(modelId);
            if (mimo) {
                String storedUrl = com.aid.common.oss.factory.OssFactory.instance().uploadSuffix(bytes, "." + extension, "audio/" + extension).getUrl();
                String ref = UUID.randomUUID().toString();
                ObjectNode stored = JSON.createObjectNode().put("adminId", adminId).put("modelId", modelId)
                        .put("credentialVersion", config.getCredentialVersion()).put("sampleUrl", storedUrl);
                redis.opsForValue().set(PREFIX + "sample:" + ref, stored.toString(), Duration.ofHours(1));
                return Map.of("fileId", ref, "name", "sample." + extension, "mime", "audio/" + extension,
                        "sizeBytes", metadata.sizeBytes(), "durationMs", metadata.durationMs(), "sampleRate", metadata.sampleRate(),
                        "channels", metadata.channels(), "verified", true);
            }
            String boundary = "AidVoice" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nvoice_clone\r\n--"
                    + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample." + extension
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            var response = transport.exchangeMultipart(config, "/gateway/minimax/v1/files/upload", body.toByteArray(), boundary);
            JsonNode uploaded = read(response.bodyUtf8());
            String upstream = uploaded.path("file").path("file_id").asText();
            if (!response.isSuccessful() || uploaded.path("base_resp").path("status_code").asInt(-1) != 0
                    || !upstream.matches("[A-Za-z0-9_-]{1,128}")) throw new ServiceException("样本上传失败");
            String ref = UUID.randomUUID().toString();
            ObjectNode stored = JSON.createObjectNode().put("adminId", adminId).put("modelId", modelId)
                    .put("credentialVersion", config.getCredentialVersion()).put("upstreamFileId", upstream);
            redis.opsForValue().set(PREFIX + "sample:" + ref, stored.toString(), Duration.ofHours(1));
            return Map.of("fileId", ref, "name", "sample." + extension, "mime", "audio/" + extension,
                    "sizeBytes", metadata.sizeBytes(), "durationMs", metadata.durationMs(), "sampleRate", metadata.sampleRate(),
                    "channels", metadata.channels(), "verified", true);
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) {
            log.info("后台音色样本上传失败: modelId={}, cause={}", modelId, ex.getClass().getSimpleName());
            throw new ServiceException("样本上传失败");
        }
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(VoiceWorkbenchPublishRequest request, Long adminId) {
        AidMediaTask task = requireTask(request.getTaskId(), adminId);
        if (!"SUCCEEDED".equals(task.getStatus()) || StrUtil.isBlank(task.getOssUrl())) throw new ServiceException("请等待样本持久化");
        if (!Set.of("DRAFT", "PUBLISHED").contains(request.getPublishStatus())) throw new ServiceException("发布状态无效");
        JsonNode route = read(task.getProviderRouteSnapshotJson());
        if (!route.hasNonNull("providerId") || !route.hasNonNull("realModelCode") || !route.hasNonNull("protocol")) throw new ServiceException("任务路由不可用");
        AidAiModel source = new AidAiModel();
        source.setProviderId(route.path("providerId").longValue());
        source.setRealModelCode(route.path("realModelCode").asText());
        source.setProtocol(route.path("protocol").asText());
        boolean referenceResult = MIMO_PROTOCOL.equals(source.getProtocol())
                && (MIMO_DESIGN.equals(source.getRealModelCode()) || MIMO_CLONE.equals(source.getRealModelCode()));
        if (!referenceResult && !CLONE.equals(source.getProtocol())) throw new ServiceException("配音试听不能发布为新音色");
        String targetRealModel = referenceResult ? MIMO_CLONE : source.getRealModelCode();
        Set<String> targetProtocols = MIMO_PROTOCOL.equals(source.getProtocol()) ? Set.of(MIMO_PROTOCOL) : TTS;
        if (referenceResult) {
            var sample = referenceSamples.fetch(task.getOssUrl());
            var metadata = probe.audio(sample.bytes());
            if (!Set.of("wav", "mp3").contains(metadata.format())) throw new ServiceException("请使用可复用格式");
        }
        AidAiModel target;
        if (request.getTargetModelId() != null) target = requireModel(request.getTargetModelId(), true);
        else {
            List<AidAiModel> candidates = models.list(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getProviderId, source.getProviderId())
                    .eq(AidAiModel::getRealModelCode, targetRealModel).in(AidAiModel::getProtocol, targetProtocols)
                    .eq(AidAiModel::getStatus, "0").eq(AidAiModel::getDelFlag, "0"));
            if (candidates.size() != 1) throw new ServiceException("请选择配音模型");
            target = candidates.get(0);
        }
        if (!targetProtocols.contains(target.getProtocol()) || !Objects.equals(target.getProviderId(), source.getProviderId())
                || !Objects.equals(target.getRealModelCode(), targetRealModel)) throw new ServiceException("音色模型不匹配");
        target = models.getOne(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getId, target.getId()).last("FOR UPDATE"), false);
        if (target == null || !"0".equals(target.getStatus()) || !"0".equals(target.getDelFlag())
                || !"audio".equalsIgnoreCase(target.getModelType()) || !targetProtocols.contains(target.getProtocol())
                || !Objects.equals(target.getProviderId(), source.getProviderId())
                || !Objects.equals(target.getRealModelCode(), targetRealModel)) throw new ServiceException("音色模型配置已变化");
        JsonNode input = read(task.getRequestJson());
        String voiceCode = input.path("voiceCode").asText();
        AidAiVoiceLibrary existing = voices.getOne(Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                .eq(AidAiVoiceLibrary::getModelId, target.getId()).eq(AidAiVoiceLibrary::getVoiceCode, voiceCode).last("limit 1"), false);
        if (existing != null && (!Objects.equals(existing.getSampleUrl(), task.getOssUrl()) || !"0".equals(existing.getDelFlag()))) {
            throw new ServiceException("音色编码已存在");
        }
        VoiceLibraryUpsertRequest upsert = new VoiceLibraryUpsertRequest();
        upsert.setModelId(target.getId());
        upsert.setVoiceCode(voiceCode);
        upsert.setVoiceName(request.getName());
        upsert.setSampleUrl(task.getOssUrl());
        upsert.setSampleText(input.path("ttsText").asText().length() <= 500 ? input.path("ttsText").asText() : null);
        upsert.setLanguage(request.getLanguage());
        upsert.setGender(request.getGender());
        upsert.setAgeRange(request.getAgeRange());
        upsert.setStatus("PUBLISHED".equals(request.getPublishStatus()) ? "0" : "1");
        upsert.setAudioFormat(input.path("audioFormat").asText("mp3"));
        Long id;
        if (existing == null) id = voiceBusiness.createVoice(upsert);
        else {
            AidAiVoiceLibrary update = new AidAiVoiceLibrary();
            update.setId(existing.getId());
            update.setVoiceName(request.getName());
            update.setLanguage(request.getLanguage());
            update.setGender(request.getGender());
            update.setAgeRange(request.getAgeRange());
            update.setStatus(upsert.getStatus());
            update.setUpdateBy(com.aid.common.utils.SecurityUtils.getUsername());
            update.setUpdateTime(new java.util.Date());
            voices.updateAidAiVoiceLibrary(update);
            id = existing.getId();
        }
        return Map.of("voiceId", id, "status", request.getPublishStatus());
    }

    private MediaAudioGenerateRequest audio(VoiceWorkbenchRequest request, AidAiModel model, Long adminId) {
        requireAdmin(adminId);
        model = operationModel(model, request.getOperation());
        if (!request.isRightsConfirmed()) throw new ServiceException("请确认音色授权");
        boolean clone = "REGISTER_CLONE".equals(request.getOperation()) && CLONE.equals(model.getProtocol());
        boolean mimo = MIMO_PROTOCOL.equals(model.getProtocol());
        boolean reference = "REFERENCE_CLONE".equals(request.getOperation()) && mimo && MIMO_CLONE.equals(model.getRealModelCode());
        boolean design = "DESIGN".equals(request.getOperation()) && mimo && MIMO_DESIGN.equals(model.getRealModelCode());
        boolean synth = "SYNTHESIZE".equals(request.getOperation()) && (TTS.contains(model.getProtocol()) || mimo && "mimo-v2.5-tts".equals(model.getRealModelCode()));
        if (!clone && !synth && !reference && !design) throw new ServiceException("音色操作未接通");
        if (!mimo && StrUtil.isNotBlank(request.getDescription()) || (synth || design) && StrUtil.isNotBlank(request.getSourceFileId())) throw new ServiceException("音色参数不支持");
        if (design && StrUtil.isBlank(request.getDescription())) throw new ServiceException("请填写音色描述");
        if (!design && Boolean.TRUE.equals(request.getOptimizeTextPreview())) throw new ServiceException("文本优化不支持");
        MediaAudioGenerateRequest audio = new MediaAudioGenerateRequest();
        audio.setModelName(model.getModelCode());
        audio.setCapabilityCode(model.getSelectedCapabilityCode());
        audio.setUserId(-adminId);
        audio.setTtsText(request.getText());
        audio.setVoiceCode(request.getVoiceCode());
        if ((design || reference) && StrUtil.isBlank(audio.getVoiceCode())) {
            audio.setVoiceCode("voice_" + SecureUtil.sha256(adminId + ":" + model.getId() + ":" + hashRequest(request)).substring(0, 24));
        }
        if (mimo && synth && StrUtil.isBlank(audio.getVoiceCode())) audio.setVoiceCode("mimo_default");
        audio.setAudioFormat(request.getAudioFormat());
        if (mimo && StrUtil.isBlank(audio.getAudioFormat())) audio.setAudioFormat("wav");
        audio.setSampleRate(request.getSampleRate());
        audio.setSpeechRate(request.getSpeechRate());
        audio.setLoudnessRate(request.getLoudnessRate());
        audio.setPitch(request.getPitch());
        audio.setEmotion(request.getEmotion());
        if (mimo && StrUtil.isNotBlank(request.getDescription())) audio.setOptions(new LinkedHashMap<>(Map.of("voiceInstruction", request.getDescription())));
        if (design && request.getOptimizeTextPreview() != null) {
            Map<String, Object> options = new LinkedHashMap<>(audio.getOptions() == null ? Map.of() : audio.getOptions());
            options.put("optimizeTextPreview", request.getOptimizeTextPreview());
            audio.setOptions(options);
        }
        if (clone || reference) {
            JsonNode sample = read(redis.opsForValue().get(PREFIX + "sample:" + StrUtil.blankToDefault(request.getSourceFileId(), "")));
            AiModelConfigVo config = configs.selectByModelId(model.getId());
            if (sample.path("adminId").asLong() != adminId || sample.path("modelId").asLong() != model.getId()
                    || sample.path("credentialVersion").asInt(-1) != config.getCredentialVersion()) throw new ServiceException("请重新上传样本");
            if (clone) audio.setOptions(Map.of("cloneFileId", sample.path("upstreamFileId").asText()));
            else audio.setTrustedReferenceSampleUrl(sample.path("sampleUrl").asText());
        }
        return audio;
    }

    private AidAiModel operationModel(AidAiModel model, String operation) {
        if (modelDefinitions.definitions(model.getId()).isEmpty()) return model;
        String capability = "REGISTER_CLONE".equals(operation) ? "voice_clone" : "audio";
        AidAiModel selected = modelDefinitions.project(model, capability);
        if (selected == null) throw new ServiceException("模型未启用此音色能力");
        return selected;
    }

    private AidAiModel requireModel(Long id, boolean enabled) {
        AidAiModel model = id == null ? null : models.getById(id);
        if (model == null || !"0".equals(model.getDelFlag()) || !"audio".equalsIgnoreCase(model.getModelType())
                || (enabled && !"0".equals(model.getStatus()))) throw new ServiceException("配音模型不可用");
        var provider = providers.getById(model.getProviderId());
        if (provider == null || !"tokendance".equalsIgnoreCase(provider.getProviderCode())) throw new ServiceException("音色协议尚未接通");
        return model;
    }

    private AidMediaTask requireTask(Long id, Long adminId) {
        requireAdmin(adminId);
        AidMediaTask task = id == null ? null : tasks.selectById(id);
        if (task == null || !Objects.equals(task.getUserId(), -adminId) || !"operator_voice".equals(task.getBizTaskType())
                || !"PROVIDER".equals(read(task.getBillingSnapshotJson()).path("payerType").asText())) throw new ServiceException("音色任务不存在");
        return task;
    }

    private Map<String, Object> existingCreate(String json, String fingerprint, Long adminId) {
        JsonNode state = read(json);
        if (!fingerprint.equals(state.path("fingerprint").asText())) throw new ServiceException("请求标识已被使用");
        if (state.hasNonNull("taskId")) return task(state.path("taskId").longValue(), adminId);
        throw new ServiceException("任务提交待确认");
    }

    private Map<String, Object> operation(String value, boolean enabled, int textLimit, boolean sample, boolean mimo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", value);
        result.put("supported", enabled);
        result.put("canPublish", enabled && !"SYNTHESIZE".equals(value));
        result.put("disabledReason", enabled ? "" : "当前协议尚未核验接通");
        result.put("requiredFields", "DESIGN".equals(value) ? List.of("description", "text") : "REFERENCE_CLONE".equals(value)
                ? List.of("text", "sourceFileId") : sample ? List.of("voiceCode", "text", "sourceFileId") : List.of("voiceCode", "text"));
        result.put("optionalFields", mimo ? List.of("description", "audioFormat") : sample ? List.of() : List.of("audioFormat", "sampleRate", "speechRate", "loudnessRate", "pitch", "emotion"));
        if ("DESIGN".equals(value)) result.put("optionalFields", List.of("audioFormat", "optimizeTextPreview"));
        result.put("textLimit", textLimit);
        result.put("sampleLimit", mimo ? Map.of("maxBytes", VoiceReferenceSampleService.MAX_SAMPLE_BYTES, "maxEncodedBytes", 10 * 1024 * 1024,
                "formats", List.of("mp3", "wav")) : Map.of("minDurationMs", 10000, "maxDurationMs", 300000,
                "maxBytes", MAX_SAMPLE_BYTES, "minSampleRate", 16000, "channels", 1, "formats", List.of("mp3", "wav", "m4a")));
        result.put("audioFormats", mimo ? List.of("wav", "mp3", "pcm") : sample ? List.of("mp3") : List.of("mp3", "wav"));
        result.put("sampleRates", mimo || sample ? List.of() : List.of(16000, 22050, 24000, 32000, 44100));
        return result;
    }

    private static void requireAdmin(Long id) { if (id == null || id <= 0) throw new ServiceException("管理员身份无效"); }
    private static JsonNode read(String json) {
        try { return StrUtil.isBlank(json) ? JSON.createObjectNode() : JSON.readTree(json); }
        catch (Exception ex) { throw new ServiceException("音色配置无效"); }
    }
    private static String hash(Object value) {
        try { return SecureUtil.sha256(JSON.writeValueAsString(value)); }
        catch (Exception ex) { throw new ServiceException("音色参数无效"); }
    }
    private static String hashRequest(VoiceWorkbenchRequest request) {
        ObjectNode value = JSON.valueToTree(request);
        value.remove(List.of("quoteRef", "chargeConfirmed", "idempotencyKey"));
        return hash(value);
    }
    private static String configurationHash(AiModelConfigVo config) {
        return com.aid.model.ModelConfigurationFingerprint.of(config);
    }
}

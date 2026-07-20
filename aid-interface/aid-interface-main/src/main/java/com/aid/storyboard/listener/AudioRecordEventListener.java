package com.aid.storyboard.listener;

import java.util.Date;
import java.util.Objects;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.domain.AidAudioAsset;
import com.aid.aid.domain.AidAudioRecord;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.aid.service.IAidAudioAssetService;
import com.aid.aid.service.IAidAudioRecordService;
import com.aid.common.utils.DateUtils;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 配音业务记录事件监听：把 aid_media_task 的终态回填到 aid_audio_record，并同步写入音频资产。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AudioRecordEventListener {

    /** 业务任务类型标识：与 StoryboardWorkbenchServiceImpl 提交 media_task 时的 bizTaskType 保持一致 */
    private static final String BIZ_TASK_TYPE_AUDIO = "audio_record";
    /** 失败短文案（≤6 字），与前端错误展示规范一致 */
    private static final String ERR_MSG_FAILED = "配音失败";
    /** 资产标题默认截断长度 */
    private static final int ASSET_TITLE_MAX_LEN = 80;
    /** 资产来源：AI 生成 */
    private static final int ASSET_SOURCE_AI = 1;

    @Resource
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Resource
    private AidMediaResultMapper aidMediaResultMapper;

    @Resource
    private IAidAudioRecordService aidAudioRecordService;

    @Resource
    private IAidAudioAssetService aidAudioAssetService;

    @Resource
    private IAidAiVoiceLibraryService aidAiVoiceLibraryService;

    /**
     * 媒体任务终态事件：
     *
     *   - SUCCEEDED 且 ossUrl 就绪 → 回填 audio_url + status=SUCCEEDED + 写资产
     *   - SUCCEEDED 但 ossUrl 未就绪 → 仅同步 ttsMediaTaskId，保持 PROCESSING 等 OSS 事件
     *   - FAILED → 回填 status=FAILED + 短文案（不写资产）
     *
     */
    @EventListener
    @Order(200)
    public void onMediaTaskCompleted(MediaTaskCompletedEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId())) {
            return;
        }
        AidMediaTask mediaTask = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isAudioBizTask(mediaTask)) {
            return;
        }

        AidAudioRecord audioRecord = aidAudioRecordService.getById(mediaTask.getBizTaskId());
        if (Objects.isNull(audioRecord)) {
            log.warn("AudioRecordEventListener 业务音频记录缺失, audioRecordId={}", mediaTask.getBizTaskId());
            return;
        }

        LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
        update.eq(AidAudioRecord::getId, audioRecord.getId());
        update.set(AidAudioRecord::getUpdateTime, DateUtils.getNowDate());
        update.set(AidAudioRecord::getTtsMediaTaskId, mediaTask.getId());
        applyDurationMs(update, mediaTask);

        if (MediaTaskStatus.SUCCEEDED.name().equals(mediaTask.getStatus())) {
            // 关键：仅当 ossUrl 就绪才推进业务终态，否则保持 PROCESSING，等待 MediaTaskOssPersistedEvent。
            if (StrUtil.isBlank(mediaTask.getOssUrl())) {
                log.info("AudioRecordEventListener 媒体任务 SUCCEEDED 但 ossUrl 未就绪, 保持 PROCESSING 等待 OSS 事件, audioRecordId={}, mediaTaskId={}",
                        audioRecord.getId(), mediaTask.getId());
                try {
                    aidAudioRecordService.update(update);
                } catch (Exception ex) {
                    log.error("AudioRecordEventListener 同步 ttsMediaTaskId 异常 audioRecordId={}", audioRecord.getId(), ex);
                }
                return;
            }
            // ossUrl 已就绪：使用 OSS URL 回填
            update.set(AidAudioRecord::getAudioUrl, mediaTask.getOssUrl());
            update.set(AidAudioRecord::getStatus, MediaTaskStatus.SUCCEEDED.name());
            update.set(AidAudioRecord::getErrorMessage, null);
            log.info("AudioRecordEventListener 回填成功(ossUrl) audioRecordId={}, len={}",
                    audioRecord.getId(), StrUtil.length(mediaTask.getOssUrl()));

            try {
                aidAudioRecordService.update(update);
            } catch (Exception ex) {
                log.error("AudioRecordEventListener 回填异常 audioRecordId={}", audioRecord.getId(), ex);
                return;
            }
            // 同步写入资产表（幂等）
            upsertAudioAsset(audioRecord, mediaTask);
            return;
        }

        if (MediaTaskStatus.FAILED.name().equals(mediaTask.getStatus())) {
            update.set(AidAudioRecord::getStatus, MediaTaskStatus.FAILED.name());
            update.set(AidAudioRecord::getErrorMessage, ERR_MSG_FAILED);
            log.info("AudioRecordEventListener 回填失败 audioRecordId={}, mediaStatus={}",
                    audioRecord.getId(), mediaTask.getStatus());
            try {
                aidAudioRecordService.update(update);
            } catch (Exception ex) {
                log.error("AudioRecordEventListener 回填异常 audioRecordId={}", audioRecord.getId(), ex);
            }
        }
        // 非终态不回填
    }

    /**
     * 媒体任务 OSS 持久化完成事件：
     * 把 audio_url 从空 / 临时值升级为 OSS URL，并推进业务 status=SUCCEEDED，
     * 同时向资产表幂等写入一条成功记录。
     */
    @EventListener
    @Order(200)
    public void onMediaTaskOssPersisted(MediaTaskOssPersistedEvent event) {
        if (Objects.isNull(event) || Objects.isNull(event.getTaskId())) {
            return;
        }
        AidMediaTask mediaTask = aidMediaTaskMapper.selectById(event.getTaskId());
        if (!isAudioBizTask(mediaTask)) {
            return;
        }
        if (StrUtil.isBlank(mediaTask.getOssUrl())) {
            log.warn("AudioRecordEventListener ossPersisted 事件 ossUrl 仍为空, mediaTaskId={}", mediaTask.getId());
            return;
        }
        AidAudioRecord audioRecord = aidAudioRecordService.getById(mediaTask.getBizTaskId());
        if (Objects.isNull(audioRecord)) {
            log.warn("AudioRecordEventListener ossPersisted 业务音频记录缺失, audioRecordId={}", mediaTask.getBizTaskId());
            return;
        }

        LambdaUpdateWrapper<AidAudioRecord> update = new LambdaUpdateWrapper<>();
        update.eq(AidAudioRecord::getId, audioRecord.getId());
        update.set(AidAudioRecord::getUpdateTime, DateUtils.getNowDate());
        update.set(AidAudioRecord::getTtsMediaTaskId, mediaTask.getId());
        update.set(AidAudioRecord::getAudioUrl, mediaTask.getOssUrl());
        update.set(AidAudioRecord::getStatus, MediaTaskStatus.SUCCEEDED.name());
        update.set(AidAudioRecord::getErrorMessage, null);
        applyDurationMs(update, mediaTask);

        try {
            aidAudioRecordService.update(update);
            log.info("AudioRecordEventListener ossPersisted 回填成功 audioRecordId={}, mediaTaskId={}",
                    audioRecord.getId(), mediaTask.getId());
        } catch (Exception ex) {
            log.error("AudioRecordEventListener ossPersisted 回填异常 audioRecordId={}", audioRecord.getId(), ex);
            return;
        }
        // 资产表幂等写入
        upsertAudioAsset(audioRecord, mediaTask);
    }

    /**
     * 向 aid_audio_asset 写一份资产快照（按 audio_record_id 幂等）。
     * 入库前提：audio_record 必须已成功（status=SUCCEEDED），mediaTask.ossUrl 必须非空。
     * 异常只记日志，不影响主流程（业务表已回填成功，资产丢失允许人工或下一轮补偿恢复）。
     */
    private void upsertAudioAsset(AidAudioRecord audioRecord, AidMediaTask mediaTask) {
        if (Objects.isNull(audioRecord) || Objects.isNull(audioRecord.getId())) {
            return;
        }
        if (StrUtil.isBlank(mediaTask.getOssUrl())) {
            return;
        }
        try {
            // 幂等：按 audio_record_id 先查后写
            AidAudioAsset existing = aidAudioAssetService.selectByAudioRecordId(audioRecord.getId());
            if (Objects.nonNull(existing)) {
                log.info("upsertAudioAsset 资产已存在, audioRecordId={}, assetId={}", audioRecord.getId(), existing.getId());
                return;
            }

            AidAudioAsset asset = new AidAudioAsset();
            asset.setUserId(audioRecord.getUserId());
            asset.setProjectId(mediaTask.getProjectId());
            asset.setEpisodeId(mediaTask.getEpisodeId());
            asset.setStoryboardId(audioRecord.getStoryboardId());
            asset.setAudioRecordId(audioRecord.getId());
            asset.setMediaTaskId(mediaTask.getId());
            asset.setAudioUrl(mediaTask.getOssUrl());
            asset.setTtsText(audioRecord.getTtsText());
            asset.setVoiceLibraryId(audioRecord.getVoiceLibraryId());
            asset.setVoiceModelId(audioRecord.getVoiceModelId());
            asset.setVoiceCode(audioRecord.getTimbreCode());
            // 音色名称：若关联音色库，回填展示名便于前端列表直接渲染
            asset.setVoiceName(resolveVoiceName(audioRecord.getVoiceLibraryId()));
            asset.setAudioSource(Objects.isNull(audioRecord.getAudioSource()) ? ASSET_SOURCE_AI : audioRecord.getAudioSource());
            asset.setAssetTitle(buildAssetTitle(audioRecord.getTtsText()));
            // 生成上下文快照：从 aid_media_task.request_json 解析（兼容旧数据为空的情况）
            applyRequestSnapshot(asset, mediaTask);
            // 文件大小：从 aid_media_result 读取（persistOssIfNeeded 写入，可能尚未落或缺失）
            applyResultSnapshot(asset, mediaTask.getId());
            asset.setDelFlag("0");

            Date now = DateUtils.getNowDate();
            String operator = Objects.isNull(audioRecord.getUserId()) ? "" : String.valueOf(audioRecord.getUserId());
            asset.setCreateBy(operator);
            asset.setCreateTime(now);
            asset.setUpdateBy(operator);
            asset.setUpdateTime(now);

            int rows = aidAudioAssetService.insertAidAudioAsset(asset);
            log.info("upsertAudioAsset 资产写入完成, audioRecordId={}, assetId={}, rows={}",
                    audioRecord.getId(), asset.getId(), rows);
        } catch (Exception ex) {
            // 唯一键冲突等并发竞争场景按 info 记录；不影响主流程
            log.info("upsertAudioAsset 写入资产跳过或异常, audioRecordId={}, err={}",
                    audioRecord.getId(), ex.getMessage());
        }
    }

    /**
     * 从 aid_media_task.request_json 反序列化原始 {@link MediaAudioGenerateRequest}，
     * 将情感 / 语速 / 音量 / 音调 / 采样率 / 音频格式等"生成上下文"一次性写入资产快照。
     * request_json 解析失败或字段缺失时，只对应单字段留空，不抛异常。
     * audio_format 优先取请求中的声明；缺失时按 OSS URL 后缀猜测。
     */
    private void applyRequestSnapshot(AidAudioAsset asset, AidMediaTask mediaTask) {
        String requestJson = mediaTask.getRequestJson();
        MediaAudioGenerateRequest req = null;
        if (StrUtil.isNotBlank(requestJson)) {
            try {
                req = JSONUtil.toBean(requestJson, MediaAudioGenerateRequest.class);
            } catch (Exception ex) {
                log.info("applyRequestSnapshot 解析 request_json 失败, mediaTaskId={}, err={}",
                        mediaTask.getId(), ex.getMessage());
            }
        }
        if (Objects.nonNull(req)) {
            asset.setEmotion(req.getEmotion());
            asset.setSpeechRate(req.getSpeechRate());
            asset.setLoudnessRate(req.getLoudnessRate());
            asset.setPitch(req.getPitch());
            asset.setSampleRate(req.getSampleRate());
            if (StrUtil.isNotBlank(req.getAudioFormat())) {
                // 归一化：ogg_opus → opus，其它小写
                asset.setAudioFormat(normalizeAudioFormat(req.getAudioFormat()));
            }
        }
        // 音频格式兜底：OSS URL 后缀猜测（已做归一化）
        if (StrUtil.isBlank(asset.getAudioFormat())) {
            asset.setAudioFormat(deriveAudioFormat(mediaTask.getOssUrl()));
        }
    }

    /**
     * 从 aid_media_result 取文件大小。缺失时留空（补偿任务后续可能回填）。
     */
    private void applyResultSnapshot(AidAudioAsset asset, Long mediaTaskId) {
        if (Objects.isNull(mediaTaskId)) {
            return;
        }
        try {
            LambdaQueryWrapper<AidMediaResult> wrapper = Wrappers.lambdaQuery();
            wrapper.select(AidMediaResult::getId, AidMediaResult::getTaskId, AidMediaResult::getFileSize);
            wrapper.eq(AidMediaResult::getTaskId, mediaTaskId);
            wrapper.last("LIMIT 1");
            AidMediaResult result = aidMediaResultMapper.selectOne(wrapper);
            if (Objects.nonNull(result)) {
                asset.setFileSize(result.getFileSize());
            }
        } catch (Exception ex) {
            log.info("applyResultSnapshot 查询媒体结果异常, mediaTaskId={}, err={}",
                    mediaTaskId, ex.getMessage());
        }
    }

    /**
     * 按音色库 ID 反查展示名（容错：音色库被删或异常时返回 null）。
     */
    private String resolveVoiceName(Long voiceLibraryId) {
        if (Objects.isNull(voiceLibraryId) || voiceLibraryId <= 0) {
            return null;
        }
        try {
            AidAiVoiceLibrary voice = aidAiVoiceLibraryService.getById(voiceLibraryId);
            return Objects.isNull(voice) ? null : voice.getVoiceName();
        } catch (Exception ex) {
            log.info("resolveVoiceName 查询音色异常, voiceLibraryId={}, err={}", voiceLibraryId, ex.getMessage());
            return null;
        }
    }

    /**
     * 资产标题默认取 tts_text 前 80 字。空文本兜底 "配音"。
     */
    private String buildAssetTitle(String ttsText) {
        if (StrUtil.isBlank(ttsText)) {
            return "配音";
        }
        String trimmed = ttsText.trim();
        if (trimmed.length() <= ASSET_TITLE_MAX_LEN) {
            return trimmed;
        }
        return trimmed.substring(0, ASSET_TITLE_MAX_LEN);
    }

    /**
     * 从 OSS URL 后缀猜测音频格式；仅用于前端展示，不参与播放。
     * 已做归一化：{@code ogg_opus / oggopus → opus}。
     * 注意：长度判断放在归一化之后，避免 {@code ogg_opus}（9 字符复合标记）被 &lt;=8 守卫误判。
     */
    private String deriveAudioFormat(String ossUrl) {
        if (StrUtil.isBlank(ossUrl)) {
            return null;
        }
        int dot = ossUrl.lastIndexOf('.');
        if (dot <= 0 || dot >= ossUrl.length() - 1) {
            return null;
        }
        String suffix = ossUrl.substring(dot + 1);
        int q = suffix.indexOf('?');
        if (q > 0) {
            suffix = suffix.substring(0, q);
        }
        // 先归一化，再按去点后的值长度判断
        String normalized = normalizeAudioFormat(suffix);
        if (StrUtil.isBlank(normalized) || normalized.length() > 8) {
            return null;
        }
        return normalized;
    }

    /**
     * 音频格式归一化（与 MediaGenerationServiceImpl 保持同义）：
     *
     *   - 去前导 "." / 小写 / 去 query 参数
     *   - ogg_opus / oggopus → opus
     *   - 空输入返回 null
     *
     */
    private String normalizeAudioFormat(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        s = s.toLowerCase();
        if ("ogg_opus".equals(s) || "oggopus".equals(s)) {
            return "opus";
        }
        return s;
    }

    /**
     * 音频时长回填（秒→毫秒，任务侧 output_duration_seconds 已向上取整）：
     * 任务无时长时不覆盖业务表既有值（豆包等厂商未返回时长的场景保持 null）。
     */
    private void applyDurationMs(LambdaUpdateWrapper<AidAudioRecord> update, AidMediaTask mediaTask) {
        if (Objects.nonNull(mediaTask.getOutputDurationSeconds()) && mediaTask.getOutputDurationSeconds() > 0) {
            update.set(AidAudioRecord::getDurationMs, (int) (mediaTask.getOutputDurationSeconds() * 1000));
        }
    }

    /**
     * 统一判定：本次事件是否对应音频业务任务（AUDIO + audio_record + 关联业务 ID）。
     */
    private boolean isAudioBizTask(AidMediaTask mediaTask) {
        if (Objects.isNull(mediaTask)) {
            return false;
        }
        if (!Objects.equals(MediaType.AUDIO.name(), mediaTask.getMediaType())) {
            return false;
        }
        if (!BIZ_TASK_TYPE_AUDIO.equals(mediaTask.getBizTaskType())
                || Objects.isNull(mediaTask.getBizTaskId())) {
            return false;
        }
        return true;
    }
}

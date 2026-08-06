package com.aid.compose.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidGenRecord;
import com.aid.common.exception.ServiceException;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.dto.ComposeGroupDto;
import com.aid.compose.dto.timeline.TimelineSegment;
import com.aid.compose.dto.timeline.TimelineSubtitleItem;
import com.aid.compose.enums.SubtitleRecognitionStatus;
import com.aid.compose.service.ExportSubtitleAlignmentService;
import com.aid.compose.util.SubtitleRecognitionMediaResolver;
import com.aid.compose.util.SubtitleDialogueFingerprint;
import com.aid.compose.util.SubtitleSpeakerMatcher;
import com.aid.media.dto.SpeechRecognitionResult;
import com.aid.media.provider.SpeechRecognitionClient;
import com.aid.voice.util.DialogueSubtitleFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 腾讯云自动字幕导出编排。厂商提交与轮询封装在同步客户端中，本服务负责分镜级检查点与断点续跑。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportSubtitleAlignmentServiceImpl implements ExportSubtitleAlignmentService {

    private static final String ASR_SOURCE = "ASR";
    private static final String DEFAULT_ERROR = "字幕生成失败";

    private final List<SpeechRecognitionClient> speechRecognitionClients;

    @Override
    public boolean isEnabled() {
        return Objects.nonNull(resolveEnabledClient());
    }

    @Override
    public int countRequired(List<ComposeGroupDto> groups, List<TimelineSegment> matchedSegments,
                             Map<Long, AidGenRecord> selectedVideos) {
        if (CollectionUtil.isEmpty(groups)) {
            return 0;
        }
        int count = 0;
        for (int index = 0; index < groups.size(); index++) {
            AlignmentDecision decision = evaluate(groups.get(index), matchedSegment(matchedSegments, index),
                    selectedVideos, providerCodeForCheckpoint());
            if (Objects.equals(decision, AlignmentDecision.REQUIRED)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void align(List<ComposeGroupDto> groups, List<TimelineSegment> matchedSegments,
                      Map<Long, AidGenRecord> selectedVideos,
                      BiConsumer<Integer, Integer> progressCallback,
                      Runnable checkpointCallback, Runnable heartbeatCallback) {
        SpeechRecognitionClient client = resolveEnabledClient();
        if (Objects.isNull(client)) {
            log.error("自动字幕执行时识别服务已关闭");
            throw new ServiceException("字幕服务已关闭");
        }
        if (CollectionUtil.isEmpty(groups)) {
            return;
        }

        List<AlignmentDecision> decisions = new ArrayList<>(groups.size());
        int total = 0;
        int completed = 0;
        for (int index = 0; index < groups.size(); index++) {
            AlignmentDecision decision = evaluate(groups.get(index), matchedSegment(matchedSegments, index),
                    selectedVideos, client.providerCode());
            decisions.add(decision);
            if (!Objects.equals(decision, AlignmentDecision.SKIPPED)) {
                total++;
            }
            if (Objects.equals(decision, AlignmentDecision.REUSABLE)) {
                completed++;
            }
        }
        notifyProgress(progressCallback, completed, total);

        for (int index = 0; index < groups.size(); index++) {
            if (!Objects.equals(decisions.get(index), AlignmentDecision.REQUIRED)) {
                continue;
            }
            ComposeGroupDto group = groups.get(index);
            TimelineSegment segment = matchedSegment(matchedSegments, index);
            String mediaFingerprint = currentFingerprint(group, selectedVideos);
            try {
                markProcessing(group, segment, client.providerCode(), mediaFingerprint);
                runCheckpoint(checkpointCallback);
                alignGroup(group, segment, selectedVideos, client, heartbeatCallback);
                runCheckpoint(checkpointCallback);
            } catch (RuntimeException ex) {
                String failureReason = shortError(ex);
                markFailed(group, segment, client.providerCode(), mediaFingerprint, failureReason);
                safeCheckpoint(checkpointCallback, group, index);
                log.error("导出分镜字幕生成失败, storyboardId={}, groupIndex={}, provider={}, error={}",
                        group.getStoryboardId(), index, client.providerCode(), ex.getMessage(), ex);
                throw new ServiceException(groupFailureMessage(index, failureReason));
            }
            completed++;
            notifyProgress(progressCallback, completed, total);
        }
    }

    private AlignmentDecision evaluate(ComposeGroupDto group, TimelineSegment segment,
                                       Map<Long, AidGenRecord> selectedVideos, String providerCode) {
        if (Objects.isNull(group) || isHidden(segment)) {
            return AlignmentDecision.SKIPPED;
        }
        List<String> mediaUrls = SubtitleRecognitionMediaResolver.resolveUrls(group);
        List<Double> mediaDurations = SubtitleRecognitionMediaResolver.resolveDurations(group);
        if (CollectionUtil.isEmpty(mediaUrls) || CollectionUtil.isEmpty(mediaDurations)
                || mediaUrls.size() != mediaDurations.size()) {
            return AlignmentDecision.SKIPPED;
        }

        String formattedDialogue = DialogueSubtitleFormatter.format(group.getSubtitle());
        if (StrUtil.isBlank(formattedDialogue)) {
            clearNoDialogue(group, segment);
            return AlignmentDecision.SKIPPED;
        }
        group.setSubtitle(formattedDialogue);

        String mediaFingerprint = currentFingerprint(group, selectedVideos);
        TimelineSubtitleItem timelineSubtitle = Objects.isNull(segment) ? null : segment.getSubtitle();
        boolean textFallbackReusable = Objects.nonNull(timelineSubtitle)
                && Objects.equals(timelineSubtitle.getRecognitionStatus(), SubtitleRecognitionStatus.TEXT_FALLBACK)
                && StrUtil.isNotBlank(mediaFingerprint)
                && Objects.equals(mediaFingerprint, timelineSubtitle.getSourceMediaFingerprint())
                && Objects.equals(dialogueFingerprint(formattedDialogue),
                timelineSubtitle.getSourceDialogueFingerprint())
                && Objects.equals(providerCode, timelineSubtitle.getRecognitionProvider());
        if (textFallbackReusable) {
            group.setSubtitleCues(null);
            group.setSubtitleSourceMediaFingerprint(mediaFingerprint);
            writeTimelineSubtitle(segment, formattedDialogue, null, mediaFingerprint);
            return AlignmentDecision.REUSABLE;
        }
        boolean cuesReusable = CollectionUtil.isNotEmpty(group.getSubtitleCues())
                && StrUtil.isNotBlank(mediaFingerprint)
                && Objects.equals(mediaFingerprint, group.getSubtitleSourceMediaFingerprint());
        if (cuesReusable && reuseExistingCues(group, segment, formattedDialogue,
                mediaFingerprint, providerCode, mediaDurations)) {
            return AlignmentDecision.REUSABLE;
        }

        if (Objects.isNull(segment)) {
            log.error("自动字幕分镜无法匹配工程时间轴, storyboardId={}", group.getStoryboardId());
            throw new ServiceException("分镜匹配失败");
        }
        clearTimedAlignment(group, segment.getSubtitle());
        return AlignmentDecision.REQUIRED;
    }

    private boolean reuseExistingCues(ComposeGroupDto group, TimelineSegment segment, String dialogue,
                                      String mediaFingerprint, String providerCode,
                                      List<Double> mediaDurations) {
        List<TimedSubtitleCue> cues = group.getSubtitleCues();
        boolean asrCues = cues.stream().filter(Objects::nonNull)
                .allMatch(cue -> ASR_SOURCE.equalsIgnoreCase(StrUtil.blankToDefault(cue.getSource(), "")));
        String dialogueFingerprint = dialogueFingerprint(dialogue);
        TimelineSubtitleItem subtitle = Objects.isNull(segment) ? null : segment.getSubtitle();

        // ASR 原文时间戳可复用，但说话人必须每次按当前台词重新匹配；历史错误人物不能因音源未变永久复用。
        if (asrCues) {
            List<TimedSubtitleCue> rematched = SubtitleSpeakerMatcher.match(
                    cues, dialogue, sumDurations(mediaDurations));
            if (CollectionUtil.isEmpty(rematched)) {
                return false;
            }
            cues = rematched;
            group.setSubtitleCues(rematched);
        }

        writeTimelineSubtitle(segment, dialogue, cues, mediaFingerprint);
        subtitle = Objects.isNull(segment) ? null : segment.getSubtitle();
        if (Objects.nonNull(subtitle)) {
            if (asrCues) {
                subtitle.setSourceDialogueFingerprint(dialogueFingerprint);
                subtitle.setRecognitionStatus(SubtitleRecognitionStatus.COMPLETED);
                subtitle.setRecognitionProvider(StrUtil.blankToDefault(
                        subtitle.getRecognitionProvider(), providerCode));
                subtitle.setRecognitionUpdatedAt(DateUtil.now());
                subtitle.setRecognitionError(null);
            } else {
                clearRecognitionMetadata(subtitle);
            }
        }
        return true;
    }

    private void alignGroup(ComposeGroupDto group, TimelineSegment segment,
                            Map<Long, AidGenRecord> selectedVideos, SpeechRecognitionClient client,
                            Runnable heartbeatCallback) {
        List<String> mediaUrls = SubtitleRecognitionMediaResolver.resolveUrls(group);
        List<Double> mediaDurations = SubtitleRecognitionMediaResolver.resolveDurations(group);
        List<TimedSubtitleCue> rawCues = new ArrayList<>();
        double mediaOffset = 0D;
        for (int index = 0; index < mediaUrls.size(); index++) {
            SpeechRecognitionResult result = client.recognize(mediaUrls.get(index), heartbeatCallback);
            if (Objects.isNull(result)) {
                log.error("导出分镜字幕识别响应为空, storyboardId={}, mediaIndex={}",
                        group.getStoryboardId(), index);
                throw new ServiceException("字幕结果为空");
            }
            if (CollectionUtil.isEmpty(result.getCues())) {
                log.warn("导出分镜未识别到人声,改用文本时长排布, storyboardId={}, mediaIndex={}",
                        group.getStoryboardId(), index);
                mediaOffset += mediaDurations.get(index);
                continue;
            }
            appendWithOffset(rawCues, result.getCues(), mediaOffset);
            mediaOffset += mediaDurations.get(index);
        }

        // 音源存在但没有可识别人声时保留原台词且不生成伪造时间戳，核心合成会沿用兼容文本排布。
        if (CollectionUtil.isEmpty(rawCues)) {
            fallbackToTextTiming(group, segment, currentFingerprint(group, selectedVideos),
                    client.providerCode());
            return;
        }

        List<TimedSubtitleCue> matchedCues = SubtitleSpeakerMatcher.match(
                rawCues, group.getSubtitle(), mediaOffset);
        if (CollectionUtil.isEmpty(matchedCues)) {
            log.warn("导出分镜字幕匹配结果为空,改用文本时长排布, storyboardId={}",
                    group.getStoryboardId());
            fallbackToTextTiming(group, segment, currentFingerprint(group, selectedVideos),
                    client.providerCode());
            return;
        }

        String mediaFingerprint = currentFingerprint(group, selectedVideos);
        group.setSubtitleCues(matchedCues);
        group.setSubtitleSourceMediaFingerprint(mediaFingerprint);
        writeTimelineSubtitle(segment, group.getSubtitle(), matchedCues, mediaFingerprint);
        TimelineSubtitleItem subtitle = segment.getSubtitle();
        subtitle.setSourceDialogueFingerprint(dialogueFingerprint(group.getSubtitle()));
        subtitle.setRecognitionStatus(SubtitleRecognitionStatus.COMPLETED);
        subtitle.setRecognitionProvider(client.providerCode());
        subtitle.setRecognitionUpdatedAt(DateUtil.now());
        subtitle.setRecognitionError(null);
    }

    /** 清除识别中的临时状态，保留台词给核心合成按当前分镜时长兼容排布。 */
    private void fallbackToTextTiming(ComposeGroupDto group, TimelineSegment segment,
                                      String mediaFingerprint, String providerCode) {
        clearTimedAlignment(group, segment.getSubtitle());
        group.setSubtitleSourceMediaFingerprint(mediaFingerprint);
        writeTimelineSubtitle(segment, group.getSubtitle(), null, mediaFingerprint);
        TimelineSubtitleItem subtitle = ensureSubtitle(segment);
        subtitle.setSourceDialogueFingerprint(dialogueFingerprint(group.getSubtitle()));
        subtitle.setRecognitionStatus(SubtitleRecognitionStatus.TEXT_FALLBACK);
        subtitle.setRecognitionProvider(providerCode);
        subtitle.setRecognitionUpdatedAt(DateUtil.now());
        subtitle.setRecognitionError(null);
    }

    private void appendWithOffset(List<TimedSubtitleCue> target, List<TimedSubtitleCue> source, double offset) {
        for (TimedSubtitleCue cue : source) {
            if (Objects.isNull(cue) || Objects.isNull(cue.getStartSeconds())
                    || Objects.isNull(cue.getEndSeconds())) {
                continue;
            }
            TimedSubtitleCue shifted = new TimedSubtitleCue();
            shifted.setStartSeconds(cue.getStartSeconds() + offset);
            shifted.setEndSeconds(cue.getEndSeconds() + offset);
            shifted.setSpeaker(cue.getSpeaker());
            shifted.setText(cue.getText());
            shifted.setSource(cue.getSource());
            target.add(shifted);
        }
    }

    private void markProcessing(ComposeGroupDto group, TimelineSegment segment, String providerCode,
                                String mediaFingerprint) {
        clearTimedAlignment(group, segment.getSubtitle());
        TimelineSubtitleItem subtitle = ensureSubtitle(segment);
        subtitle.setText(group.getSubtitle());
        subtitle.setShow(true);
        subtitle.setSourceMediaFingerprint(mediaFingerprint);
        subtitle.setSourceDialogueFingerprint(dialogueFingerprint(group.getSubtitle()));
        subtitle.setRecognitionStatus(SubtitleRecognitionStatus.PROCESSING);
        subtitle.setRecognitionProvider(providerCode);
        subtitle.setRecognitionUpdatedAt(DateUtil.now());
        subtitle.setRecognitionError(null);
    }

    private void markFailed(ComposeGroupDto group, TimelineSegment segment, String providerCode,
                            String mediaFingerprint, String error) {
        if (Objects.isNull(segment)) {
            return;
        }
        clearTimedAlignment(group, segment.getSubtitle());
        TimelineSubtitleItem subtitle = ensureSubtitle(segment);
        subtitle.setText(group.getSubtitle());
        subtitle.setSourceMediaFingerprint(mediaFingerprint);
        subtitle.setSourceDialogueFingerprint(dialogueFingerprint(group.getSubtitle()));
        subtitle.setRecognitionStatus(SubtitleRecognitionStatus.FAILED);
        subtitle.setRecognitionProvider(providerCode);
        subtitle.setRecognitionUpdatedAt(DateUtil.now());
        subtitle.setRecognitionError(error);
    }

    private void writeTimelineSubtitle(TimelineSegment segment, String text,
                                       List<TimedSubtitleCue> cues, String fingerprint) {
        if (Objects.isNull(segment)) {
            return;
        }
        TimelineSubtitleItem subtitle = ensureSubtitle(segment);
        subtitle.setText(text);
        subtitle.setShow(true);
        subtitle.setCues(cues);
        subtitle.setSourceMediaFingerprint(fingerprint);
    }

    private void clearNoDialogue(ComposeGroupDto group, TimelineSegment segment) {
        group.setSubtitle(null);
        TimelineSubtitleItem subtitle = Objects.isNull(segment) ? null : segment.getSubtitle();
        clearTimedAlignment(group, subtitle);
        if (Objects.nonNull(subtitle)) {
            subtitle.setText(null);
        }
    }

    private void clearTimedAlignment(ComposeGroupDto group, TimelineSubtitleItem subtitle) {
        group.setSubtitleCues(null);
        group.setSubtitleSourceMediaFingerprint(null);
        if (Objects.isNull(subtitle)) {
            return;
        }
        subtitle.setCues(null);
        subtitle.setSourceMediaFingerprint(null);
        subtitle.setSourceDialogueFingerprint(null);
        clearRecognitionMetadata(subtitle);
    }

    private void clearRecognitionMetadata(TimelineSubtitleItem subtitle) {
        subtitle.setRecognitionStatus(null);
        subtitle.setRecognitionProvider(null);
        subtitle.setRecognitionUpdatedAt(null);
        subtitle.setRecognitionError(null);
        subtitle.setSourceDialogueFingerprint(null);
    }

    private String currentFingerprint(ComposeGroupDto group, Map<Long, AidGenRecord> selectedVideos) {
        AidGenRecord selected = Objects.isNull(group.getStoryboardId()) || Objects.isNull(selectedVideos)
                ? null : selectedVideos.get(group.getStoryboardId());
        return SubtitleRecognitionMediaResolver.fingerprint(group, selected);
    }

    private String dialogueFingerprint(String dialogue) {
        return SubtitleDialogueFingerprint.of(dialogue);
    }

    private double sumDurations(List<Double> durations) {
        return durations.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    }

    private boolean isHidden(TimelineSegment segment) {
        return Objects.nonNull(segment) && Objects.nonNull(segment.getSubtitle())
                && Boolean.FALSE.equals(segment.getSubtitle().getShow());
    }

    private TimelineSubtitleItem ensureSubtitle(TimelineSegment segment) {
        TimelineSubtitleItem subtitle = segment.getSubtitle();
        if (Objects.isNull(subtitle)) {
            subtitle = new TimelineSubtitleItem();
            segment.setSubtitle(subtitle);
        }
        return subtitle;
    }

    private TimelineSegment matchedSegment(List<TimelineSegment> segments, int index) {
        return CollectionUtil.isEmpty(segments) || index >= segments.size() ? null : segments.get(index);
    }

    private String providerCodeForCheckpoint() {
        SpeechRecognitionClient client = resolveEnabledClient();
        return Objects.isNull(client) ? null : client.providerCode();
    }

    private SpeechRecognitionClient resolveEnabledClient() {
        if (CollectionUtil.isEmpty(speechRecognitionClients)) {
            return null;
        }
        return speechRecognitionClients.stream().filter(SpeechRecognitionClient::isEnabled).findFirst().orElse(null);
    }

    private void notifyProgress(BiConsumer<Integer, Integer> progressCallback, int completed, int total) {
        if (Objects.nonNull(progressCallback) && total > 0) {
            progressCallback.accept(completed, total);
        }
    }

    private void runCheckpoint(Runnable checkpointCallback) {
        if (Objects.nonNull(checkpointCallback)) {
            checkpointCallback.run();
        }
    }

    private void safeCheckpoint(Runnable checkpointCallback, ComposeGroupDto group, int groupIndex) {
        try {
            runCheckpoint(checkpointCallback);
        } catch (RuntimeException checkpointEx) {
            log.error("失败分镜检查点写入异常, storyboardId={}, groupIndex={}",
                    group.getStoryboardId(), groupIndex, checkpointEx);
        }
    }

    private String shortError(RuntimeException ex) {
        String message = ex instanceof ServiceException ? ex.getMessage() : null;
        return StrUtil.isNotBlank(message) && message.length() <= 12 ? message : DEFAULT_ERROR;
    }

    /** 导出终态携带一基分镜序号，前端可直接提示用户处理对应音源。 */
    private String groupFailureMessage(int groupIndex, String failureReason) {
        String prefix = "第" + (groupIndex + 1) + "镜";
        String message = prefix + StrUtil.blankToDefault(failureReason, DEFAULT_ERROR);
        return message.length() <= 12 ? message : prefix + DEFAULT_ERROR;
    }

    private enum AlignmentDecision {
        SKIPPED,
        REUSABLE,
        REQUIRED
    }
}

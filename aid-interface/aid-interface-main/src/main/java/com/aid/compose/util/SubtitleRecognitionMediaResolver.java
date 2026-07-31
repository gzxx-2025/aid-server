package com.aid.compose.util;

import cn.hutool.core.collection.CollectionUtil;
import com.aid.aid.domain.AidGenRecord;
import com.aid.compose.dto.ComposeGroupDto;

import java.util.List;
import java.util.Objects;

/**
 * 自动字幕识别音源解析器：独立配音是最终人声时优先识别配音，否则识别视频原声。
 *
 * @author 视觉AID
 */
public final class SubtitleRecognitionMediaResolver {

    private SubtitleRecognitionMediaResolver() {
    }

    /** 返回需要提交语音识别的媒体地址，保持段内播放顺序。 */
    public static List<String> resolveUrls(ComposeGroupDto group) {
        if (hasValidVoiceTrack(group)) {
            return group.getAudioUrls();
        }
        return Objects.isNull(group) || CollectionUtil.isEmpty(group.getVideoUrls())
                ? List.of() : group.getVideoUrls();
    }

    /** 返回识别媒体对应的时长，和 {@link #resolveUrls(ComposeGroupDto)} 下标一致。 */
    public static List<Double> resolveDurations(ComposeGroupDto group) {
        if (hasValidVoiceTrack(group)) {
            return group.getAudioDurations();
        }
        return Objects.isNull(group) || CollectionUtil.isEmpty(group.getVideoDurations())
                ? List.of() : group.getVideoDurations();
    }

    /**
     * 生成最终人声音源指纹。视频原声且为系统单分镜素材时优先使用生成记录 ID，
     * 其余场景按有序媒体 URL 生成稳定指纹。
     */
    public static String fingerprint(ComposeGroupDto group, AidGenRecord selectedVideo) {
        List<String> mediaUrls = resolveUrls(group);
        if (hasValidVoiceTrack(group)) {
            return TimelineMediaFingerprint.ofGroup(mediaUrls);
        }
        if (Objects.nonNull(selectedVideo) && mediaUrls.size() == 1) {
            return TimelineMediaFingerprint.of(selectedVideo.getId(), selectedVideo.getFileUrl());
        }
        return TimelineMediaFingerprint.ofGroup(mediaUrls);
    }

    private static boolean hasValidVoiceTrack(ComposeGroupDto group) {
        return Objects.nonNull(group)
                && CollectionUtil.isNotEmpty(group.getAudioUrls())
                && CollectionUtil.isNotEmpty(group.getAudioDurations())
                && group.getAudioUrls().size() == group.getAudioDurations().size();
    }
}

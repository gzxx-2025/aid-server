package com.aid.media.provider.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.config.AidAppConfig;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.domain.ComposeFileInfo;
import com.aid.compose.domain.ComposeJobPlan;
import com.aid.compose.domain.ComposeStorageSnapshot;
import com.aid.compose.domain.ComposeTrackItem;
import com.aid.compose.domain.ComposeTrackItemType;
import com.aid.compose.domain.ComposeTracks;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;

/** 本地 FFmpeg 合成 Provider。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFfmpegVideoProviderClient implements VideoProviderClient
{
    public static final String OPTION_COMPOSE_PLAN = "composePlan";
    private static final double AUDIO_FADE_SECONDS = 2D;

    private final MpsConfigManager configManager;
    private final OssTemplate ossTemplate;
    private final AidMediaTaskMapper taskMapper;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aid-ffmpeg-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public String protocol()
    {
        return MpsConfigManager.MODE_LOCAL_FFMPEG;
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo ignored, MediaVideoGenerateRequest request)
    {
        Path output = null;
        ScheduledFuture<?> heartbeat = null;
        try
        {
            MpsProperties config = configManager.getMpsProperties();
            ComposeJobPlan plan = readPlan(request);
            if (StrUtil.hasBlank(config.getFfmpegPath(), config.getFfprobePath()))
            {
                throw new IllegalStateException("FFmpeg未配置");
            }
            validateStorageOwnership(plan);
            heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                    () -> touchTask(plan.getTaskId()), 30L, 30L, TimeUnit.SECONDS);
            Path tempDir = resolveTempDir(config);
            Files.createDirectories(tempDir);
            output = Files.createTempFile(tempDir, "aid-compose-" + plan.getTaskId() + "-", ".mp4");

            List<String> command = buildCommand(config, plan, output);
            ProcessResult process = run(command, Math.max(60, config.getFfmpegTimeoutSeconds()));
            if (process.exitCode() != 0)
            {
                log.error("FFmpeg合成失败, taskId={}, exitCode={}, output={}",
                        plan.getTaskId(), process.exitCode(), abbreviate(process.output()));
                return ProviderSubmitResult.builder().rawResponse(process.output()).build();
            }
            long durationSeconds = probeDuration(config, output);
            if (durationSeconds <= 0 || !Files.isRegularFile(output) || Files.size(output) <= 0)
            {
                log.error("FFmpeg输出校验失败, taskId={}, duration={}", plan.getTaskId(), durationSeconds);
                return ProviderSubmitResult.builder().rawResponse("输出校验失败").build();
            }
            validateStorageOwnership(plan);
            String relativeUrl = ossTemplate.uploadSystemGeneratedFile(
                    new PathMultipartFile(output, "compose_" + plan.getTaskId() + ".mp4"),
                    resolveOutputDirectory(plan));
            return ProviderSubmitResult.builder()
                    .directUrl(relativeUrl)
                    .audioDurationMs(durationSeconds * 1000L)
                    .rawResponse("{\"status\":\"Success\"}")
                    .build();
        }
        catch (Exception e)
        {
            log.error("本地FFmpeg合成异常", e);
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        finally
        {
            if (heartbeat != null)
            {
                heartbeat.cancel(false);
            }
            if (output != null)
            {
                try
                {
                    Files.deleteIfExists(output);
                }
                catch (Exception e)
                {
                    log.warn("FFmpeg临时文件清理失败, path={}", output);
                }
            }
        }
    }

    @PreDestroy
    public void shutdownHeartbeatExecutor()
    {
        heartbeatExecutor.shutdownNow();
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo ignored, String providerTaskId)
    {
        // 本地任务在 submit 内以退出码 + ffprobe 同步确认终态；若进程重启未收口，由 PENDING 看门狗重新排队。
        return ProviderTaskResult.builder()
                .status(MediaTaskStatus.PROCESSING.name())
                .providerStatus("LOCAL_UNKNOWN")
                .querySuccessful(Boolean.FALSE)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }

    private ComposeJobPlan readPlan(MediaVideoGenerateRequest request)
    {
        Object raw = request == null || request.getOptions() == null
                ? null : request.getOptions().get(OPTION_COMPOSE_PLAN);
        if (raw == null)
        {
            throw new IllegalArgumentException("计划缺失");
        }
        return JSON.parseObject(JSON.toJSONString(raw), ComposeJobPlan.class);
    }

    private ComposeTracks requireTracks(ComposeJobPlan plan)
    {
        if (plan == null || plan.getTracks() == null
                || plan.getTracks().getFileInfos() == null
                || plan.getTracks().getVideoItems() == null
                || plan.getTracks().getVideoItems().isEmpty())
        {
            throw new IllegalArgumentException("合成计划无效");
        }
        return plan.getTracks();
    }

    /**
     * 找出可以安全使用 FFmpeg {@code -stream_loop -1} 的 BGM 输入。
     * 视频轨引用的文件以及未知时长配音引用的文件不能循环，否则未裁剪的消费方会变成无限流。
     */
    private Set<String> findLoopingBgmFileIds(List<ComposeTrackItem> bgmItems,
                                               List<ComposeTrackItem> videoItems,
                                               List<ComposeTrackItem> voiceItems)
    {
        Set<String> nonLoopable = new LinkedHashSet<>();
        if (videoItems != null)
        {
            for (ComposeTrackItem item : videoItems)
            {
                if (item != null && StrUtil.isNotBlank(item.getFileId()))
                {
                    nonLoopable.add(item.getFileId());
                }
            }
        }
        if (voiceItems != null)
        {
            for (ComposeTrackItem item : voiceItems)
            {
                if (item != null && item.getType() == ComposeTrackItemType.AUDIO
                        && itemDuration(item) <= 0D && StrUtil.isNotBlank(item.getFileId()))
                {
                    nonLoopable.add(item.getFileId());
                }
            }
        }
        Set<String> result = new LinkedHashSet<>();
        if (bgmItems != null)
        {
            for (ComposeTrackItem item : bgmItems)
            {
                if (item != null && item.getType() == ComposeTrackItemType.AUDIO
                        && itemDuration(item) > 0D && StrUtil.isNotBlank(item.getFileId())
                        && !nonLoopable.contains(item.getFileId()))
                {
                    result.add(item.getFileId());
                }
            }
        }
        return result;
    }

    /**
     * 本地滤镜要求每个画面片段都有确定时长。接口一的历史任务可能缺少视频时长，
     * 此时用 FFprobe 补齐，避免画面可播放但对应原声音轨因时长为零被跳过。
     */
    private List<ComposeTrackItem> resolveVideoDurations(MpsProperties config,
                                                          List<ComposeTrackItem> items,
                                                          Map<String, String> readableInputs) throws Exception
    {
        if (items == null || items.isEmpty())
        {
            throw new IllegalArgumentException("视频轨为空");
        }
        Map<String, Double> sourceDurations = new LinkedHashMap<>();
        for (ComposeTrackItem item : items)
        {
            if (item == null || item.getType() != ComposeTrackItemType.VIDEO
                    || StrUtil.isBlank(item.getFileId()))
            {
                throw new IllegalArgumentException("视频轨素材无效");
            }
            validateTrackTimes(item);
            if (itemDuration(item) > 0D)
            {
                continue;
            }
            String input = readableInputs.get(item.getFileId());
            if (StrUtil.isBlank(input))
            {
                throw new IllegalArgumentException("视频轨素材缺失");
            }
            Double sourceDuration = sourceDurations.get(item.getFileId());
            if (sourceDuration == null)
            {
                sourceDuration = probeInputDuration(config, input);
                sourceDurations.put(item.getFileId(), sourceDuration);
            }
            double start = item.getSourceStartSeconds() == null ? 0D : item.getSourceStartSeconds();
            double end = item.getSourceEndSeconds() == null ? sourceDuration : item.getSourceEndSeconds();
            double resolved = Math.min(sourceDuration, end) - start;
            if (!Double.isFinite(resolved) || resolved <= 0D)
            {
                throw new IllegalArgumentException("视频轨时长无效");
            }
            item.setDuration(resolved);
        }
        return items;
    }

    private double totalDuration(List<ComposeTrackItem> items)
    {
        double total = 0D;
        for (ComposeTrackItem item : items)
        {
            double duration = itemDuration(item);
            if (duration <= 0D)
            {
                throw new IllegalArgumentException("视频轨时长无效");
            }
            total += duration;
        }
        if (!Double.isFinite(total) || total <= 0D)
        {
            throw new IllegalArgumentException("视频轨时长无效");
        }
        return total;
    }

    private List<String> buildCommand(MpsProperties config, ComposeJobPlan plan, Path output) throws Exception
    {
        ComposeTracks tracks = requireTracks(plan);
        Set<String> loopingBgmFileIds = findLoopingBgmFileIds(
                tracks.getBgmItems(), tracks.getVideoItems(), tracks.getAudioItems());
        List<String> command = new ArrayList<>();
        command.add(config.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");
        command.add("-nostdin");
        command.add("-y");

        Map<String, Integer> inputIndex = new LinkedHashMap<>();
        Map<String, String> readableInputs = new LinkedHashMap<>();
        int index = 0;
        for (ComposeFileInfo file : tracks.getFileInfos())
        {
            if (file == null || StrUtil.hasBlank(file.getFileId(), file.getUrl()))
            {
                throw new IllegalArgumentException("合成素材信息缺失");
            }
            if (inputIndex.containsKey(file.getFileId()))
            {
                throw new IllegalArgumentException("合成素材编号重复");
            }
            String readableInput = toReadableInput(file.getUrl());
            if (loopingBgmFileIds.contains(file.getFileId()))
            {
                // BGM 轨要求铺满目标时段。仅对未被视频轨或未知时长配音复用的音频输入启用循环，
                // 避免把没有显式裁剪上限的视频/配音输入变成无限流。
                command.add("-stream_loop");
                command.add("-1");
            }
            command.add("-i");
            command.add(readableInput);
            inputIndex.put(file.getFileId(), index++);
            readableInputs.put(file.getFileId(), readableInput);
        }

        List<ComposeTrackItem> videoItems = resolveVideoDurations(
                config, tracks.getVideoItems(), readableInputs);
        double outputDuration = totalDuration(videoItems);
        VideoSize sourceSize = probeFirstVideoSize(config, videoItems, readableInputs);
        VideoSize outputSize = resolveOutputSize(plan.getResolution(), sourceSize);
        Map<String, Boolean> videoAudioStreams = probeVideoAudioStreams(
                config, videoItems, readableInputs);
        List<ComposeTrackItem> originalAudioItems = buildOriginalAudioItems(
                videoItems, videoAudioStreams);

        List<String> filters = new ArrayList<>();
        Map<String, Deque<String>> videoInputs = buildStreamInputs(filters, inputIndex,
                countStreamUses(videoItems, false), false);
        Map<String, Integer> audioUseCounts = countStreamUses(originalAudioItems, false);
        mergeStreamUses(audioUseCounts, countStreamUses(tracks.getAudioItems(), false));
        mergeStreamUses(audioUseCounts, countStreamUses(tracks.getBgmItems(), false));
        Map<String, Deque<String>> audioInputs = buildStreamInputs(filters, inputIndex,
                audioUseCounts, true);
        String videoLabel = buildVideoFilter(filters, videoItems, videoInputs, outputSize);
        videoLabel = appendSubtitleFilters(filters, videoLabel, tracks.getSubtitleItems(), config);
        String originalAudioLabel = buildAudioFilter(
                filters, "original", originalAudioItems, audioInputs, true);
        String voiceLabel = buildAudioFilter(filters, "voice", tracks.getAudioItems(), audioInputs, true);
        String bgmLabel = buildAudioFilter(filters, "bgm", tracks.getBgmItems(), audioInputs, true);
        String audioLabel = mixAudio(filters, outputDuration,
                originalAudioLabel, voiceLabel, bgmLabel);

        command.add("-filter_complex");
        command.add(String.join(";", filters));
        command.add("-map");
        command.add("[" + videoLabel + "]");
        if (StrUtil.isNotBlank(audioLabel))
        {
            command.add("-map");
            command.add("[" + audioLabel + "]");
            command.add("-c:a");
            command.add("aac");
            command.add("-b:a");
            command.add("192k");
        }
        command.add("-c:v");
        command.add(resolveVideoEncoder(plan.getCodec()));
        command.add("-pix_fmt");
        command.add("yuv420p");
        if (config.getFfmpegThreads() > 0)
        {
            command.add("-threads");
            command.add(String.valueOf(config.getFfmpegThreads()));
        }
        command.add("-movflags");
        command.add("+faststart");
        command.add("-t");
        command.add(decimal(outputDuration));
        command.add(output.toString());
        return command;
    }

    private String buildVideoFilter(List<String> filters, List<ComposeTrackItem> items,
                                    Map<String, Deque<String>> streamInputs, VideoSize outputSize)
    {
        if (items == null || items.isEmpty())
        {
            throw new IllegalArgumentException("视频轨为空");
        }
        List<String> labels = new ArrayList<>();
        int number = 0;
        for (ComposeTrackItem item : items)
        {
            if (item == null || item.getType() != ComposeTrackItemType.VIDEO
                    || StrUtil.isBlank(item.getFileId()))
            {
                throw new IllegalArgumentException("视频轨素材无效");
            }
            String input = takeStreamInput(streamInputs, item.getFileId());
            if (input == null)
            {
                throw new IllegalArgumentException("视频轨素材缺失");
            }
            String label = "v" + number++;
            StringBuilder filter = new StringBuilder(input);
            double duration = itemDuration(item);
            if (duration <= 0D)
            {
                throw new IllegalArgumentException("视频轨时长无效");
            }
            if (item.getSourceStartSeconds() != null || item.getSourceEndSeconds() != null)
            {
                filter.append("trim=");
                if (item.getSourceStartSeconds() != null)
                {
                    filter.append("start=").append(decimal(item.getSourceStartSeconds()));
                }
                if (item.getSourceEndSeconds() != null)
                {
                    if (item.getSourceStartSeconds() != null)
                    {
                        filter.append(":");
                    }
                    filter.append("end=").append(decimal(item.getSourceEndSeconds()));
                }
                else
                {
                    if (item.getSourceStartSeconds() != null)
                    {
                        filter.append(":");
                    }
                    filter.append("duration=").append(decimal(duration));
                }
                filter.append(",");
            }
            filter.append("setpts=PTS-STARTPTS,scale=")
                    .append(outputSize.width()).append(":").append(outputSize.height())
                    .append(":force_original_aspect_ratio=decrease,pad=")
                    .append(outputSize.width()).append(":").append(outputSize.height())
                    .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,settb=AVTB,fps=30,")
                    // 输入真实时长可能比元数据短少量帧；统一补末帧后裁剪，保证音画时间轴不前移。
                    .append("tpad=stop_mode=clone:stop_duration=").append(decimal(duration))
                    .append(",trim=duration=").append(decimal(duration))
                    .append(",setpts=PTS-STARTPTS,format=yuv420p[")
                    .append(label).append("]");
            filters.add(filter.toString());
            labels.add("[" + label + "]");
        }
        if (labels.isEmpty())
        {
            throw new IllegalArgumentException("视频轨为空");
        }
        filters.add(String.join("", labels) + "concat=n=" + labels.size() + ":v=1:a=0[vbase]");
        return "vbase";
    }

    private String buildAudioFilter(List<String> filters, String prefix, List<ComposeTrackItem> items,
                                    Map<String, Deque<String>> streamInputs)
    {
        return buildAudioFilter(filters, prefix, items, streamInputs, true);
    }

    private String buildAudioFilter(List<String> filters, String prefix, List<ComposeTrackItem> items,
                                    Map<String, Deque<String>> streamInputs, boolean padToDuration)
    {
        if (items == null || items.isEmpty())
        {
            return null;
        }
        if (!hasPlayableAudio(items))
        {
            // 全 Empty 轨道没有有效声音，跳过后可避免 amix 默认归一化把视频原声无谓压低。
            return null;
        }
        List<String> labels = new ArrayList<>();
        int number = 0;
        for (ComposeTrackItem item : items)
        {
            if (item == null)
            {
                throw new IllegalArgumentException("音频轨素材无效");
            }
            validateTrackTimes(item);
            double duration = itemDuration(item);
            boolean empty = item.getType() == ComposeTrackItemType.EMPTY;
            if (empty && duration <= 0D)
            {
                continue;
            }
            if (!empty && item.getType() != ComposeTrackItemType.AUDIO)
            {
                throw new IllegalArgumentException("音频轨素材无效");
            }
            String label = prefix + number++;
            if (empty)
            {
                filters.add("anullsrc=r=44100:cl=stereo,atrim=duration=" + decimal(duration)
                        + ",asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo["
                        + label + "]");
            }
            else
            {
                if (StrUtil.isBlank(item.getFileId()))
                {
                    throw new IllegalArgumentException("音频轨素材无效");
                }
                String input = takeStreamInput(streamInputs, item.getFileId());
                if (input == null)
                {
                    throw new IllegalArgumentException("音频轨素材缺失");
                }
                StringBuilder filter = new StringBuilder(input);
                if (item.getSourceStartSeconds() != null || duration > 0D)
                {
                    filter.append("atrim=");
                }
                if (item.getSourceStartSeconds() != null)
                {
                    filter.append("start=").append(decimal(item.getSourceStartSeconds()));
                }
                if (duration > 0D)
                {
                    if (item.getSourceStartSeconds() != null)
                    {
                        filter.append(":");
                    }
                    filter.append("duration=").append(decimal(duration));
                }
                if (item.getSourceStartSeconds() != null || duration > 0D)
                {
                    filter.append(",");
                }
                filter.append("asetpts=PTS-STARTPTS,aresample=44100,")
                        .append("aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo");
                if (item.getVolume() != null)
                {
                    if (!Double.isFinite(item.getVolume()) || item.getVolume() < 0D || item.getVolume() > 1D)
                    {
                        throw new IllegalArgumentException("音频音量无效");
                    }
                    filter.append(",volume=").append(decimal(item.getVolume()));
                }
                if (padToDuration && duration > 0D)
                {
                    // 原声、配音或 BGM 可能短于声明时长；补静音后再次裁剪，防止后续片段前移。
                    appendAudioDurationPadding(filter, duration);
                }
                if (item.isFade() && duration > 0D)
                {
                    double fadeDuration = Math.min(AUDIO_FADE_SECONDS, duration / 2D);
                    if (fadeDuration > 0D)
                    {
                        filter.append(",afade=t=in:st=0:d=").append(decimal(fadeDuration))
                                .append(",afade=t=out:st=")
                                .append(decimal(Math.max(0D, duration - fadeDuration)))
                                .append(":d=").append(decimal(fadeDuration));
                    }
                }
                filter.append("[").append(label).append("]");
                filters.add(filter.toString());
            }
            labels.add("[" + label + "]");
        }
        if (labels.isEmpty())
        {
            return null;
        }
        String output = prefix + "out";
        filters.add(String.join("", labels) + "concat=n=" + labels.size() + ":v=0:a=1[" + output + "]");
        return output;
    }

    private boolean hasPlayableAudio(List<ComposeTrackItem> items)
    {
        for (ComposeTrackItem item : items)
        {
            if (item != null && item.getType() == ComposeTrackItemType.AUDIO)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * FFmpeg 的同一个输入流输出垫不能被多个滤镜直接重复消费。循环补画面或同一音频复用时，
     * 先按实际使用次数生成 split/asplit，再由各轨道项依次领取独立标签。
     */
    private Map<String, Deque<String>> buildStreamInputs(List<String> filters,
                                                          Map<String, Integer> indexes,
                                                          Map<String, Integer> useCounts,
                                                          boolean audio)
    {
        Map<String, Deque<String>> result = new LinkedHashMap<>();
        useCounts.forEach((fileId, count) -> {
            Integer inputIndex = indexes.get(fileId);
            if (inputIndex == null || count == null || count <= 0)
            {
                return;
            }
            Deque<String> labels = new ArrayDeque<>();
            if (count == 1)
            {
                labels.add("[" + inputIndex + (audio ? ":a:0]" : ":v:0]"));
            }
            else
            {
                String prefix = (audio ? "asrc" : "vsrc") + inputIndex + "_";
                StringBuilder split = new StringBuilder("[").append(inputIndex)
                        .append(audio ? ":a:0]asplit=" : ":v:0]split=").append(count);
                for (int i = 0; i < count; i++)
                {
                    String label = prefix + i;
                    split.append("[").append(label).append("]");
                    labels.add("[" + label + "]");
                }
                filters.add(split.toString());
            }
            result.put(fileId, labels);
        });
        return result;
    }

    private Map<String, Integer> countStreamUses(List<ComposeTrackItem> items, boolean requirePositiveDuration)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (items == null)
        {
            return counts;
        }
        for (ComposeTrackItem item : items)
        {
            if (item != null && item.getType() != ComposeTrackItemType.EMPTY
                    && StrUtil.isNotBlank(item.getFileId())
                    && (!requirePositiveDuration || itemDuration(item) > 0))
            {
                counts.merge(item.getFileId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private void mergeStreamUses(Map<String, Integer> target, Map<String, Integer> source)
    {
        source.forEach((fileId, count) -> target.merge(fileId, count, Integer::sum));
    }

    private String takeStreamInput(Map<String, Deque<String>> inputs, String fileId)
    {
        Deque<String> labels = inputs.get(fileId);
        return labels == null ? null : labels.pollFirst();
    }

    private String mixAudio(List<String> filters, double targetDuration, String... tracks)
    {
        List<String> playableTracks = new ArrayList<>();
        if (tracks != null)
        {
            for (String track : tracks)
            {
                if (StrUtil.isNotBlank(track))
                {
                    playableTracks.add(track);
                }
            }
        }
        if (playableTracks.isEmpty())
        {
            return null;
        }
        StringBuilder inputs = new StringBuilder();
        for (String track : playableTracks)
        {
            inputs.append("[").append(track).append("]");
        }
        StringBuilder mixed = new StringBuilder(inputs);
        if (playableTracks.size() > 1)
        {
            // 关闭 amix 的输入数归一化，随后只对叠加峰值限幅，避免静音轨压低有效声音。
            mixed.append("amix=inputs=").append(playableTracks.size())
                    .append(":duration=longest:dropout_transition=0:normalize=0,")
                    .append("alimiter=limit=0.95:level=false,");
        }
        else
        {
            mixed.append("anull,");
        }
        mixed.append("apad=whole_dur=").append(decimal(targetDuration))
                .append(",atrim=duration=").append(decimal(targetDuration))
                .append("[aout]");
        filters.add(mixed.toString());
        return "aout";
    }

    private void appendAudioDurationPadding(StringBuilder filter, double duration)
    {
        filter.append(",apad=whole_dur=").append(decimal(duration))
                .append(",atrim=duration=").append(decimal(duration));
    }

    private String appendSubtitleFilters(List<String> filters, String input,
                                         List<ComposeTrackItem> subtitles, MpsProperties config)
    {
        if (subtitles == null || subtitles.isEmpty())
        {
            return input;
        }
        String current = input;
        int index = 0;
        for (ComposeTrackItem item : subtitles)
        {
            if (item == null || item.getType() != ComposeTrackItemType.SUBTITLE
                    || StrUtil.isBlank(item.getSubtitleText()))
            {
                throw new IllegalArgumentException("字幕轨素材无效");
            }
            validateTrackTimes(item);
            String next = "vsub" + index++;
            double start = item.getStart() == null ? 0D : item.getStart();
            double end = start + (item.getDuration() == null ? 0D : item.getDuration());
            StringBuilder draw = new StringBuilder("[").append(current).append("]drawtext=");
            if (StrUtil.isNotBlank(config.getFfmpegFontFile()))
            {
                draw.append("fontfile='").append(escapeFilter(config.getFfmpegFontFile())).append("':");
            }
            draw.append("text='").append(escapeFilter(item.getSubtitleText())).append("':")
                    .append("fontcolor=white:fontsize=").append(resolveSubtitleFontSize(config.getSubtitleFontSize()))
                    .append(":borderw=2:bordercolor=black:")
                    .append("x=(w-text_w)/2:y=h-text_h-h/12:")
                    .append("enable='between(t,").append(decimal(start)).append(",").append(decimal(end)).append(")'")
                    .append("[").append(next).append("]");
            filters.add(draw.toString());
            current = next;
        }
        return current;
    }

    private String toReadableInput(String url)
    {
        OssProperties properties = ossTemplate.getProperties();
        boolean managed = ossTemplate.isManagedResourceUrl(url);
        if (properties != null && "local".equalsIgnoreCase(properties.getUploadMode()))
        {
            if (!managed)
            {
                return url;
            }
            String path = url;
            if (StrUtil.startWithAnyIgnoreCase(url, "http://", "https://"))
            {
                path = URI.create(url).getPath();
            }
            if (path.startsWith("/") && !path.startsWith("//") && !path.contains("..")
                    && !path.contains("\\") && !path.contains("?") && !path.contains("#")
                    && path.chars().noneMatch(Character::isISOControl))
            {
                Path profileRoot = Paths.get(AidAppConfig.getProfile()).toAbsolutePath().normalize();
                String relative = path.startsWith("/profile/")
                        ? path.substring("/profile/".length()) : path.substring(1);
                Path localInput = profileRoot.resolve(relative).normalize();
                if (!localInput.startsWith(profileRoot))
                {
                    log.error("FFmpeg本地素材路径越界, url={}", url);
                    throw new IllegalArgumentException("素材路径错误");
                }
                return localInput.toString();
            }
            log.error("FFmpeg本地素材相对路径非法, url={}", url);
            throw new IllegalArgumentException("素材路径错误");
        }
        return managed ? ossTemplate.getSignedUrl(url, 6 * 3600) : url;
    }

    private Path resolveTempDir(MpsProperties config)
    {
        return StrUtil.isBlank(config.getFfmpegTempDir())
                ? Paths.get(System.getProperty("java.io.tmpdir"), "aid-ffmpeg")
                : Paths.get(config.getFfmpegTempDir()).toAbsolutePath().normalize();
    }

    private String resolveOutputDirectory(ComposeJobPlan plan)
    {
        String path = plan == null ? null : plan.getOutputObjectPath();
        path = StrUtil.blankToDefault(path, "/compose_result/output.mp4").replace('\\', '/');
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "compose_result" : StrUtil.strip(path.substring(0, slash), "/");
    }

    private ProcessResult run(List<String> command, int timeoutSeconds) throws Exception
    {
        Process process;
        try
        {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        }
        catch (IOException e)
        {
            String executable = command.isEmpty() ? "" : command.get(0);
            log.error("FFmpeg进程启动失败, executable={}, error={}", executable, e.getMessage());
            String errorMessage = executable.toLowerCase().contains("ffprobe")
                    ? "FFprobe不可用" : "FFmpeg不可用";
            throw new IllegalStateException(errorMessage, e);
        }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        try
        {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
                terminateProcess(process);
                throw new IllegalStateException("合成超时");
            }
            return new ProcessResult(process.exitValue(),
                    new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            terminateProcess(process);
            throw new IllegalStateException("合成已中断", e);
        }
        finally
        {
            if (process.isAlive())
            {
                terminateProcess(process);
            }
        }
    }

    private void terminateProcess(Process process)
    {
        if (process == null || !process.isAlive())
        {
            return;
        }
        process.destroy();
        try
        {
            if (!process.waitFor(2, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private byte[] readAll(InputStream input)
    {
        try (input)
        {
            final int maxBytes = 64 * 1024;
            byte[] tail = new byte[maxBytes];
            byte[] chunk = new byte[8192];
            int size = 0;
            int read;
            while ((read = input.read(chunk)) >= 0)
            {
                if (read >= maxBytes)
                {
                    System.arraycopy(chunk, read - maxBytes, tail, 0, maxBytes);
                    size = maxBytes;
                }
                else
                {
                    int overflow = Math.max(0, size + read - maxBytes);
                    if (overflow > 0)
                    {
                        System.arraycopy(tail, overflow, tail, 0, size - overflow);
                        size -= overflow;
                    }
                    System.arraycopy(chunk, 0, tail, size, read);
                    size += read;
                }
            }
            return java.util.Arrays.copyOf(tail, size);
        }
        catch (Exception e)
        {
            return new byte[0];
        }
    }

    private long probeDuration(MpsProperties config, Path output) throws Exception
    {
        List<String> command = List.of(config.getFfprobePath(), "-v", "error", "-show_entries",
                "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", output.toString());
        ProcessResult result = run(command, 60);
        if (result.exitCode() != 0)
        {
            return 0L;
        }
        try
        {
            return new BigDecimal(result.output().trim()).setScale(0, RoundingMode.CEILING).longValue();
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private double probeInputDuration(MpsProperties config, String input) throws Exception
    {
        List<String> command = List.of(config.getFfprobePath(), "-v", "error", "-show_entries",
                "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", input);
        ProcessResult result = run(command, 60);
        if (result.exitCode() != 0)
        {
            log.error("FFmpeg视频时长读取失败, output={}", abbreviate(result.output()));
            throw new IllegalArgumentException("视频时长读取失败");
        }
        try
        {
            double duration = new BigDecimal(result.output().trim()).doubleValue();
            if (!Double.isFinite(duration) || duration <= 0D)
            {
                throw new NumberFormatException("duration");
            }
            return duration;
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("视频时长读取失败", e);
        }
    }

    private VideoSize probeFirstVideoSize(MpsProperties config, List<ComposeTrackItem> items,
                                          Map<String, String> readableInputs) throws Exception
    {
        String input = null;
        if (items != null)
        {
            for (ComposeTrackItem item : items)
            {
                if (item != null && item.getType() != ComposeTrackItemType.EMPTY)
                {
                    input = readableInputs.get(item.getFileId());
                    if (StrUtil.isNotBlank(input))
                    {
                        break;
                    }
                }
            }
        }
        if (StrUtil.isBlank(input))
        {
            throw new IllegalArgumentException("视频轨为空");
        }
        List<String> command = List.of(config.getFfprobePath(), "-v", "error", "-select_streams", "v:0",
                "-show_streams", "-of", "json", input);
        ProcessResult result = run(command, 60);
        if (result.exitCode() != 0)
        {
            throw new IllegalArgumentException("视频尺寸读取失败");
        }
        int width;
        int height;
        int rotation = 0;
        try
        {
            JSONObject root = JSON.parseObject(result.output());
            JSONArray streams = root == null ? null : root.getJSONArray("streams");
            JSONObject stream = streams == null || streams.isEmpty() ? null : streams.getJSONObject(0);
            if (stream == null)
            {
                throw new IllegalArgumentException("stream");
            }
            width = stream.getIntValue("width");
            height = stream.getIntValue("height");
            JSONObject tags = stream.getJSONObject("tags");
            if (tags != null && StrUtil.isNotBlank(tags.getString("rotate")))
            {
                rotation = Integer.parseInt(tags.getString("rotate"));
            }
            JSONArray sideData = stream.getJSONArray("side_data_list");
            if (sideData != null)
            {
                for (int i = 0; i < sideData.size(); i++)
                {
                    JSONObject side = sideData.getJSONObject(i);
                    if (side != null && side.containsKey("rotation"))
                    {
                        rotation = side.getIntValue("rotation");
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("视频尺寸读取失败", e);
        }
        if (width < 2 || height < 2 || width > 8192 || height > 8192)
        {
            throw new IllegalArgumentException("视频尺寸不支持");
        }
        int normalizedRotation = Math.floorMod(rotation, 360);
        if (normalizedRotation == 90 || normalizedRotation == 270)
        {
            int originalWidth = width;
            width = height;
            height = originalWidth;
        }
        // 常见编码器要求偶数尺寸；至多裁掉一个像素，不改变画幅方向。
        return new VideoSize(width - width % 2, height - height % 2);
    }

    /** 按分辨率档把首段画幅等比缩放进目标边界，横竖屏方向保持不变。 */
    private VideoSize resolveOutputSize(String resolution, VideoSize source)
    {
        int landscapeWidth;
        int landscapeHeight;
        String tier = StrUtil.blankToDefault(resolution, "FHD").trim().toUpperCase(java.util.Locale.ROOT);
        switch (tier)
        {
            case "SD" -> {
                landscapeWidth = 854;
                landscapeHeight = 480;
            }
            case "HD" -> {
                landscapeWidth = 1280;
                landscapeHeight = 720;
            }
            case "2K" -> {
                landscapeWidth = 2560;
                landscapeHeight = 1440;
            }
            case "4K" -> {
                landscapeWidth = 3840;
                landscapeHeight = 2160;
            }
            default -> {
                landscapeWidth = 1920;
                landscapeHeight = 1080;
            }
        }
        int maxWidth = source.width() >= source.height() ? landscapeWidth : landscapeHeight;
        int maxHeight = source.width() >= source.height() ? landscapeHeight : landscapeWidth;
        double scale = Math.min((double) maxWidth / source.width(), (double) maxHeight / source.height());
        int width = evenDimension(source.width() * scale, maxWidth);
        int height = evenDimension(source.height() * scale, maxHeight);
        return new VideoSize(width, height);
    }

    private int evenDimension(double value, int maximum)
    {
        int rounded = Math.max(2, (int) Math.round(value));
        if ((rounded & 1) != 0)
        {
            rounded++;
        }
        if (rounded > maximum)
        {
            rounded = maximum - maximum % 2;
        }
        return rounded;
    }

    /** 把后台的 5% / 40px 字号转换为 drawtext 可用的表达式，非法历史值回退 5%。 */
    private String resolveSubtitleFontSize(String configured)
    {
        String value = StrUtil.blankToDefault(configured, "5%").trim().toLowerCase(java.util.Locale.ROOT);
        try
        {
            if (value.endsWith("%"))
            {
                double percent = Double.parseDouble(value.substring(0, value.length() - 1));
                if (Double.isFinite(percent) && percent >= 1D && percent <= 20D)
                {
                    return "h*" + decimal(percent / 100D);
                }
            }
            else
            {
                String pixels = value.endsWith("px") ? value.substring(0, value.length() - 2) : value;
                double pixelSize = Double.parseDouble(pixels);
                if (Double.isFinite(pixelSize) && pixelSize >= 8D && pixelSize <= 500D)
                {
                    return decimal(pixelSize);
                }
            }
        }
        catch (NumberFormatException ignored)
        {
            // 使用统一回退值。
        }
        log.warn("FFmpeg字幕字号配置无效, value={}, fallback=5%", configured);
        return "h*0.05";
    }

    /**
     * 探测视频素材是否包含可用音频流，同一文件只探测一次。
     *
     * @param config         合成配置
     * @param items          视频轨道项
     * @param readableInputs FFmpeg 可读输入
     * @return 文件ID与音频流存在状态
     */
    private Map<String, Boolean> probeVideoAudioStreams(MpsProperties config, List<ComposeTrackItem> items,
                                                         Map<String, String> readableInputs) throws Exception
    {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (items == null)
        {
            return result;
        }
        for (ComposeTrackItem item : items)
        {
            if (item == null || item.getType() == ComposeTrackItemType.EMPTY
                    || StrUtil.isBlank(item.getFileId()) || result.containsKey(item.getFileId()))
            {
                continue;
            }
            String input = readableInputs.get(item.getFileId());
            if (StrUtil.isBlank(input))
            {
                throw new IllegalArgumentException("视频素材缺失");
            }
            List<String> command = List.of(config.getFfprobePath(), "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=index", "-of", "csv=p=0", input);
            ProcessResult probe = run(command, 60);
            if (probe.exitCode() != 0)
            {
                log.error("FFmpeg视频原声音轨读取失败, fileId={}, output={}",
                        item.getFileId(), abbreviate(probe.output()));
                throw new IllegalArgumentException("视频音轨读取失败");
            }
            result.put(item.getFileId(), StrUtil.isNotBlank(probe.output()));
        }
        return result;
    }

    /**
     * 按视频轨顺序构建原声音轨；无音频流的片段使用等长静音占位。
     *
     * @param videoItems       视频轨道项
     * @param videoAudioStreams 文件音频流状态
     * @return 原声音轨道项
     */
    private List<ComposeTrackItem> buildOriginalAudioItems(List<ComposeTrackItem> videoItems,
                                                            Map<String, Boolean> videoAudioStreams)
    {
        List<ComposeTrackItem> result = new ArrayList<>();
        boolean hasPlayableAudio = false;
        if (videoItems == null)
        {
            return result;
        }
        for (ComposeTrackItem videoItem : videoItems)
        {
            if (videoItem == null)
            {
                continue;
            }
            double duration = itemDuration(videoItem);
            if (duration <= 0)
            {
                continue;
            }
            boolean hasAudio = Boolean.TRUE.equals(videoAudioStreams.get(videoItem.getFileId()));
            hasPlayableAudio = hasPlayableAudio || hasAudio;
            ComposeTrackItem audioItem = new ComposeTrackItem();
            audioItem.setType(hasAudio ? ComposeTrackItemType.AUDIO : ComposeTrackItemType.EMPTY);
            audioItem.setFileId(hasAudio ? videoItem.getFileId() : null);
            audioItem.setDuration(duration);
            audioItem.setSourceStartSeconds(videoItem.getSourceStartSeconds());
            audioItem.setSourceEndSeconds(videoItem.getSourceEndSeconds());
            result.add(audioItem);
        }
        return hasPlayableAudio ? result : List.of();
    }

    private double itemDuration(ComposeTrackItem item)
    {
        if (item == null)
        {
            return 0D;
        }
        if (item.getTrackDurationSeconds() != null)
        {
            return item.getTrackDurationSeconds();
        }
        if (item.getDuration() != null)
        {
            return item.getDuration();
        }
        if (item.getSourceEndSeconds() != null)
        {
            double start = item.getSourceStartSeconds() == null ? 0D : item.getSourceStartSeconds();
            return Math.max(0D, item.getSourceEndSeconds() - start);
        }
        return 0D;
    }

    private void validateTrackTimes(ComposeTrackItem item)
    {
        validatePositiveTime(item.getDuration(), "轨道时长无效");
        validatePositiveTime(item.getTrackDurationSeconds(), "轨道时长无效");
        Double start = item.getSourceStartSeconds();
        Double end = item.getSourceEndSeconds();
        if (start != null && (!Double.isFinite(start) || start < 0D))
        {
            throw new IllegalArgumentException("素材裁剪时间无效");
        }
        if (end != null && (!Double.isFinite(end) || end <= 0D))
        {
            throw new IllegalArgumentException("素材裁剪时间无效");
        }
        if (start != null && end != null && end <= start)
        {
            throw new IllegalArgumentException("素材裁剪时间无效");
        }
        Double timelineStart = item.getStart();
        if (timelineStart != null && (!Double.isFinite(timelineStart) || timelineStart < 0D))
        {
            throw new IllegalArgumentException("轨道起点无效");
        }
    }

    private void validatePositiveTime(Double value, String message)
    {
        if (value != null && (!Double.isFinite(value) || value <= 0D))
        {
            throw new IllegalArgumentException(message);
        }
    }

    private String decimal(double value)
    {
        if (!Double.isFinite(value))
        {
            throw new IllegalArgumentException("数值无效");
        }
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String escapeFilter(String value)
    {
        return StrUtil.blankToDefault(value, "").replace("\\", "\\\\")
                .replace(":", "\\:").replace("'", "\\'")
                .replace(",", "\\,").replace(";", "\\;")
                .replace("[", "\\[").replace("]", "\\]")
                .replace("%", "\\%").replace("\n", " ").replace("\r", " ");
    }

    private String abbreviate(String value)
    {
        if (value == null || value.length() <= 2000)
        {
            return value;
        }
        return value.substring(value.length() - 2000);
    }

    private String resolveVideoEncoder(String codec)
    {
        if ("H.265".equalsIgnoreCase(codec))
        {
            return "libx265";
        }
        if ("AV1".equalsIgnoreCase(codec))
        {
            return "libaom-av1";
        }
        return "libx264";
    }

    private void touchTask(Long taskId)
    {
        if (taskId == null)
        {
            return;
        }
        try
        {
            taskMapper.update(null, Wrappers.<AidMediaTask>lambdaUpdate()
                    .eq(AidMediaTask::getId, taskId)
                    .eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name())
                    .set(AidMediaTask::getUpdateTime, new java.util.Date()));
        }
        catch (Exception e)
        {
            log.warn("FFmpeg任务心跳失败, taskId={}", taskId);
        }
    }

    private void validateStorageOwnership(ComposeJobPlan plan)
    {
        ComposeStorageSnapshot snapshot = plan == null ? null : plan.getStorage();
        OssProperties current = ossTemplate.getProperties();
        if (snapshot == null || current == null
                || !StrUtil.equalsIgnoreCase(snapshot.getMode(), current.getUploadMode()))
        {
            throw new IllegalStateException("存储已变更");
        }
        boolean matched = switch (snapshot.getMode().toLowerCase())
        {
            case "oss" -> StrUtil.equals(snapshot.getBucket(), current.getBucketName())
                    && StrUtil.equalsIgnoreCase(normalizeEndpoint(snapshot.getEndpoint()),
                    normalizeEndpoint(current.getEndpoint()));
            case "cos" -> StrUtil.equals(snapshot.getBucket(), current.getCosBucketName())
                    && StrUtil.equalsIgnoreCase(snapshot.getRegion(), current.getCosRegion());
            case "qiniu" -> StrUtil.equals(snapshot.getBucket(), current.getQiniuBucketName());
            case "local" -> true;
            default -> false;
        };
        if (!matched)
        {
            throw new IllegalStateException("存储已变更");
        }
    }

    private String normalizeEndpoint(String value)
    {
        return StrUtil.removeSuffix(StrUtil.blankToDefault(value, "")
                .replaceFirst("(?i)^https?://", ""), "/")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** 以流方式把 FFmpeg 输出交给统一存储层，避免整片一次性读入 JVM 堆。 */
    private static final class PathMultipartFile implements MultipartFile
    {
        private final Path path;
        private final String originalFilename;

        private PathMultipartFile(Path path, String originalFilename)
        {
            this.path = path;
            this.originalFilename = originalFilename;
        }

        @Override
        public String getName() { return "file"; }

        @Override
        public String getOriginalFilename() { return originalFilename; }

        @Override
        public String getContentType() { return "video/mp4"; }

        @Override
        public boolean isEmpty() { return size() == 0L; }

        @Override
        public long getSize() { return size(); }

        private long size()
        {
            try
            {
                return Files.size(path);
            }
            catch (Exception e)
            {
                return 0L;
            }
        }

        @Override
        public byte[] getBytes() throws java.io.IOException { return Files.readAllBytes(path); }

        @Override
        public InputStream getInputStream() throws java.io.IOException { return Files.newInputStream(path); }

        @Override
        public void transferTo(File dest) throws java.io.IOException
        {
            Files.copy(path, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    private record VideoSize(int width, int height) {}
}

package com.aid.media.provider.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.domain.ComposeFileInfo;
import com.aid.compose.domain.ComposeJobPlan;
import com.aid.compose.domain.ComposeStorageSnapshot;
import com.aid.compose.domain.ComposeTrackItem;
import com.aid.compose.domain.ComposeTrackItemType;
import com.aid.compose.exception.ComposeUpstreamUnavailableException;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.aliyun.ice20201109.Client;
import com.aliyun.ice20201109.models.GetMediaProducingJobRequest;
import com.aliyun.ice20201109.models.GetMediaProducingJobResponse;
import com.aliyun.ice20201109.models.GetMediaProducingJobResponseBody.GetMediaProducingJobResponseBodyMediaProducingJob;
import com.aliyun.ice20201109.models.SubmitMediaProducingJobRequest;
import com.aliyun.ice20201109.models.SubmitMediaProducingJobResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.tea.TeaException;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 阿里云智能媒体服务 IMS 云剪辑 Provider。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunImsVideoProviderClient implements VideoProviderClient
{
    public static final String OPTION_COMPOSE_PLAN = "composePlan";

    private final MpsConfigManager configManager;
    private final OssConfigManager ossConfigManager;

    @Override
    public String protocol()
    {
        return MpsConfigManager.MODE_ALIYUN_IMS;
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo ignored, MediaVideoGenerateRequest request)
    {
        try
        {
            MpsProperties config = configManager.getMpsProperties();
            ComposeJobPlan plan = readPlan(request);
            validate(config, plan);
            SubmitMediaProducingJobRequest upstream = new SubmitMediaProducingJobRequest()
                    .setTimeline(JSON.toJSONString(buildTimeline(plan)))
                    .setOutputMediaTarget("oss-object")
                    .setOutputMediaConfig(JSON.toJSONString(buildOutputConfig(plan)))
                    .setSource("OpenAPI")
                    .setClientToken("compose-" + plan.getTaskId());
            if (StrUtil.isNotBlank(config.getAliyunCallbackUrl()))
            {
                Map<String, Object> userData = new LinkedHashMap<>();
                userData.put("NotifyAddress", config.getAliyunCallbackUrl());
                userData.put("TaskId", String.valueOf(plan.getTaskId()));
                upstream.setUserData(JSON.toJSONString(userData));
            }
            SubmitMediaProducingJobResponse response = createClient(config).submitMediaProducingJob(upstream);
            String jobId = response == null || response.getBody() == null ? null : response.getBody().getJobId();
            String raw = response == null || response.getBody() == null
                    ? null : JSON.toJSONString(response.getBody().toMap());
            return ProviderSubmitResult.builder().providerTaskId(jobId).rawResponse(raw).build();
        }
        catch (TeaException e)
        {
            Integer statusCode = e.getStatusCode();
            if (statusCode == null || statusCode == 408 || statusCode == 429 || statusCode >= 500)
            {
                log.warn("阿里IMS提交结果待确认, status={}, code={}", statusCode, e.getCode());
                throw new ComposeUpstreamUnavailableException("提交待确认", e);
            }
            log.error("阿里IMS拒绝合成提交, status={}, code={}, message={}",
                    statusCode, e.getCode(), e.getMessage());
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        catch (ComposeUpstreamUnavailableException e)
        {
            throw e;
        }
        catch (IllegalArgumentException | IllegalStateException e)
        {
            // 请求还未到达上游前已能确认的本地配置/计划错误，可直接失败。
            log.error("阿里IMS合成请求无效, error={}", e.getMessage());
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        catch (Exception e)
        {
            // SDK 的非标准网络/反序列化异常无法证明上游未受理；ClientToken 可保证重试幂等。
            log.warn("阿里IMS提交结果无法确认, errorType={}", e.getClass().getSimpleName(), e);
            throw new ComposeUpstreamUnavailableException("提交待确认", e);
        }
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo ignored, String providerTaskId)
    {
        try
        {
            MpsProperties config = configManager.getMpsProperties();
            GetMediaProducingJobResponse response = createClient(config)
                    .getMediaProducingJob(new GetMediaProducingJobRequest().setJobId(providerTaskId));
            GetMediaProducingJobResponseBodyMediaProducingJob job = response == null || response.getBody() == null
                    ? null : response.getBody().getMediaProducingJob();
            if (job == null || StrUtil.isBlank(job.getStatus()))
            {
                return processing(null, null, false);
            }
            String status = job.getStatus().trim();
            String raw = JSON.toJSONString(response.getBody().toMap());
            Integer progress = readInteger(job.toMap(), "Progress", "progress");
            if ("Success".equalsIgnoreCase(status))
            {
                String resultUrl = job.getMediaURL();
                if (StrUtil.isBlank(resultUrl))
                {
                    log.warn("阿里IMS任务成功但成片地址尚未就绪, jobId={}", providerTaskId);
                    return processing(status, progress, false);
                }
                if (!isOwnedOssUrl(resultUrl))
                {
                    log.error("阿里IMS输出不属于当前OSS, jobId={}, resultUrl={}", providerTaskId, resultUrl);
                    return ProviderTaskResult.builder()
                            .status(MediaTaskStatus.FAILED.name())
                            .providerStatus(status)
                            .errorMessage("输出归属错误")
                            .rawErrorMessage("IMS output storage mismatch")
                            .rawResponse(raw)
                            .querySuccessful(Boolean.TRUE)
                            .terminalConfirmed(Boolean.TRUE)
                            .build();
                }
                Long duration = job.getDuration() == null ? null
                        : BigDecimal.valueOf(job.getDuration()).setScale(0, RoundingMode.CEILING).longValue();
                return ProviderTaskResult.builder()
                        .status(MediaTaskStatus.SUCCEEDED.name())
                        .providerStatus(status)
                        .resultUrl(resultUrl)
                        .outputDurationSeconds(duration)
                        .progress(100)
                        .rawResponse(raw)
                        .querySuccessful(Boolean.TRUE)
                        .terminalConfirmed(Boolean.TRUE)
                        .build();
            }
            if ("Failed".equalsIgnoreCase(status))
            {
                String detail = StrUtil.join(": ", job.getCode(), job.getMessage());
                return ProviderTaskResult.builder()
                        .status(MediaTaskStatus.FAILED.name())
                        .providerStatus(status)
                        .errorMessage("合成失败")
                        .rawErrorMessage(detail)
                        .rawResponse(raw)
                        .querySuccessful(Boolean.TRUE)
                        .terminalConfirmed(Boolean.TRUE)
                        .build();
            }
            if (List.of("Init", "Queuing", "Processing").stream().anyMatch(v -> v.equalsIgnoreCase(status)))
            {
                return processing(status, progress, true);
            }
            log.warn("阿里IMS返回未知任务状态, jobId={}, status={}", providerTaskId, status);
            return processing(status, progress, false);
        }
        catch (Exception e)
        {
            // 查询异常不是上游终态，保持处理中交给补偿轮询。
            log.warn("阿里IMS任务查询异常, jobId={}, error={}", providerTaskId, e.getMessage());
            return processing(null, null, false);
        }
    }

    private ProviderTaskResult processing(String providerStatus, Integer progress, boolean recognized)
    {
        return ProviderTaskResult.builder()
                .status(MediaTaskStatus.PROCESSING.name())
                .providerStatus(providerStatus)
                .progress(progress)
                .querySuccessful(recognized)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }

    private Client createClient(MpsProperties config) throws Exception
    {
        Config sdk = new Config()
                .setAccessKeyId(config.getAliyunAccessKeyId())
                .setAccessKeySecret(config.getAliyunAccessKeySecret())
                .setRegionId(config.getAliyunRegion())
                .setEndpoint("ice." + config.getAliyunRegion() + ".aliyuncs.com");
        return new Client(sdk);
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

    private void validate(MpsProperties config, ComposeJobPlan plan)
    {
        if (StrUtil.hasBlank(config.getAliyunAccessKeyId(), config.getAliyunAccessKeySecret(),
                config.getAliyunRegion()))
        {
            throw new IllegalStateException("IMS未配置");
        }
        ComposeStorageSnapshot storage = plan == null ? null : plan.getStorage();
        if (storage == null || !"oss".equalsIgnoreCase(storage.getMode())
                || !StrUtil.equalsIgnoreCase(storage.getRegion(), config.getAliyunRegion()))
        {
            throw new IllegalStateException("存储不匹配");
        }
        OssProperties current = ossConfigManager.getOssProperties();
        if (current == null || !"oss".equalsIgnoreCase(current.getUploadMode())
                || !StrUtil.equals(storage.getBucket(), current.getBucketName())
                || !StrUtil.equalsIgnoreCase(normalizeEndpoint(storage.getEndpoint()),
                normalizeEndpoint(current.getEndpoint())))
        {
            throw new IllegalStateException("存储已变更");
        }
    }

    private Map<String, Object> buildTimeline(ComposeJobPlan plan)
    {
        Map<String, String> urls = new LinkedHashMap<>();
        for (ComposeFileInfo file : plan.getTracks().getFileInfos())
        {
            urls.put(file.getFileId(), toOssInputUrl(file.getUrl(), plan.getStorage()));
        }
        Map<String, Object> timeline = new LinkedHashMap<>();
        List<Map<String, Object>> videoClips = buildMediaClips(plan.getTracks().getVideoItems(), urls, false);
        timeline.put("VideoTracks", List.of(Map.of("VideoTrackClips", videoClips)));

        List<Map<String, Object>> audioTracks = new ArrayList<>();
        addAudioTrack(audioTracks, plan.getTracks().getAudioItems(), urls);
        addAudioTrack(audioTracks, plan.getTracks().getBgmItems(), urls);
        if (!audioTracks.isEmpty())
        {
            timeline.put("AudioTracks", audioTracks);
        }
        if (plan.getTracks().getSubtitleItems() != null && !plan.getTracks().getSubtitleItems().isEmpty())
        {
            List<Map<String, Object>> subtitles = new ArrayList<>();
            for (ComposeTrackItem item : plan.getTracks().getSubtitleItems())
            {
                Map<String, Object> clip = new LinkedHashMap<>();
                clip.put("Type", "Text");
                clip.put("Content", item.getSubtitleText());
                clip.put("TimelineIn", decimal(item.getStart()));
                clip.put("TimelineOut", decimal(item.getStart() + item.getDuration()));
                clip.put("X", 0.1);
                clip.put("Y", 0.84);
                clip.put("FontSize", 48);
                clip.put("FontColor", "#FFFFFF");
                subtitles.add(clip);
            }
            timeline.put("SubtitleTracks", List.of(Map.of("SubtitleTrackClips", subtitles)));
        }
        return timeline;
    }

    private List<Map<String, Object>> buildMediaClips(List<ComposeTrackItem> items,
                                                       Map<String, String> urls, boolean audio)
    {
        List<Map<String, Object>> clips = new ArrayList<>();
        if (items == null)
        {
            return clips;
        }
        double cursor = 0D;
        for (ComposeTrackItem item : items)
        {
            double duration = itemDuration(item);
            double start = item.getStart() == null ? cursor : item.getStart();
            cursor = Math.max(cursor, start + duration);
            if (item.getType() == ComposeTrackItemType.EMPTY || StrUtil.isBlank(item.getFileId()))
            {
                continue;
            }
            Map<String, Object> clip = new LinkedHashMap<>();
            clip.put("MediaURL", urls.get(item.getFileId()));
            clip.put("TimelineIn", decimal(start));
            clip.put("TimelineOut", decimal(start + duration));
            if (item.getSourceStartSeconds() != null)
            {
                clip.put("In", decimal(item.getSourceStartSeconds()));
            }
            if (item.getSourceEndSeconds() != null)
            {
                clip.put("Out", decimal(item.getSourceEndSeconds()));
            }
            if (audio && item.getVolume() != null)
            {
                clip.put("Effects", List.of(Map.of("Type", "Volume", "Gain", item.getVolume())));
            }
            clips.add(clip);
        }
        return clips;
    }

    private void addAudioTrack(List<Map<String, Object>> tracks, List<ComposeTrackItem> items,
                               Map<String, String> urls)
    {
        List<Map<String, Object>> clips = buildMediaClips(items, urls, true);
        if (!clips.isEmpty())
        {
            tracks.add(Map.of("AudioTrackClips", clips));
        }
    }

    private Map<String, Object> buildOutputConfig(ComposeJobPlan plan)
    {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("MediaURL", toOwnedOssUrl(plan.getOutputObjectPath(), plan.getStorage()));
        String codec = "H.265".equalsIgnoreCase(plan.getCodec()) ? "H.265" : "H.264";
        output.put("Video", Map.of("Codec", codec));
        return output;
    }

    private String toOssInputUrl(String value, ComposeStorageSnapshot storage)
    {
        if (!StrUtil.startWithAnyIgnoreCase(value, "http://", "https://"))
        {
            return toOwnedOssUrl(value, storage);
        }
        URI uri = URI.create(value);
        String resourceDomain = StrUtil.removeSuffix(storage.getResourceAccessDomain(), "/");
        String endpoint = toPublicOssEndpoint(storage.getEndpoint());
        String originHost = storage.getBucket() + "." + endpoint;
        if ((StrUtil.isNotBlank(resourceDomain) && value.startsWith(resourceDomain + "/"))
                || StrUtil.equalsIgnoreCase(uri.getHost(), originHost))
        {
            return toOwnedOssUrl(uri.getPath(), storage);
        }
        // SubmitMediaProducingJob 的 Timeline 素材只接受媒资库或同地域 OSS 地址，
        // 不接受外部地址/CDN 地址；调用前的业务校验会在冻结费用前给出同样结论。
        throw new IllegalArgumentException("素材不在OSS");
    }

    private String toOwnedOssUrl(String value, ComposeStorageSnapshot storage)
    {
        String object = value;
        if (StrUtil.startWithAnyIgnoreCase(value, "http://", "https://"))
        {
            object = URI.create(value).getPath();
        }
        object = StrUtil.removePrefix(object, "/");
        String endpoint = toPublicOssEndpoint(storage.getEndpoint());
        return "https://" + storage.getBucket() + "." + endpoint + "/" + object;
    }

    private boolean isOwnedOssUrl(String value)
    {
        try
        {
            OssProperties current = ossConfigManager.getOssProperties();
            if (current == null || !"oss".equalsIgnoreCase(current.getUploadMode()))
            {
                return false;
            }
            URI uri = URI.create(value);
            String expectedHost = current.getBucketName() + "." + toPublicOssEndpoint(current.getEndpoint());
            return StrUtil.equalsIgnoreCase(uri.getHost(), expectedHost)
                    && StrUtil.isNotBlank(uri.getPath()) && !"/".equals(uri.getPath());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private String normalizeEndpoint(String endpoint)
    {
        return StrUtil.removeSuffix(StrUtil.blankToDefault(endpoint, "")
                .replaceFirst("(?i)^https?://", ""), "/")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** IMS 时间线与成片地址按官方格式使用 OSS 公网 Endpoint；存储 SDK 仍可继续使用后台配置的内网 Endpoint。 */
    private String toPublicOssEndpoint(String endpoint)
    {
        String normalized = normalizeEndpoint(endpoint);
        int firstDot = normalized.indexOf('.');
        if (firstDot < 0)
        {
            return normalized.replace("-internal", "").replace("-intranet", "");
        }
        String regionHost = normalized.substring(0, firstDot)
                .replace("-internal", "").replace("-intranet", "");
        return regionHost + normalized.substring(firstDot);
    }

    private double itemDuration(ComposeTrackItem item)
    {
        if (item.getTrackDurationSeconds() != null)
        {
            return item.getTrackDurationSeconds();
        }
        if (item.getDuration() != null)
        {
            return item.getDuration();
        }
        if (item.getSourceStartSeconds() != null && item.getSourceEndSeconds() != null)
        {
            return Math.max(0D, item.getSourceEndSeconds() - item.getSourceStartSeconds());
        }
        return 0D;
    }

    private double decimal(Double value)
    {
        return value == null ? 0D : BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private Integer readInteger(Map<String, Object> map, String... keys)
    {
        if (map == null)
        {
            return null;
        }
        for (String key : keys)
        {
            Object value = map.get(key);
            if (value instanceof Number number)
            {
                return number.intValue();
            }
            if (value != null)
            {
                try
                {
                    return Integer.parseInt(String.valueOf(value));
                }
                catch (NumberFormatException ignored)
                {
                    // 继续尝试下一个兼容键。
                }
            }
        }
        return null;
    }
}

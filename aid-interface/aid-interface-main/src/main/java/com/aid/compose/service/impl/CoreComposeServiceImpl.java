package com.aid.compose.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.alibaba.fastjson2.JSON;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.compose.ComposeConstants;
import com.aid.compose.config.MpsConfigManager;
import com.aid.compose.config.MpsProperties;
import com.aid.compose.domain.ComposeBillingSnapshot;
import com.aid.compose.domain.ComposeCommand;
import com.aid.compose.domain.ComposeFileInfo;
import com.aid.compose.domain.ComposeGroup;
import com.aid.compose.domain.ComposeSubmitResult;
import com.aid.compose.domain.ComposeTrackItem;
import com.aid.compose.domain.ComposeTrackItemType;
import com.aid.compose.domain.ComposeTracks;
import com.aid.compose.service.ComposeBillingService;
import com.aid.compose.service.ComposeUrlNormalizer;
import com.aid.compose.service.CoreComposeService;
import com.aid.media.enums.DispatchMode;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.util.MediaTaskPayloadSanitizer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 核心合成方法实现：四轨道组装 + EditMedia 组装 + 落 COMPOSE 任务（含预冻结）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoreComposeServiceImpl implements CoreComposeService {

    /** 协议标识：按 protocol 路由到 MPS Provider */
    private static final String PROTOCOL_MPS = "tencent-mps";

    /** 媒体类型：合成任务 */
    private static final String MEDIA_TYPE_COMPOSE = "COMPOSE";

    /** 对齐策略：以配音为准 */
    private static final String ALIGN_AUDIO = "AUDIO";

    /** 浮点比较精度 */
    private static final double EPS = 0.001;

    /** 整片 BGM 压低音量 */
    private static final double BGM_VOLUME = 0.3;

    /** 默认分辨率档 */
    private static final String DEFAULT_RESOLUTION = "FHD";

    /** 单个成片最大秒数（60 分钟业务约束） */
    private static final long MAX_OUTPUT_SECONDS = 60L * 60L;

    /** 合法分辨率档 */
    private static final Set<String> VALID_RESOLUTIONS = Set.of("SD", "HD", "FHD", "2K", "4K");

    /** 媒体任务表 Mapper（aid_media_task 无独立 Service，沿用现有直读 Mapper 的统一做法） */
    private final AidMediaTaskMapper aidMediaTaskMapper;

    /** URL 规范化器 */
    private final ComposeUrlNormalizer composeUrlNormalizer;

    /** 合成计费服务 */
    private final ComposeBillingService composeBillingService;

    /** MPS 配置管理器 */
    private final MpsConfigManager mpsConfigManager;

    /** 统一媒体生成服务：复用其并发/排队/异步提交机制提交 COMPOSE 任务 */
    private final IMediaGenerationService mediaGenerationService;

    /** 编程式事务模板（容器装配的默认模板，仅用于派生短事务模板） */
    private final TransactionTemplate transactionTemplate;

    /** REQUIRES_NEW 短事务模板：任务落库独立提交，不并入调用方事务，提交后任务行立即对异步提交线程可见 */
    private TransactionTemplate requiresNewTxTemplate;

    @PostConstruct
    void initShortTxTemplate() {
        this.requiresNewTxTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public ComposeSubmitResult compose(ComposeCommand command) {
        validate(command);
        // 合成任务也只允许对象存储 URL，冻结费用前先拒绝 Base64/data URI 文件内容。
        MediaTaskPayloadSanitizer.serializeRequest(command);
        normalizeCommandUrls(command);
        long estimatedSeconds = estimateSeconds(command);
        if (estimatedSeconds > MAX_OUTPUT_SECONDS) {
            log.error("合成估算时长超限, estimatedSeconds={}, max={}", estimatedSeconds, MAX_OUTPUT_SECONDS);
            throw new RuntimeException("时长超限");
        }
        String resolution = resolveResolution(command.getResolution());
        ComposeTracks tracks = buildTracks(command);
        String traceId = "compose_" + IdUtil.fastSimpleUUID();
        // 预冻结通过统一账户执行器独立事务提交（内部 REQUIRES_NEW），成功后立即生效
        ComposeBillingSnapshot snapshot = composeBillingService.freeze(
                command.getUserId(), estimatedSeconds, resolution, traceId);
        AidMediaTask task = buildTask(command, resolution, estimatedSeconds, traceId, snapshot);
        try {
            // 短事务独立提交任务行：不受调用方事务牵连，提交后 submitComposeTaskAsync 必能读到任务
            requiresNewTxTemplate.executeWithoutResult(s -> {
                aidMediaTaskMapper.insert(task);
                Map<String, Object> editMediaRequest =
                        assembleEditMediaRequest(command, tracks, resolution, task.getId());
                task.setRequestJson(MediaTaskPayloadSanitizer.serializeRequest(editMediaRequest));
                task.setUpdateTime(new Date());
                aidMediaTaskMapper.updateById(task);
            });
        } catch (Exception persistEx) {
            // 任务落库失败但冻结已独立提交：立即全额退回，杜绝积分悬挂（refund 按 traceId 幂等）
            log.error("合成任务落库失败,执行冻结退回, traceId={}, batchId={}",
                    traceId, command.getComposeBatchId(), persistEx);
            refundFrozenQuietly(command.getUserId(), snapshot, traceId);
            throw persistEx;
        }
        log.info("合成任务已落库, taskId={}, batchId={}, estimatedSeconds={}, resolution={}",
                task.getId(), command.getComposeBatchId(), estimatedSeconds, resolution);

        mediaGenerationService.submitComposeTaskAsync(task.getId());

        ComposeSubmitResult result = new ComposeSubmitResult();
        result.setMediaTaskId(task.getId());
        result.setProviderTaskId(task.getProviderTaskId());
        result.setEstimatedSeconds(estimatedSeconds);
        return result;
    }

    /**
     * 冻结退回兜底（不抛出）：退款异常时仅记录，traceId 幂等可人工补退。
     *
     * @param userId   用户ID
     * @param snapshot 计费快照
     * @param traceId  计费追踪ID
     */
    private void refundFrozenQuietly(Long userId, ComposeBillingSnapshot snapshot, String traceId) {
        try {
            composeBillingService.refund(userId, snapshot, traceId);
        } catch (Exception refundEx) {
            log.error("合成冻结退回失败,需人工核对, userId={}, traceId={}", userId, traceId, refundEx);
        }
    }

    @Override
    public ComposeTracks buildTracks(ComposeCommand command) {
        ComposeTracks tracks = new ComposeTracks();
        List<ComposeFileInfo> fileInfos = tracks.getFileInfos();
        // 文件去重：同一 URL 复用同一 fileId
        Map<String, String> fileIdByUrl = new LinkedHashMap<>();
        double cursor = 0.0;
        boolean hasGroupBgm = false;

        for (ComposeGroup group : command.getGroups()) {
            double segVideoDur = sumDurations(group.getVideoDurations());
            double segAudioDur = sumDurations(group.getAudioDurations());
            double segDur = resolveSegDuration(command.getAlignStrategy(), segVideoDur, segAudioDur);

            // 视频轨：顺序排，连续无洞
            List<String> videoUrls = group.getVideoUrls();
            for (int i = 0; i < videoUrls.size(); i++) {
                String fileId = registerFile(fileInfos, fileIdByUrl, videoUrls.get(i));
                ComposeTrackItem item = new ComposeTrackItem();
                item.setType(ComposeTrackItemType.VIDEO);
                item.setFileId(fileId);
                item.setDuration(durationAt(group.getVideoDurations(), i));
                tracks.getVideoItems().add(item);
            }
            // 段长超出画面总长（AUDIO 对齐配音更长）：末条视频变速拉伸补满段长，
            // 否则视频轨短于配音/字幕轨，成片尾部黑屏且后续段音画错位
            double videoGap = segDur - segVideoDur;
            if (videoGap > EPS && !videoUrls.isEmpty()) {
                ComposeTrackItem lastVideo = tracks.getVideoItems().get(tracks.getVideoItems().size() - 1);
                double lastDur = Objects.isNull(lastVideo.getDuration()) ? 0.0 : lastVideo.getDuration();
                lastVideo.setTrackDurationSeconds(lastDur + videoGap);
            }

            // 配音轨：有配音排配音并补齐 Empty；无配音整段 Empty
            if (CollectionUtil.isNotEmpty(group.getAudioUrls())) {
                List<String> audioUrls = group.getAudioUrls();
                boolean durationsTrusted = true;
                for (int i = 0; i < audioUrls.size(); i++) {
                    String fileId = registerFile(fileInfos, fileIdByUrl, audioUrls.get(i));
                    ComposeTrackItem item = new ComposeTrackItem();
                    item.setType(ComposeTrackItemType.AUDIO);
                    item.setFileId(fileId);
                    Double audioDur = durationAt(group.getAudioDurations(), i);
                    item.setDuration(audioDur);
                    if (Objects.isNull(audioDur)) {
                        durationsTrusted = false;
                    }
                    tracks.getAudioItems().add(item);
                }
                // 时长不可信（存在 null）时禁止补静音占位：真实配音会按自身长度播放，
                // 再插 Empty 会双重占位把后续配音越挤越晚（本批成片音画漂移的主因）
                double gap = segDur - segAudioDur;
                if (durationsTrusted && gap > EPS) {
                    tracks.getAudioItems().add(emptyItem(gap));
                } else if (!durationsTrusted) {
                    log.warn("合成分组配音时长缺失,跳过该段静音补位,可能存在轻微音画偏差, batchId={}",
                            command.getComposeBatchId());
                }
            } else {
                tracks.getAudioItems().add(emptyItem(segDur));
            }

            // 字幕轨：有字幕用绝对 Start+Duration 钉位
            if (StrUtil.isNotBlank(group.getSubtitle())) {
                ComposeTrackItem sub = new ComposeTrackItem();
                sub.setType(ComposeTrackItemType.SUBTITLE);
                sub.setSubtitleText(group.getSubtitle());
                sub.setStart(cursor);
                sub.setDuration(segDur);
                tracks.getSubtitleItems().add(sub);
            }

            // 背景音轨（分组模式）：global 为空时，有 bgmUrl 放对应时段，缺的组 Empty
            if (StrUtil.isBlank(command.getGlobalBgmUrl())) {
                if (StrUtil.isNotBlank(group.getBgmUrl())) {
                    String fileId = registerFile(fileInfos, fileIdByUrl, group.getBgmUrl());
                    ComposeTrackItem bgm = new ComposeTrackItem();
                    bgm.setType(ComposeTrackItemType.AUDIO);
                    bgm.setFileId(fileId);
                    // 分组 BGM 与缺位 Empty 同轨顺排对齐，不设绝对 Start，避免绝对/顺排混用导致错位
                    bgm.setDuration(segDur);
                    tracks.getBgmItems().add(bgm);
                    hasGroupBgm = true;
                } else {
                    tracks.getBgmItems().add(emptyItem(segDur));
                }
            }

            cursor += segDur;
        }

        double totalDur = cursor;
        tracks.setTotalDuration(totalDur);

        // 背景音轨（整片模式）：global 非空 → 单条铺满整片，压低音量 + 淡入淡出，互斥覆盖分组项
        if (StrUtil.isNotBlank(command.getGlobalBgmUrl())) {
            tracks.getBgmItems().clear();
            String fileId = registerFile(fileInfos, fileIdByUrl, command.getGlobalBgmUrl());
            ComposeTrackItem bgm = new ComposeTrackItem();
            bgm.setType(ComposeTrackItemType.AUDIO);
            bgm.setFileId(fileId);
            bgm.setStart(0.0);
            bgm.setDuration(totalDur);
            bgm.setVolume(BGM_VOLUME);
            bgm.setFade(true);
            tracks.getBgmItems().add(bgm);
        } else if (!hasGroupBgm) {
            // 全无 BGM（global 空且无任一组 bgmUrl）：不建背景音轨
            tracks.getBgmItems().clear();
        }
        return tracks;
    }
    /**
     * 入参校验：groups 非空、每组视频非空、分辨率合法，非法直接拒绝。
     *
     * @param command 合成指令
     */
    private void validate(ComposeCommand command) {
        if (Objects.isNull(command) || CollectionUtil.isEmpty(command.getGroups())) {
            log.error("合成入参非法: groups 为空");
            throw new RuntimeException("参数有误");
        }
        for (ComposeGroup group : command.getGroups()) {
            if (Objects.isNull(group) || CollectionUtil.isEmpty(group.getVideoUrls())) {
                log.error("合成入参非法: 某组视频为空");
                throw new RuntimeException("参数有误");
            }
        }
        String resolution = command.getResolution();
        if (StrUtil.isNotBlank(resolution) && !VALID_RESOLUTIONS.contains(resolution.trim().toUpperCase())) {
            log.error("合成入参非法: 分辨率档非法, resolution={}", resolution);
            throw new RuntimeException("参数有误");
        }
    }

    /**
     * 对 command 内所有素材 URL 规范化 + 可用性校验，就地替换为规范化结果。
     * 同一 URL（如整片各段复用同一 BGM）只探测一次，避免重复 HEAD 开销。
     *
     * @param command 合成指令
     */
    private void normalizeCommandUrls(ComposeCommand command) {
        Map<String, String> normalizedCache = new HashMap<>();
        if (StrUtil.isNotBlank(command.getGlobalBgmUrl())) {
            command.setGlobalBgmUrl(normalizeCached(
                    command.getGlobalBgmUrl(), ComposeConstants.MATERIAL_LABEL_BGM, normalizedCache));
        }
        for (ComposeGroup group : command.getGroups()) {
            group.setVideoUrls(normalizeList(
                    group.getVideoUrls(), ComposeConstants.MATERIAL_LABEL_VIDEO, normalizedCache));
            group.setAudioUrls(normalizeList(
                    group.getAudioUrls(), ComposeConstants.MATERIAL_LABEL_AUDIO, normalizedCache));
            if (StrUtil.isNotBlank(group.getBgmUrl())) {
                group.setBgmUrl(normalizeCached(
                        group.getBgmUrl(), ComposeConstants.MATERIAL_LABEL_BGM, normalizedCache));
            }
        }
    }

    /**
     * 带缓存的单 URL 规范化：同请求内相同 URL 复用首次校验结果。
     *
     * @param url   原 URL
     * @param label 素材标签
     * @param cache 原 URL → 规范化结果缓存
     * @return 规范化后的 URL
     */
    private String normalizeCached(String url, String label, Map<String, String> cache) {
        String cached = cache.get(url);
        if (Objects.nonNull(cached)) {
            return cached;
        }
        String normalized = composeUrlNormalizer.normalizeAndValidate(url, label);
        cache.put(url, normalized);
        return normalized;
    }

    /**
     * 规范化 URL 列表，空列表原样返回。
     *
     * @param urls  原 URL 列表
     * @param label 素材标签
     * @param cache 规范化缓存
     * @return 规范化后的列表
     */
    private List<String> normalizeList(List<String> urls, String label, Map<String, String> cache) {
        if (CollectionUtil.isEmpty(urls)) {
            return urls;
        }
        List<String> result = new ArrayList<>(urls.size());
        for (String url : urls) {
            result.add(normalizeCached(url, label, cache));
        }
        return result;
    }

    /**
     * 估算成片秒数：各组段时长之和，向上取整为整秒。
     *
     * @param command 合成指令
     * @return 估算秒数
     */
    private long estimateSeconds(ComposeCommand command) {
        double total = 0.0;
        for (ComposeGroup group : command.getGroups()) {
            double segVideoDur = sumDurations(group.getVideoDurations());
            double segAudioDur = sumDurations(group.getAudioDurations());
            total += resolveSegDuration(command.getAlignStrategy(), segVideoDur, segAudioDur);
        }
        return (long) Math.ceil(total);
    }

    /**
     * 解析分辨率档：空则默认 FHD，统一大写。
     *
     * @param resolution 入参分辨率
     * @return 规范化分辨率档
     */
    private String resolveResolution(String resolution) {
        if (StrUtil.isBlank(resolution)) {
            return DEFAULT_RESOLUTION;
        }
        return resolution.trim().toUpperCase();
    }
    /**
     * 段对齐策略：AUDIO 以配音为准——段长取视频/配音较长者（配音超长时段随配音延长、视频拉伸填满；
     * 配音短或缺失回落视频长度，避免截画面）；默认 VIDEO 以视频为准。
     *
     * @param strategy     对齐策略
     * @param videoSeconds 视频时长
     * @param audioSeconds 配音时长
     * @return 段时长
     */
    private double resolveSegDuration(String strategy, double videoSeconds, double audioSeconds) {
        if (ALIGN_AUDIO.equalsIgnoreCase(strategy)) {
            return Math.max(videoSeconds, audioSeconds);
        }
        return videoSeconds;
    }

    /**
     * 累加时长列表（null 安全，null 项按 0 计）。
     *
     * @param durations 时长列表
     * @return 总时长
     */
    private double sumDurations(List<Double> durations) {
        if (CollectionUtil.isEmpty(durations)) {
            return 0.0;
        }
        double sum = 0.0;
        for (Double d : durations) {
            if (Objects.nonNull(d)) {
                sum += d;
            }
        }
        return sum;
    }

    /**
     * 取下标处时长，越界/缺失返回 null。
     *
     * @param durations 时长列表
     * @param index     下标
     * @return 时长或 null
     */
    private Double durationAt(List<Double> durations, int index) {
        if (CollectionUtil.isEmpty(durations) || index < 0 || index >= durations.size()) {
            return null;
        }
        return durations.get(index);
    }

    /**
     * 构造 Empty 占位项。
     *
     * @param duration 时长
     * @return Empty 轨道项
     */
    private ComposeTrackItem emptyItem(double duration) {
        ComposeTrackItem item = new ComposeTrackItem();
        item.setType(ComposeTrackItemType.EMPTY);
        item.setDuration(duration);
        return item;
    }

    /**
     * 秒（Double）转 MPS 轨道时间字符串（如 3.0 → "3s"、3.4333 → "3.433s"）。
     * MPS EditMedia 的 ComposeTrackTime.Start/Duration、ComposeEmptyItem.Duration 均要求字符串秒（带 s 后缀），
     * 直接下发数字会被上游判为参数不识别；此处整秒去小数、非整秒保留最多 3 位并去尾零，避免 "3.0s" 之类。
     *
     * @param seconds 秒数（可空）
     * @return 形如 "3s" / "3.5s" 的字符串；入参为空返回 null
     */
    private String secToStr(Double seconds) {
        if (Objects.isNull(seconds)) {
            return null;
        }
        // 整秒：直接去掉小数点，避免 "3.0s"
        if (seconds == Math.floor(seconds) && !Double.isInfinite(seconds)) {
            return (long) (double) seconds + "s";
        }
        // 非整秒：保留最多 3 位小数并去掉尾部多余的 0
        BigDecimal bd = BigDecimal.valueOf(seconds).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return bd.toPlainString() + "s";
    }

    /**
     * 注册素材文件，按 URL 去重复用同一 fileId。
     *
     * @param fileInfos   文件列表
     * @param fileIdByUrl URL → fileId 缓存
     * @param url         素材 URL
     * @return 文件ID
     */
    private String registerFile(List<ComposeFileInfo> fileInfos, Map<String, String> fileIdByUrl, String url) {
        String existed = fileIdByUrl.get(url);
        if (StrUtil.isNotBlank(existed)) {
            return existed;
        }
        String fileId = "f" + (fileInfos.size() + 1);
        ComposeFileInfo info = new ComposeFileInfo();
        info.setFileId(fileId);
        info.setUrl(url);
        fileInfos.add(info);
        fileIdByUrl.put(url, fileId);
        return fileId;
    }
    /**
     * 构建 COMPOSE 任务实体。
     *
     * @param command          合成指令
     * @param resolution       分辨率档
     * @param estimatedSeconds 估算秒数
     * @param traceId          计费追踪ID
     * @param snapshot         计费快照
     * @return 任务实体
     */
    private AidMediaTask buildTask(ComposeCommand command, String resolution, long estimatedSeconds,
                                   String traceId, ComposeBillingSnapshot snapshot) {
        AidMediaTask task = new AidMediaTask();
        task.setUserId(command.getUserId());
        task.setProjectId(command.getProjectId());
        task.setEpisodeId(command.getEpisodeId());
        task.setMediaType(MEDIA_TYPE_COMPOSE);
        task.setProtocol(PROTOCOL_MPS);
        task.setModelName(PROTOCOL_MPS);
        task.setStatus(MediaTaskStatus.QUEUED.name());
        task.setDispatchMode(DispatchMode.CALLBACK_FIRST.name());
        task.setBillingTraceId(traceId);
        BigDecimal frozen = Objects.isNull(snapshot) ? null : snapshot.getFrozenCredits();
        task.setFrozenAmount(frozen);
        task.setBillingStatus(Objects.nonNull(frozen) && frozen.signum() > 0
                ? MediaBillingStatus.FROZEN.name() : MediaBillingStatus.INIT.name());
        task.setBillingSnapshotJson(JSON.toJSONString(snapshot));
        task.setComposeBatchId(command.getComposeBatchId());
        task.setCallbackCategory(command.getCallbackCategory());
        task.setCallbackRecordId(command.getCallbackRecordId());
        task.setRetryCount(0);
        Date now = new Date();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        return task;
    }

    /**
     * 组装 MPS EditMedia 请求体：FileInfos / ComposeConfig(Tracks/Styles/TargetInfo) /
     * OutputStorage / 回调通知 / 会话上下文。
     *
     * @param command    合成指令
     * @param tracks     四轨道
     * @param resolution 分辨率档
     * @param taskId     我方任务ID（透传 SessionContext）
     * @return EditMedia 请求体
     */
    private Map<String, Object> assembleEditMediaRequest(ComposeCommand command, ComposeTracks tracks,
                                                         String resolution, Long taskId) {
        MpsProperties props = mpsConfigManager.getMpsProperties();
        Map<String, Object> request = new LinkedHashMap<>();

        // FileInfos：每个素材 Type=URL
        List<Map<String, Object>> fileInfos = new ArrayList<>();
        for (ComposeFileInfo file : tracks.getFileInfos()) {
            Map<String, Object> fi = new LinkedHashMap<>();
            fi.put("Id", file.getFileId());
            Map<String, Object> inputInfo = new LinkedHashMap<>();
            inputInfo.put("Type", "URL");
            Map<String, Object> urlInput = new LinkedHashMap<>();
            urlInput.put("Url", file.getUrl());
            inputInfo.put("UrlInputInfo", urlInput);
            fi.put("InputInfo", inputInfo);
            fileInfos.add(fi);
        }
        request.put("FileInfos", fileInfos);

        // ComposeConfig.Tracks
        List<Map<String, Object>> mpsTracks = new ArrayList<>();
        addTrackIfPresent(mpsTracks, "Video", buildVideoTrackItems(tracks.getVideoItems()));
        // 纯 Empty 占位的音轨（全片无配音）不允许下发：MPS 会以「duration is 0」拒绝整个任务；
        // 混合轨（Empty 补位 + 真实配音）合法，正常下发
        if (hasPlayableItem(tracks.getAudioItems())) {
            addTrackIfPresent(mpsTracks, "Audio", buildAudioTrackItems(tracks.getAudioItems()));
        }
        // 背景音轨作为独立音轨参与 MPS 自动混音（同样跳过纯 Empty 防御）
        if (hasPlayableItem(tracks.getBgmItems())) {
            addTrackIfPresent(mpsTracks, "Audio", buildAudioTrackItems(tracks.getBgmItems()));
        }
        addTrackIfPresent(mpsTracks, "Title", buildSubtitleTrackItems(tracks.getSubtitleItems(), props));

        Map<String, Object> composeConfig = new LinkedHashMap<>();
        composeConfig.put("Tracks", mpsTracks);
        composeConfig.put("Styles", buildSubtitleStyles(tracks.getSubtitleItems(), props));
        composeConfig.put("TargetInfo", buildTargetInfo());
        request.put("ComposeConfig", composeConfig);

        // OutputStorage（COS）
        Map<String, Object> outputStorage = new LinkedHashMap<>();
        outputStorage.put("Type", "COS");
        Map<String, Object> cos = new LinkedHashMap<>();
        cos.put("Bucket", props.getOutputBucket());
        cos.put("Region", props.getOutputRegion());
        outputStorage.put("CosOutputStorage", cos);
        request.put("OutputStorage", outputStorage);

        // OutputObjectPath：文件名只用数字/字母/-/_，扩展名必须用 {format} 占位符（MPS 按实际输出容器替换为 mp4）。
        // 不能写死 .mp4，否则 MPS 会在其后再追加一次容器扩展名，导致成片路径出现 compose_xxx.mp4.mp4 双后缀。
        String dir = StrUtil.isBlank(props.getOutputDir()) ? "/compose_result/" : props.getOutputDir();
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        request.put("OutputObjectPath", dir + "compose_" + taskId + ".{format}");

        // TaskNotifyConfig（回调）
        if (StrUtil.isNotBlank(props.getCallbackUrl())) {
            Map<String, Object> notify = new LinkedHashMap<>();
            notify.put("NotifyType", "URL");
            notify.put("NotifyUrl", props.getCallbackUrl());
            request.put("TaskNotifyConfig", notify);
        }

        // SessionContext 透传我方 taskId（供回调匹配）；SessionId 去重
        request.put("SessionContext", String.valueOf(taskId));
        request.put("SessionId", "compose_" + taskId);
        return request;
    }

    /**
     * 构建视频轨 Items。
     *
     * @param items 视频轨项
     * @return MPS Items
     */
    private List<Map<String, Object>> buildVideoTrackItems(List<ComposeTrackItem> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ComposeTrackItem item : items) {
            Map<String, Object> mpsItem = new LinkedHashMap<>();
            mpsItem.put("Type", "Video");
            Map<String, Object> video = new LinkedHashMap<>();
            Map<String, Object> sourceMedia = new LinkedHashMap<>();
            sourceMedia.put("FileId", item.getFileId());
            video.put("SourceMedia", sourceMedia);
            // 拉伸填满段长：TrackTime.Duration ≠ 素材实际时长时 MPS 自动变速（配音超长时慢放画面补齐）
            if (Objects.nonNull(item.getTrackDurationSeconds())) {
                Map<String, Object> trackTime = new LinkedHashMap<>();
                trackTime.put("Duration", secToStr(item.getTrackDurationSeconds()));
                video.put("TrackTime", trackTime);
            }
            mpsItem.put("Video", video);
            result.add(mpsItem);
        }
        return result;
    }

    /**
     * 构建音频轨 Items（配音 / 背景音 / Empty）。
     *
     * @param items 音频轨项
     * @return MPS Items
     */
    private List<Map<String, Object>> buildAudioTrackItems(List<ComposeTrackItem> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ComposeTrackItem item : items) {
            Map<String, Object> mpsItem = new LinkedHashMap<>();
            if (ComposeTrackItemType.EMPTY.equals(item.getType())) {
                // 空白占位元素：走 ComposeEmptyItem，Duration 为字符串秒（如 "3.5s"），不能用 TrackTime
                mpsItem.put("Type", "Empty");
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("Duration", secToStr(item.getDuration()));
                mpsItem.put("Empty", empty);
            } else {
                mpsItem.put("Type", "Audio");
                Map<String, Object> audio = new LinkedHashMap<>();
                Map<String, Object> sourceMedia = new LinkedHashMap<>();
                sourceMedia.put("FileId", item.getFileId());
                audio.put("SourceMedia", sourceMedia);
                // TrackTime 属于 Audio 元素内部（ComposeAudioItem.TrackTime），Start/Duration 为字符串秒
                if (Objects.nonNull(item.getStart()) || Objects.nonNull(item.getDuration())) {
                    Map<String, Object> trackTime = new LinkedHashMap<>();
                    if (Objects.nonNull(item.getStart())) {
                        trackTime.put("Start", secToStr(item.getStart()));
                    }
                    if (Objects.nonNull(item.getDuration())) {
                        trackTime.put("Duration", secToStr(item.getDuration()));
                    }
                    audio.put("TrackTime", trackTime);
                }
                // 音频操作：ComposeAudioOperation 为数组，且仅支持 Type + Volume（MPS 不支持淡入淡出参数）
                if (Objects.nonNull(item.getVolume())) {
                    List<Map<String, Object>> ops = new ArrayList<>();
                    Map<String, Object> volumeOp = new LinkedHashMap<>();
                    volumeOp.put("Type", "Volume");
                    volumeOp.put("Volume", item.getVolume());
                    ops.add(volumeOp);
                    audio.put("AudioOperations", ops);
                }
                mpsItem.put("Audio", audio);
            }
            result.add(mpsItem);
        }
        return result;
    }

    /**
     * 构建字幕轨 Items（绝对钉位）：文本按可配上限自动换行，防止长台词横向超出画面。
     *
     * @param items 字幕轨项
     * @param props MPS 配置（换行字数）
     * @return MPS Items
     */
    private List<Map<String, Object>> buildSubtitleTrackItems(List<ComposeTrackItem> items, MpsProperties props) {
        List<Map<String, Object>> result = new ArrayList<>();
        int wrapChars = props.getSubtitleWrapChars();
        for (ComposeTrackItem item : items) {
            Map<String, Object> mpsItem = new LinkedHashMap<>();
            mpsItem.put("Type", "Subtitle");
            Map<String, Object> subtitle = new LinkedHashMap<>();
            subtitle.put("StyleId", "default_subtitle");
            // MPS 字幕不自动折行，超宽直接溢出画面：按每行上限硬换行
            subtitle.put("Text", wrapSubtitleText(item.getSubtitleText(), wrapChars));
            // TrackTime 属于 Subtitle 元素内部；Start/Duration 为字符串秒
            Map<String, Object> trackTime = new LinkedHashMap<>();
            trackTime.put("Start", secToStr(item.getStart()));
            trackTime.put("Duration", secToStr(item.getDuration()));
            subtitle.put("TrackTime", trackTime);
            mpsItem.put("Subtitle", subtitle);
            result.add(mpsItem);
        }
        return result;
    }

    /**
     * 字幕文本自动换行：按 maxChars 逐行硬折行（保留原有换行），防止单行长台词超出画面宽度。
     *
     * @param text     字幕文本（可含换行）
     * @param maxChars 每行最大字数（≤0 表示不折行）
     * @return 折行后的文本
     */
    private String wrapSubtitleText(String text, int maxChars) {
        if (StrUtil.isBlank(text) || maxChars <= 0) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (int start = 0; start < trimmed.length(); start += maxChars) {
                if (result.length() > 0) {
                    result.append('\n');
                }
                result.append(trimmed, start, Math.min(trimmed.length(), start + maxChars));
            }
        }
        return result.length() == 0 ? text : result.toString();
    }

    /**
     * 构建字幕样式（仅在有字幕时），字号支持后台配置。
     *
     * @param subtitleItems 字幕轨项
     * @param props         MPS 配置（字幕字号）
     * @return Styles 列表
     */
    private List<Map<String, Object>> buildSubtitleStyles(List<ComposeTrackItem> subtitleItems, MpsProperties props) {
        List<Map<String, Object>> styles = new ArrayList<>();
        if (CollectionUtil.isEmpty(subtitleItems)) {
            return styles;
        }
        // ComposeStyles：Id（与元素 StyleId 关联）+ Type 在样式顶层；具体样式在 Subtitle(ComposeSubtitleStyle)
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("Id", "default_subtitle");
        style.put("Type", "Subtitle");
        Map<String, Object> subtitle = new LinkedHashMap<>();
        subtitle.put("FontType", "SimHei");
        // 字号可配（aid_config category=mps, config_name=subtitleFontSize），默认 5%
        subtitle.put("FontSize", StrUtil.isBlank(props.getSubtitleFontSize()) ? "5%" : props.getSubtitleFontSize());
        // 颜色格式必须为 #RRGGBBAA（含透明度，官方示例格式），0x 前缀会被 MPS 判为 InvalidParameterValue；白色不透明
        subtitle.put("FontColor", "#FFFFFFFF");
        subtitle.put("FontAlign", "Center");
        // 居中偏底部（距底部 10%）
        subtitle.put("MarginBottom", "10%");
        style.put("Subtitle", subtitle);
        styles.add(style);
        return styles;
    }

    /**
     * 构建 TargetInfo。
     *
     * @return TargetInfo
     */
    private Map<String, Object> buildTargetInfo() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("Container", "mp4");
        return target;
    }

    /**
     * 仅在 items 非空时追加一条轨道。
     *
     * @param tracks 轨道列表
     * @param type   轨道类型
     * @param items  轨道项
     */
    private void addTrackIfPresent(List<Map<String, Object>> tracks, String type, List<Map<String, Object>> items) {
        if (CollectionUtil.isEmpty(items)) {
            return;
        }
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("Type", type);
        track.put("Items", items);
        tracks.add(track);
    }

    /**
     * 判断轨道项中是否存在可播放的真实素材（非 EMPTY 占位）。
     * 全片无配音时配音轨只剩 Empty 占位，这种轨道 MPS 判定媒体时长为 0 直接拒单，必须整轨跳过。
     *
     * @param items 轨道项列表
     * @return true=存在真实素材
     */
    private boolean hasPlayableItem(List<ComposeTrackItem> items) {
        if (CollectionUtil.isEmpty(items)) {
            return false;
        }
        for (ComposeTrackItem item : items) {
            if (!ComposeTrackItemType.EMPTY.equals(item.getType())) {
                return true;
            }
        }
        return false;
    }
}

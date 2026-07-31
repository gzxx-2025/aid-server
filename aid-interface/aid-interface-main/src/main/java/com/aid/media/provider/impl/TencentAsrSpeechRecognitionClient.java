package com.aid.media.provider.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.exception.ServiceException;
import com.aid.common.moderation.tencent.TencentCloudTc3Signer;
import com.aid.compose.config.TencentAsrConfigManager;
import com.aid.compose.config.TencentAsrProperties;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.media.dto.SpeechRecognitionResult;
import com.aid.media.provider.SpeechRecognitionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 腾讯云录音文件识别同步客户端：CreateRecTask 提交后在当前调用内轮询至终态。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TencentAsrSpeechRecognitionClient implements SpeechRecognitionClient {

    private static final String PROVIDER_CODE = "tencent_asr";
    private static final String ASR_HOST = "asr.tencentcloudapi.com";
    private static final String ASR_ENDPOINT = "https://asr.tencentcloudapi.com";
    private static final String ASR_SERVICE = "asr";
    private static final String ASR_VERSION = "2019-06-14";
    private static final String ACTION_CREATE_TASK = "CreateRecTask";
    private static final String ACTION_QUERY_TASK = "DescribeTaskStatus";
    private static final int RESPONSE_FORMAT_SUBTITLE = 3;
    private static final int SOURCE_TYPE_URL = 0;
    private static final int CHANNEL_SINGLE = 1;
    private static final int STATUS_WAITING = 0;
    private static final int STATUS_PROCESSING = 1;
    private static final int STATUS_SUCCEEDED = 2;
    private static final int STATUS_FAILED = 3;
    private static final int HTTP_TIMEOUT_MS = 30_000;
    private static final long POLL_INTERVAL_MS = 1_000L;
    private static final long RETRY_INTERVAL_MS = 500L;
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final Pattern RESULT_LINE = Pattern.compile(
            "\\[[^:]+:([0-9.]+),[^:]+:([0-9.]+)]\\s*(.+)");

    private final TencentAsrConfigManager configManager;

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public boolean isEnabled() {
        return configManager.isEnabled();
    }

    @Override
    public SpeechRecognitionResult recognize(String mediaUrl) {
        return recognize(mediaUrl, null);
    }

    @Override
    public SpeechRecognitionResult recognize(String mediaUrl, Runnable heartbeatCallback) {
        return recognizeInternal(mediaUrl, heartbeatCallback, true);
    }

    /** 后台配置测试入口：允许在自动字幕总开关关闭时验证已保存凭证与识别参数。 */
    public SpeechRecognitionResult recognizeForTest(String mediaUrl) {
        return recognizeInternal(mediaUrl, null, false);
    }

    private SpeechRecognitionResult recognizeInternal(String mediaUrl, Runnable heartbeatCallback,
                                                       boolean requireEnabled) {
        if (StrUtil.isBlank(mediaUrl)) {
            log.error("腾讯云语音识别媒体地址为空");
            throw new ServiceException("视频地址为空");
        }
        TencentAsrProperties properties = configManager.getProperties();
        if (requireEnabled && !configManager.isConfigured()) {
            log.error("腾讯云语音识别已开启但凭证未配置");
            throw new ServiceException("字幕服务未配置");
        }
        if (!requireEnabled && (StrUtil.isBlank(properties.getSecretId())
                || StrUtil.isBlank(properties.getSecretKey()))) {
            log.error("腾讯云语音识别测试凭证未配置");
            throw new ServiceException("识别凭证未配置");
        }
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                runHeartbeat(heartbeatCallback);
                String taskId = createTask(mediaUrl, properties);
                return waitForResult(taskId, properties, heartbeatCallback);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                log.error("腾讯云语音识别失败, attempt={}, maxAttempts={}, error={}",
                        attempt, properties.getMaxAttempts(), ex.getMessage());
                if (attempt < properties.getMaxAttempts()) {
                    sleep(RETRY_INTERVAL_MS);
                }
            }
        }
        throw Objects.nonNull(lastFailure) ? lastFailure : new ServiceException("字幕生成失败");
    }

    private String createTask(String mediaUrl, TencentAsrProperties properties) {
        JSONObject payload = new JSONObject();
        payload.put("EngineModelType", properties.getEngineModelType());
        payload.put("ChannelNum", CHANNEL_SINGLE);
        payload.put("ResTextFormat", RESPONSE_FORMAT_SUBTITLE);
        payload.put("SourceType", SOURCE_TYPE_URL);
        payload.put("Url", mediaUrl);
        payload.put("SpeakerDiarization", properties.getSpeakerDiarization());
        if (properties.getSpeakerDiarization() > 0) {
            payload.put("SpeakerNumber", 0);
        }
        payload.put("SentenceMaxLength", properties.getSentenceMaxLength());
        if (StrUtil.isNotBlank(properties.getHotwordId())) {
            payload.put("HotwordId", properties.getHotwordId());
        }
        if (StrUtil.isNotBlank(properties.getHotwordList())) {
            payload.put("HotwordList", properties.getHotwordList());
        }
        JSONObject response = responseData(doRequest(ACTION_CREATE_TASK, payload.toJSONString(), properties));
        String taskId = response.getString("TaskId");
        if (StrUtil.isBlank(taskId)) {
            log.error("腾讯云语音识别未返回任务ID");
            throw new ServiceException("字幕提交失败");
        }
        return taskId;
    }

    private SpeechRecognitionResult waitForResult(String taskId, TencentAsrProperties properties,
                                                   Runnable heartbeatCallback) {
        long deadline = System.nanoTime() + properties.getTimeoutSeconds() * 1_000_000_000L;
        long nextHeartbeat = 0L;
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            if (now >= nextHeartbeat) {
                runHeartbeat(heartbeatCallback);
                nextHeartbeat = now + HEARTBEAT_INTERVAL_MS * 1_000_000L;
            }
            JSONObject payload = new JSONObject();
            payload.put("TaskId", new BigInteger(taskId));
            JSONObject data = responseData(doRequest(ACTION_QUERY_TASK, payload.toJSONString(), properties));
            Integer status = data.getInteger("Status");
            if (Objects.equals(status, STATUS_SUCCEEDED)) {
                return normalizeResult(data);
            }
            if (Objects.equals(status, STATUS_FAILED)) {
                String error = StrUtil.blankToDefault(data.getString("ErrorMsg"), "上游识别失败");
                log.error("腾讯云语音识别任务失败, taskId={}, error={}", taskId, error);
                throw new ServiceException("字幕生成失败");
            }
            if (!Objects.equals(status, STATUS_WAITING) && !Objects.equals(status, STATUS_PROCESSING)) {
                log.error("腾讯云语音识别任务状态异常, taskId={}, status={}", taskId, status);
                throw new ServiceException("字幕状态异常");
            }
            sleep(POLL_INTERVAL_MS);
        }
        log.error("腾讯云语音识别等待超时, taskId={}, timeoutSeconds={}",
                taskId, properties.getTimeoutSeconds());
        throw new ServiceException("字幕生成超时");
    }

    SpeechRecognitionResult normalizeResult(JSONObject data) {
        SpeechRecognitionResult result = new SpeechRecognitionResult();
        result.setText(data.getString("Result"));
        result.setDurationSeconds(data.getDouble("AudioDuration"));
        List<TimedSubtitleCue> cues = normalizeDetails(data.getJSONArray("ResultDetail"));
        if (CollectionUtil.isEmpty(cues)) {
            cues = normalizePlainResult(data.getString("Result"));
        }
        if (CollectionUtil.isEmpty(cues)) {
            log.error("腾讯云语音识别成功但无字幕时间戳, taskId={}", data.getString("TaskId"));
            throw new ServiceException("字幕结果为空");
        }
        result.setCues(cues);
        return result;
    }

    private List<TimedSubtitleCue> normalizeDetails(JSONArray details) {
        List<TimedSubtitleCue> cues = new ArrayList<>();
        if (CollectionUtil.isEmpty(details)) {
            return cues;
        }
        for (Object item : details) {
            JSONObject detail = JSON.parseObject(JSON.toJSONString(item));
            String text = detail.getString("FinalSentence");
            Long startMs = detail.getLong("StartMs");
            Long endMs = detail.getLong("EndMs");
            if (StrUtil.isBlank(text) || Objects.isNull(startMs) || Objects.isNull(endMs)
                    || endMs <= startMs) {
                continue;
            }
            TimedSubtitleCue cue = new TimedSubtitleCue();
            cue.setStartSeconds(Math.max(startMs, 0L) / 1000D);
            cue.setEndSeconds(Math.max(endMs, startMs) / 1000D);
            cue.setText(text.trim());
            cue.setSpeaker(resolveSpeaker(detail));
            cue.setSource("ASR");
            cues.add(cue);
        }
        return cues;
    }

    private List<TimedSubtitleCue> normalizePlainResult(String raw) {
        List<TimedSubtitleCue> cues = new ArrayList<>();
        if (StrUtil.isBlank(raw)) {
            return cues;
        }
        for (String line : raw.split("\\R")) {
            Matcher matcher = RESULT_LINE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            double start = Double.parseDouble(matcher.group(1));
            double end = Double.parseDouble(matcher.group(2));
            String text = matcher.group(3).trim();
            if (StrUtil.isBlank(text) || end <= start) {
                continue;
            }
            TimedSubtitleCue cue = new TimedSubtitleCue();
            cue.setStartSeconds(Math.max(start, 0D));
            cue.setEndSeconds(end);
            cue.setText(text);
            cue.setSource("ASR");
            cues.add(cue);
        }
        return cues;
    }

    private String resolveSpeaker(JSONObject detail) {
        String roleName = detail.getString("SpeakerRoleName");
        if (StrUtil.isNotBlank(roleName)) {
            return roleName.trim();
        }
        Integer speakerId = detail.getInteger("SpeakerId");
        return Objects.isNull(speakerId) ? null : "speaker_" + speakerId;
    }

    private JSONObject responseData(String raw) {
        JSONObject root;
        try {
            root = JSON.parseObject(raw);
        } catch (Exception ex) {
            log.error("腾讯云语音识别响应解析失败, responseLen={}", StrUtil.length(raw));
            throw new ServiceException("字幕响应异常");
        }
        JSONObject response = root.getJSONObject("Response");
        if (Objects.isNull(response)) {
            log.error("腾讯云语音识别响应结构异常, responseLen={}", StrUtil.length(raw));
            throw new ServiceException("字幕响应异常");
        }
        JSONObject error = response.getJSONObject("Error");
        if (Objects.nonNull(error)) {
            log.error("腾讯云语音识别接口失败, code={}, message={}",
                    error.getString("Code"), error.getString("Message"));
            throw new ServiceException("字幕服务异常");
        }
        JSONObject data = response.getJSONObject("Data");
        if (Objects.isNull(data)) {
            log.error("腾讯云语音识别缺少Data, responseLen={}", StrUtil.length(raw));
            throw new ServiceException("字幕响应异常");
        }
        return data;
    }

    private String doRequest(String action, String payload, TencentAsrProperties properties) {
        long timestamp = System.currentTimeMillis() / 1000L;
        Map<String, String> headers = TencentCloudTc3Signer.buildHeaders(
                ASR_SERVICE, ASR_HOST, action, ASR_VERSION, properties.getRegion(), payload,
                properties.getSecretId(), properties.getSecretKey(), timestamp);
        try (HttpResponse response = HttpRequest.post(ASR_ENDPOINT)
                .addHeaders(headers)
                .body(payload)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()) {
            return response.body();
        } catch (Exception ex) {
            log.error("腾讯云语音识别请求失败, action={}, error={}", action, ex.getMessage());
            throw new ServiceException("字幕请求失败");
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("腾讯云语音识别等待被中断");
            throw new ServiceException("字幕生成中断");
        }
    }

    private void runHeartbeat(Runnable heartbeatCallback) {
        if (heartbeatCallback != null) {
            heartbeatCallback.run();
        }
    }

}

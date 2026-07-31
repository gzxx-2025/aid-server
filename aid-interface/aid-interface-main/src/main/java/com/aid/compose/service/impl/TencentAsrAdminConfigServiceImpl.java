package com.aid.compose.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.exception.OssException;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.compose.config.TencentAsrConfigManager;
import com.aid.compose.config.TencentAsrProperties;
import com.aid.compose.domain.TimedSubtitleCue;
import com.aid.compose.dto.TencentAsrConfigUpdateCommand;
import com.aid.compose.dto.TencentAsrTestRequest;
import com.aid.compose.dto.TencentAsrTestResult;
import com.aid.compose.service.TencentAsrAdminConfigService;
import com.aid.media.dto.SpeechRecognitionResult;
import com.aid.media.provider.impl.TencentAsrSpeechRecognitionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 腾讯云自动字幕后台配置服务实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TencentAsrAdminConfigServiceImpl implements TencentAsrAdminConfigService {

    private static final String MASK_FLAG = "****";
    private static final int MIN_SENTENCE_LENGTH = 6;
    private static final int MAX_SENTENCE_LENGTH = 40;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MIN_ATTEMPTS = 1;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_TEST_FILE_SIZE = 1024L * 1024L * 1024L;
    private static final String TEST_UPLOAD_DIR = "asr-test";
    private static final Set<String> SUPPORTED_TEST_VIDEO_EXTENSIONS = Set.of("mp4", "flv", "3gp");

    private final ConfigService configService;
    private final IAidConfigService aidConfigService;
    private final TencentAsrConfigManager configManager;
    private final OssTemplate ossTemplate;
    private final MediaUrlResolver mediaUrlResolver;
    private final TencentAsrSpeechRecognitionClient recognitionClient;

    @Override
    public Map<String, String> getMaskedConfig() {
        Map<String, String> config = configService.getConfigValues(TencentAsrConfigManager.CATEGORY);
        Map<String, String> result = CollectionUtil.isEmpty(config) ? new HashMap<>() : new HashMap<>(config);
        maskInPlace(result, "secretId");
        maskInPlace(result, "secretKey");
        return result;
    }

    @Override
    public void save(TencentAsrConfigUpdateCommand command) {
        validate(command);
        try {
            saveBoolean("enabled", command.getEnabled());
            saveSecret("secretId", command.getSecretId());
            saveSecret("secretKey", command.getSecretKey());
            saveString("region", command.getRegion());
            saveString("engineModelType", command.getEngineModelType());
            saveInteger("sentenceMaxLength", command.getSentenceMaxLength());
            saveInteger("speakerDiarization", command.getSpeakerDiarization());
            saveString("hotwordId", command.getHotwordId());
            saveString("hotwordList", command.getHotwordList());
            saveInteger("timeoutSeconds", command.getTimeoutSeconds());
            saveInteger("maxAttempts", command.getMaxAttempts());
            configManager.refresh();
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("保存腾讯云语音识别配置失败", ex);
            throw new ServiceException("保存配置失败");
        }
    }

    @Override
    public TencentAsrTestResult test(TencentAsrTestRequest request) {
        MultipartFile file = validateTestFile(request);
        refreshAndValidateTestConfig();
        long start = System.currentTimeMillis();
        String uploadedPath = null;
        try {
            // 测试文件只临时进入对象存储，供腾讯云按 URL 拉取，不写入业务表。
            uploadedPath = ossTemplate.upload(file, TEST_UPLOAD_DIR);
            String fullUrl = mediaUrlResolver.toFullUrl(uploadedPath);
            String providerUrl = mediaUrlResolver.toProviderUrl(fullUrl);
            String lowerProviderUrl = StrUtil.blankToDefault(providerUrl, "").toLowerCase(Locale.ROOT);
            if (!lowerProviderUrl.startsWith("http://") && !lowerProviderUrl.startsWith("https://")) {
                log.error("腾讯云语音识别测试媒体地址不可公网访问, path={}", uploadedPath);
                throw new ServiceException("视频地址不可用");
            }

            SpeechRecognitionResult recognition = recognitionClient.recognizeForTest(providerUrl);
            return buildTestResult(file, recognition, System.currentTimeMillis() - start);
        } catch (ServiceException | OssException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("腾讯云语音识别测试失败, file={}", file.getOriginalFilename(), ex);
            throw new ServiceException("识别测试失败");
        } finally {
            deleteTestFile(uploadedPath);
        }
    }

    private void refreshAndValidateTestConfig() {
        // 直接改库后也以数据库最新配置测试；总开关关闭不影响管理员主动测试。
        configManager.refresh();
        TencentAsrProperties properties = configManager.getProperties();
        if (Objects.isNull(properties) || StrUtil.isBlank(properties.getSecretId())
                || StrUtil.isBlank(properties.getSecretKey())) {
            log.error("腾讯云语音识别测试凭证未配置");
            throw new ServiceException("识别凭证未配置");
        }
    }

    private MultipartFile validateTestFile(TencentAsrTestRequest request) {
        if (Objects.isNull(request) || Objects.isNull(request.getFile()) || request.getFile().isEmpty()) {
            log.error("腾讯云语音识别测试文件为空");
            throw new ServiceException("请选择测试视频");
        }
        MultipartFile file = request.getFile();
        String extension = StrUtil.blankToDefault(
                FilenameUtils.getExtension(file.getOriginalFilename()), "").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TEST_VIDEO_EXTENSIONS.contains(extension)) {
            log.error("腾讯云语音识别测试格式不支持, extension={}", extension);
            throw new ServiceException("视频格式不支持");
        }
        if (file.getSize() > MAX_TEST_FILE_SIZE) {
            log.error("腾讯云语音识别测试文件超限, size={}", file.getSize());
            throw new ServiceException("视频不能超1GB");
        }
        // 继续复用文件存储配置中的动态扩展名与大小限制。
        ossTemplate.validate(file);
        return file;
    }

    private TencentAsrTestResult buildTestResult(MultipartFile file, SpeechRecognitionResult recognition,
                                                  long elapsedMs) {
        if (Objects.isNull(recognition)) {
            log.error("腾讯云语音识别测试结果为空");
            throw new ServiceException("识别结果为空");
        }
        List<TimedSubtitleCue> cues = CollectionUtil.isEmpty(recognition.getCues())
                ? List.of() : recognition.getCues();
        TencentAsrTestResult result = new TencentAsrTestResult();
        result.setFileName(file.getOriginalFilename());
        result.setFileSize(file.getSize());
        result.setDurationSeconds(recognition.getDurationSeconds());
        result.setText(cues.stream()
                .map(TimedSubtitleCue::getText)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n")));
        result.setRawText(recognition.getText());
        result.setCueCount(cues.size());
        result.setCues(cues);
        result.setElapsedMs(elapsedMs);
        return result;
    }

    private void deleteTestFile(String uploadedPath) {
        if (StrUtil.isBlank(uploadedPath)) {
            return;
        }
        try {
            if (!ossTemplate.deleteByUrl(uploadedPath)) {
                log.warn("腾讯云语音识别测试文件清理失败, path={}", uploadedPath);
            }
        } catch (Exception ex) {
            log.warn("腾讯云语音识别测试文件清理异常, path={}, error={}", uploadedPath, ex.getMessage());
        }
    }

    private void validate(TencentAsrConfigUpdateCommand command) {
        if (Objects.isNull(command)) {
            log.error("腾讯云语音识别配置入参为空");
            throw new ServiceException("参数不能为空");
        }
        validateRange(command.getSentenceMaxLength(), MIN_SENTENCE_LENGTH, MAX_SENTENCE_LENGTH, "字幕字数无效");
        validateRange(command.getSpeakerDiarization(), 0, 1, "说话人配置错");
        validateRange(command.getTimeoutSeconds(), MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, "超时时间无效");
        validateRange(command.getMaxAttempts(), MIN_ATTEMPTS, MAX_ATTEMPTS, "重试次数无效");
        if (Boolean.TRUE.equals(command.getEnabled())) {
            Map<String, String> existing = configService.getConfigValues(TencentAsrConfigManager.CATEGORY);
            String secretId = effectiveSecret(command.getSecretId(), existing.get("secretId"));
            String secretKey = effectiveSecret(command.getSecretKey(), existing.get("secretKey"));
            if (StrUtil.isBlank(secretId) || StrUtil.isBlank(secretKey)
                    || StrUtil.isBlank(command.getRegion()) || StrUtil.isBlank(command.getEngineModelType())) {
                log.error("腾讯云语音识别开启时配置不完整");
                throw new ServiceException("识别配置不全");
            }
        }
    }

    private void validateRange(Integer value, int min, int max, String errorMessage) {
        if (Objects.nonNull(value) && (value < min || value > max)) {
            log.error("腾讯云语音识别配置超范围, value={}, min={}, max={}", value, min, max);
            throw new ServiceException(errorMessage);
        }
    }

    private String effectiveSecret(String submitted, String existing) {
        return StrUtil.isBlank(submitted) || submitted.contains(MASK_FLAG) ? existing : submitted.trim();
    }

    private void saveString(String name, String value) {
        if (Objects.nonNull(value)) {
            aidConfigService.upsertConfigValue(TencentAsrConfigManager.CATEGORY, name, value.trim());
        }
    }

    private void saveBoolean(String name, Boolean value) {
        if (Objects.nonNull(value)) {
            aidConfigService.upsertConfigValue(
                    TencentAsrConfigManager.CATEGORY, name, String.valueOf(value));
        }
    }

    private void saveInteger(String name, Integer value) {
        if (Objects.nonNull(value)) {
            aidConfigService.upsertConfigValue(
                    TencentAsrConfigManager.CATEGORY, name, String.valueOf(value));
        }
    }

    private void saveSecret(String name, String value) {
        if (StrUtil.isNotBlank(value) && !value.contains(MASK_FLAG)) {
            aidConfigService.upsertConfigValue(TencentAsrConfigManager.CATEGORY, name, value.trim());
        }
    }

    private void maskInPlace(Map<String, String> config, String key) {
        String value = config.get(key);
        if (StrUtil.isBlank(value)) {
            return;
        }
        config.put(key, value.length() > 8
                ? value.substring(0, 4) + MASK_FLAG + value.substring(value.length() - 4) : MASK_FLAG);
    }
}

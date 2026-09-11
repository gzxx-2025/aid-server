package com.aid.voice.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.aid.aid.domain.AidAiVoiceLibrary;
import com.aid.aid.service.IAidAiVoiceLibraryService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.util.TrustedMediaProbe;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** 参考式音色只从已授权的本地音色库或后台样本读取，并固化内容指纹。 */
@Service
@RequiredArgsConstructor
public class VoiceReferenceSampleService {
    public static final int MAX_SAMPLE_BYTES = 10 * 1024 * 1024 / 4 * 3;
    private final IAidAiVoiceLibraryService voices;
    private final MediaUrlResolver urls;
    private final TrustedMediaProbe probe;

    public void prepare(AiModelConfigVo config, MediaAudioGenerateRequest request) {
        prepare(config, request, true);
    }

    public void prepare(AiModelConfigVo config, MediaAudioGenerateRequest request, boolean verifyMetadata) {
        if (!isReferenceModel(config)) return;
        String source = request.getTrustedReferenceSampleUrl();
        if (StrUtil.isBlank(source)) {
            AidAiVoiceLibrary voice = voices.getOne(Wrappers.<AidAiVoiceLibrary>lambdaQuery()
                    .eq(AidAiVoiceLibrary::getModelId, config.getId()).eq(AidAiVoiceLibrary::getProviderId, config.getProviderId())
                    .eq(AidAiVoiceLibrary::getVoiceCode, request.getVoiceCode()).eq(AidAiVoiceLibrary::getStatus, "0")
                    .eq(AidAiVoiceLibrary::getDelFlag, "0").last("limit 1"), false);
            if (voice == null || voice.getOfflineTime() != null && !voice.getOfflineTime().after(new Date())) {
                throw new ServiceException("参考音色不可用");
            }
            source = voice.getSampleUrl();
        }
        if (!urls.isSiteImageUrl(source)) throw new ServiceException("样本地址不可用");
        Map<String, Object> options = new LinkedHashMap<>(request.getOptions() == null ? Map.of() : request.getOptions());
        options.put("referenceSampleUrl", source);
        options.remove("referenceSampleSha256");
        if (verifyMetadata) {
            Sample sample = fetch(source);
            TrustedMediaProbe.Metadata metadata = probe.audio(sample.bytes());
            if (!"wav".equals(metadata.format()) && !"mp3".equals(metadata.format())) throw new ServiceException("样本格式不支持");
            options.put("referenceSampleSha256", SecureUtil.sha256().digestHex(sample.bytes()));
        }
        request.setOptions(options);
    }

    public Sample forSubmission(MediaAudioGenerateRequest request) {
        Object source = request.getOptions() == null ? null : request.getOptions().get("referenceSampleUrl");
        Object digest = request.getOptions() == null ? null : request.getOptions().get("referenceSampleSha256");
        if (!(source instanceof String url) || !(digest instanceof String hash)) throw new ServiceException("参考样本未核验");
        Sample sample = fetch(url);
        if (!SecureUtil.sha256().digestHex(sample.bytes()).equals(hash)) throw new ServiceException("参考样本已变化");
        return sample;
    }

    public Sample fetch(String source) {
        HttpURLConnection connection = null;
        try {
            if (!urls.isSiteImageUrl(source)) throw new ServiceException("样本地址不可用");
            String full = urls.toFullUrl(source);
            if (!urls.isSiteImageUrl(full)) throw new ServiceException("样本地址不可用");
            URI uri = URI.create(full);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new ServiceException("样本地址不可用");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != 200) throw new ServiceException("样本读取失败");
            byte[] bytes;
            try (var stream = connection.getInputStream()) { bytes = stream.readNBytes(MAX_SAMPLE_BYTES + 1); }
            if (bytes.length == 0 || bytes.length > MAX_SAMPLE_BYTES) throw new ServiceException("样本编码大小超限");
            String mime = com.aid.media.util.WavAudioSupport.isWav(bytes) ? "audio/wav" : "audio/mpeg";
            return new Sample(bytes, mime);
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("样本读取失败"); }
        finally { if (connection != null) connection.disconnect(); }
    }

    public static boolean isReferenceModel(AiModelConfigVo config) {
        return "tokendance".equalsIgnoreCase(config.getProviderCode())
                && "tokendance:openai:chat-completions".equals(config.getProtocol())
                && "mimo-v2.5-tts-voiceclone".equals(config.getRealModelCode());
    }

    public record Sample(byte[] bytes, String mime) {
        @Override public String toString() { return "VoiceSample[bytes=" + bytes.length + "]"; }
    }
}

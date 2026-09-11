package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.service.IAidAiModelService;
import com.aid.common.exception.ServiceException;
import com.aid.media.provider.AudioProviderClient;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.VideoProviderClient;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 暴露当前运行时实际支持的模型协议。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProtocolRegistry {
    private final ObjectProvider<ImageProviderClient> images;
    private final ObjectProvider<VideoProviderClient> videos;
    private final ObjectProvider<AudioProviderClient> audios;
    private final ObjectProvider<TextProviderClient> texts;
    private final IAidAiModelService models;

    public boolean supports(String type, String protocol) {
        if (protocol == null || protocol.isBlank() || type == null) return false;
        return switch (type) {
            case "image" -> images.stream().filter(p -> p.supportsProtocol(protocol)).count() == 1;
            case "video" -> videos.stream().filter(p -> p.supportsProtocol(protocol)).count() == 1;
            case "audio" -> audios.stream().filter(p -> p.supportsProtocol(protocol)).count() == 1;
            case "text" -> texts.stream().filter(p -> p.supportsProtocol(protocol)).count() == 1;
            default -> false;
        };
    }

    public List<Map<String, String>> options(String type) {
        String modelType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        Stream<String> runtimeProtocols = switch (modelType) {
            case "image" -> Stream.concat(images.stream().map(ImageProviderClient::protocol),
                    TokenDanceProtocols.IMAGE_PROTOCOLS.stream());
            case "video" -> Stream.concat(videos.stream().map(VideoProviderClient::protocol),
                    TokenDanceProtocols.VIDEO_PROTOCOLS.stream());
            case "text" -> Stream.concat(texts.stream().map(TextProviderClient::protocol),
                    TokenDanceProtocols.TEXT_PROTOCOLS.stream());
            case "audio" -> Stream.concat(audios.stream().map(AudioProviderClient::protocol),
                    Stream.of("tokendance:ark:tts", "tokendance:ark:tts_ws",
                            "tokendance:minimax:t2a_v2_ws", "tokendance:minimax:voice_clone",
                            TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS));
            default -> {
                log.info("查询协议的模型类型无效: modelType={}", type);
                throw new ServiceException("模型类型不支持");
            }
        };
        Set<String> protocols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        runtimeProtocols.filter(Objects::nonNull).map(String::trim)
                .filter(p -> !p.isEmpty()).forEach(protocols::add);
        // 适配器可能支持历史别名，只有真正可被运行时识别的配置才进入选项。
        // 仅投影一个可空列时，MyBatis 会把全空结果行映射成 null；同时查询主键并过滤空协议。
        models.list(Wrappers.<AidAiModel>lambdaQuery().select(AidAiModel::getId, AidAiModel::getProtocol)
                        .eq(AidAiModel::getModelType, modelType).eq(AidAiModel::getDelFlag, "0")
                        .isNotNull(AidAiModel::getProtocol))
                .stream().filter(Objects::nonNull).map(AidAiModel::getProtocol)
                .filter(Objects::nonNull).map(String::trim).filter(p -> !p.isEmpty())
                .forEach(protocols::add);
        return protocols.stream().filter(p -> supports(modelType, p))
                .map(p -> Map.of("value", p, "label", p)).toList();
    }

    public void validate(AidAiModel model) {
        if (model.getCapabilities() == null) return;
        String type = model.getModelType();
        if (type == null && model.getId() != null) type = models.getById(model.getId()).getModelType();
        for (ModelCapabilityDefinition capability : model.getCapabilities()) {
            ModelRequestParameters.validate(type, capability);
            for (var route : capability.getBindings()) {
                if (Boolean.TRUE.equals(route.getEnabled()) && !supports(type, route.getProtocol())) {
                    log.info("模型协议未实现: modelType={}, protocol={}", type, route.getProtocol());
                    throw new ServiceException("调用协议未实现");
                }
            }
        }
    }
}

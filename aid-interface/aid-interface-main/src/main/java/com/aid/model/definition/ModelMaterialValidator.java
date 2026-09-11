package com.aid.model.definition;

import com.aid.aid.domain.model.ModelParameter;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 可配置素材限制使用共用元数据校验器，不能依赖扩展名或客户端声明的时长。 */
public final class ModelMaterialValidator {
    private ModelMaterialValidator() { }

    public static void validate(AiModelConfigVo model, Object request, VerifiedMediaMetadataService metadata) {
        if (model == null || model.getResolvedDefinition() == null) return;
        Map<String, Object> parameters = JSON.parseObject(JSON.toJSONString(request));
        if (request instanceof MediaVideoGenerateRequest video && video.getResolvedReferenceVideos() != null)
            parameters.put("referenceVideoRecordIds", video.getResolvedReferenceVideos().stream().map(item -> item.getVideoUrl()).toList());
        walk(model.getResolvedDefinition().getParameters(), parameters, metadata);
        if (!(request instanceof MediaVideoGenerateRequest) && ModelMaterialStatistics.required(model.getResolvedDefinition())) {
            parameters.put("materials", ModelMaterialStatistics.fromDeclaredRequest(model.getResolvedDefinition(), request, metadata));
            ModelParameterValidator.normalize(model.getResolvedDefinition(), parameters);
        }
    }

    private static void walk(List<ModelParameter> fields, Map<?, ?> values, VerifiedMediaMetadataService metadata) {
        if (fields == null) return;
        for (var field : fields) {
            Object value = values.get(field.getName());
            if (value == null) continue;
            if (field.getMaterialRole() != null && (field.getFormats() != null && !field.getFormats().isEmpty()
                    || field.getMaxFileSizeMb() != null || field.getMinDurationSeconds() != null || field.getMaxDurationSeconds() != null || field.getMaxTotalDurationSeconds() != null)) {
                Set<String> urls = new LinkedHashSet<>(); collect(urls, value);
                if (urls.isEmpty() && !(value instanceof List<?> list && list.isEmpty())) fail(field, "无法解析素材地址");
                String type = "reference_video".equals(field.getMaterialRole()) ? "video" : "reference_audio".equals(field.getMaterialRole()) ? "audio" : "image";
                BigDecimal total = BigDecimal.ZERO;
                for (String url : urls) {
                    var actual = metadata.inspect(url, type);
                    if (field.getFormats() != null && !field.getFormats().isEmpty() && field.getFormats().stream().noneMatch(format -> equivalent(format, actual.format()))) fail(field, "素材格式不支持");
                    if (field.getMaxFileSizeMb() != null && BigDecimal.valueOf(actual.sizeBytes()).compareTo(field.getMaxFileSizeMb().multiply(BigDecimal.valueOf(1048576))) > 0) fail(field, "素材文件过大");
                    if (field.getMinDurationSeconds() != null || field.getMaxDurationSeconds() != null || field.getMaxTotalDurationSeconds() != null) {
                        if (actual.durationSeconds() == null) fail(field, "素材缺少可信时长");
                        if (field.getMinDurationSeconds() != null && actual.durationSeconds().compareTo(field.getMinDurationSeconds()) < 0) fail(field, "素材时长过短");
                        if (field.getMaxDurationSeconds() != null && actual.durationSeconds().compareTo(field.getMaxDurationSeconds()) > 0) fail(field, "素材时长过长");
                        total = total.add(actual.durationSeconds());
                    }
                }
                if (field.getMaxTotalDurationSeconds() != null && total.compareTo(field.getMaxTotalDurationSeconds()) > 0) fail(field, "素材总时长超过上限");
            }
            if (value instanceof Map<?, ?> nested) walk(field.getProperties(), nested, metadata);
            if (value instanceof List<?> list && field.getItems() != null) for (Object item : list) {
                if (item != null) walk(List.of(field.getItems()), Map.of(field.getItems().getName(), item), metadata);
            }
        }
    }

    static void collect(Set<String> urls, Object value) {
        if (value instanceof String text && !text.isBlank()) urls.add(text);
        else if (value instanceof List<?> list) list.forEach(item -> collect(urls, item));
        else if (value instanceof Map<?, ?> map) for (String key : List.of("url", "image_url", "imageUrl", "key_image", "keyImage", "video_url", "videoUrl", "sampleUrl")) {
            if (map.get(key) != null) { collect(urls, map.get(key)); break; }
        }
    }
    private static boolean equivalent(String configured, String actual) {
        return normalize(configured).equals(normalize(actual));
    }
    private static String normalize(String value) {
        String format = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return "jpeg".equals(format) ? "jpg" : format;
    }
    private static void fail(ModelParameter field, String message) { throw new ServiceException((field.getLabel() == null ? field.getName() : field.getLabel()) + "：" + message); }
}

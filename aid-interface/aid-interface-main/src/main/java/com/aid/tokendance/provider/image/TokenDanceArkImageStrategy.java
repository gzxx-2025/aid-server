package com.aid.tokendance.provider.image;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.tokendance.provider.common.TokenDanceEndpoints;
import com.aid.tokendance.provider.common.TokenDancePayloadSupport;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** TokenDance Ark Image Generations 请求方言。 */
final class TokenDanceArkImageStrategy implements TokenDanceImageProtocolStrategy
{
    private static final Set<String> OPTIONAL_FIELDS = Set.of(
            "seed", "watermark", "output_format", "optimize_prompt_options", "response_format");
    private static final Set<String> SAFE_CONFIG_DEFAULTS = Set.of(
            "seed", "watermark", "output_format", "response_format");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "seed", "watermark", "output_format", "optimize_prompt_options", "response_format",
            "stream", "enable_sequential", "sequential_image_generation",
            "sequential_image_generation_options",
            "referenceImages", "images", "n", "aspect_ratio", "aspectRatio", "ratio");
    private static final Set<String> LITE_PRESETS = Set.of("2K", "3K", "4K");
    private static final Set<String> PRO_PRESETS = Set.of("1K", "2K");
    private static final Set<String> SEEDREAM_RATIOS = Set.of(
            "1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3", "21:9");

    @Override
    public String protocol()
    {
        return TokenDanceProtocols.ARK_IMAGE_GENERATIONS;
    }

    @Override
    public String endpoint()
    {
        return TokenDanceEndpoints.ARK_IMAGES;
    }

    @Override
    public Map<String, Object> buildBody(AiModelConfigVo modelConfig, MediaImageGenerateRequest request)
    {
        Map<String, Object> requestOptions = request.getOptions() == null
                ? Map.of() : request.getOptions();
        TokenDancePayloadSupport.rejectUnknownRequestOptions(modelConfig, requestOptions, REQUEST_FIELDS);
        if (StrUtil.isNotBlank(request.getNegativePrompt()))
        {
            throw new ServiceException("该协议不支持负向词");
        }
        Map<String, Object> options = TokenDancePayloadSupport.requestScopedOptions(
                modelConfig, requestOptions, SAFE_CONFIG_DEFAULTS);
        String model = TokenDancePayloadSupport.upstreamModel(modelConfig, request.getModelName());
        ModelVariant variant = variant(model);
        int count = request.getExpectedImageCount() == null ? 1 : request.getExpectedImageCount();
        if (count <= 0)
        {
            throw new ServiceException("图片数量无效");
        }
        boolean stream = strictBoolean(requestOptions, "stream", false);
        boolean sequential = sequentialRequested(requestOptions, count);
        requireSupportedCombination(variant, stream, sequential);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", request.getPrompt());
        if (StrUtil.isNotBlank(request.getSize()))
        {
            String size = normalizePixels(request.getSize());
            size = normalizePreset(variant, size);
            body.put("size", size);
        }
        applyAspectRatio(body, requestOptions, variant);
        if (sequential)
        {
            requireSequentialCount(requestOptions, count);
            body.put("sequential_image_generation", "auto");
            body.put("sequential_image_generation_options", Map.of("max_images", count));
        }
        if (stream)
        {
            body.put("stream", true);
        }
        // 输入媒体只能来自已经进入能力校验和计费快照的请求字段；供应商 extra_body
        // 不能注入额外 images/referenceImages 绕过数量、所有权或附加费校验。
        List<String> images = TokenDancePayloadSupport.concatUrls(request.getReferenceImageUrl(),
                requestOptions.get("referenceImages"), requestOptions.get("images"));
        int referenceLimit = variant == ModelVariant.PRO ? 10
                : variant == ModelVariant.LITE ? 14 : Integer.MAX_VALUE;
        if (images.size() > referenceLimit)
        {
            throw new ServiceException("参考图数量超限");
        }
        if (variant == ModelVariant.LITE && (long) images.size() + count > 15) {
            throw new ServiceException("输入输出图片超限");
        }
        if (images.size() == 1)
        {
            body.put("image", images.get(0));
        }
        else if (!images.isEmpty())
        {
            body.put("image", images);
        }
        TokenDancePayloadSupport.copyAllowlisted(body, options, OPTIONAL_FIELDS);
        validateOutputFields(body);
        body.putIfAbsent("response_format", "url");
        return body;
    }

    private void applyAspectRatio(Map<String, Object> body, Map<String, Object> options,
            ModelVariant variant)
    {
        String ratio = null;
        for (String key : List.of("aspect_ratio", "aspectRatio", "ratio"))
        {
            Object raw = options.get(key);
            if (raw == null) continue;
            if (!(raw instanceof String text) || text.isBlank())
            {
                throw new ServiceException("画面比例不支持");
            }
            String value = text.trim();
            if (ratio != null && !ratio.equals(value))
            {
                throw new ServiceException("画面比例冲突");
            }
            ratio = value;
        }
        if (ratio == null) return;
        if (variant == ModelVariant.OTHER || !SEEDREAM_RATIOS.contains(ratio))
        {
            throw new ServiceException("画面比例不支持");
        }
        String size = String.valueOf(body.getOrDefault("size", ""));
        if (size.matches("\\d+x\\d+"))
        {
            String[] pixels = size.split("x");
            String[] parts = ratio.split(":");
            double actual = Double.parseDouble(pixels[0]) / Double.parseDouble(pixels[1]);
            double expected = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            // 官方参考尺寸存在像素取整；允许相对 2% 的取整偏差，不覆盖显式宽高。
            if (!Double.isFinite(actual) || Math.abs(actual / expected - 1) > 0.02)
            {
                throw new ServiceException("尺寸与比例冲突");
            }
            return;
        }
        // 采用原厂公布的参考宽高，明确 size 以使报价像素与上游请求一致。
        List<String> ratios = List.of("1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3", "21:9");
        List<String> pixels = switch (variant.name() + ":" + size) {
            case "PRO:1K" -> List.of("1024x1024", "1152x864", "864x1152", "1424x800", "800x1424", "1248x832", "832x1248", "1568x672");
            case "PRO:2K" -> List.of("2048x2048", "2368x1776", "1776x2368", "2816x1584", "1584x2816", "2496x1664", "1664x2496", "3136x1344");
            case "LITE:2K" -> List.of("2048x2048", "2304x1728", "1728x2304", "2848x1600", "1600x2848", "2496x1664", "1664x2496", "3136x1344");
            case "LITE:3K" -> List.of("3072x3072", "3456x2592", "2592x3456", "4096x2304", "2304x4096", "3744x2496", "2496x3744", "4704x2016");
            case "LITE:4K" -> List.of("4096x4096", "4704x3520", "3520x4704", "5504x3040", "3040x5504", "4992x3328", "3328x4992", "6240x2656");
            default -> throw new ServiceException("请指定图片规格");
        };
        body.put("size", pixels.get(ratios.indexOf(ratio)));
    }

    private ModelVariant variant(String model)
    {
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if ("seedream-5.0-lite".equals(normalized))
        {
            return ModelVariant.LITE;
        }
        if ("seedream-5.0-pro".equals(normalized))
        {
            return ModelVariant.PRO;
        }
        return ModelVariant.OTHER;
    }

    /** 相同 Seedream 模型的尺寸约束与协议无关，OpenAI 方言仅复用尺寸换算，不复用请求体。 */
    static String resolveSeedreamSize(String model, String size, Map<String, Object> options)
    {
        TokenDanceArkImageStrategy resolver = new TokenDanceArkImageStrategy();
        ModelVariant variant = resolver.variant(model);
        Map<String, Object> dimensions = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(size)) {
            dimensions.put("size", resolver.normalizePreset(variant, resolver.normalizePixels(size)));
        }
        resolver.applyAspectRatio(dimensions, options, variant);
        return (String) dimensions.get("size");
    }

    private void requireSupportedCombination(ModelVariant variant, boolean stream,
            boolean sequential)
    {
        if (variant == ModelVariant.LITE)
        {
            return;
        }
        if (stream)
        {
            throw new ServiceException(variant == ModelVariant.PRO
                    ? "专业版不支持流式" : "该模型不支持流式");
        }
        if (sequential)
        {
            throw new ServiceException(variant == ModelVariant.PRO
                    ? "专业版不支持组图" : "该模型不支持组图");
        }
    }

    private boolean sequentialRequested(Map<String, Object> options, int count)
    {
        Object enabled = options.get("enable_sequential");
        if (enabled != null && !(enabled instanceof Boolean))
        {
            throw new ServiceException("组图开关无效");
        }
        Object upstream = options.get("sequential_image_generation");
        if (upstream != null && !Set.of("auto", "disabled").contains(String.valueOf(upstream)))
        {
            throw new ServiceException("组图模式无效");
        }
        boolean requested = Boolean.TRUE.equals(enabled) || "auto".equals(upstream)
                || options.get("sequential_image_generation_options") != null || count > 1;
        if (requested && (Boolean.FALSE.equals(enabled) || "disabled".equals(upstream)))
        {
            throw new ServiceException("组图参数冲突");
        }
        return requested;
    }

    private void requireSequentialCount(Map<String, Object> options, int count)
    {
        Object raw = options.get("sequential_image_generation_options");
        if (raw == null)
        {
            return;
        }
        if (!(raw instanceof Map<?, ?> map) || map.size() != 1 || !map.containsKey("max_images")
                || !(map.get("max_images") instanceof Number number)
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.intValue() != count)
        {
            throw new ServiceException("组图数量不一致");
        }
    }

    private boolean strictBoolean(Map<String, Object> options, String key, boolean fallback)
    {
        Object raw = options.get(key);
        if (raw == null)
        {
            return fallback;
        }
        if (!(raw instanceof Boolean value))
        {
            throw new ServiceException("流式开关无效");
        }
        return value;
    }

    private void validateOutputFields(Map<String, Object> body)
    {
        Object output = body.get("output_format");
        if (output != null && !Set.of("png", "jpeg").contains(String.valueOf(output)))
        {
            throw new ServiceException("图片格式不支持");
        }
        Object response = body.get("response_format");
        if (response != null && !Set.of("url", "b64_json").contains(String.valueOf(response)))
        {
            throw new ServiceException("图片响应格式错误");
        }
        if (body.get("watermark") != null && !(body.get("watermark") instanceof Boolean))
        {
            throw new ServiceException("水印开关无效");
        }
        if (body.get("seed") != null && !(body.get("seed") instanceof Number))
        {
            throw new ServiceException("图片种子无效");
        }
    }

    private String normalizePreset(ModelVariant variant, String size)
    {
        if (size.matches("(?i)\\d+x\\d+"))
        {
            try {
                String[] parts = size.split("x");
                long width = Long.parseLong(parts[0]);
                long height = Long.parseLong(parts[1]);
                long pixels = Math.multiplyExact(width, height);
                long min = variant == ModelVariant.PRO ? 921600 : 3686400;
                long max = variant == ModelVariant.PRO ? 4624220 : 16777216;
                if (variant != ModelVariant.OTHER && (width <= 0 || height <= 0
                        || pixels < min || pixels > max || (double) width / height < 1.0 / 16
                        || (double) width / height > 16)) throw new ServiceException("图片尺寸超限");
            } catch (ArithmeticException | NumberFormatException ex) {
                throw new ServiceException("图片尺寸无效");
            }
            return size;
        }
        String preset = size.toUpperCase(Locale.ROOT);
        if (variant == ModelVariant.LITE && !LITE_PRESETS.contains(preset)
                || variant == ModelVariant.PRO && !PRO_PRESETS.contains(preset))
        {
            throw new ServiceException("图片规格不支持");
        }
        return preset;
    }

    private String normalizePixels(String value)
    {
        return value.trim().replace('×', 'x').replace('*', 'x').replace('X', 'x');
    }

    private enum ModelVariant
    {
        LITE,
        PRO,
        OTHER
    }
}

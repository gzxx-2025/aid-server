package com.aid.billing.estimate;

import cn.hutool.core.text.CharSequenceUtil;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.domain.vo.AiModelConfigVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * TOKEN 计费口径的预冻结参数估算策略。
 */
@Slf4j
@Component
public class TokenBillingEstimateStrategy implements BillingEstimateStrategy {

    @Override
    public void enrichEstimate(BillingInput billingInput, AiModelConfigVo modelConfig) {
        if (billingInput == null || billingInput.getParams() == null) {
            return;
        }
        Map<String, Object> params = billingInput.getParams();
        String mediaType = billingInput.getMediaType();
        String modelCode = modelConfig != null ? modelConfig.getModelCode() : null;

        // 文本模型：fromTextRequest 已提供 inputTokens/outputTokens，无需二次估算
        if ("TEXT".equalsIgnoreCase(mediaType)) {
            return;
        }

        // 图片模型走 TOKEN 计费：按模型族子分发估算
        if ("IMAGE".equalsIgnoreCase(mediaType)) {
            enrichForImageToken(params, modelCode);
        }
    }
    /**
     * 图片 TOKEN 模型估算入口：按模型族分发。
     * 当前支持 Gemini 系列与 OpenAI gpt-image 系列，未来可在此挂接新模型族规则。
     */
    private void enrichForImageToken(Map<String, Object> params, String modelCode) {
        if (isGeminiImageModel(modelCode)) {
            enrichForGeminiImage(params, modelCode);
            return;
        }
        if (isGptImage2Model(modelCode)) {
            enrichForGptImage2(params, modelCode);
            return;
        }

        // 兜底：未识别的 TOKEN 图片模型，使用通用字符→token 换算
        enrichFallbackImageToken(params);
    }

    /**
     * gpt-image-2 等 OpenAI GPT Image 模型：按 quality × 画幅朝向 查官方成本换算表预估输出 token。
     * 输入 token = 文本 prompt 字符换算 + 编辑模式参考图高保真输入 token 预估；
     * 输出 token = 单张查表值 × 出图张数。
     * 结算阶段使用 provider 返回的真实 usage（input_tokens/output_tokens）重算，此处仅用于预冻结。
     */
    private void enrichForGptImage2(Map<String, Object> params, String modelCode) {
        // 输入 token：文本字符换算
        int inputChars = safeGetInt(params, "inputChars", 0);
        int inputTokens = BillingConstants.charsToTokens(inputChars);
        // 编辑模式参考图高保真输入 token：官方说明 gpt-image-2 恒以高保真处理输入图，编辑请求 input token 显著增加
        int refImageCount = safeGetInt(params, "referenceImageCount", 0);
        if (refImageCount > 0) {
            inputTokens += refImageCount * BillingConstants.GPT_IMAGE2_INPUT_TOKENS_PER_REF_IMAGE;
        }
        params.put("inputTokens", inputTokens);

        // 输出 token：按 quality × 画幅朝向查表
        String quality = resolveGptImageQuality(params);
        String orientation = resolveOrientation(params);
        int expectedImageCount = Math.max(1, safeGetInt(params, "expectedImageCount",
                safeGetInt(params, "imageCount", 1)));
        int tokensPerImage = BillingConstants.gptImage2OutputTokens(quality, orientation);
        int estimatedOutputTokens = tokensPerImage * expectedImageCount;
        params.put("outputTokens", estimatedOutputTokens);
        params.put("estimatedOutputChars", 0);
        params.put("totalChars", inputChars);

        log.info("[TOKEN估算-gptImage2] modelCode={}, quality={}, orientation={}, tokensPerImage={}, " +
                        "imageCount={}, refImageCount={}, inputTokens={}, outputTokens={}",
                modelCode, quality, orientation, tokensPerImage, expectedImageCount, refImageCount,
                inputTokens, estimatedOutputTokens);
    }

    /**
     * Gemini 图片 TOKEN 模型：按官方文档 token 表查表估算输出 token。
     * 输入 token 按字符→token 通用公式换算。
     * 输出 token 按 (modelCode + resolution + aspectRatio) 三维查表。
     * 结算阶段使用 provider 返回的真实 usage，此处仅用于预冻结。
     */
    private void enrichForGeminiImage(Map<String, Object> params, String modelCode) {
        // 输入 token：从 inputChars 换算
        int inputChars = safeGetInt(params, "inputChars", 0);
        int inputTokens = BillingConstants.charsToTokens(inputChars);
        params.put("inputTokens", inputTokens);

        // 输出 token：按官方文档 token 表查表（model + resolution + aspectRatio）
        String resolution = resolveImageSizeTier(params);
        String aspectRatio = resolveAspectRatio(params);
        int expectedImageCount = Math.max(1, safeGetInt(params, "expectedImageCount",
                safeGetInt(params, "imageCount", 1)));
        int tokensPerImage = BillingConstants.geminiImageOutputTokens(modelCode, resolution, aspectRatio);
        int estimatedOutputTokens = tokensPerImage * expectedImageCount;
        params.put("outputTokens", estimatedOutputTokens);
        params.put("estimatedOutputChars", 0);
        params.put("totalChars", inputChars);

        log.info("[TOKEN估算-Gemini图片] modelCode={}, resolution={}, aspectRatio={}, tokensPerImage={}, " +
                        "imageCount={}, inputTokens={}, outputTokens={}",
                modelCode, resolution, aspectRatio, tokensPerImage, expectedImageCount,
                inputTokens, estimatedOutputTokens);
    }

    /**
     * 兜底：未识别的 TOKEN 图片模型，使用通用字符→token 换算。
     */
    private void enrichFallbackImageToken(Map<String, Object> params) {
        int inputChars = safeGetInt(params, "inputChars", 0);
        int inputTokens = BillingConstants.charsToTokens(inputChars);
        params.put("inputTokens", inputTokens);
        // 兜底输出 token：按默认输出字数换算
        int outputTokens = BillingConstants.charsToTokens(BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS);
        params.put("outputTokens", outputTokens);
        params.put("estimatedOutputChars", 0);
        params.put("totalChars", inputChars);
    }
    /**
     * 判断是否为 Gemini 图片模型（modelCode 含 "gemini" 且含 "image"）。
     */
    private boolean isGeminiImageModel(String modelCode) {
        if (CharSequenceUtil.isBlank(modelCode)) {
            return false;
        }
        String lower = modelCode.toLowerCase();
        return lower.contains("gemini") && lower.contains("image");
    }

    /**
     * 判断是否为 OpenAI GPT Image 模型（modelCode 含 "gpt-image"，覆盖 gpt-image-2 及后续版本）。
     */
    private boolean isGptImage2Model(String modelCode) {
        if (CharSequenceUtil.isBlank(modelCode)) {
            return false;
        }
        return modelCode.toLowerCase().contains("gpt-image");
    }

    /**
     * 解析 gpt-image quality 档位。
     * 优先 params.quality（来自 options.quality）；缺省按 "high" 预扣（只退不补，宁高勿低）。
     */
    private String resolveGptImageQuality(Map<String, Object> params) {
        Object q = params.get("quality");
        if (q != null) {
            String s = String.valueOf(q).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "high";
    }

    /**
     * 解析画幅朝向：square/portrait/landscape。
     * 优先 rawSize（"宽x高"），其次 aspectRatio / ratio（"16:9" 等）；都无法判定时按 square（同档最高，预扣宁高勿低）。
     */
    private String resolveOrientation(Map<String, Object> params) {
        String fromSize = orientationFromSize(strParam(params, "rawSize"));
        if (fromSize != null) {
            return fromSize;
        }
        String ratio = strParam(params, "aspectRatio");
        if (ratio == null) {
            ratio = strParam(params, "ratio");
        }
        String fromRatio = orientationFromRatio(ratio);
        if (fromRatio != null) {
            return fromRatio;
        }
        return "square";
    }

    /** 由 "宽x高" 字符串判定朝向；非法返回 null。 */
    private String orientationFromSize(String size) {
        if (CharSequenceUtil.isBlank(size)) {
            return null;
        }
        String[] dims = size.trim().split("[*xX×]");
        if (dims.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(dims[0].trim());
            int h = Integer.parseInt(dims[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            if (w == h) {
                return "square";
            }
            return w > h ? "landscape" : "portrait";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 由 "宽:高" 比例字符串判定朝向；非法返回 null。 */
    private String orientationFromRatio(String ratio) {
        if (CharSequenceUtil.isBlank(ratio)) {
            return null;
        }
        String[] parts = ratio.trim().split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            if (w == h) {
                return "square";
            }
            return w > h ? "landscape" : "portrait";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取 params 中的字符串值，空白返回 null。 */
    private String strParam(Map<String, Object> params, String key) {
        if (params == null || key == null) {
            return null;
        }
        Object val = params.get(key);
        if (val == null) {
            return null;
        }
        String s = String.valueOf(val).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 从 params 中解析图片分辨率档位（512/1K/2K/4K）。
     */
    private String resolveImageSizeTier(Map<String, Object> params) {
        // 优先从 imageSize 取（options 中明确指定）
        Object imageSizeObj = params.get("imageSize");
        if (imageSizeObj != null) {
            String imageSize = String.valueOf(imageSizeObj).trim().toUpperCase();
            if (isValidSizeTier(imageSize)) {
                return imageSize;
            }
        }
        // 其次从 rawSize 取（request.size 字段直传）
        Object rawSizeObj = params.get("rawSize");
        if (rawSizeObj != null) {
            String rawSize = String.valueOf(rawSizeObj).trim().toUpperCase();
            if (isValidSizeTier(rawSize)) {
                return rawSize;
            }
        }
        // 兜底：系统默认 2K
        return "2K";
    }

    /** 判断是否为合法的分辨率档位值 */
    private boolean isValidSizeTier(String value) {
        return "512".equals(value) || "1K".equals(value) || "2K".equals(value) || "4K".equals(value);
    }

    /**
     * 从 params 中解析宽高比。
     */
    private String resolveAspectRatio(Map<String, Object> params) {
        // 优先从显式 aspectRatio 取
        Object arObj = params.get("aspectRatio");
        if (arObj != null) {
            String ar = String.valueOf(arObj).trim();
            if (!ar.isEmpty()) {
                return ar;
            }
        }
        // 其次从 ratio 取（ResolutionUtil.parseRatio 解析结果）
        Object ratioObj = params.get("ratio");
        if (ratioObj != null) {
            String ratio = String.valueOf(ratioObj).trim();
            if (!ratio.isEmpty()) {
                return ratio;
            }
        }
        // 兆底：Gemini 默认 1:1
        return "1:1";
    }

    private int safeGetInt(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object val = params.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

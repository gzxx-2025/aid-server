package com.aid.media.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Service;

import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.aid.common.moderation.fetch.ImageBytesFetcher;
import com.aid.common.moderation.fetch.ImageBytesFetcher.FetchOutcome;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MediaInternalOptionKeys;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.util.ModelCapabilityResolver;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 视频输入图比例归一化服务。
 *
 * <p>仅处理 capability.videoAspectRatioMode=FOLLOW_INPUT 的模型。图片按配置选择居中补边或居中裁剪，
 * 经统一对象存储上传后写入任务请求快照；任务终态恢复原始 URL 并删除临时对象，避免临时文件长期残留。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoInputAspectRatioNormalizer {

    /** 图片像素上限，防止压缩炸弹占满堆内存。 */
    private static final long MAX_IMAGE_PIXELS = 50_000_000L;

    /** 输入图片字节上限，防止异常响应占满堆内存。 */
    private static final int MAX_IMAGE_BYTES = 50 * 1024 * 1024;

    /** 临时图片对象目录。 */
    private static final String TEMP_IMAGE_DIR = "media/video-input-aspect";

    /** 图片实际比例与目标比例差异小于该值时直接复用原图。 */
    private static final double ASPECT_RATIO_TOLERANCE = 0.005D;

    /** 视频清晰度中的短边数值。 */
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(\\d{3,4})[pP]");

    /** 单图片 URL 字段。 */
    private static final Set<String> SINGLE_IMAGE_KEYS = Set.of(
            "image", "imageurl", "image_url", "firstframeimageurl", "first_frame_url",
            "firstimageurl", "first_image_url", "first_image", "lastframeimageurl",
            "last_frame_url", "lastimageurl", "last_image_url", "last_image",
            "startimageurl", "start_image_url", "start_image", "keyimage",
            "key_image", "key_image_url", "endimageurl", "end_image_url", "end_image");

    /** 图片 URL 列表字段。 */
    private static final Set<String> IMAGE_LIST_KEYS = Set.of(
            "images", "referenceimages", "image_urls", "key_images");

    /** 保留完整画面并补边。 */
    private static final String FIT_CONTAIN = "CONTAIN";

    /** 铺满目标画布并居中裁剪。 */
    private static final String FIT_COVER = "COVER";

    private final ImageBytesFetcher imageBytesFetcher;
    private final OssTemplate ossTemplate;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 按模型能力原地归一化视频请求中的所有输入图片。
     *
     * @return true=请求发生变化，需要回写 request_json
     */
    public boolean normalize(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (Objects.isNull(request) || !ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig)) {
            return false;
        }
        Map<String, Object> options = request.getOptions();
        if (options != null && options.containsKey(MediaInternalOptionKeys.NORMALIZED_VIDEO_INPUTS)) {
            return false;
        }
        if (options != null) {
            // 请求来源可能传入不可变 Map；统一复制后再执行 URL 替换与跟踪信息写入。
            options = new LinkedHashMap<>(options);
            request.setOptions(options);
        }
        String aspectRatio = StrUtil.trimToNull(request.getAspectRatio());
        if (StrUtil.isBlank(aspectRatio)) {
            aspectRatio = ModelCapabilityResolver.resolveAspectRatio(modelConfig, null);
            request.setAspectRatio(aspectRatio);
        }
        double targetRatio = parseAspectRatio(aspectRatio);
        int shortEdge = resolveShortEdge(options);
        String fitMode = resolveFitMode(modelConfig);
        Map<String, String> sourceToNormalized = new LinkedHashMap<>();
        Map<String, String> normalizedToSource = new LinkedHashMap<>();
        try {
            if (StrUtil.isNotBlank(request.getImageUrl())) {
                request.setImageUrl(normalizeUrl(request.getImageUrl(), targetRatio, shortEdge, fitMode,
                        sourceToNormalized, normalizedToSource));
            }
            if (options != null) {
                normalizeMap(options, targetRatio, shortEdge, fitMode,
                        sourceToNormalized, normalizedToSource);
            }
        } catch (RuntimeException ex) {
            // 多图归一化中途失败时立即回收已上传对象，避免未形成任务跟踪信息的孤儿文件。
            deleteTemporaryObjects(normalizedToSource.keySet());
            throw ex;
        }
        if (normalizedToSource.isEmpty()) {
            return false;
        }
        if (options == null) {
            options = new LinkedHashMap<>();
            request.setOptions(options);
        }
        options.put(MediaInternalOptionKeys.NORMALIZED_VIDEO_INPUTS, normalizedToSource);
        log.info("视频输入图比例已归一化: modelCode={}, aspectRatio={}, fitMode={}, imageCount={}",
                modelConfig == null ? null : modelConfig.getModelCode(),
                aspectRatio, fitMode, normalizedToSource.size());
        return true;
    }

    /**
     * 任务终态恢复请求中的原始图片 URL，并删除比例归一化产生的临时对象。
     *
     * @return true=请求发生变化，需要回写 request_json
     */
    public boolean restoreAndCleanup(MediaVideoGenerateRequest request) {
        if (Objects.isNull(request) || request.getOptions() == null) {
            return false;
        }
        Object raw = request.getOptions().remove(MediaInternalOptionKeys.NORMALIZED_VIDEO_INPUTS);
        Map<String, String> normalizedToSource = toStringMap(raw);
        if (normalizedToSource.isEmpty()) {
            return false;
        }
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            request.setImageUrl(normalizedToSource.getOrDefault(request.getImageUrl(), request.getImageUrl()));
        }
        restoreMap(request.getOptions(), normalizedToSource);
        deleteTemporaryObjects(normalizedToSource.keySet());
        return true;
    }

    @SuppressWarnings("unchecked")
    private void normalizeMap(Map<String, Object> map, double targetRatio, int shortEdge, String fitMode,
                              Map<String, String> sourceToNormalized,
                              Map<String, String> normalizedToSource) {
        for (Map.Entry<String, Object> entry : new ArrayList<>(map.entrySet())) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (MediaInternalOptionKeys.isInternal(key)
                    && !MediaInternalOptionKeys.REFERENCE_IMAGES.equals(key)) {
                continue;
            }
            String normalizedKey = String.valueOf(key).toLowerCase(Locale.ROOT);
            if (value instanceof String url && SINGLE_IMAGE_KEYS.contains(normalizedKey)) {
                map.put(key, normalizeUrl(url, targetRatio, shortEdge, fitMode,
                        sourceToNormalized, normalizedToSource));
            } else if (value instanceof List<?> rawList) {
                // 图片列表可能由 List.of 等不可变集合构造，复制后再原位替换 URL。
                List<Object> list = IMAGE_LIST_KEYS.contains(normalizedKey)
                        ? new ArrayList<>(rawList) : (List<Object>) rawList;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof String url && IMAGE_LIST_KEYS.contains(normalizedKey)) {
                        list.set(i, normalizeUrl(url, targetRatio, shortEdge,
                                fitMode, sourceToNormalized, normalizedToSource));
                    } else if (item instanceof Map<?, ?> nested) {
                        normalizeMap((Map<String, Object>) nested, targetRatio, shortEdge, fitMode,
                                sourceToNormalized, normalizedToSource);
                    }
                }
                if (IMAGE_LIST_KEYS.contains(normalizedKey)) {
                    map.put(key, list);
                }
            } else if (value instanceof Map<?, ?> nested) {
                normalizeMap((Map<String, Object>) nested, targetRatio, shortEdge, fitMode,
                        sourceToNormalized, normalizedToSource);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void restoreMap(Map<String, Object> map, Map<String, String> normalizedToSource) {
        for (Map.Entry<String, Object> entry : new ArrayList<>(map.entrySet())) {
            Object value = entry.getValue();
            if (value instanceof String text && normalizedToSource.containsKey(text)) {
                map.put(entry.getKey(), normalizedToSource.get(text));
            } else if (value instanceof List<?> rawList) {
                List<Object> list = (List<Object>) rawList;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof String text && normalizedToSource.containsKey(text)) {
                        list.set(i, normalizedToSource.get(text));
                    } else if (item instanceof Map<?, ?> nested) {
                        restoreMap((Map<String, Object>) nested, normalizedToSource);
                    }
                }
            } else if (value instanceof Map<?, ?> nested) {
                restoreMap((Map<String, Object>) nested, normalizedToSource);
            }
        }
    }

    private String normalizeUrl(String sourceUrl, double targetRatio, int shortEdge, String fitMode,
                                Map<String, String> sourceToNormalized,
                                Map<String, String> normalizedToSource) {
        if (StrUtil.isBlank(sourceUrl)) {
            return sourceUrl;
        }
        String cached = sourceToNormalized.get(sourceUrl);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] sourceBytes = readImageBytes(sourceUrl);
            BufferedImage source = decodeImage(sourceBytes, sourceUrl);
            if (source == null) {
                log.error("视频输入图无法解码或像素超限: url={}", sourceUrl);
                throw new ServiceException("图片处理失败");
            }
            double sourceRatio = (double) source.getWidth() / source.getHeight();
            if (Math.abs(sourceRatio - targetRatio) <= ASPECT_RATIO_TOLERANCE) {
                sourceToNormalized.put(sourceUrl, sourceUrl);
                return sourceUrl;
            }
            int[] dimensions = resolveTargetDimensions(targetRatio, shortEdge);
            if (dimensions[0] <= 0 || dimensions[1] <= 0
                    || (long) dimensions[0] * dimensions[1] > MAX_IMAGE_PIXELS) {
                log.error("视频目标画布像素超限: width={}, height={}, url={}",
                        dimensions[0], dimensions[1], sourceUrl);
                throw new ServiceException("画面比例不支持");
            }
            BufferedImage canvas = new BufferedImage(dimensions[0], dimensions[1], BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setColor(Color.BLACK);
                graphics.fillRect(0, 0, dimensions[0], dimensions[1]);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double widthScale = (double) dimensions[0] / source.getWidth();
                double heightScale = (double) dimensions[1] / source.getHeight();
                double scale = FIT_COVER.equals(fitMode)
                        ? Math.max(widthScale, heightScale) : Math.min(widthScale, heightScale);
                int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
                int x = (dimensions[0] - drawWidth) / 2;
                int y = (dimensions[1] - drawHeight) / 2;
                graphics.drawImage(source, x, y, drawWidth, drawHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(canvas, "jpg", output)) {
                log.error("视频输入图编码失败: url={}", sourceUrl);
                throw new ServiceException("图片处理失败");
            }
            String stored = ossTemplate.uploadBytes(output.toByteArray(), "video-input.jpg", TEMP_IMAGE_DIR);
            String providerUrl = mediaUrlResolver.toProviderUrl(mediaUrlResolver.toFullUrl(stored));
            sourceToNormalized.put(sourceUrl, providerUrl);
            normalizedToSource.put(providerUrl, sourceUrl);
            return providerUrl;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("视频输入图比例归一化失败: url={}", sourceUrl, ex);
            throw new ServiceException("图片处理失败");
        }
    }

    private byte[] readImageBytes(String url) {
        FetchOutcome outcome = imageBytesFetcher.resolve(url);
        if (!outcome.isUseUrl()) {
            return validateImageBytes(outcome.getBytes(), url);
        }
        try (HttpResponse response = HttpRequest.get(outcome.getUrl()).timeout(10_000).execute()) {
            if (response.getStatus() < 200 || response.getStatus() >= 300) {
                log.error("视频输入图下载失败: status={}, url={}", response.getStatus(), outcome.getUrl());
                throw new ServiceException("图片下载失败");
            }
            return validateImageBytes(response.bodyBytes(), outcome.getUrl());
        }
    }

    /** 先读取图片头部尺寸再完整解码，使像素上限能在大画布分配前生效。 */
    private BufferedImage decodeImage(byte[] bytes, String url) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    log.error("视频输入图像素超限: width={}, height={}, url={}", width, height, url);
                    throw new ServiceException("图片处理失败");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 校验下载字节，禁止空文件和超大文件进入图片解码。 */
    private byte[] validateImageBytes(byte[] bytes, String url) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
            log.error("视频输入图字节为空或超限: size={}, url={}", bytes == null ? null : bytes.length, url);
            throw new ServiceException("图片处理失败");
        }
        return bytes;
    }

    private double parseAspectRatio(String value) {
        String[] parts = StrUtil.blankToDefault(value, "").split(":");
        if (parts.length != 2) {
            log.info("视频目标比例格式错误: aspectRatio={}", value);
            throw new ServiceException("画面比例不支持");
        }
        try {
            double width = Double.parseDouble(parts[0]);
            double height = Double.parseDouble(parts[1]);
            if (width <= 0 || height <= 0 || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw new NumberFormatException("non-positive ratio");
            }
            return width / height;
        } catch (NumberFormatException ex) {
            log.info("视频目标比例格式错误: aspectRatio={}", value);
            throw new ServiceException("画面比例不支持");
        }
    }

    private int resolveShortEdge(Map<String, Object> options) {
        if (options != null && options.get("resolution") != null) {
            Matcher matcher = RESOLUTION_PATTERN.matcher(String.valueOf(options.get("resolution")).trim());
            if (matcher.matches()) {
                return Math.max(360, Math.min(Integer.parseInt(matcher.group(1)), 2160));
            }
        }
        return 720;
    }

    /** 按模型能力读取输入图适配策略，非法配置安全回退 CONTAIN。 */
    private String resolveFitMode(AiModelConfigVo modelConfig) {
        String configured = ModelCapabilityResolver.readText(
                ModelCapabilityResolver.parseCapability(
                        modelConfig == null ? null : modelConfig.getCapabilityJson()),
                ModelCapabilityResolver.KEY_INPUT_ASPECT_RATIO_FIT);
        if (FIT_COVER.equalsIgnoreCase(configured)) {
            return FIT_COVER;
        }
        if (StrUtil.isNotBlank(configured) && !FIT_CONTAIN.equalsIgnoreCase(configured)) {
            log.warn("视频输入图适配策略不支持已回退: modelCode={}, fitMode={}",
                    modelConfig == null ? null : modelConfig.getModelCode(), configured);
        }
        return FIT_CONTAIN;
    }

    /** 删除本次比例归一化产生的临时对象。 */
    private void deleteTemporaryObjects(Iterable<String> normalizedUrls) {
        for (String normalizedUrl : normalizedUrls) {
            try {
                if (!ossTemplate.deleteByUrl(normalizedUrl)) {
                    log.warn("视频输入图临时对象清理失败: url={}", normalizedUrl);
                }
            } catch (Exception ex) {
                log.warn("视频输入图临时对象清理异常: url={}, error={}", normalizedUrl, ex.getMessage());
            }
        }
    }

    private int[] resolveTargetDimensions(double ratio, int shortEdge) {
        int width;
        int height;
        if (ratio >= 1D) {
            height = shortEdge;
            width = even((int) Math.round(shortEdge * ratio));
        } else {
            width = shortEdge;
            height = even((int) Math.round(shortEdge / ratio));
        }
        return new int[]{even(width), even(height)};
    }

    private int even(int value) {
        return value % 2 == 0 ? value : value + 1;
    }

    private Map<String, String> toStringMap(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }
}

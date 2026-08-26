package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参考素材占位「下发上游前」统一清洗工具。
 *
 * <p>占位协议（{@code @图片N[name]} / {@code @音频N[name]}）里的 N 是<b>位置引用</b>：它指向随请求一起下发的
 * 第 N 个参考素材。因此清洗必须同时知道「正文写了什么」和「本次实际能下发几条」——只看正文会留下悬空引用：
 * 正文说「参考音频1」而请求体里根本没有 referenceAudios，模型读到一个找不到实物的编号，轻则忽略、重则自行脑补。
 *
 * <p>能力位丢弃是常态而非例外：模型 {@code capability_json} 配 {@code maxReferenceImages:0} 会丢掉全部参考图，
 * 厂商不支持参考音频则整个字段都不会下发，条数上限也会截断超出部分。这些丢弃都发生在业务层排好编号之后，
 * 所以「编号越界」必须在这里统一收口。
 *
 * <p>越界引用一律<b>文字降级</b>——剥掉占位壳只保留方括号里的名称，与业务层
 * {@code StoryboardVideoGenerationServiceImpl#compactResolvedReferences} 对失效引用的处理同一套规则：
 * 名称本身是有效的自然语言描述，删掉整句反而会让句子残缺。图片与音频走同一条规则，不为音频单开分支。
 */
@Slf4j
public final class ReferencePromptSanitizer {

    private ReferencePromptSanitizer() {
    }

    /**
     * {@code @图片N[name]} 占位正则：组 1 = 序号 N，组 2 = 名称；name 为除右方括号外任意字符。
     * 与 {@link com.aid.rps.resolver.StoryboardImageReferenceResolver} 的解析正则严格对齐。
     */
    private static final Pattern REF_PLACEHOLDER = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    /**
     * {@code ---参考图映射---} 段正则：从该 header 起删到 prompt 结尾。
     * 兼容前置换行与两侧任意数量的连字符（业务层固定写 {@code \n---参考图映射---\n}）。
     */
    private static final Pattern MAPPING_SECTION = Pattern.compile("\\n?-{2,}\\s*参考图映射\\s*-{2,}[\\s\\S]*$");

    /**
     * {@code @音频N[音频-角色名]} 占位正则：视觉导演在台词行标注角色配音引用的私有占位
     * （与 {@code @图片N[name]} 同族，指向角色音色绑定而非现成资产）。
     * 厂商只接收业务层已解析出的结构化参考音频 URL，不识别方括号内的私有名称。
     */
    private static final Pattern AUDIO_REF_PLACEHOLDER = Pattern.compile("@音频(\\d+)\\[([^\\]]*)\\]");

    /**
     * 音频占位名的固定前缀（业务层写作 {@code [音频-角色名]}）。
     * 文字降级时剥掉该前缀，让「白素贞的音色参考@音频1[音频-白素贞]」退化成读得通的
     * 「白素贞的音色参考白素贞」，而不是夹一个协议残片「音频-白素贞」。
     */
    private static final String AUDIO_NAME_PREFIX = "音频-";

    /**
     * 残留的 {@code @选择标记} 正则：{@code @} 紧跟非空白字符。
     * 视觉导演 / 分镜画师模板用 {@code @} 前缀标注「已选枚举值」，如
     * {@code @中近景 / @平视 / @50mm标准 / @顺光 / @深景深 / @对角构图 / @全景 / @黄金时刻}。
     * 这些 {@code @} 对图像模型是噪声（标记语义模型不认）。本正则在所有占位已处理完之后执行，
     * 因此此时剩余的 {@code @} 必为选择标记，去掉 {@code @} 仅保留枚举值文本。
     */
    private static final Pattern STRAY_AT_MARKER = Pattern.compile("@(?=\\S)");

    /** Seedance 官方索引引用：{@code @imageN/@videoN/@audioN}。 */
    private static final Pattern SEEDANCE_INDEX_REF = Pattern.compile("@(image|video|audio)(\\d+)\\b");

    /** Wan3.0 兼容的英文索引引用，允许带或不带 {@code @}。 */
    private static final Pattern WAN3_INDEX_REF = Pattern.compile("@?(image|video|audio)(\\d+)(?!\\d)");
    /** Wan3.0 兼容业务历史的中文 {@code @图片N/@图N/@视频N/@音频N} 引用。 */
    private static final Pattern WAN3_CHINESE_INDEX_REF =
            Pattern.compile("@(图片|图|视频|音频)(\\d+)(?!\\d)");

    /** Agnes Video 2.5 官方素材引用：{@code <Picture N>/<Audio N>/<Video N>}。 */
    private static final Pattern AGNES25_OFFICIAL_REF =
            Pattern.compile("<(Picture|Audio|Video)\\s+(\\d+)>", Pattern.CASE_INSENSITIVE);
    /** Agnes Video 2.5 兼容系统英文索引引用。 */
    private static final Pattern AGNES25_INDEX_REF =
            Pattern.compile("@(image|video|audio)(\\d+)(?!\\d)", Pattern.CASE_INSENSITIVE);
    /** Agnes Video 2.5 兼容系统中文索引引用。 */
    private static final Pattern AGNES25_CHINESE_INDEX_REF =
            Pattern.compile("@(图片|图|视频|音频)(\\d+)(?!\\d)");

    /** 仅清理非 Seedance 官方索引语法的选择标记。 */
    private static final Pattern SEEDANCE_STRAY_AT_MARKER =
            Pattern.compile("@(?!(?:image|video|audio)\\d+\\b)(?=\\S)");

    /**
     * 清洗 prompt：删除参考图映射段 + 占位按实际下发能力转裸引用或文字降级。
     *
     * @param prompt              原始 prompt（可能含 {@code @图片N[name]} / {@code @音频N[name]} 占位与映射段）
     * @param dispatchedImageMax  本次实际能下发的参考图张数；编号超出者文字降级。传 0 表示一张都不下发
     * @param dispatchedAudioMax  本次实际能下发的参考音频条数；编号超出者文字降级。传 0 表示一条都不下发
     * @return 清洗后的 prompt；入参为空白时原样返回
     */
    public static String sanitize(String prompt, int dispatchedImageMax, int dispatchedAudioMax) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = applyPlaceholder(cleaned, REF_PLACEHOLDER, "图片", dispatchedImageMax, null);
        // 音频占位必须在 STRAY_AT_MARKER 之前处理，否则残留标记清理会把 @ 剥掉、占位再也匹配不上
        cleaned = applyPlaceholder(cleaned, AUDIO_REF_PLACEHOLDER, "音频", dispatchedAudioMax, AUDIO_NAME_PREFIX);
        cleaned = STRAY_AT_MARKER.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }

    /**
     * 按实际下发条数处理一类占位：编号在范围内转裸引用（{@code 图片3} / {@code 音频1}），
     * 越界则剥壳降级为名称文本，使正文不再引用一个请求体里不存在的实物。
     *
     * @param prompt       待处理文本
     * @param pattern      占位正则（组 1 = 序号，组 2 = 名称）
     * @param kindLabel    裸引用前缀（「图片」/「音频」）
     * @param dispatchedMax 本次实际能下发的条数
     * @param namePrefix   降级时需从名称上剥掉的协议前缀；无前缀传 null
     * @return 处理后的文本
     */
    private static String applyPlaceholder(String prompt, Pattern pattern, String kindLabel,
                                           int dispatchedMax, String namePrefix) {
        Matcher matcher = pattern.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        int degraded = 0;
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                // 序号不可解析：按越界处理，绝不留下一个无法校验的编号引用
                index = Integer.MAX_VALUE;
            }
            String replacement;
            if (index >= 1 && index <= dispatchedMax) {
                replacement = kindLabel + index;
            } else {
                replacement = degradeName(matcher.group(2), namePrefix);
                degraded++;
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        if (degraded > 0) {
            log.info("{}引用编号越界已文字降级: 实际可下发={}, 降级数={}", kindLabel, dispatchedMax, degraded);
        }
        return rewritten.toString();
    }

    /** 占位降级文本：剥掉协议前缀后的名称；名称为空时整体删除，不留空壳。 */
    private static String degradeName(String rawName, String namePrefix) {
        String name = StrUtil.trimToEmpty(rawName);
        if (StrUtil.isNotBlank(namePrefix) && name.startsWith(namePrefix)) {
            name = name.substring(namePrefix.length());
        }
        return StrUtil.trimToEmpty(name);
    }

    /**
     * 原地清洗图片请求的 prompt（仅当清洗前后不同才回写，避免无谓写入）。
     * 各图片 Provider 在 {@code submit} 入口调用一次，即可让后续所有 dialect / buildBody 拿到干净 prompt。
     * 图片请求不承载参考音频，音频条数固定为 0——正文里的任何 {@code @音频N} 必然悬空，一律降级。
     *
     * @param request            图片生成请求（可为 null，内部判空）
     * @param dispatchedImageMax 本次实际能下发的参考图张数
     */
    public static void sanitizeInPlace(MediaImageGenerateRequest request, int dispatchedImageMax) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitize(original, dispatchedImageMax, 0);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }

    /**
     * 原地清洗视频请求的 prompt（仅当清洗前后不同才回写）。
     *
     * @param request            视频生成请求（可为 null，内部判空）
     * @param dispatchedImageMax 本次实际能下发的参考图张数
     * @param dispatchedAudioMax 本次实际能下发的参考音频条数
     */
    public static void sanitizeInPlace(MediaVideoGenerateRequest request,
                                       int dispatchedImageMax, int dispatchedAudioMax) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitize(original, dispatchedImageMax, dispatchedAudioMax);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }

    /**
     * Seedance 索引素材专用清洗：内部图片/音频占位转换为厂商官方语法，
     * 保留实际已下发范围内的 {@code @imageN/@videoN/@audioN}，越界引用文字降级。
     */
    public static String sanitizeForSeedance(String prompt, int dispatchedImageCount,
                                             int dispatchedVideoCount, int dispatchedAudioCount) {
        return sanitizeForIndexedMedia(prompt, dispatchedImageCount,
                dispatchedVideoCount, dispatchedAudioCount);
    }

    /** Wan3.0 索引素材清洗：统一转换为官方的“图N/视频N/音频N”自然语言引用。 */
    public static String sanitizeForWan3(String prompt, int imageCount,
                                         int videoCount, int audioCount) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = applyPlaceholder(cleaned, REF_PLACEHOLDER, "图", imageCount, null);
        cleaned = applyPlaceholder(cleaned, AUDIO_REF_PLACEHOLDER, "音频", audioCount, AUDIO_NAME_PREFIX);
        cleaned = rewriteWan3ChineseRefs(cleaned, imageCount, videoCount, audioCount);
        Matcher matcher = WAN3_INDEX_REF.matcher(cleaned);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String kind = matcher.group(1);
            int index = parseIndex(matcher.group(2));
            int max = "image".equals(kind) ? imageCount : "video".equals(kind) ? videoCount : audioCount;
            String label = "image".equals(kind) ? "图" : "video".equals(kind) ? "视频" : "音频";
            String replacement = index >= 1 && index <= max ? label + index : label + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return STRAY_AT_MARKER.matcher(rewritten.toString()).replaceAll("").strip();
    }

    /** Agnes Video 2.5 素材索引清洗：统一转换为厂商官方尖括号引用。 */
    public static String sanitizeForAgnes25(String prompt, int imageCount,
                                            int videoCount, int audioCount) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = applyAgnes25Placeholder(cleaned, REF_PLACEHOLDER,
                "Picture", imageCount, null);
        cleaned = applyAgnes25Placeholder(cleaned, AUDIO_REF_PLACEHOLDER,
                "Audio", audioCount, AUDIO_NAME_PREFIX);
        cleaned = rewriteAgnes25ChineseRefs(cleaned, imageCount, videoCount, audioCount);
        cleaned = rewriteAgnes25IndexedRefs(cleaned, imageCount, videoCount, audioCount);
        cleaned = rewriteAgnes25OfficialRefs(cleaned, imageCount, videoCount, audioCount);
        return STRAY_AT_MARKER.matcher(cleaned).replaceAll("").strip();
    }

    private static String applyAgnes25Placeholder(String prompt, Pattern pattern, String kind,
                                                   int count, String namePrefix) {
        Matcher matcher = pattern.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            int index = parseIndex(matcher.group(1));
            String replacement = index >= 1 && index <= count
                    ? "<" + kind + " " + index + ">"
                    : degradeName(matcher.group(2), namePrefix);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String rewriteAgnes25ChineseRefs(String prompt, int imageCount,
                                                     int videoCount, int audioCount) {
        Matcher matcher = AGNES25_CHINESE_INDEX_REF.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String rawKind = matcher.group(1);
            String kind = "视频".equals(rawKind) ? "Video" : "音频".equals(rawKind) ? "Audio" : "Picture";
            int max = "Video".equals(kind) ? videoCount : "Audio".equals(kind) ? audioCount : imageCount;
            int index = parseIndex(matcher.group(2));
            String replacement = index >= 1 && index <= max
                    ? "<" + kind + " " + index + ">" : rawKind + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String rewriteAgnes25IndexedRefs(String prompt, int imageCount,
                                                     int videoCount, int audioCount) {
        Matcher matcher = AGNES25_INDEX_REF.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String rawKind = matcher.group(1).toLowerCase();
            String kind = "video".equals(rawKind) ? "Video" : "audio".equals(rawKind) ? "Audio" : "Picture";
            int max = "Video".equals(kind) ? videoCount : "Audio".equals(kind) ? audioCount : imageCount;
            int index = parseIndex(matcher.group(2));
            String replacement = index >= 1 && index <= max
                    ? "<" + kind + " " + index + ">" : rawKind + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String rewriteAgnes25OfficialRefs(String prompt, int imageCount,
                                                      int videoCount, int audioCount) {
        Matcher matcher = AGNES25_OFFICIAL_REF.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String normalizedKind = "video".equalsIgnoreCase(kind) ? "Video"
                    : "audio".equalsIgnoreCase(kind) ? "Audio" : "Picture";
            int max = "Video".equals(normalizedKind) ? videoCount
                    : "Audio".equals(normalizedKind) ? audioCount : imageCount;
            int index = parseIndex(matcher.group(2));
            String replacement = index >= 1 && index <= max
                    ? "<" + normalizedKind + " " + index + ">"
                    : normalizedKind + " " + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String rewriteWan3ChineseRefs(String prompt, int imageCount,
                                                  int videoCount, int audioCount) {
        Matcher matcher = WAN3_CHINESE_INDEX_REF.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String kind = matcher.group(1);
            int index = parseIndex(matcher.group(2));
            int max = "视频".equals(kind) ? videoCount : "音频".equals(kind) ? audioCount : imageCount;
            String label = "视频".equals(kind) ? "视频" : "音频".equals(kind) ? "音频" : "图";
            String replacement = index >= 1 && index <= max ? label + index : label + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /**
     * 索引媒体协议清洗：保留实际下发范围内的 {@code @imageN/@videoN/@audioN}，越界降级。
     */
    public static String sanitizeForIndexedMedia(String prompt, int dispatchedImageCount,
                                                 int dispatchedVideoCount, int dispatchedAudioCount) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = applySeedancePlaceholder(cleaned, REF_PLACEHOLDER, "image", dispatchedImageCount, null);
        cleaned = applySeedancePlaceholder(cleaned, AUDIO_REF_PLACEHOLDER, "audio",
                dispatchedAudioCount, AUDIO_NAME_PREFIX);
        cleaned = applySeedanceOfficialRefs(cleaned, dispatchedImageCount,
                dispatchedVideoCount, dispatchedAudioCount);
        cleaned = SEEDANCE_STRAY_AT_MARKER.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }

    public static void sanitizeInPlaceForSeedance(MediaVideoGenerateRequest request,
                                                   int dispatchedImageCount,
                                                   int dispatchedVideoCount,
                                                   int dispatchedAudioCount) {
        sanitizeInPlaceForIndexedMedia(request, dispatchedImageCount,
                dispatchedVideoCount, dispatchedAudioCount);
    }

    /** 原地执行中性索引媒体清洗。 */
    public static void sanitizeInPlaceForIndexedMedia(MediaVideoGenerateRequest request,
                                                       int dispatchedImageCount,
                                                       int dispatchedVideoCount,
                                                       int dispatchedAudioCount) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitizeForIndexedMedia(original, dispatchedImageCount,
                dispatchedVideoCount, dispatchedAudioCount);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }

    private static String applySeedancePlaceholder(String prompt, Pattern pattern, String officialKind,
                                                    int dispatchedCount, String namePrefix) {
        Matcher matcher = pattern.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            int index = parseIndex(matcher.group(1));
            String replacement = index >= 1 && index <= dispatchedCount
                    ? "@" + officialKind + index
                    : degradeName(matcher.group(2), namePrefix);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String applySeedanceOfficialRefs(String prompt, int imageCount,
                                                     int videoCount, int audioCount) {
        Matcher matcher = SEEDANCE_INDEX_REF.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String kind = matcher.group(1);
            int index = parseIndex(matcher.group(2));
            int max = "image".equals(kind) ? imageCount : "video".equals(kind) ? videoCount : audioCount;
            String replacement = index >= 1 && index <= max ? matcher.group() : kind + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static int parseIndex(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 主体引用保留版清洗：删映射段 + 系统私有占位按实际下发能力处理，但<strong>不剥</strong>剩余 {@code @xxx}。
     * 仅供「厂商官方支持 @主体名 引用语义」的主体调用形态使用（如 Vidu 参考生 subjects 模式，
     * prompt 中 {@code @主体名} 是官方请求语义而非噪声）。选择标记类 {@code @} 由装配策略层负责清理，
     * 本方法作为 Provider 入口的幂等兜底，只清理确定是系统私有协议的占位。
     *
     * @param prompt             原始 prompt
     * @param dispatchedImageMax 本次实际能下发的参考图张数
     * @param dispatchedAudioMax 本次实际能下发的参考音频条数
     * @return 清洗后的 prompt；入参为空白时原样返回
     */
    public static String sanitizePreservingSubjectRefs(String prompt,
                                                       int dispatchedImageMax, int dispatchedAudioMax) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = applyPlaceholder(cleaned, REF_PLACEHOLDER, "图片", dispatchedImageMax, null);
        cleaned = applyPlaceholder(cleaned, AUDIO_REF_PLACEHOLDER, "音频", dispatchedAudioMax, AUDIO_NAME_PREFIX);
        return cleaned.strip();
    }

    /**
     * 原地执行主体引用保留版清洗（仅当清洗前后不同才回写）。
     * Vidu 等主体调用形态（options.subjects 非空）在 submit 入口用本方法替代 {@link #sanitizeInPlace}。
     *
     * @param request            视频生成请求（可为 null，内部判空）
     * @param dispatchedImageMax 本次实际能下发的参考图张数
     * @param dispatchedAudioMax 本次实际能下发的参考音频条数
     */
    public static void sanitizeInPlacePreservingSubjectRefs(MediaVideoGenerateRequest request,
                                                            int dispatchedImageMax, int dispatchedAudioMax) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitizePreservingSubjectRefs(original, dispatchedImageMax, dispatchedAudioMax);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }
}

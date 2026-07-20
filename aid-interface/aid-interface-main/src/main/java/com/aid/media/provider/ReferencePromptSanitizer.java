package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

import java.util.regex.Pattern;

/**
 * 参考图占位「下发上游前」统一清洗工具。
 */
public final class ReferencePromptSanitizer {

    private ReferencePromptSanitizer() {
    }

    /**
     * {@code @图片N[name]} 占位正则：组 1 = 序号 N；name 为除右方括号外任意字符。
     * 与 {@link com.aid.rps.resolver.StoryboardImageReferenceResolver} 的解析正则严格对齐。
     */
    private static final Pattern REF_PLACEHOLDER = Pattern.compile("@图片(\\d+)\\[[^\\]]*\\]");

    /**
     * {@code ---参考图映射---} 段正则：从该 header 起删到 prompt 结尾。
     * 兼容前置换行与两侧任意数量的连字符（业务层固定写 {@code \n---参考图映射---\n}）。
     */
    private static final Pattern MAPPING_SECTION = Pattern.compile("\\n?-{2,}\\s*参考图映射\\s*-{2,}[\\s\\S]*$");

    /**
     * {@code @音频N[音频-角色名]} 占位正则：视觉导演在台词行标注角色配音引用的私有占位
     * （与 {@code @图片N[name]} 同族，指向角色音色绑定而非现成资产）。
     * 当前没有任何上游厂商识别该占位，方括号内部引用名对模型是纯噪声且泄露内部命名，
     * 因此下发前整体删除；台词行的角色/情感标注（【罗峰_初始形象，低沉独白】）保留，供音画同出模型理解说话人语义。
     */
    private static final Pattern AUDIO_REF_PLACEHOLDER = Pattern.compile("@音频(\\d+)\\[[^\\]]*\\]");

    /**
     * 残留的 {@code @选择标记} 正则：{@code @} 紧跟非空白字符。
     * 视觉导演 / 分镜画师模板用 {@code @} 前缀标注「已选枚举值」，如
     * {@code @中近景 / @平视 / @50mm标准 / @顺光 / @深景深 / @对角构图 / @全景 / @黄金时刻}。
     * 这些 {@code @} 对图像模型是噪声（标记语义模型不认）。本正则在 {@code @图片N[name]} 已转为
     * 裸 {@code 图片N} 之后执行，因此此时剩余的 {@code @} 必为选择标记，去掉 {@code @} 仅保留枚举值文本。
     */
    private static final Pattern STRAY_AT_MARKER = Pattern.compile("@(?=\\S)");

    /**
     * 清洗 prompt：删除参考图映射段 + 占位转裸引用。
     *
     * @param prompt 原始 prompt（可能含 {@code @图片N[name]} 占位与 {@code ---参考图映射---} 段）
     * @return 清洗后的 prompt；入参为空白时原样返回
     */
    public static String sanitize(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = REF_PLACEHOLDER.matcher(cleaned).replaceAll("图片$1");
        // 音频占位必须在 STRAY_AT_MARKER 之前删除（@ 被剥掉后本正则将无法命中）
        cleaned = AUDIO_REF_PLACEHOLDER.matcher(cleaned).replaceAll("");
        cleaned = STRAY_AT_MARKER.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }

    /**
     * 原地清洗图片请求的 prompt（仅当清洗前后不同才回写，避免无谓写入）。
     * 各图片 Provider 在 {@code submit} 入口调用一次，即可让后续所有 dialect / buildBody 拿到干净 prompt。
     *
     * @param request 图片生成请求（可为 null，内部判空）
     */
    public static void sanitizeInPlace(MediaImageGenerateRequest request) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitize(original);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }

    /**
     * 原地清洗视频请求的 prompt（仅当清洗前后不同才回写）。
     *
     * @param request 视频生成请求（可为 null，内部判空）
     */
    public static void sanitizeInPlace(MediaVideoGenerateRequest request) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitize(original);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }

    /**
     * 主体引用保留版清洗：删映射段 + 系统私有占位转裸引用，但<strong>不剥</strong>剩余 {@code @xxx}。
     * 仅供「厂商官方支持 @主体名 引用语义」的主体调用形态使用（如 Vidu 参考生 subjects 模式，
     * prompt 中 {@code @主体名} 是官方请求语义而非噪声）。选择标记类 {@code @} 由装配策略层负责清理，
     * 本方法作为 Provider 入口的幂等兜底，只清理确定是系统私有协议的占位。
     *
     * @param prompt 原始 prompt
     * @return 清洗后的 prompt；入参为空白时原样返回
     */
    public static String sanitizePreservingSubjectRefs(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        String cleaned = MAPPING_SECTION.matcher(prompt).replaceAll("");
        cleaned = REF_PLACEHOLDER.matcher(cleaned).replaceAll("图片$1");
        cleaned = AUDIO_REF_PLACEHOLDER.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }

    /**
     * 原地执行主体引用保留版清洗（仅当清洗前后不同才回写）。
     * Vidu 等主体调用形态（options.subjects 非空）在 submit 入口用本方法替代 {@link #sanitizeInPlace}。
     *
     * @param request 视频生成请求（可为 null，内部判空）
     */
    public static void sanitizeInPlacePreservingSubjectRefs(MediaVideoGenerateRequest request) {
        if (request == null) {
            return;
        }
        String original = request.getPrompt();
        String cleaned = sanitizePreservingSubjectRefs(original);
        if (!StrUtil.equals(original, cleaned)) {
            request.setPrompt(cleaned);
        }
    }
}

package com.aid.storyboard.video.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Vidu 视频参考装配策略，按模型形态二选一：
 * <ol>
 *   <li><b>主体调用</b>（capability_json.subjectReference=true，如 viduq3 参考生）：
 *       参考素材映射为官方 {@code subjects} 结构（每主体 name + images），正文 {@code @图片N[name]}
 *       重写为官方 {@code @主体名} 引用——官方主体绑定语义，生成一致性最好，凡模型支持必须走该形态；</li>
 *   <li><b>非主体多图</b>（如 viduq3-mix，官方注明暂不支持主体）：参考图平铺为 {@code images}
 *       数组（1~7 张），正文占位交由 Provider 统一清洗为裸「图片N」，附参考图说明段。</li>
 * </ol>
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class ViduVideoReferenceStrategy extends AbstractVideoReferenceStrategy
{
    /** {@code @图片N[name]} 占位正则（与解析器对齐）。 */
    private static final Pattern AT_REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    /** 残留 {@code @选择标记} 正则（主体重写完成后执行，此时剩余 @ 均为模板选择标记噪声）。 */
    private static final Pattern STRAY_AT_MARKER = Pattern.compile("@(?=\\S)");

    /** 主体名重写占位包裹符（不可见控制符，避免与正文冲突；剥残留 @ 后再恢复为 @主体名）。 */
    private static final char SUBJECT_TOKEN_MARK = '\u0001';

    /** 官方约束：图片/文字主体最多 7 个。 */
    private static final int MAX_SUBJECTS = 7;

    /** 官方约束：每个主体最多 3 张图。 */
    private static final int MAX_IMAGES_PER_SUBJECT = 3;

    /** capability_json 主体调用标记键。 */
    private static final String CAPABILITY_SUBJECT_REFERENCE = "subjectReference";

    /** capability_json sceneRules 多帧场景键。 */
    private static final String CAPABILITY_SCENE_RULES = "sceneRules";
    private static final String CAPABILITY_SCENE_MULTI_FRAME = "multiFrame";

    @Override
    public boolean supportsProviderCode(String providerCode)
    {
        return providerCode != null
                && ViduConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext ctx)
    {
        if (isMultiFrameModel(ctx.getModelConfig()))
        {
            return assembleMultiFrame(ctx);
        }
        if (isSubjectReferenceModel(ctx.getModelConfig()))
        {
            return assembleSubjects(ctx);
        }
        return assembleImages(ctx);
    }

    /**
     * 多帧装配（官方 multiframe 协议）：首帧走 {@code start_image}（垫图优先，否则第 1 张参考图），
     * 其余参考图按时间轴顺序作关键帧 {@code key_images}（Provider 组装 image_settings，每项 key_image + prompt + duration）。
     * 官方约束 image_settings 至少 2 帧、最多 9 帧，张数由模型 capability 的 min/maxReferenceImages 控制。
     */
    private VideoReferencePlan assembleMultiFrame(VideoReferenceContext ctx)
    {
        List<ResolvedReference> picked = takeRefs(ctx.getReferences(), ctx.getMaxReferenceImages());
        List<String> urls = new ArrayList<>();
        for (ResolvedReference r : picked)
        {
            urls.add(r.getUrl());
        }
        String firstFrame;
        List<String> keyImages;
        if (StrUtil.isNotBlank(ctx.getBaseImageUrl()))
        {
            // 有分镜主图垫图：主图作首帧，全部参考图按序作关键帧
            firstFrame = ctx.getBaseImageUrl();
            keyImages = new ArrayList<>(urls);
        }
        else if (!urls.isEmpty())
        {
            // 无垫图：第 1 张参考图作首帧，其余作关键帧
            firstFrame = urls.get(0);
            keyImages = new ArrayList<>(urls.subList(1, urls.size()));
        }
        else
        {
            firstFrame = null;
            keyImages = new ArrayList<>();
        }

        String legend = buildReferenceLegend(picked);
        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked),
                legend, ctx.getUserInputText());

        Map<String, Object> extraOptions = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(keyImages))
        {
            extraOptions.put(ViduConstants.OPTIONS_KEY_IMAGES_ALT, keyImages);
        }
        log.info("Vidu 多帧参考装配: firstFrame={}, keyImages={}", StrUtil.isNotBlank(firstFrame), keyImages.size());
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame, extraOptions);
    }

    /**
     * 读 capability_json sceneRules.multiFrame：声明即该模型走官方 multiframe 协议装配。
     */
    private boolean isMultiFrameModel(AiModelConfigVo modelConfig)
    {
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getCapabilityJson()))
        {
            return false;
        }
        try
        {
            JSONObject capability = JSONUtil.parseObj(modelConfig.getCapabilityJson());
            JSONObject sceneRules = capability.getJSONObject(CAPABILITY_SCENE_RULES);
            return sceneRules != null && sceneRules.containsKey(CAPABILITY_SCENE_MULTI_FRAME);
        }
        catch (Exception e)
        {
            log.warn("Vidu capability_json 解析失败，按非多帧形态装配: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 主体调用装配：参考素材 → subjects（同名资产合并图，主体 ≤7、每主体图 ≤3），
     * 正文 {@code @图片N[name]} → {@code @主体名}（被截断的回退为资产名裸文本），
     * 残留 {@code @选择标记} 剥 @ 保留枚举值。
     */
    private VideoReferencePlan assembleSubjects(VideoReferenceContext ctx)
    {
        int effectiveMax = Math.min(
                ctx.getMaxReferenceImages() > 0 ? ctx.getMaxReferenceImages() : MAX_SUBJECTS, MAX_SUBJECTS);
        List<ResolvedReference> picked = takeRefs(ctx.getReferences(), effectiveMax);

        // 主体聚合：name → images（保序、同名合并、每主体最多 3 图）；同时记录 原始N → 主体名
        Map<String, List<String>> imagesBySubject = new LinkedHashMap<>();
        Map<Integer, String> subjectNameByN = new LinkedHashMap<>();
        for (ResolvedReference r : picked)
        {
            String subjectName = normalizeSubjectName(r.displayName(), imagesBySubject.size() + 1);
            List<String> images = imagesBySubject.computeIfAbsent(subjectName, k -> new ArrayList<>());
            if (images.size() < MAX_IMAGES_PER_SUBJECT)
            {
                images.add(r.getUrl());
            }
            subjectNameByN.put(r.getOriginalN(), subjectName);
        }
        // 被截断的引用：正文回退为资产名裸文本
        Map<Integer, String> droppedNameByN = new LinkedHashMap<>();
        for (ResolvedReference r : ctx.getReferences())
        {
            if (r != null && !subjectNameByN.containsKey(r.getOriginalN()))
            {
                droppedNameByN.put(r.getOriginalN(), r.displayName());
            }
        }

        List<Map<String, Object>> subjects = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : imagesBySubject.entrySet())
        {
            Map<String, Object> subject = new LinkedHashMap<>();
            subject.put(ViduConstants.JSON_SUBJECT_NAME, e.getKey());
            subject.put(ViduConstants.JSON_IMAGES, e.getValue());
            subjects.add(subject);
            urls.addAll(e.getValue());
        }

        String remapped = remapToSubjectRefs(ctx.getVideoPrompt(), subjectNameByN, droppedNameByN);
        String finalPrompt = composePrompt(remapped, null, ctx.getUserInputText());

        Map<String, Object> extraOptions = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(subjects))
        {
            extraOptions.put(ViduConstants.OPTIONS_SUBJECTS, subjects);
        }
        log.info("Vidu 主体参考装配: subjects={}, 图片总数={}, 截断回退={}",
                subjects.size(), urls.size(), droppedNameByN.keySet());
        // 参考生视频无首帧概念；urls 仅用于任务快照与参考图计数审计
        return VideoReferencePlan.of(finalPrompt, urls, null, extraOptions);
    }

    /**
     * 非主体多图装配（viduq3-mix 等）：参考图平铺 images（1~7 张）+ 参考图说明段，
     * 正文 {@code @图片N[name]} 占位由 Provider 入口统一清洗为裸「图片N」。
     */
    private VideoReferencePlan assembleImages(VideoReferenceContext ctx)
    {
        List<ResolvedReference> picked = takeRefs(ctx.getReferences(), ctx.getMaxReferenceImages());

        List<String> urls = new ArrayList<>();
        for (ResolvedReference r : picked)
        {
            urls.add(r.getUrl());
        }
        // 无参考素材时首帧垫图走 plan.firstFrame 主通道（图生/首尾帧场景由 Provider 按官方字段组装首尾图），
        // 不塞进 images 扩展参数——否则首尾帧场景 images 被单首帧抢占，尾帧无法并入且首帧从请求上下文丢失
        String firstFrame = null;
        if (urls.isEmpty() && StrUtil.isNotBlank(ctx.getBaseImageUrl()))
        {
            firstFrame = ctx.getBaseImageUrl();
            urls.add(firstFrame);
        }

        String legend = buildReferenceLegend(picked);
        String finalPrompt = composePrompt(remapPromptForPicked(ctx.getVideoPrompt(), picked),
                legend, ctx.getUserInputText());

        Map<String, Object> extraOptions = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(picked))
        {
            // 仅真实参考素材平铺官方 images 字段；首帧垫图不在此列
            List<String> refUrls = new ArrayList<>();
            for (ResolvedReference r : picked)
            {
                refUrls.add(r.getUrl());
            }
            extraOptions.put(ViduConstants.OPTIONS_KEY_IMAGES, refUrls);
        }
        log.info("Vidu 非主体多图参考装配: images={}, firstFrame={}", urls.size(), StrUtil.isNotBlank(firstFrame));
        return VideoReferencePlan.of(finalPrompt, urls, firstFrame, extraOptions);
    }

    /**
     * 正文重写：{@code @图片N[name]} → {@code @主体名}（截断的回退资产名裸文本），
     * 再剥残留 {@code @选择标记}（如 @中近景 / @平视），最后恢复主体 @ 引用。
     * 用控制符包裹主体名防止其被残留标记清理误剥。
     */
    private String remapToSubjectRefs(String prompt, Map<Integer, String> subjectNameByN,
                                      Map<Integer, String> droppedNameByN)
    {
        if (StrUtil.isBlank(prompt))
        {
            return prompt;
        }
        Matcher matcher = AT_REF_PATTERN.matcher(prompt);
        StringBuilder sb = new StringBuilder();
        while (matcher.find())
        {
            int n = Integer.parseInt(matcher.group(1));
            String replacement;
            if (subjectNameByN.containsKey(n))
            {
                // 先落控制符占位，防止下一步剥残留 @ 时误伤主体引用
                replacement = SUBJECT_TOKEN_MARK + subjectNameByN.get(n) + SUBJECT_TOKEN_MARK;
            }
            else
            {
                String dropped = droppedNameByN.get(n);
                replacement = StrUtil.isNotBlank(dropped) ? dropped : matcher.group(2);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        // 剥模板选择标记的 @（此时正文已无 @图片N 占位，主体引用被控制符保护）
        String stripped = STRAY_AT_MARKER.matcher(sb.toString()).replaceAll("");
        // 恢复主体引用：控制符 → @主体名
        StringBuilder out = new StringBuilder(stripped.length());
        boolean inSubject = false;
        for (int i = 0; i < stripped.length(); i++)
        {
            char c = stripped.charAt(i);
            if (c == SUBJECT_TOKEN_MARK)
            {
                if (!inSubject)
                {
                    out.append('@');
                }
                inSubject = !inSubject;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 主体名归一化：剥掉会破坏 {@code @主体名} 引用边界的字符（空白 / @ / 方括号），空名回退「主体N」。
     */
    private String normalizeSubjectName(String rawName, int ordinal)
    {
        String name = StrUtil.nullToEmpty(rawName)
                .replaceAll("[\\s@\\[\\]]+", "")
                .trim();
        if (StrUtil.isBlank(name))
        {
            return "主体" + ordinal;
        }
        return name;
    }

    /**
     * 读 capability_json.subjectReference 标记：true = 该模型走官方主体调用形态。
     */
    private boolean isSubjectReferenceModel(AiModelConfigVo modelConfig)
    {
        if (modelConfig == null || StrUtil.isBlank(modelConfig.getCapabilityJson()))
        {
            return false;
        }
        try
        {
            JSONObject capability = JSONUtil.parseObj(modelConfig.getCapabilityJson());
            return Boolean.TRUE.equals(capability.getBool(CAPABILITY_SUBJECT_REFERENCE, false));
        }
        catch (Exception e)
        {
            // capability 解析失败按非主体形态处理，不阻断装配
            log.warn("Vidu capability_json 解析失败，按非主体形态装配: {}", e.getMessage());
            return false;
        }
    }
}

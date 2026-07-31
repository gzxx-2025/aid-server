package com.aid.storyboard.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜视频生成参考素材装配产物：装配策略的输出，直接落到下游视频请求（提示词 / 参考图列表 / 首帧垫图 / 厂商扩展参数）。
 *
 * @author 视觉AID
 */
public class VideoReferencePlan
{
    /** 最终下发模型的提示词（含厂商特定的参考图标号 / 说明）。 */
    private final String finalPrompt;

    /** 按 N 升序排列的参考图 URL 列表（与 finalPrompt 内"图片N"严格对齐）。 */
    private final List<String> referenceImageUrls;

    /** 首帧垫图 URL（可空）。 */
    private final String firstFrameImageUrl;

    /**
     * 厂商专属扩展参数（可空）：提交时原样并入 {@code MediaVideoGenerateRequest.options}。
     * 如 Vidu 主体调用参考生的 {@code subjects} 结构、非主体多图的 {@code images} 列表。
     * 键名必须是厂商官方请求字段（Provider 侧按官方字段白名单透传）。
     */
    private final Map<String, Object> extraOptions;

    private VideoReferencePlan(String finalPrompt, List<String> referenceImageUrls,
                               String firstFrameImageUrl, Map<String, Object> extraOptions)
    {
        this.finalPrompt = finalPrompt;
        this.referenceImageUrls = referenceImageUrls == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(referenceImageUrls));
        this.firstFrameImageUrl = firstFrameImageUrl;
        this.extraOptions = extraOptions == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extraOptions));
    }

    public static VideoReferencePlan of(String finalPrompt, List<String> referenceImageUrls, String firstFrameImageUrl)
    {
        return new VideoReferencePlan(finalPrompt, referenceImageUrls, firstFrameImageUrl, null);
    }

    public static VideoReferencePlan of(String finalPrompt, List<String> referenceImageUrls,
                                        String firstFrameImageUrl, Map<String, Object> extraOptions)
    {
        return new VideoReferencePlan(finalPrompt, referenceImageUrls, firstFrameImageUrl, extraOptions);
    }

    public String getFinalPrompt()
    {
        return finalPrompt;
    }

    public List<String> getReferenceImageUrls()
    {
        return referenceImageUrls;
    }

    public String getFirstFrameImageUrl()
    {
        return firstFrameImageUrl;
    }

    public Map<String, Object> getExtraOptions()
    {
        return extraOptions;
    }
}

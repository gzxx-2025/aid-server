package com.aid.storyboard.video;

import java.util.Collections;
import java.util.List;

import com.aid.domain.vo.AiModelConfigVo;

/**
 * 分镜视频生成参考素材装配上下文，作为装配策略的只读入参。
 *
 * @author 视觉AID
 */
public class VideoReferenceContext
{
    /** 视觉导演产出的视频提示词正文（已是自然语言；不含 @图片N 占位）。 */
    private final String videoPrompt;

    /** 用户补充文本（可空），策略决定是否拼接及拼接位置。 */
    private final String userInputText;

    /** 本镜按 N 升序解析成功的参考素材（角色 / 场景 / 道具 / 设定卡混合）。 */
    private final List<ResolvedReference> references;

    /** 首帧垫图完整 URL（baseImageRecordId / final_image_id 解析得到，可空）。 */
    private final String baseImageUrl;

    /** 命中的模型聚合配置（含 supportsMultiImageInput / supportsFirstFrame / providerCode 等能力位）。 */
    private final AiModelConfigVo modelConfig;

    /** 是否生成音频（用户偏好，可空）。 */
    private final Boolean generateAudio;

    /** 参考图业务层上限（厂商内部仍会二次裁剪）。 */
    private final int maxReferenceImages;

    public VideoReferenceContext(String videoPrompt, String userInputText,
                                 List<ResolvedReference> references, String baseImageUrl,
                                 AiModelConfigVo modelConfig, Boolean generateAudio,
                                 int maxReferenceImages)
    {
        this.videoPrompt = videoPrompt;
        this.userInputText = userInputText;
        this.references = references == null ? Collections.emptyList()
                : Collections.unmodifiableList(references);
        this.baseImageUrl = baseImageUrl;
        this.modelConfig = modelConfig;
        this.generateAudio = generateAudio;
        this.maxReferenceImages = maxReferenceImages;
    }

    public String getVideoPrompt()
    {
        return videoPrompt;
    }

    public String getUserInputText()
    {
        return userInputText;
    }

    public List<ResolvedReference> getReferences()
    {
        return references;
    }

    public String getBaseImageUrl()
    {
        return baseImageUrl;
    }

    public AiModelConfigVo getModelConfig()
    {
        return modelConfig;
    }

    public Boolean getGenerateAudio()
    {
        return generateAudio;
    }

    public int getMaxReferenceImages()
    {
        return maxReferenceImages;
    }
}

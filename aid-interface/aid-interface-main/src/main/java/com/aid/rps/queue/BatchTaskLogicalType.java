package com.aid.rps.queue;

/**
 * 批量任务逻辑类型。
 *
 * @author 视觉AID
 */
public final class BatchTaskLogicalType
{
    public static final String ASSET_EXTRACT_CHARACTER = "asset_extract_character";
    public static final String ASSET_EXTRACT_SCENE = "asset_extract_scene";
    public static final String ASSET_EXTRACT_PROP = "asset_extract_prop";
    /** 兼容旧任务快照；新任务按资产类型使用下方三个形态生成槽。 */
    public static final String FORM_GENERATE_BATCH = "form_generate_batch";
    public static final String FORM_GENERATE_CHARACTER_BATCH = "form_generate_character_batch";
    public static final String FORM_GENERATE_SCENE_BATCH = "form_generate_scene_batch";
    public static final String FORM_GENERATE_PROP_BATCH = "form_generate_prop_batch";
    /** 兼容旧任务快照；新任务按资产类型使用下方三个形态图槽。 */
    public static final String FORM_IMAGE_BATCH = "form_image_batch";
    public static final String FORM_IMAGE_CHARACTER_BATCH = "form_image_character_batch";
    public static final String FORM_IMAGE_SCENE_BATCH = "form_image_scene_batch";
    public static final String FORM_IMAGE_PROP_BATCH = "form_image_prop_batch";
    public static final String FORM_CARD_IMAGE_BATCH = "form_card_image_batch";
    public static final String STORYBOARD_SCRIPT_WORKFLOW = "storyboard_script_workflow";
    public static final String STORYBOARD_IMAGE_WORKFLOW = "storyboard_image_workflow";
    public static final String STORYBOARD_VIDEO_WORKFLOW = "storyboard_video_workflow";
    public static final String STORYBOARD_AUDIO_GENERATE = "storyboard_audio_generate";
    public static final String STORYBOARD_LIP_SYNC_GENERATE = "storyboard_lip_sync_generate";
    public static final String EPISODE_EXPORT = "episode_export";

    private BatchTaskLogicalType()
    {
    }
}

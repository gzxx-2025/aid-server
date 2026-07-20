package com.aid.projectgenconfig.enums;

import java.util.Objects;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;

/**
 * 项目级生成配置场景枚举。
 *
 * @author 视觉AID
 */
@Getter
public enum ProjectGenConfigScene
{
    /** 角色提取 */
    CHARACTER_EXTRACT("main_character_extract", "text", false, false),
    /** 场景提取 */
    SCENE_EXTRACT("main_scene_extract", "text", false, false),
    /** 道具提取 */
    PROP_EXTRACT("main_prop_extract", "text", false, false),
    /** 角色形态提取 */
    CHARACTER_FORM("main_character_form", "text", false, false),
    /** 场景形态提取 */
    SCENE_FORM("main_scene_form", "text", false, false),
    /** 道具形态提取 */
    PROP_FORM("main_prop_form", "text", false, false),
    /** 生成角色图 */
    CHARACTER_IMAGE("main_character_image", "image", true, true),
    /** 生成场景图 */
    SCENE_IMAGE("main_scene_image", "image", true, true),
    /** 生成道具图 */
    PROP_IMAGE("main_prop_image", "image", true, true),
    /** 角色设定卡 */
    CHARACTER_CARD_IMAGE("main_character_card_image", "image", true, true),
    /** 分镜脚本提取 */
    STORYBOARD_SCRIPT("main_storyboard_script", "text", false, false),
    /** 分镜图提示词（分镜画师，image-prompt） */
    STORYBOARD_STYLIST("main_storyboard_stylist", "text", false, false),
    /** 视频提示词-多参数版（视觉导演，video-prompt） */
    STORYBOARD_VIDEO_PROMPT("main_storyboard_video_prompt", "text", false, false),
    /** 视频提示词-图生视频（视觉导演图生方向，video-prompt-image） */
    STORYBOARD_VIDEO_PROMPT_IMAGE("main_storyboard_video_prompt_image", "text", false, false),

    /** 视频提示词-宫格版（视觉导演宫格方向，auto_grid 创作模式专用） */
    STORYBOARD_VIDEO_PROMPT_GRID("main_storyboard_video_prompt_grid", "text", false, false),
    /** 分镜生图 */
    STORYBOARD_IMAGE("main_storyboard_image", "image", true, true);

    /** 场景编码（=biz_category_code=func_code） */
    private final String sceneCode;

    /** 期望模型类型：text / image */
    private final String modelType;

    /** 是否需要清晰度/分辨率（图片类场景为 true） */
    private final boolean needResolution;

    /** 是否需要图片比例（仅分镜生图为 true） */
    private final boolean needAspectRatio;

    ProjectGenConfigScene(String sceneCode, String modelType, boolean needResolution, boolean needAspectRatio)
    {
        this.sceneCode = sceneCode;
        this.modelType = modelType;
        this.needResolution = needResolution;
        this.needAspectRatio = needAspectRatio;
    }

    /** 是否图片类场景（含分镜生图） */
    public boolean isImageScene()
    {
        return Objects.equals("image", modelType);
    }

    /**
     * 根据 sceneCode 查找枚举；不存在返回 null。
     *
     * @param sceneCode 场景编码
     * @return 匹配的场景枚举，未匹配返回 null
     */
    public static ProjectGenConfigScene fromCode(String sceneCode)
    {
        if (StrUtil.isBlank(sceneCode))
        {
            return null;
        }
        for (ProjectGenConfigScene scene : values())
        {
            if (Objects.equals(scene.sceneCode, sceneCode))
            {
                return scene;
            }
        }
        return null;
    }
}

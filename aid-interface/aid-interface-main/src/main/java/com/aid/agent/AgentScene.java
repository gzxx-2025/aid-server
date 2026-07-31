package com.aid.agent;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 场景枚举。
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum AgentScene
{
    /** 分镜视频生成。 */
    STORYBOARD_VIDEO("storyboard_video", "分镜视频生成", "video");

    /** 配置键前缀。 */
    private final String key;
    /** 场景描述。 */
    private final String desc;
    /** 模型类型。 */
    private final String modelType;
}

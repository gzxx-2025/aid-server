package com.aid.storyboard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 保存/更新分镜图纸配置请求DTO
 *
 * @author 视觉AID
 */
@Data
public class StoryboardSaveRequest {

    /** 分镜ID */
    @NotNull(message = "分镜ID不能为空")
    private Long id;

    /** 分镜标题 */
    private String title;

    /** 分镜剧本内容(对该镜头的剧情描述) */
    private String storyScript;

    /** 分镜台词配音文本(该镜头角色的对话) */
    private String dialogueText;

    /** 分镜序号 */
    private Integer sortOrder;
}

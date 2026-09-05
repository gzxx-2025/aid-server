package com.aid.rps.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手动落库分镜视频提示词请求 DTO，仅做格式校验 + 落库，不走异步任务/扣费/LLM。
 *
 * @author 视觉AID
 */
@Data
public class StoryboardVideoPromptManualRequest
{
    /** 分镜 ID（{@code aid_storyboard.id}） */
    @NotNull(message = "分镜ID不能为空")
    private Long storyboardId;

    /** 用户手动填写的视频提示词（须符合当前创作模式格式，长度 1~20000）。 */
    @NotNull(message = "提示词不能为空")
    @Size(min = 1, max = 20_000, message = "提示词不规范")
    private String videoPrompt;
}

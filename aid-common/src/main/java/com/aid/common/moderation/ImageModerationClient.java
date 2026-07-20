package com.aid.common.moderation;

/**
 * 图片内容安全审查客户端接口
 *
 * @author 视觉AID
 */
public interface ImageModerationClient
{
    /**
     * 执行图片审查
     *
     * @param req 审查请求
     * @return 审查结果
     */
    ModerationResult moderate(ModerationRequest req);

    /**
     * 当前客户端是否可用（开关开启且凭证齐备）
     *
     * @return true=可用
     */
    boolean enabled();
}

package com.aid.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.hutool.core.util.StrUtil;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 按功能编码批量查询可用AI模型列表请求DTO。
 *
 * @author 视觉AID
 */
@Data
public class AiModelByFuncRequest
{
    /**
     * 功能编码数组，对应 {@code aid_ai_model_func_config.func_code}。
     */
    @NotEmpty(message = "功能编码不能为空")
    private List<String> funcCodes;

    /**
     * 项目ID（可选）。传入后按该项目创作模式重映射出片模型池：
     * {@code main_storyboard_video} 在专业版（pro）下自动切到 {@code main_storyboard_video_multi_pro}，
     * 与 {@code /storyboard/generate/video} 校验池一致。
     */
    private Long projectId;

    /**
     * 剧集ID（可选）。剧集类项目按该剧集 {@code creation_mode} 重映射；不传或电影类用项目默认。
     */
    private Long episodeId;

    /**
     * 归一化后的功能编码列表：trim、过滤空白、按出现顺序去重。
     *
     * @return 去重后的有序功能编码列表（可能为空）
     */
    public List<String> resolveFuncCodes()
    {
        Set<String> ordered = new LinkedHashSet<>();
        if (funcCodes != null)
        {
            for (String code : funcCodes)
            {
                if (StrUtil.isNotBlank(code))
                {
                    ordered.add(code.trim());
                }
            }
        }
        return new ArrayList<>(ordered);
    }
}

package com.aid.project.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户修改项目请求DTO
 *
 * @author 视觉AID
 */
@Data
public class UserProjectUpdateRequest {

    /** 主键ID */
    @NotNull(message = "项目ID不能为空")
    private Long id;

    /** 项目名称 */
    @Size(max = 100, message = "项目名称不能超过100个字符")
    private String projectName;

    /** 项目描述 */
    @Size(max = 500, message = "项目描述不能超过500个字符")
    private String projectDesc;

    /** 封面图（入参自动剥离域名入库） */
    @MediaUrl
    private String coverUrl;

    /** 画面比例(16:9, 9:16等) */
    private String aspectRatio;

    /** 剧本类型(剧情演绎plot, 真人解说monologue) */
    private String scriptType;

    /** 视频风格名称（前端传什么存什么，不做枚举校验） */
    @Size(max = 100, message = "风格名称过长")
    private String videoStyleType;

    /** 视频风格值字符串（前端传什么存什么） */
    @Size(max = 500, message = "风格值过长")
    private String videoStyleValue;

    /** 默认生成模式(economy经济, performance性能) */
    private String defaultGenMode;

    /** 默认创作模式(i2v图生视频, multi多参生视频) */
    private String defaultCreationMode;
}

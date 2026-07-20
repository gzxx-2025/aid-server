package com.aid.aid.domain;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.core.domain.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 项目级生成配置实体
 * 维度：项目 + 用户 + 场景（{@code scene_code} 等于 {@code aid_agent.biz_category_code}，
 * 也等于 {@code aid_ai_model_func_config.func_code}）。每个项目、每个用户、每个场景一行。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_project_gen_config")
public class AidProjectGenConfig extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属项目ID（aid_comic_project.id） */
    private Long projectId;

    /** 所属用户ID（aid_comic_project.user_id） */
    private Long userId;

    /** 场景编码（=biz_category_code=func_code），如 main_character_extract / main_storyboard_image */
    private String sceneCode;

    /** 智能体编码（aid_agent.agent_code） */
    private String agentCode;

    /** 模型编码（aid_ai_model.model_code） */
    private String modelCode;

    /** 清晰度/分辨率档位（图片场景），如 1K/2K/4K，命中模型 capability_json.sizeOptions */
    private String resolution;

    /** 图片比例（仅分镜生图场景），如 16:9，命中模型 capability_json.aspectRatioOptions */
    private String aspectRatio;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;
}

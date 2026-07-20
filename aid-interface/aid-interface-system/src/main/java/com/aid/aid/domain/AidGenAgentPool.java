package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 生成链路智能体可选池+默认矩阵对象 aid_gen_agent_pool
 * 维度：step × creation_mode × script_type × strategy → 候选智能体（is_default=1 为默认）。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_gen_agent_pool")
public class AidGenAgentPool extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 步骤(script分镜脚本 stylist分镜图提示词 video_prompt分镜视频提示词)。
     */
    @Excel(name = "步骤")
    private String step;

    /** 业务场景编码(=aid_agent.biz_category_code=func_code) */
    @Excel(name = "业务场景编码")
    private String bizCategoryCode;

    /** 创作模式(i2v图生 multi多参 pro专业 auto_grid宫格) */
    @Excel(name = "创作模式")
    private String creationMode;

    /** 剧本类型(plot剧情演绎 monologue真人解说) */
    @Excel(name = "剧本类型")
    private String scriptType;

    /** 模型策略(economy经济 performance性能) */
    @Excel(name = "模型策略")
    private String strategy;

    /** 智能体编码(aid_agent.agent_code) */
    @Excel(name = "智能体编码")
    private String agentCode;

    /** 该智能体在此策略下的默认模型(空则走智能体默认模型兜底) */
    @Excel(name = "默认模型")
    private String modelCode;

    /** 默认清晰度(仅图片场景，如 1K/2K；文字场景为空) */
    @Excel(name = "默认清晰度")
    private String resolution;

    /** 默认比例(仅图片场景，如 1:1/16:9；文字场景为空) */
    @Excel(name = "默认比例")
    private String aspectRatio;

    /** 是否该组合默认选择(1是 0候选) */
    @Excel(name = "是否默认")
    private String isDefault;

    /** 显示顺序 */
    @Excel(name = "显示顺序")
    private Integer sortOrder;

    /** 状态(0正常 1停用) */
    @Excel(name = "状态")
    private String status;

    /** 删除标志(0存在 1删除) */
    private String delFlag;
}

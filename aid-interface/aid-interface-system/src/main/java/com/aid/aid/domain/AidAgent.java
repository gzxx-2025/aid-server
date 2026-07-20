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
 * 智能体配置对象 aid_agent
 * 承载所有"业务系统提示词 + 默认模型 + 默认参数"配置，按 biz_type 隔离不同业务。
 * 注意：prompt_content 仅管理端可见，C 端接口绝对不返回（由 AgentInfoVO 隔离）。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_agent")
public class AidAgent extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 智能体编码（业务唯一标识，前端引用此字段） */
    @Excel(name = "智能体编码")
    private String agentCode;

    /** 智能体名称（管理端展示） */
    @Excel(name = "智能体名称")
    private String name;

    /** 智能体图标地址（管理端上传、C 端展示） */
    @Excel(name = "智能体图标")
    private String iconUrl;

    /** 副标题/简述 */
    @Excel(name = "副标题")
    private String subTitle;

    /** 介绍 */
    @Excel(name = "介绍")
    private String introduction;

    /** 系统提示词正文（仅管理端可见，C 端绝不返回） */
    @Excel(name = "系统提示词正文")
    private String promptContent;

    /** 默认模型编码（不填走业务侧 AgentModelResolver 兜底） */
    @Excel(name = "默认模型编码")
    private String modelCode;

    /** 业务分类编码（强校验：与 aid_ai_model_func_config.func_code 联动；
     *  各业务接口按此判断智能体是否可用，例如 main_character_extract / main_scene_form 等） */
    @Excel(name = "业务分类编码")
    private String bizCategoryCode;

    /** 状态：1启用 0停用 */
    @Excel(name = "状态", readConverterExp = "1=启用,0=停用")
    private Integer status;

    /** 删除标志（0代表存在 2代表软删） */
    private String delFlag;

}

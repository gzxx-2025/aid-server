package com.aid.aid.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 分镜脚本批次对象 aid_storyboard_batch。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_storyboard_batch")
public class AidStoryboardBatch extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 父任务 aid_extract_task.id */
    @Excel(name = "父任务ID")
    private Long parentTaskId;

    /** 所属主表 aid_role_prop_scene.id */
    @Excel(name = "场景ID")
    private Long sceneId;

    /** 批次序号（从 0 起，同 scene_id 内连续） */
    @Excel(name = "批次序号")
    private Integer batchIndex;

    /** 该批包含的 sceneCode JSON 数组（如 ["003","004","005"]） */
    @Excel(name = "场次序号清单JSON")
    private String shotCodes;

    /** 批次状态：PENDING / PROCESSING / SUCCEEDED / FAILED / CANCELLED */
    @Excel(name = "批次状态")
    private String status;

    /** 计费状态：FROZEN / SETTLED / REFUNDED */
    @Excel(name = "计费状态")
    private String billingStatus;

    /** 该批预冻结金额（元） */
    @Excel(name = "预冻结金额")
    private BigDecimal frozenAmount;

    /** 该批结算金额（成功时填） */
    @Excel(name = "结算金额")
    private BigDecimal settledAmount;

    /** 重试轮次（每次续生 +1，用于幂等 traceId） */
    @Excel(name = "重试轮次")
    private Integer retryRound;

    /** 该批 LLM 输出 JSON 数组 */
    @TableField(value = "result_data")
    private String resultData;

    /** 该批产出分镜数（成功时填） */
    @Excel(name = "产出分镜数")
    private Integer shotCount;

    /** 失败原因（截断 500 字以内） */
    @Excel(name = "失败原因")
    private String errorMessage;

    /** 镜头组拆分计划ID（专业版/宫格模式批次关联拆分计划） */
    @Excel(name = "镜头组计划ID")
    @TableField(value = "shot_group_plan_id")
    private Long shotGroupPlanId;

    /** 删除标志（0存在 1删除） */
    private String delFlag;
}

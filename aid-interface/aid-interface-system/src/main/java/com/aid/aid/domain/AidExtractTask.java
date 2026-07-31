package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 资产提取任务对象 aid_extract_task
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_extract_task")
public class AidExtractTask extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 项目ID */
    @Excel(name = "项目ID")
    private Long projectId;

    /** 剧集ID(电影为0) */
    @Excel(name = "剧集ID(电影为0)")
    private Long episodeId;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long userId;

    /** 任务类型 */
    @Excel(name = "任务类型")
    private String taskType;

    /** 状态: PENDING/PROCESSING/SUCCEEDED/FAILED/CANCELLED */
    @Excel(name = "状态: PENDING/PROCESSING/SUCCEEDED/FAILED/CANCELLED")
    private String status;

    /** 输入快照JSON */
    @Excel(name = "输入快照JSON")
    private String inputSnapshot;

    /** 结果数据JSON */
    @Excel(name = "结果数据JSON")
    private String resultData;

    /** 错误信息 */
    @Excel(name = "错误信息")
    private String errorMessage;

    /** 提取资产总数 */
    @Excel(name = "提取资产总数")
    private Integer totalCount;

    /** 删除标志(0存在 1删除) */
    private String delFlag;

    /** AI模型编码（必填，对应aid_ai_model.model_code） */
    @Excel(name = "AI模型编码")
    private String modelCode;

    /** 计费状态: INIT/FROZEN/SUCCESS/FAILED */
    private String billingStatus;

    /** 预冻结金额（元） */
    private java.math.BigDecimal frozenAmount;

    /** 计费追踪ID（幂等） */
    private String billingTraceId;

    /** 计费快照JSON：SKU定价信息+token估值，用于差额结算 */
    private String billingSnapshotJson;

    /** 实际扣费金额（结算时由 BillingAmountCalculator 计算） */
    private java.math.BigDecimal actualCost;

}

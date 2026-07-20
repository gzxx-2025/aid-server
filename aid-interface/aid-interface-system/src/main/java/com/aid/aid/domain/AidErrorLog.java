package com.aid.aid.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 上游错误样本日志 aid_error_log。
 * 命中规则与未识别错误统一写入本表，按 {@code provider_code + sample_hash} 去重累加，
 * 后台管理可基于"未识别错误"列表一键创建归一化规则。
 *
 * @author 视觉AID
 */
@Data
@TableName("aid_error_log")
public class AidErrorLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联任务 ID */
    private String taskId;

    /** 厂商编码 */
    private String providerCode;

    /** 模型编码 */
    private String modelCode;

    /** HTTP 状态码 */
    private Integer httpStatus;

    /** 上游原始错误体（截断 2KB） */
    private String rawMessage;

    /** 命中规则 ID（NULL 表示未识别） */
    private Long matchedRuleId;

    /** 最终归一化的错误码 */
    private String matchedErrorCode;

    /** 相同 hash 累计次数 */
    private Integer occurrenceCount;

    /** raw_message 归一化 MD5 */
    private String sampleHash;

    /** 首次出现时间 */
    private Date firstSeen;

    /** 最近出现时间 */
    private Date lastSeen;
}

package com.aid.aid.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 上游错误归一化规则 aid_provider_error_rule。
 * 将错误码归一化规则从代码硬编码下沉到库表，运营在管理后台可直接 CRUD，
 * 启动时加载到内存并落本地缓存文件。
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_provider_error_rule")
public class AidProviderErrorRule extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 厂商编码（NULL 表示全局规则） */
    @Excel(name = "厂商编码")
    private String providerCode;

    /** 模型编码（NULL 表示厂商所有模型） */
    @Excel(name = "模型编码")
    private String modelCode;

    /** 规则名称（运营可读） */
    @Excel(name = "规则名称")
    private String ruleName;

    /** 匹配类型：HTTP_STATUS / CODE / KEYWORD / REGEX / JSON_PATH */
    @Excel(name = "匹配类型")
    private String matchType;

    /**
     * 匹配内容：。
     */
    @Excel(name = "匹配内容")
    private String matchPattern;

    /** JSON_PATH 模式下的字段路径，如 $.error.code */
    private String matchField;

    /** 是否区分大小写（0 否 1 是） */
    private Integer caseSensitive;

    /** 映射到的 TaskErrorCode 枚举名 */
    @Excel(name = "错误码")
    private String errorCode;

    /** 覆盖默认 userMessage（NULL 用枚举默认值） */
    private String userMessage;

    /**
     * 优先级，数字越小越先匹配。
     */
    private Integer priority;

    /** 启用 (0 禁 1 启) */
    private Integer enabled;

    /** 内置规则 (1 内置不可删除，只能禁用) */
    private Integer isBuiltin;
}

package com.aid.aid.domain;

import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.annotation.Excel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * 配置信息对象 aid_config
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_config")
public class AidConfig extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 配置类型 */
    @Excel(name = "配置类型")
    private String category;

    /** 配置名称 */
    @Excel(name = "配置名称")
    private String configName;

    /** 配置值 */
    @Excel(name = "配置值")
    private String configValue;

    /** 说明 */
    @Excel(name = "说明")
    private String configDict;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /** 显示顺序 */
    @Excel(name = "显示顺序")
    private Integer orderNum;

    /** 版本 */
    @Excel(name = "版本")
    private Long version;

    /** 更新IP */
    @Excel(name = "更新IP")
    private String updateIp;

    /** 租户Id */
    @Excel(name = "租户Id")
    private Long tenantId;

}

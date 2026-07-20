package com.aid.vo;

import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 配置信息业务对象 chat_config
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChatConfigVO extends BaseEntity {

    /**
     * 主键
     */
    private Long id;

    /**
     * 配置类型
     */
    private String category;

    /**
     * 配置名称
     */
    private String configName;

    /**
     * 配置值
     */
    private String configValue;

    /**
     * 说明
     */
    private String configDict;

    /**
     * 备注
     */
    private String remark;

    /**
     * 更新IP
     */
    private String updateIp;


}

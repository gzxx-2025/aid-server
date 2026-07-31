package com.aid.modelhealth.dto;

import lombok.Data;

/**
 * 模型运行状态看板查询入参（C端 / 后台管理共用）。
 *
 * @author 视觉AID
 */
@Data
public class ModelHealthBoardRequest {

    /** 服务商编码（aid_ai_provider.provider_code），可空=查全部服务商 */
    private String providerCode;

    /** 模型类型过滤（text/image/video/audio），可空=全部类型 */
    private String modelType;

    /** 页码，缺省 1 */
    private Integer pageNum;

    /** 每页条数（按模型时间线分页），缺省 20，上限 100 */
    private Integer pageSize;
}

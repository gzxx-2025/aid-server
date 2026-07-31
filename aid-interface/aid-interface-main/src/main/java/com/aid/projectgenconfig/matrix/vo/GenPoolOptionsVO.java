package com.aid.projectgenconfig.matrix.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 某业务场景(biz)下可选的智能体 + 模型选项（供矩阵配置页下拉用）。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class GenPoolOptionsVO {

    /** 业务场景编码 */
    private String bizCategoryCode;

    /** 该 biz 下启用的智能体（value=agentCode, label=名称） */
    private List<SelectOption> agents;

    /** 该 biz(func_code) 模型池中的模型（value=modelCode, label=模型名） */
    private List<SelectOption> models;
}

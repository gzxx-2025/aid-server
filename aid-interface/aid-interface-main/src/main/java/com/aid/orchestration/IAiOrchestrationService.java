package com.aid.orchestration;

import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.orchestration.dto.RetireResourceRequest;
import com.aid.orchestration.vo.OrchestrationImpactVO;

/**
 * AI 业务编排完整性与资源生命周期服务。
 */
public interface IAiOrchestrationService
{
    /** 校验模型池定义、编码不可变性以及现有活动引用。 */
    void validateFunctionConfig(AidAiModelFuncConfig config);

    /** 校验功能配置没有智能体、矩阵或项目级活动引用。 */
    void validateFunctionConfigsRemovable(Long[] ids);

    OrchestrationImpactVO previewModelRetirement(Long modelId);

    OrchestrationImpactVO previewAgentRetirement(Long agentId);

    /** 兼容旧删除接口：仅在完全无活动引用时执行软删除。 */
    int deleteModelsIfUnreferenced(Long[] modelIds, String operator);

    /** 兼容旧删除接口：仅在完全无活动引用时执行软删除。 */
    int deleteAgentIfUnreferenced(Long agentId, String operator);

    /** 替换或清理活动引用后软删除模型；历史任务和计费记录不变。 */
    void retireModel(Long modelId, RetireResourceRequest request, String operator);

    /** 替换或清理活动引用后软删除智能体；历史任务和生成记录不变。 */
    void retireAgent(Long agentId, RetireResourceRequest request, String operator);
}

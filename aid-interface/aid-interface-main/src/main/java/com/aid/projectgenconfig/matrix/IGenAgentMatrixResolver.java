package com.aid.projectgenconfig.matrix;

import java.util.List;

/**
 * 智能体可选池/默认矩阵解析器。
 * 读取 {@code aid_gen_agent_pool}，按 (业务场景 × 创作模式 × 剧本类型 × 模型策略) 解析
 * 默认智能体+模型与可选池。供 /get 默认值解析、批量/单次生成的池校验复用。
 *
 * @author 视觉AID
 */
public interface IGenAgentMatrixResolver {

    /**
     * 解析单个业务场景在指定维度下的默认智能体+模型与可选池。
     *
     * @param bizCategoryCode 业务场景编码
     * @param creationMode    创作模式(i2v/multi/pro/auto_grid)
     * @param scriptType      剧本类型(plot/monologue)
     * @param strategy        模型策略(economy/performance)
     * @return 解析结果（不存在配置时 configured=false、agentCode/池为空）
     */
    GenAgentMatrixResult resolve(String bizCategoryCode, String creationMode, String scriptType, String strategy);

    /**
     * 查询某业务场景在指定创作模式+剧本类型下的可选智能体池（不区分策略，去重）。
     *
     * @param bizCategoryCode 业务场景编码
     * @param creationMode    创作模式
     * @param scriptType      剧本类型
     * @return 可选 agentCode 列表（空表示该场景不适用/未配置）
     */
    List<String> listAgentPool(String bizCategoryCode, String creationMode, String scriptType);

    /**
     * 校验某 agentCode 是否在指定维度的可选池内。
     *
     * @return true=合法（在池内）
     */
    boolean isAgentAllowed(String bizCategoryCode, String creationMode, String scriptType, String agentCode);

    /**
     * 校验某 agentCode 是否为该业务场景在任意创作模式 / 剧本类型下的合法候选。
     *
     * @param bizCategoryCode 业务场景编码
     * @param agentCode       智能体编码
     * @return true=该场景在任意创作模式下的合法候选
     */
    boolean isAgentInScenePool(String bizCategoryCode, String agentCode);

    /**
     * 批量解析多个业务场景在指定创作模式下的可选池（一次查库，避免 N+1）。
     *
     * @param bizCategoryCodes 业务场景编码集合
     * @param creationMode     创作模式（i2v/multi/pro/auto_grid）
     * @return 受管场景 → 当前模式可选池（有序）；未受管场景不出现在返回 Map 中
     */
    java.util.Map<String, List<String>> listManagedScenePools(java.util.Collection<String> bizCategoryCodes,
                                                              String creationMode);
}

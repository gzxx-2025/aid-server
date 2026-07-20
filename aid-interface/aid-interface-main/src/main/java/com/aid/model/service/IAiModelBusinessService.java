package com.aid.model.service;

import java.util.List;

import com.aid.model.dto.AiModelListRequest;
import com.aid.model.vo.AiModelFuncGroupVO;
import com.aid.model.vo.AiModelVO;

/**
 * C端AI模型业务Service接口
 *
 * @author 视觉AID
 */
public interface IAiModelBusinessService
{
    /**
     * 查询可用模型列表。
     *
     * @param modelType 模型类型（可选）: text/image/video/audio
     * @return 模型列表（按优先级降序）
     */
    List<AiModelVO> listAvailableModels(String modelType);

    /**
     * 查询可用模型列表（支持大类 + 生成模式细分筛选）。
     *
     * @param request 查询请求（modelType / generateMode 均可选）
     * @return 模型列表（按优先级降序）
     */
    List<AiModelVO> listAvailableModels(AiModelListRequest request);

    /**
     * 按功能编码查询可用模型列表。
     *
     * @param funcCode 功能编码，不能为空；无对应配置 / 配置未启用时返回空数组
     * @return 模型列表（按 modelIds 配置顺序）
     */
    List<AiModelVO> listAvailableModelsByFuncCode(String funcCode);

    /**
     * 按多个功能编码查询可用模型列表（并集，去重）。
     *
     * @param funcCodes 功能编码列表；为空或全部无效时返回空数组
     * @return 去重后的模型列表（按入参编码顺序、各编码内按 modelIds 配置顺序）
     */
    List<AiModelVO> listAvailableModelsByFuncCodes(List<String> funcCodes);

    /**
     * 按多个功能编码查询可用模型列表（按功能分组，不去重、不合并）。
     *
     * @param funcCodes 功能编码列表；为空时返回空数组
     * @return 按入参编码顺序的功能分组列表
     */
    List<AiModelFuncGroupVO> listAvailableModelsGroupedByFuncCodes(List<String> funcCodes);

    /**
     * 按功能编码分组查询可用模型，并可按项目创作模式重映射出片池。
     *
     * @param funcCodes 功能编码列表
     * @param projectId 项目ID（可空；空则不重映射）
     * @param episodeId 剧集ID（可空）
     * @param userId    当前用户ID（projectId 非空时必填）
     * @return 按映射后编码顺序的功能分组列表
     */
    List<AiModelFuncGroupVO> listAvailableModelsGroupedByFuncCodes(List<String> funcCodes,
            Long projectId, Long episodeId, Long userId);
}

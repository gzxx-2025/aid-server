package com.aid.prompt.service;

import java.util.List;
import com.aid.aid.domain.AidPromptLib;
import com.aid.prompt.dto.PromptFileQueryRequest;
import com.aid.prompt.dto.PromptLibCreateRequest;
import com.aid.prompt.dto.PromptLibQueryRequest;
import com.aid.prompt.dto.PromptLibUpdateRequest;
import com.aid.prompt.vo.PromptFileContentVO;
import com.aid.aid.domain.dto.PromptLibDataDTO;
import com.aid.aid.domain.dto.PromptLibQueryDTO;

/**
 * 提示词素材库业务Service接口
 *
 * @author 视觉AID
 */
public interface IPromptLibBusinessService
{
    /**
     * 查询提示词列表（个人在前+官方在后，合并返回）
     *
     * @param request 查询条件
     * @param userId 用户ID
     * @return 合并后的提示词列表
     */
    List<AidPromptLib> selectPromptList(PromptLibQueryRequest request, Long userId);

    /**
     * 查询提示词详情
     *
     * @param id 提示词ID
     * @param userId 当前用户ID
     * @return 提示词详情
     */
    AidPromptLib selectPromptDetail(Long id, Long userId);

    /**
     * 创建用户自定义提示词
     *
     * @param request 创建请求
     * @param userId 用户ID
     * @return 新增的提示词
     */
    AidPromptLib createPrompt(PromptLibCreateRequest request, Long userId);

    /**
     * 修改用户自定义提示词
     *
     * @param request 修改请求
     * @param userId 用户ID
     * @return 修改后的提示词
     */
    AidPromptLib updatePrompt(PromptLibUpdateRequest request, Long userId);

    /**
     * 删除用户自定义提示词（软删除）
     *
     * @param id 提示词ID
     * @param userId 用户ID
     * @return 影响行数
     */
    int deletePrompt(Long id, Long userId);

    /**
     * 获取统一字典数据
     * 提供系统中的提示词库数据和枚举数据
     *
     * @param queryDTO 查询条件
     * @return 统一字典数据
     */
    PromptLibDataDTO getPromptLibData(PromptLibQueryDTO queryDTO);

    /**
     * 获取提示词文件内容
     * 根据promptType和remark查询数据库，优先从本地文件缓存读取，不存在则从数据库写入文件
     *
     * @param request 查询请求（promptType、remark、lang）
     * @return 中文和英文提示词内容
     */
    PromptFileContentVO getPromptFileContent(PromptFileQueryRequest request);
}

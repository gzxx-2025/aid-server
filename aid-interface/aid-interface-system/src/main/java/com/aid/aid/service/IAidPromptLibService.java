package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.domain.dto.SystemPromptUpdateRequest;
import com.aid.aid.domain.vo.PromptVersionCheckVO;
import com.aid.aid.domain.vo.PromptVersionItemVO;

/**
 * 提示词素材库(官方预设与用户自定义)Service接口
 *
 * @author 视觉AID
 */
public interface IAidPromptLibService extends IService<AidPromptLib>
{
    /**
     * 查询提示词素材库(官方预设与用户自定义)
     *
     * @param id 提示词素材库(官方预设与用户自定义)主键
     * @return 提示词素材库(官方预设与用户自定义)
     */
    public AidPromptLib selectAidPromptLibById(Long id);

    /**
     * 查询提示词素材库(官方预设与用户自定义)列表
     *
     * @param aidPromptLib 提示词素材库(官方预设与用户自定义)
     * @return 提示词素材库(官方预设与用户自定义)集合
     */
    public List<AidPromptLib> selectAidPromptLibList(AidPromptLib aidPromptLib);

    /**
     * 新增提示词素材库(官方预设与用户自定义)
     *
     * @param aidPromptLib 提示词素材库(官方预设与用户自定义)
     * @return 结果
     */
    public int insertAidPromptLib(AidPromptLib aidPromptLib);

    /**
     * 修改提示词素材库(官方预设与用户自定义)
     *
     * @param aidPromptLib 提示词素材库(官方预设与用户自定义)
     * @return 结果
     */
    public int updateAidPromptLib(AidPromptLib aidPromptLib);

    /**
     * 批量删除提示词素材库(官方预设与用户自定义)
     *
     * @param ids 需要删除的提示词素材库(官方预设与用户自定义)主键集合
     * @return 结果
     */
    public int deleteAidPromptLibByIds(Long[] ids);

    /**
     * 删除提示词素材库(官方预设与用户自定义)信息
     *
     * @param id 提示词素材库(官方预设与用户自定义)主键
     * @return 结果
     */
    public int deleteAidPromptLibById(Long id);

    /**
     * 查询系统提示词列表（仅 main_business_prompt / main_teacher_prompt）
     *
     * @param promptType 提示词分类（可选，不传查全部两种类型）
     * @return 提示词列表
     */
    List<AidPromptLib> selectSystemPromptList(String promptType);

    /**
     * 修改系统提示词（仅允许修改 main_business_prompt / main_teacher_prompt 及版本号）
     *
     * @param request 修改请求
     * @return 修改后的提示词
     */
    AidPromptLib updateSystemPrompt(SystemPromptUpdateRequest request);

    /**
     * 检查系统提示词版本更新状态
     *
     * @return 各提示词的版本检查结果
     */
    List<PromptVersionCheckVO> checkSystemPromptUpdate();

    /**
     * 根据文件名称获取提示词的历史版本列表
     *
     * @param remark 文件名称
     * @return 历史版本列表
     */
    List<PromptVersionItemVO> getPromptVersionsByRemark(String remark);

    /**
     * 拉取系统提示词更新（将本地版本更新到远程最新版本）
     *
     * @param remark 文件名称
     * @return 更新后的提示词
     */
    AidPromptLib pullSystemPromptUpdate(String remark);
}

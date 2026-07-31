package com.aid.aid.service;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidExtractTask;

/**
 * 资产提取任务Service接口
 *
 * @author 视觉AID
 */
public interface IAidExtractTaskService extends IService<AidExtractTask>
{
    /**
     * 查询资产提取任务
     *
     * @param id 资产提取任务主键
     * @return 资产提取任务
     */
    public AidExtractTask selectAidExtractTaskById(Long id);

    /**
     * 查询资产提取任务列表
     *
     * @param aidExtractTask 资产提取任务
     * @return 资产提取任务集合
     */
    public List<AidExtractTask> selectAidExtractTaskList(AidExtractTask aidExtractTask);

    /**
     * 新增资产提取任务
     *
     * @param aidExtractTask 资产提取任务
     * @return 结果
     */
    public int insertAidExtractTask(AidExtractTask aidExtractTask);

    /**
     * 修改资产提取任务
     *
     * @param aidExtractTask 资产提取任务
     * @return 结果
     */
    public int updateAidExtractTask(AidExtractTask aidExtractTask);

    /**
     * 批量删除资产提取任务
     *
     * @param ids 需要删除的资产提取任务主键集合
     * @return 结果
     */
    public int deleteAidExtractTaskByIds(Long[] ids);

    /**
     * 删除资产提取任务信息
     *
     * @param id 资产提取任务主键
     * @return 结果
     */
    public int deleteAidExtractTaskById(Long id);
}

package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;
import cn.hutool.core.util.StrUtil;
import com.aid.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.aid.mapper.AidExtractTaskMapper;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidExtractTaskService;

/**
 * 资产提取任务Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidExtractTaskServiceImpl extends ServiceImpl<AidExtractTaskMapper, AidExtractTask> implements IAidExtractTaskService
{
    @Autowired
    private AidExtractTaskMapper aidExtractTaskMapper;

    /**
     * 查询资产提取任务
     *
     * @param id 资产提取任务主键
     * @return 资产提取任务
     */
    @Override
    public AidExtractTask selectAidExtractTaskById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询资产提取任务列表
     *
     * @param aidExtractTask 资产提取任务
     * @return 资产提取任务
     */
    @Override
    public List<AidExtractTask> selectAidExtractTaskList(AidExtractTask aidExtractTask)
    {
        LambdaQueryWrapper<AidExtractTask> wrapper = Wrappers.lambdaQuery();
        if (aidExtractTask != null)
        {
            if (aidExtractTask.getProjectId() != null)
            {
                wrapper.eq(AidExtractTask::getProjectId, aidExtractTask.getProjectId());
            }
            if (aidExtractTask.getEpisodeId() != null)
            {
                wrapper.eq(AidExtractTask::getEpisodeId, aidExtractTask.getEpisodeId());
            }
            if (aidExtractTask.getUserId() != null)
            {
                wrapper.eq(AidExtractTask::getUserId, aidExtractTask.getUserId());
            }
            if (StrUtil.isNotBlank(aidExtractTask.getTaskType()))
            {
                wrapper.eq(AidExtractTask::getTaskType, aidExtractTask.getTaskType());
            }
            if (StrUtil.isNotBlank(aidExtractTask.getStatus()))
            {
                wrapper.eq(AidExtractTask::getStatus, aidExtractTask.getStatus());
            }
            if (StrUtil.isNotBlank(aidExtractTask.getModelCode()))
            {
                wrapper.eq(AidExtractTask::getModelCode, aidExtractTask.getModelCode());
            }
            if (StrUtil.isNotBlank(aidExtractTask.getBillingStatus()))
            {
                wrapper.eq(AidExtractTask::getBillingStatus, aidExtractTask.getBillingStatus());
            }
        }
        wrapper.orderByDesc(AidExtractTask::getId);
        return this.list(wrapper);
    }

    /**
     * 新增资产提取任务
     *
     * @param aidExtractTask 资产提取任务
     * @return 结果
     */
    @Override
    public int insertAidExtractTask(AidExtractTask aidExtractTask)
    {
        aidExtractTask.setCreateTime(DateUtils.getNowDate());
        return this.save(aidExtractTask) ? 1 : 0;
    }

    /**
     * 修改资产提取任务
     *
     * @param aidExtractTask 资产提取任务
     * @return 结果
     */
    @Override
    public int updateAidExtractTask(AidExtractTask aidExtractTask)
    {
        aidExtractTask.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidExtractTask) ? 1 : 0;
    }

    /**
     * 批量删除资产提取任务
     *
     * @param ids 需要删除的资产提取任务主键
     * @return 结果
     */
    @Override
    public int deleteAidExtractTaskByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除资产提取任务信息
     *
     * @param id 资产提取任务主键
     * @return 结果
     */
    @Override
    public int deleteAidExtractTaskById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }
}

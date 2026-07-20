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
import com.aid.aid.mapper.AidBalanceLogMapper;
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.domain.dto.BalanceConsumeAggDTO;
import com.aid.aid.service.IAidBalanceLogService;

/**
 * 余额变动记录Service业务层处理
 *
 * @author 视觉AID
 */
@Service
public class AidBalanceLogServiceImpl extends ServiceImpl<AidBalanceLogMapper, AidBalanceLog> implements IAidBalanceLogService
{
    @Autowired
    private AidBalanceLogMapper aidBalanceLogMapper;

    /**
     * 查询余额变动记录
     *
     * @param id 余额变动记录主键
     * @return 余额变动记录
     */
    @Override
    public AidBalanceLog selectAidBalanceLogById(Long id)
    {
        return this.getById(id);
    }

    /**
     * 查询余额变动记录列表
     *
     * @param aidBalanceLog 余额变动记录
     * @return 余额变动记录
     */
    @Override
    public List<AidBalanceLog> selectAidBalanceLogList(AidBalanceLog aidBalanceLog)
    {
        LambdaQueryWrapper<AidBalanceLog> wrapper = Wrappers.lambdaQuery();
        if (aidBalanceLog != null)
        {
            if (aidBalanceLog.getUserId() != null)
            {
                wrapper.eq(AidBalanceLog::getUserId, aidBalanceLog.getUserId());
            }
            if (StrUtil.isNotBlank(aidBalanceLog.getChangeType()))
            {
                wrapper.eq(AidBalanceLog::getChangeType, aidBalanceLog.getChangeType());
            }
            if (StrUtil.isNotBlank(aidBalanceLog.getRelatedId()))
            {
                wrapper.eq(AidBalanceLog::getRelatedId, aidBalanceLog.getRelatedId());
            }
            if (StrUtil.isNotBlank(aidBalanceLog.getBizType()))
            {
                wrapper.eq(AidBalanceLog::getBizType, aidBalanceLog.getBizType());
            }
            if (StrUtil.isNotBlank(aidBalanceLog.getBizName()))
            {
                wrapper.like(AidBalanceLog::getBizName, aidBalanceLog.getBizName());
            }
        }
        wrapper.orderByDesc(AidBalanceLog::getId);
        return this.list(wrapper);
    }

    /**
     * 新增余额变动记录
     *
     * @param aidBalanceLog 余额变动记录
     * @return 结果
     */
    @Override
    public int insertAidBalanceLog(AidBalanceLog aidBalanceLog)
    {
        aidBalanceLog.setCreateTime(DateUtils.getNowDate());
        return this.save(aidBalanceLog) ? 1 : 0;
    }

    /**
     * 修改余额变动记录
     *
     * @param aidBalanceLog 余额变动记录
     * @return 结果
     */
    @Override
    public int updateAidBalanceLog(AidBalanceLog aidBalanceLog)
    {
        aidBalanceLog.setUpdateTime(DateUtils.getNowDate());
        return this.updateById(aidBalanceLog) ? 1 : 0;
    }

    /**
     * 批量删除余额变动记录
     *
     * @param ids 需要删除的余额变动记录主键
     * @return 结果
     */
    @Override
    public int deleteAidBalanceLogByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return this.removeByIds(Arrays.asList(ids)) ? 1 : 0;
    }

    /**
     * 删除余额变动记录信息
     *
     * @param id 余额变动记录主键
     * @return 结果
     */
    @Override
    public int deleteAidBalanceLogById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return this.removeById(id) ? 1 : 0;
    }

    @Override
    public List<BalanceConsumeAggDTO> selectUserConsumeAgg(Long userId)
    {
        // 直接委托 Mapper 的聚合查询；分页由调用方在调用前 PageHelper.startPage 接管
        return aidBalanceLogMapper.selectUserConsumeAgg(userId);
    }
}

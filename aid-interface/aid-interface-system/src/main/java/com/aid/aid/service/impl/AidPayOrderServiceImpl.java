package com.aid.aid.service.impl;

import java.util.Arrays;
import java.util.List;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidPayOrder;
import com.aid.aid.mapper.AidPayOrderMapper;
import com.aid.aid.service.IAidPayOrderService;
import com.aid.common.utils.DateUtils;

/**
 * 支付订单CRUD Service业务层处理
 * 用于后台管理的增删改查操作
 *
 * @author 视觉AID
 */
@Service
public class AidPayOrderServiceImpl extends ServiceImpl<AidPayOrderMapper, AidPayOrder>  implements IAidPayOrderService
{
    @Autowired
    private AidPayOrderMapper aidPayOrderMapper;

    /**
     * 查询支付订单
     *
     * @param id 支付订单主键
     * @return 支付订单
     */
    @Override
    public AidPayOrder selectAidPayOrderById(Long id)
    {
        return aidPayOrderMapper.selectById(id);
    }

    /**
     * 查询支付订单列表
     *
     * @param aidPayOrder 支付订单
     * @return 支付订单
     */
    @Override
    public List<AidPayOrder> selectAidPayOrderList(AidPayOrder aidPayOrder)
    {
        LambdaQueryWrapper<AidPayOrder> wrapper = Wrappers.lambdaQuery();
        if (aidPayOrder != null)
        {
            if (StrUtil.isNotBlank(aidPayOrder.getOrderNo()))
            {
                wrapper.like(AidPayOrder::getOrderNo, aidPayOrder.getOrderNo());
            }
            if (StrUtil.isNotBlank(aidPayOrder.getTradeNo()))
            {
                wrapper.like(AidPayOrder::getTradeNo, aidPayOrder.getTradeNo());
            }
            if (aidPayOrder.getUserId() != null)
            {
                wrapper.eq(AidPayOrder::getUserId, aidPayOrder.getUserId());
            }
            if (aidPayOrder.getPackageId() != null)
            {
                wrapper.eq(AidPayOrder::getPackageId, aidPayOrder.getPackageId());
            }
            if (StrUtil.isNotBlank(aidPayOrder.getProductName()))
            {
                wrapper.like(AidPayOrder::getProductName, aidPayOrder.getProductName());
            }
            if (StrUtil.isNotBlank(aidPayOrder.getPayChannel()))
            {
                wrapper.eq(AidPayOrder::getPayChannel, aidPayOrder.getPayChannel());
            }
            if (StrUtil.isNotBlank(aidPayOrder.getPayStatus()))
            {
                wrapper.eq(AidPayOrder::getPayStatus, aidPayOrder.getPayStatus());
            }
        }
        wrapper.orderByDesc(AidPayOrder::getId);
        return aidPayOrderMapper.selectList(wrapper);
    }

    /**
     * 新增支付订单
     *
     * @param aidPayOrder 支付订单
     * @return 结果
     */
    @Override
    public int insertAidPayOrder(AidPayOrder aidPayOrder)
    {
        aidPayOrder.setCreateTime(DateUtils.getNowDate());
        return aidPayOrderMapper.insert(aidPayOrder) > 0 ? 1 : 0;
    }

    /**
     * 修改支付订单
     *
     * @param aidPayOrder 支付订单
     * @return 结果
     */
    @Override
    public int updateAidPayOrder(AidPayOrder aidPayOrder)
    {
        aidPayOrder.setUpdateTime(DateUtils.getNowDate());
        return aidPayOrderMapper.updateById(aidPayOrder) > 0 ? 1 : 0;
    }

    /**
     * 批量删除支付订单
     *
     * @param ids 需要删除的支付订单主键
     * @return 结果
     */
    @Override
    public int deleteAidPayOrderByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        return aidPayOrderMapper.deleteBatchIds(Arrays.asList(ids));
    }

    /**
     * 删除支付订单信息
     *
     * @param id 支付订单主键
     * @return 结果
     */
    @Override
    public int deleteAidPayOrderById(Long id)
    {
        if (id == null)
        {
            return 0;
        }
        return aidPayOrderMapper.deleteById(id);
    }

    /**
     * 按状态 + 过期时间下限扫描超时的待支付订单（QB1：Quartz 任务专用）
     */
    @Override
    public List<AidPayOrder> selectExpiredByStatus(String status, java.util.Date createdBefore, int limit)
    {
        // 兜底：limit 落在 1..1000 范围，避免上游传 0/Integer.MAX_VALUE 造成 OOM/无效查询
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        LambdaQueryWrapper<AidPayOrder> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.eq(AidPayOrder::getPayStatus, status)
                .lt(AidPayOrder::getCreateTime, createdBefore)
                .orderByAsc(AidPayOrder::getCreateTime)
                .last("LIMIT " + safeLimit);
        return aidPayOrderMapper.selectList(queryWrapper);
    }
}

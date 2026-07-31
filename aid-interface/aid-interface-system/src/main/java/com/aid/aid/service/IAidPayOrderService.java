package com.aid.aid.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aid.aid.domain.AidPayOrder;

/**
 * 支付订单CRUD Service接口
 * 用于后台管理的增删改查操作
 *
 * @author 视觉AID
 */
public interface IAidPayOrderService  extends IService<AidPayOrder>
{
    /**
     * 查询支付订单
     *
     * @param id 支付订单主键
     * @return 支付订单
     */
    AidPayOrder selectAidPayOrderById(Long id);

    /**
     * 查询支付订单列表
     *
     * @param aidPayOrder 支付订单
     * @return 支付订单集合
     */
    List<AidPayOrder> selectAidPayOrderList(AidPayOrder aidPayOrder);

    /**
     * 新增支付订单
     *
     * @param aidPayOrder 支付订单
     * @return 结果
     */
    int insertAidPayOrder(AidPayOrder aidPayOrder);

    /**
     * 修改支付订单
     *
     * @param aidPayOrder 支付订单
     * @return 结果
     */
    int updateAidPayOrder(AidPayOrder aidPayOrder);

    /**
     * 批量删除支付订单
     *
     * @param ids 需要删除的支付订单主键集合
     * @return 结果
     */
    int deleteAidPayOrderByIds(Long[] ids);

    /**
     * 删除支付订单信息
     *
     * @param id 支付订单主键
     * @return 结果
     */
    int deleteAidPayOrderById(Long id);

    /**
     * 按状态 + 过期时间下限扫描超时的待支付订单（QB1：Quartz 任务专用）。
     *
     * @param status       订单状态（例如 PayConstants.STATUS_PENDING）
     * @param createdBefore 只取 createTime 小于该时间的订单
     * @param limit        本轮最多返回条数
     * @return 订单列表（可能为空）
     */
    List<AidPayOrder> selectExpiredByStatus(String status, java.util.Date createdBefore, int limit);
}

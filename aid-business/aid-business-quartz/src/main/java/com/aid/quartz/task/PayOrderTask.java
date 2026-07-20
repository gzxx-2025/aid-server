package com.aid.quartz.task;

import com.aid.common.constant.PayConstants;
import com.aid.aid.domain.AidPayOrder;
import com.aid.pay.service.IAidPayOrderBussinessService;
import com.aid.aid.service.IAidPayOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 支付订单定时任务
 * - 扫描超时的待支付订单
 * - 查询官方支付状态，处理漏单情况
 *
 * <p>QB1：Quartz 层不再持有 LambdaQueryWrapper，所有查询下沉到
 * {@link IAidPayOrderService#selectExpiredByStatus(String, Date, int)}。</p>
 *
 * @author AID
 */
@Slf4j
@Component("payOrderTask")
public class PayOrderTask {

    @Autowired
    private IAidPayOrderService payOrderService;
    @Autowired
    private IAidPayOrderBussinessService iAidPayOrderBussinessService;

    /**
     * QZ6：防重入标识。Quartz 单机内存模式下，若本轮还没跑完下一轮触发会并发重复处理；
     * 与 MediaTask / ExtractBillingTask 对齐，使用 AtomicBoolean 抢占式执行。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 兜底查单防重入标识 */
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    /**
     * 支付订单兜底查单（固定任务，禁止关闭）。
     * 扫描超时的待支付订单并向支付渠道查单，回调丢失时补入账或关单。
     * 原 PayOrderSyncScheduler 的 @Scheduled 逻辑统一收口到 Quartz 管理。
     *
     * 调用示例：payOrderTask.syncPendingExpiredOrders
     */
    public void syncPendingExpiredOrders() {
        // 防重入：上一轮还在跑就直接跳过
        if (!syncRunning.compareAndSet(false, true)) {
            log.debug("支付订单兜底查单上一轮仍在执行，跳过本次触发");
            return;
        }
        try {
            iAidPayOrderBussinessService.autoSyncPendingExpiredOrders();
        } catch (Exception e) {
            // 兜底任务失败不影响业务，仅记日志
            log.error("支付订单兜底查单任务异常", e);
        } finally {
            syncRunning.set(false);
        }
    }

    /**
     * QZ4：每次最多处理的订单条数上限。
     * 避免一次扫描把内存堆满 + 占用支付渠道 API 配额；过剩任务留给下一轮周期消费。
     */
    private static final int MAX_BATCH_PER_ROUND = 200;

    /**
     * 扫描超时的待支付订单并处理。
     * 建议配置：每 5 分钟执行一次。
     */
    public void checkPendingOrders() {
        // QZ6: 防重入，上一轮还在跑就直接跳过
        if (!running.compareAndSet(false, true)) {
            log.info("PayOrderTask 上一轮未结束，本轮跳过");
            return;
        }
        log.info("========== 开始扫描超时待支付订单 ==========");

        try {
            // 超时条件：createTime + ORDER_EXPIRE_MINUTES < now
            Date expireDeadline = new Date(
                    System.currentTimeMillis() - (long) PayConstants.ORDER_EXPIRE_MINUTES * 60 * 1000);

            // QB1 / QZ4：通过 Service 扫描本轮待处理订单（单轮分页上限）
            List<AidPayOrder> expiredOrders = payOrderService.selectExpiredByStatus(
                    PayConstants.STATUS_PENDING, expireDeadline, MAX_BATCH_PER_ROUND);

            if (expiredOrders.isEmpty()) {
                log.info("没有超时的待支付订单");
                return;
            }

            log.info("发现 {} 个超时待支付订单（本轮批量上限={}）", expiredOrders.size(), MAX_BATCH_PER_ROUND);

            int successCount = 0;
            int failCount = 0;

            // 遍历订单，查询官方状态并处理
            for (AidPayOrder order : expiredOrders) {
                try {
                    processOrder(order);
                    successCount++;
                } catch (Exception e) {
                    log.error("处理订单异常: orderNo={}", order.getOrderNo(), e);
                    failCount++;
                }
            }

            log.info("========== 订单扫描完成: 成功处理{}个, 失败{}个 ==========", successCount, failCount);

        } catch (Exception e) {
            log.error("扫描超时待支付订单异常", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 处理单个订单
     *
     * @param order 订单信息
     */
    private void processOrder(AidPayOrder order) {
        String orderNo = order.getOrderNo();
        String payChannel = order.getPayChannel();

        log.info("处理超时订单: orderNo={}, payChannel={}", orderNo, payChannel);

        // 检查支付渠道
        if (!PayConstants.CHANNEL_ALIPAY.equals(payChannel) && !PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
            log.warn("不支持的支付渠道: orderNo={}, payChannel={}", orderNo, payChannel);
            return;
        }

        // 调用同步接口查询并处理
        iAidPayOrderBussinessService.syncOrderStatus(orderNo);
    }
}

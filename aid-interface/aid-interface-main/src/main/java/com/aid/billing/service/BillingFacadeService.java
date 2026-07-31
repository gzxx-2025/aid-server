package com.aid.billing.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.dto.BillingInput;
import com.aid.domain.vo.AiModelConfigVo;

import java.util.Map;

/**
 * 计费门面服务：对外统一入口，提供预扣、结算、退款能力。
 * 内部负责规则解析和金额计算，账户动作委托给 MediaBillingServiceImpl。
 */
public interface BillingFacadeService {

    /**
     * 预扣：任务提交前冻结预估费用。
     *
     * @param task        媒体任务（需已设置 userId）
     * @param modelConfig 模型配置（含 billingMode / billingRuleJson）
     * @param billingInput 统一计费输入（已由调用方从请求中提取）
     */
    void prepareBilling(AidMediaTask task, AiModelConfigVo modelConfig, BillingInput billingInput);

    /**
     * 结算：任务成功后处理最终扣费。
     *
     * @param task      媒体任务
     * @param usageData 上游usage数据（文本模型可传，图片/视频传null）
     * @return true=结算成功，false=CAS失败（已被其他线程处理）
     */
    boolean settleBilling(AidMediaTask task, Map<String, Object> usageData);

    /**
     * 退款：任务失败后退回冻结金额。
     *
     * @param task 媒体任务
     * @return true=退款成功，false=CAS失败
     */
    boolean refundBilling(AidMediaTask task);

    /**
     * 追补扫描：扫描 text_settle_status=PARTIAL_DONE 的媒体任务，
     * 从用户可用余额追补剩余差额。全额补完后推进到 DONE，仍不足则保持 PARTIAL_DONE。
     * 由定时任务周期调用。
     *
     * @param batchSize 单次批量拉取上限
     * @return 本次成功处理的任务数
     */
    int retryPartialExtraCharges(int batchSize);
}

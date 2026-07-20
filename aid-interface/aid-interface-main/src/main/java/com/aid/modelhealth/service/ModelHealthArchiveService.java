package com.aid.modelhealth.service;

/**
 * 模型健康统计归档服务：把早于保留天数的统计行导出为服务器本地 txt 存档后从库删除，
 * 控制 aid_model_health_stat 表体量恒定（保留窗口内行数 = 模型数 × 每日48桶 × 保留天数）。
 *
 * <p>存档文件属于「可丢失」的运维留档，不参与任何业务读取。</p>
 *
 * @author 视觉AID
 */
public interface ModelHealthArchiveService {

    /**
     * 归档并删除早于保留天数的统计数据。
     *
     * @param keepDays 保留最近天数（按自然时间往前推）
     * @return 归档并删除的行数
     */
    int archiveAndCleanup(int keepDays);
}

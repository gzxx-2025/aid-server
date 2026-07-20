package com.aid.modelhealth.service;

/**
 * 模型健康采集器：把模型调用的成功/失败结果累加进 aid_model_health_stat 时间桶计数。
 *
 * <p>口径约定（必须遵守）：</p>
 * <ul>
 *   <li>只统计数字，不记录请求人、不记录请求明细；</li>
 *   <li>失败仅统计「上游返回错误」：提交被上游拒绝、上游生成失败、上游超时无响应；</li>
 *   <li>上游已正常返回数据、但我方后续处理（OSS 转存/回写/计费等）出问题的，<b>不算模型失败</b>，不得调用 recordFailure；</li>
 *   <li>本采集器所有方法内部吞异常，绝不影响主业务流程。</li>
 * </ul>
 *
 * @author 视觉AID
 */
public interface ModelHealthRecorder {

    /**
     * 记录一次上游成功。
     *
     * @param modelCode 模型编码（aid_media_task.model_name / aid_ai_model.model_code）
     * @param mediaType 媒体类型（TEXT/IMAGE/VIDEO/AUDIO）
     * @param latencyMs 耗时毫秒（任务创建到上游返回结果；同步调用即请求耗时），可空
     */
    void recordSuccess(String modelCode, String mediaType, Long latencyMs);

    /**
     * 记录一次上游失败（仅上游返回错误时调用，内部处理异常禁止调用）。
     *
     * @param modelCode    模型编码
     * @param mediaType    媒体类型（TEXT/IMAGE/VIDEO/AUDIO）
     * @param errorMessage 上游错误信息（存库时截断至200字）
     */
    void recordFailure(String modelCode, String mediaType, String errorMessage);
}

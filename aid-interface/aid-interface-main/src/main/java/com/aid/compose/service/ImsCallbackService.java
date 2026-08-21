package com.aid.compose.service;

/** 阿里云 IMS 回调唤醒服务；最终状态始终以 GetMediaProducingJob 反查结果为准。 */
public interface ImsCallbackService
{
    void handle(String jobId, String eventMessage, String userData);
}

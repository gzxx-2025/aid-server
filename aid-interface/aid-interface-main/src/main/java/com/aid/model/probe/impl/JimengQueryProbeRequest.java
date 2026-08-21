package com.aid.model.probe.impl;

import com.alibaba.fastjson2.annotation.JSONField;

import lombok.Data;

/**
 * 即梦任务查询探测请求。
 */
@Data
public class JimengQueryProbeRequest {

    /** 模型实际服务标识 */
    @JSONField(name = "req_key")
    private String reqKey;

    /** 不存在的任务标识 */
    @JSONField(name = "task_id")
    private String taskId;
}

package com.aid.common.config.test;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 配置连通性测试请求。
 */
@Data
public class ConfigTestRequest {

    /**
     * 测试类型标识，用于路由到对应的 Tester。
     */
    @NotBlank(message = "测试类型不能为空")
    private String testKey;

    /**
     * 用户临时填写的配置参数（可能含密钥）。
     *
     * 仅在内存流转，不落库、不写日志。
     */
    private Map<String, Object> payload;
}

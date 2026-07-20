package com.aid.common.config.test;

/**
 * 配置连通性测试 SPI 接口。
 *
 * 由 Spring 自动发现：各业务模块实现本接口并注册为 Bean，
 * {@link ConfigConnectivityTestRegistry} 会收集所有实现并按 {@link #testKey()} 索引。
 *
 * 实现类放在各自归属的业务模块（依赖各业务 SDK），避免 aid-common 反向依赖业务。
 */
public interface ConfigConnectivityTester {

    /**
     * 测试类型标识，需全局唯一（如 alipay、smtp、oss、sms、wxpay、ai-model 等）。
     *
     * @return 测试类型 key
     */
    String testKey();

    /**
     * 执行连通性测试。
     *
     * 实现内部必须对全部异常做兜底，转换为失败结果返回，禁止异常冒泡到调用方。
     *
     * @param request 测试请求（含临时配置 payload，密钥不落库）
     * @return 测试结果
     */
    ConfigTestResult test(ConfigTestRequest request);
}

package com.aid.common.config.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.common.exception.ServiceException;

import lombok.extern.slf4j.Slf4j;

/**
 * 配置连通性测试注册中心。
 *
 * 构造时由 Spring 注入所有 {@link ConfigConnectivityTester} 实现，
 * 按 {@link ConfigConnectivityTester#testKey()} 建立索引；list 为空时允许空 map。
 */
@Slf4j
@Component
public class ConfigConnectivityTestRegistry {

    /**
     * testKey -> Tester 索引。
     */
    private final Map<String, ConfigConnectivityTester> testers = new HashMap<>();

    /**
     * 构造注入所有 Tester 并按 testKey 索引。
     *
     * @param testerList 所有 Tester 实现（可能为空）
     */
    public ConfigConnectivityTestRegistry(List<ConfigConnectivityTester> testerList) {
        if (testerList != null) {
            for (ConfigConnectivityTester tester : testerList) {
                testers.put(tester.testKey(), tester);
            }
        }
    }

    /**
     * 执行连通性测试。
     *
     * @param req 测试请求
     * @return 测试结果（含耗时）
     */
    public ConfigTestResult run(ConfigTestRequest req) {
        ConfigConnectivityTester tester = testers.get(req.getTestKey());
        if (tester == null) {
            throw new ServiceException("不支持的测试类型");
        }
        long start = System.currentTimeMillis();
        ConfigTestResult result;
        try {
            result = tester.test(req);
            if (result == null) {
                result = ConfigTestResult.fail("测试无返回结果");
            }
        } catch (Exception e) {
            // 兜底：任何异常都转为失败结果，禁止冒泡到调用方
            log.warn("配置连通性测试执行异常, testKey={}", req.getTestKey(), e);
            result = ConfigTestResult.fail("测试执行异常");
        }
        result.setElapsedMs(System.currentTimeMillis() - start);
        return result;
    }
}

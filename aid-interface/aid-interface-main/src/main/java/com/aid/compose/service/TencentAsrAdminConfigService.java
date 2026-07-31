package com.aid.compose.service;

import com.aid.compose.dto.TencentAsrConfigUpdateCommand;
import com.aid.compose.dto.TencentAsrTestRequest;
import com.aid.compose.dto.TencentAsrTestResult;

import java.util.Map;

/** 腾讯云自动字幕后台配置服务。 */
public interface TencentAsrAdminConfigService {

    /** 读取脱敏后的配置。 */
    Map<String, String> getMaskedConfig();

    /** 校验并保存整组配置，同时刷新运行时缓存。 */
    void save(TencentAsrConfigUpdateCommand command);

    /** 使用已保存配置识别一个临时上传的视频，并返回最终结果。 */
    TencentAsrTestResult test(TencentAsrTestRequest request);
}

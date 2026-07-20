package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * Vidu 回调地址解析支持：回调基地址跟随「供应商 / 模型」配置走，不放配置中心，与其它厂商口径一致。
 */
@Slf4j
public final class ViduCallbackSupport {

    private ViduCallbackSupport() {
    }

    /**
     * 解析回调基地址：模型级优先，回退供应商级；任何解析异常一律按「未配置」返回 null。
     *
     * @param modelConfig 模型聚合配置（含模型级与供应商级 schedule_strategy_json）
     * @return 回调基地址；未配置/解析失败返回 null
     */
    public static String resolveCallbackBaseUrl(AiModelConfigVo modelConfig) {
        if (modelConfig == null) {
            return null;
        }
        String fromModel = readCallbackBaseUrl(modelConfig.getScheduleStrategyJson());
        if (StrUtil.isNotBlank(fromModel)) {
            return fromModel;
        }
        return readCallbackBaseUrl(modelConfig.getProviderScheduleStrategyJson());
    }

    /** 从单段 schedule_strategy_json 读取 callbackBaseUrl，非法/缺失返回 null。 */
    private static String readCallbackBaseUrl(String strategyJson) {
        if (StrUtil.isBlank(strategyJson)) {
            return null;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(strategyJson);
            String url = obj.getStr(ViduConstants.STRATEGY_KEY_CALLBACK_BASE_URL);
            return StrUtil.isBlank(url) ? null : url.trim();
        } catch (Exception e) {
            // 脏数据不阻断主流程：回调仅作加速，轮询兜底。
            log.warn("vidu 解析 schedule_strategy_json.callbackBaseUrl 失败，按未配置处理：{}", e.getMessage());
            return null;
        }
    }
}

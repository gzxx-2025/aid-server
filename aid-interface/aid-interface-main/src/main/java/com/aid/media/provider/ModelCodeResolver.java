package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;

import java.util.Objects;

/**
 * 上游模型名解析工具：将「前端展示/选择码 model_code」解耦为「真实上游模型名 real_model_code」。
 */
public final class ModelCodeResolver {

    private ModelCodeResolver() {
    }

    /**
     * 解析最终下发上游的模型名（不含各 Provider 的兜底常量）。
     *
     * @param modelConfig      聚合模型配置（含 modelCode 展示码 / realModelCode 真实模型名）
     * @param requestModelName 本次请求显式指定的模型名（可能为展示码、真实模型名或空）
     * @return 解析后的上游模型名；无法确定时返回 {@code null}
     */
    public static String resolveUpstreamModel(AiModelConfigVo modelConfig, String requestModelName) {
        String configReal = null;
        String displayCode = null;
        if (Objects.nonNull(modelConfig)) {
            displayCode = modelConfig.getModelCode();
            configReal = StrUtil.isNotBlank(modelConfig.getRealModelCode())
                    ? modelConfig.getRealModelCode() : modelConfig.getModelCode();
        }

        if (StrUtil.isNotBlank(requestModelName)
                && !StrUtil.equals(requestModelName, displayCode)) {
            return requestModelName;
        }

        return configReal;
    }
}

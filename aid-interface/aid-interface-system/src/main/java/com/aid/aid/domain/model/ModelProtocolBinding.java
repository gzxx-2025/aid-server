package com.aid.aid.domain.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** 模型能力的具体协议及请求映射。 */
@Data
public class ModelProtocolBinding {
    private String code;
    private String protocol;
    private String upstreamModel;
    private String apiSuffix;
    private String apiVersion;
    private String taskQuerySuffix;
    private Boolean defaultBinding = false;
    private Boolean enabled = true;
    private Map<String, Object> capability;
    private Map<String, Object> presentation;
    private Map<String, Object> fixedParameters;
    private Map<String, Object> parameterMapping;
    private List<FieldMapping> mappings;
    private String billingMode;
    private Map<String, Object> billingRule;
    private BigDecimal costCredits;

    @Data
    public static class FieldMapping {
        private String source;
        private String target;
        private String type;
    }
}

package com.aid.aid.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** 同一真实模型下的能力与协议配置。 */
@Data
public class ModelCapabilityDefinition {
    private String code;
    private String label;
    private String generateMode;
    private Boolean enabled = true;
    private Boolean defaultCapability = false;
    private String evidenceStatus;
    private List<String> sourceUrls = new ArrayList<>();
    private List<ModelParameter> parameters = new ArrayList<>();
    private List<ModelParameterRule> rules = new ArrayList<>();
    private Map<String, Object> presentation;
    private List<ModelProtocolBinding> bindings = new ArrayList<>();
}

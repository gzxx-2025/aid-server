package com.aid.skill.vo;

import com.aid.billing.vo.ModelBillingDetailVO;
import com.aid.model.vo.CapabilityVO;
import lombok.Data;

import java.util.List;

/** Public identity returned by the authenticated Skill Runtime catalog. */
public final class SkillCatalogVO {
    private SkillCatalogVO() { }

    @Data
    public static class Item {
        private Long id;
        private String skillCode;
        private String name;
        private String description;
        private String capabilityDescription;
        private String iconUrl;
        private String capability;
        private String outputKind;
        private String defaultModelCode;
        private List<ModelItem> models;
    }

    @Data
    public static class ModelItem {
        private String modelCode;
        private String modelName;
        @com.aid.common.aid.oss.annotation.MediaUrl
        private String modelLogo;
        private String providerName;
        @com.aid.common.aid.oss.annotation.MediaUrl
        private String providerLogo;
        private Boolean defaultModel;
        private CapabilityVO capability;
        private ModelBillingDetailVO billing;
    }
}

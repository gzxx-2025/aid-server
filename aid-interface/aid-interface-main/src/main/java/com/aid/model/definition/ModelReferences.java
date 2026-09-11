package com.aid.model.definition;

import com.aid.model.vo.AiModelVO;
import java.util.List;
import java.util.Objects;

/** 在当前可用模型池内恢复旧引用，不扩大业务模型选择范围。 */
public final class ModelReferences {
    private ModelReferences() { }

    public static String canonicalCode(List<AiModelVO> pool, String code) {
        if (code == null || pool == null) return code;
        if (pool.stream().anyMatch(model -> Objects.equals(model.getModelCode(), code))) return code;
        List<AiModelVO> matches = pool.stream().filter(model -> model.getLegacyModelCodes() != null
                && model.getLegacyModelCodes().contains(code)).toList();
        return matches.size() == 1 ? matches.get(0).getModelCode() : code;
    }
}

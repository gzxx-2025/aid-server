package com.aid.prompt.dto;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 枚举字典查询入参
 * enumTypes 必须从白名单 {@link com.aid.prompt.constant.EnumDictRegistry} 中传入，
 * 严格区分大小写；非法类型将直接抛出异常。
 *
 * @author 视觉AID
 */
@Data
public class EnumDictListRequest {

    /** 枚举类型列表，必填，必须来自白名单 */
    @NotEmpty(message = "类型不能为空")
    private List<String> enumTypes;
}

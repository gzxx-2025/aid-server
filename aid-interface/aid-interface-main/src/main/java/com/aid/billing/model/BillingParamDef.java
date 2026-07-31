package com.aid.billing.model;

import lombok.Data;

import java.util.List;

/**
 * 计费参数定义：描述本模型计费依赖的参数元数据。
 */
@Data
public class BillingParamDef {

    /** 参数编码，程序识别用（如 resolution、duration、inputChars） */
    private String code;

    /** 参数名称，后台展示用（如 分辨率、时长、输入字数） */
    private String name;

    /** 参数类型：ENUM / NUMBER / RANGE */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 单位（如 秒、字、张） */
    private String unit;

    /** 枚举可选项 */
    private List<String> options;
}

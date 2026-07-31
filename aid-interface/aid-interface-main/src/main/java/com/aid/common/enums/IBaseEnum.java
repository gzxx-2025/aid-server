package com.aid.common.enums;

/**
 * 基础枚举接口
 * 所有需要统一返回的枚举类都应实现此接口
 *
 * @author 视觉AID
 */
public interface IBaseEnum {

    /**
     * 获取枚举值
     *
     * @return 枚举值
     */
    String getValue();

    /**
     * 获取枚举描述
     *
     * @return 枚举描述
     */
    String getDesc();
}

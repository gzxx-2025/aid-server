package com.aid.rps.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * expected_appearances 数组元素 DTO（角色多形态定义）。
 *
 * @author 视觉AID
 */
@Data
public class ExpectedAppearanceItem {

    /** 形象编号（0-based，0 = 初始/默认形象） */
    private Integer id;

    /** 形态名称（如"初始形象"/"觉醒后形象"），用于 form 命名 */
    private String name;

    /** 形象变更原因（详细描述），兼容 change_reason */
    @JsonAlias("change_reason")
    private String changeReason;
}

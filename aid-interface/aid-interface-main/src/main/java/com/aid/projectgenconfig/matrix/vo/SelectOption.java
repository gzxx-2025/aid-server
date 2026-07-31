package com.aid.projectgenconfig.matrix.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用下拉选项 {label, value}。
 *
 * @author 视觉AID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelectOption {

    /** 选项值 */
    private String value;

    /** 选项展示名 */
    private String label;
}

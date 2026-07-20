package com.aid.billing.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 计费表格列定义 VO（表头）。
 * 描述某个计费口径下计费档位表格应展示的列，前端按本列表的顺序渲染表头，
 * 并用 {@link #key} 去 {@link BillingRuleItemVO} 取对应字段值填充单元格。
 *
 * @author 视觉AID
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillingColumnVO implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 列字段名，对应 {@link BillingRuleItemVO} 的属性名（如 resolution / durationMin / pricePerSecond） */
    private String key;

    /** 列标题（如 分辨率 / 时长下限 / 每秒单价） */
    private String label;

    /** 列单位（如 秒 / Credits / Credits/百万Token），无单位时为 null */
    private String unit;

    /** 列数据类型：number-数值，text-文本，便于前端对齐与格式化 */
    private String type;
}

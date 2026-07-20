package com.aid.aid.domain.dto;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一字典数据返回对象
 * 包含提示词库数据和枚举数据，作为系统字典对外提供
 *
 * @author 视觉AID
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PromptLibDataDTO {

    /**
     * 提示词库数据列表（来自aid_prompt_lib表）
     */
    private List<PromptLibItem> promptLibList;

    /**
     * 枚举数据列表（来自com.aid.enums包）
     */
    private List<EnumItem> enumList;

    /**
     * 提示词库数据项
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PromptLibItem {
        /**
         * 主键ID
         */
        private Long id;

        /**
         * 提示词分类
         */
        private String promptType;

        /**
         * 提示词名称
         */
        private String promptName;

        /**
         * 提示词内容
         */
        private String promptContent;

        /**
         * 效果预览图URL
         */
        @MediaUrl
        private String coverUrl;

        /**
         * 展示排序
         */
        private Long sortOrder;

        /**
         * 状态 (0正常 1停用)
         */
        private String status;
    }

    /**
     * 枚举数据项
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EnumItem {
        /**
         * 枚举类型名称
         */
        private String enumType;

        /**
         * 枚举值
         */
        private String value;

        /**
         * 枚举描述
         */
        private String desc;

        /**
         * 枚举分类（用于分组显示）
         */
        private String category;
    }
}

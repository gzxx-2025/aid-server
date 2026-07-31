package com.aid.step.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 步骤状态VO(返回给前端渲染导航栏)。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class StepStatusVO {

    /**
     * 当前已解锁到的步骤(1~7)。
     */
    private Integer currentStep;

    /**
     * 每个步骤的详细状态。
     */
    private List<StepDetail> steps;

    @Data
    @Builder
    public static class StepDetail {

        /**
         * 步骤编号(1~7)。
         */
        private Integer step;

        /**
         * 步骤名称。
         */
        private String name;

        /** 状态: completed已完成, current当前, waiting等待 */
        private String status;
    }
}

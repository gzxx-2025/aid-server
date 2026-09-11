package com.aid.aid.domain.model;

import java.util.List;
import lombok.Data;

/** 可视化条件组和参数约束。 */
@Data
public class ModelParameterRule {
    private String label;
    private String match;
    private List<Condition> conditions;
    private List<Action> actions;

    @Data
    public static class Condition {
        /** 嵌套条件组；为空时是单个字段条件。 */
        private String match;
        private List<Condition> conditions;
        private String field;
        private String operator;
        private Object value;
    }

    @Data
    public static class Action {
        private String field;
        private String operator;
        private Object value;
        /** 合计上限中需要相加的只读素材统计字段。 */
        private String valueField;
    }
}

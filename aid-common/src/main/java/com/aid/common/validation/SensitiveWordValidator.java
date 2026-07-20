package com.aid.common.validation;

import com.aid.common.utils.SensitiveWordUtil;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

/**
 * 敏感词校验器
 * 配合 @SensitiveWordCheck 注解使用
 *
 * @author 视觉AID
 */
public class SensitiveWordValidator implements ConstraintValidator<SensitiveWordCheck, String> {

    private String message;
    private String fieldName;

    @Override
    public void initialize(SensitiveWordCheck constraintAnnotation) {
        this.message = constraintAnnotation.message();
        this.fieldName = constraintAnnotation.fieldName();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 空值不进行校验，交给 @NotNull/@NotEmpty 等注解处理
        if (value == null || value.isEmpty()) {
            return true;
        }

        // 检测敏感词
        List<String> sensitiveWords = SensitiveWordUtil.check(value);
        if (sensitiveWords.isEmpty()) {
            return true;
        }

        // 禁用默认消息
        context.disableDefaultConstraintViolation();

        // 构建错误消息
        String errorMessage;
        if (fieldName != null && !fieldName.isEmpty()) {
            errorMessage = String.format("%s: %s", fieldName,
                    String.format(message, String.join(", ", sensitiveWords)));
        } else {
            errorMessage = String.format("%s (%s)", message,
                    String.join(", ", sensitiveWords));
        }

        // 设置自定义错误消息
        context.buildConstraintViolationWithTemplate(errorMessage).addConstraintViolation();

        return false;
    }
}

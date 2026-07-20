package com.aid.common.utils;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.core.SensitiveWordHelper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 敏感词工具类
 *
 * @author 视觉AID
 */
@Component
public class SensitiveWordUtil {

    private static SensitiveWordBs sensitiveWordBs;

    /**
     * 通过Spring设置敏感词检测实例
     */
    public static void setSensitiveWordBs(SensitiveWordBs bs) {
        SensitiveWordUtil.sensitiveWordBs = bs;
    }

    /**
     * 判断文本是否包含敏感词
     *
     * @param text 待检测的文本
     * @return true-包含敏感词，false-不包含
     */
    public static boolean contains(String text) {
        if (sensitiveWordBs != null) {
            return sensitiveWordBs.contains(text);
        }
        return SensitiveWordHelper.contains(text);
    }

    /**
     * 获取文本中的第一个敏感词
     *
     * @param text 待检测的文本
     * @return 第一个敏感词，不存在则返回null
     */
    public static String findFirst(String text) {
        if (sensitiveWordBs != null) {
            return sensitiveWordBs.findFirst(text);
        }
        return SensitiveWordHelper.findFirst(text);
    }

    /**
     * 获取文本中所有的敏感词
     *
     * @param text 待检测的文本
     * @return 敏感词列表
     */
    public static List<String> findAll(String text) {
        if (sensitiveWordBs != null) {
            return sensitiveWordBs.findAll(text);
        }
        return SensitiveWordHelper.findAll(text);
    }

    /**
     * 替换文本中的敏感词为默认的*号
     *
     * @param text 待检测的文本
     * @return 替换后的文本
     */
    public static String replace(String text) {
        if (sensitiveWordBs != null) {
            return sensitiveWordBs.replace(text);
        }
        return SensitiveWordHelper.replace(text);
    }

    /**
     * 替换文本中的敏感词为指定字符
     *
     * @param text 待检测的文本
     * @param ch   替换字符
     * @return 替换后的文本
     */
    public static String replace(String text, char ch) {
        return SensitiveWordHelper.replace(text, ch);
    }

    /**
     * 获取敏感词的标签
     *
     * @param word 敏感词
     * @return 标签集合
     */
    public static Set<String> tags(String word) {
        if (sensitiveWordBs != null) {
            return sensitiveWordBs.tags(word);
        }
        return Set.of();
    }

    /**
     * 检测文本并返回敏感词列表，如果不存在则返回空列表
     * 此方法主要用于业务逻辑判断
     *
     * @param text 待检测的文本
     * @return 敏感词列表，不存在返回空列表
     */
    public static List<String> check(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return findAll(text);
    }

    /**
     * 检测文本是否包含敏感词，包含则抛出异常
     *
     * @param text      待检测的文本
     * @param fieldName 字段名称，用于异常提示
     * @throws ServiceException 包含敏感词时抛出
     */
    public static void checkAndThrow(String text, String fieldName) {
        List<String> sensitiveWords = check(text);
        if (!sensitiveWords.isEmpty()) {
            throw new com.aid.common.exception.ServiceException(
                    String.format("%s包含敏感词: %s", fieldName, String.join(", ", sensitiveWords))
            );
        }
    }
}

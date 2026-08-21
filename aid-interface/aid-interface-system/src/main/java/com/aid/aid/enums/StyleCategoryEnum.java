package com.aid.aid.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 官方风格分类字典。
 *
 * @author 视觉AID
 */
@Getter
@AllArgsConstructor
public enum StyleCategoryEnum {

    COMIC_DRAMA("comic_drama", "漫剧", 10),
    LIVE_ACTION("live_action", "真人剧", 20),
    THREE_D("three_d", "3D", 30),
    CHINESE("chinese", "国风", 40),
    TWO_D("two_d", "2D", 50),
    CHIBI("chibi", "Q版", 60),
    GAME("game", "游戏", 70),
    JAPANESE("japanese", "日漫", 80),
    WESTERN("western", "欧美", 90),
    KOREAN("korean", "韩流", 100);

    public static final String ALL_CODE = "all";

    private static final Map<String, StyleCategoryEnum> CODE_MAP = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(StyleCategoryEnum::getCode, Function.identity()));

    private final String code;
    private final String label;
    private final int sortOrder;

    public static StyleCategoryEnum fromCode(String code) {
        return CODE_MAP.get(code);
    }

    public static List<StyleCategoryEnum> sortedValues() {
        return Arrays.stream(values())
                .sorted(Comparator.comparingInt(StyleCategoryEnum::getSortOrder))
                .toList();
    }
}

package com.aid.prompt.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 官方只读参数词库分类常量
 * 分类名称与排序统一在此处维护，不依赖数据库，不允许新增、修改、删除。
 * 仅这 11 类官方参数词库允许进入 C 端只读接口。
 * 注意：style 已剥离，不属于该模块。
 *
 * @author 视觉AID
 */
public final class OfficialPromptCategory {

    /** 构图方式 */
    public static final String COMPOSITION = "composition";
    /** 景别 */
    public static final String SHOT_SIZE = "shot_size";
    /** 拍摄角度 */
    public static final String CAMERA_ANGLE = "camera_angle";
    /** 焦距 */
    public static final String FOCAL_LENGTH = "focal_length";
    /** 色调 */
    public static final String COLOR_TONE = "color_tone";
    /** 光线 */
    public static final String LIGHTING = "lighting";
    /** 曝光与虚化 */
    public static final String EXPOSURE_BLUR = "exposure_blur";
    /** 镜头运动 */
    public static final String CAMERA_MOVEMENT = "camera_movement";
    /** 拍摄技法 */
    public static final String SHOOTING_TECHNIQUE = "shooting_technique";
    /** 质量基础词 */
    public static final String QUALITY_BASE = "quality_base";
    /** 负面基础词 */
    public static final String NEGATIVE_BASE = "negative_base";

    /** 分类定义（保持插入顺序即为展示排序） */
    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        register(COMPOSITION, "构图方式", 1);
        register(SHOT_SIZE, "景别", 2);
        register(CAMERA_ANGLE, "拍摄角度", 3);
        register(FOCAL_LENGTH, "焦距", 4);
        register(COLOR_TONE, "色调", 5);
        register(LIGHTING, "光线", 6);
        register(EXPOSURE_BLUR, "曝光与虚化", 7);
        register(CAMERA_MOVEMENT, "镜头运动", 8);
        register(SHOOTING_TECHNIQUE, "拍摄技法", 9);
        register(QUALITY_BASE, "质量基础词", 10);
        register(NEGATIVE_BASE, "负面基础词", 11);
    }

    private OfficialPromptCategory() {
    }

    private static void register(String code, String name, int sortOrder) {
        DEFINITIONS.put(code, new Definition(code, name, sortOrder));
    }

    /** 分类代码是否在白名单内 */
    public static boolean isAllowed(String code) {
        return code != null && DEFINITIONS.containsKey(code);
    }

    /** 获取所有合法分类代码 */
    public static List<String> codes() {
        return Collections.unmodifiableList(Arrays.asList(DEFINITIONS.keySet().toArray(new String[0])));
    }

    /** 获取分类中文名 */
    public static String nameOf(String code) {
        Definition def = DEFINITIONS.get(code);
        return def == null ? null : def.name;
    }

    /** 获取分类展示排序 */
    public static Integer sortOrderOf(String code) {
        Definition def = DEFINITIONS.get(code);
        return def == null ? null : def.sortOrder;
    }

    /** 全部分类定义（按展示顺序） */
    public static List<Definition> all() {
        return Collections.unmodifiableList(Arrays.asList(DEFINITIONS.values().toArray(new Definition[0])));
    }

    /** 分类定义 */
    public static final class Definition {
        private final String code;
        private final String name;
        private final int sortOrder;

        public Definition(String code, String name, int sortOrder) {
            this.code = code;
            this.name = name;
            this.sortOrder = sortOrder;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public int getSortOrder() {
            return sortOrder;
        }
    }
}

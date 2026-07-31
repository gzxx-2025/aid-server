package com.aid.enums;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 资产中心统一分类编码枚举。
 * 贯穿分类树、个人资产列表、资产明细三个接口，每个分类映射到对应业务表，按 userId 隔离查询。
 *
 * @author 视觉AID
 */
public enum AssetCenterCategoryEnum {

    /** 剧本 -> aid_comic_script */
    SCRIPT("script", "剧本"),
    /** 角色 -> aid_role_prop_scene(assetType=character) */
    ROLE("role", "角色"),
    /** 场景 -> aid_role_prop_scene(assetType=scene) */
    SCENE("scene", "场景"),
    /** 道具 -> aid_role_prop_scene(assetType=prop) */
    PROP("prop", "道具"),
    /** 角色设定 -> aid_role_prop_scene_form(主资产=character) */
    ROLE_SETTING("role_setting", "角色设定"),
    /** 场景设定 -> aid_role_prop_scene_form(主资产=scene) */
    SCENE_SETTING("scene_setting", "场景设定"),
    /** 道具设定 -> aid_role_prop_scene_form(主资产=prop) */
    PROP_SETTING("prop_setting", "道具设定"),
    /** 角色图 -> aid_role_prop_scene_form_image(主资产=character) */
    ROLE_IMAGE("role_image", "角色图"),
    /** 场景图 -> aid_role_prop_scene_form_image(主资产=scene) */
    SCENE_IMAGE("scene_image", "场景图"),
    /** 道具图 -> aid_role_prop_scene_form_image(主资产=prop) */
    PROP_IMAGE("prop_image", "道具图"),
    /** 分镜脚本 -> aid_storyboard */
    STORYBOARD_SCRIPT("storyboard_script", "分镜脚本"),
    /** 分镜脚本图 -> aid_gen_record(genType in image,grid) */
    STORYBOARD_IMAGE("storyboard_image", "分镜脚本图"),
    /** 分镜视频 -> aid_gen_record(genType in i2v,multi,edge) */
    STORYBOARD_VIDEO("storyboard_video", "分镜视频"),
    /** 配音 -> aid_audio_record */
    DUBBING("dubbing", "配音"),
    /** 预览视频 -> aid_episode_editor */
    PREVIEW_VIDEO("preview_video", "预览视频");

    /** 分类编码（对外契约，三接口共用） */
    private final String code;

    /** 分类中文名称 */
    private final String name;

    AssetCenterCategoryEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * 按编码查找分类，找不到返回 null。
     *
     * @param code 分类编码
     * @return 枚举或 null
     */
    public static AssetCenterCategoryEnum getByCode(String code) {
        if (Objects.isNull(code)) {
            return null;
        }
        for (AssetCenterCategoryEnum item : values()) {
            if (Objects.equals(item.code, code)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 判断编码是否合法。
     *
     * @param code 分类编码
     * @return true 合法
     */
    public static boolean isValid(String code) {
        return Objects.nonNull(getByCode(code));
    }

    /**
     * 按固定顺序返回所有分类编码。
     *
     * @return 编码列表
     */
    public static List<String> codes() {
        List<String> list = new ArrayList<>();
        for (AssetCenterCategoryEnum item : values()) {
            list.add(item.code);
        }
        return list;
    }
}

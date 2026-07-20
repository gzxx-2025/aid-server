package com.aid.asset.constants;

import java.util.Set;

/**
 * C端用户侧可用的资产类型白名单常量
 * 供 /api/user/asset/custom/* 与 /api/user/asset/official/query 等 C端接口共用
 *
 * @author 视觉AID
 */
public final class UserAssetTypeConstants {

    private UserAssetTypeConstants() {
    }

    /** C端允许的资产类型白名单（与 aid_user_comic_asset / aid_comic_asset 可查询类型对齐） */
    public static final Set<String> ALLOWED_ASSET_TYPES = Set.of(
            "reference_character",
            "reference_scene",
            "reference_prop",
            "style",
            "pose",
            "expression",
            "effect",
            "file",
            "mood",
            "camera"
    );
}

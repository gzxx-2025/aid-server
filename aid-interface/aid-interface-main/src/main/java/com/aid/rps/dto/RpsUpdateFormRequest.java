package com.aid.rps.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 仅更新从表形态请求DTO（角色/场景/道具），支持传 promptText 或平铺字段（前者优先）。
 *
 * @author 视觉AID
 */
@Data
public class RpsUpdateFormRequest {

    /** 从表形态ID */
    @NotNull(message = "形态ID不能为空")
    private Long id;

    /** 形态名称 */
    @Size(max = 100, message = "形态名称不能超过100个字符")
    private String name;

    /** 形象变更原因 */
    @Size(max = 200, message = "形象变更原因不能超过200个字符")
    private String changeReason;

    /** 提示词（可直接传 JSON 字符串；平铺字段模式下由后端根据平铺字段自动组装） */
    private String promptText;
    /** 资产类型：character / scene / prop（平铺模式必传），兼容 asset_type */
    @JsonAlias("asset_type")
    private String assetType;

    /** 提示词协议版本，当前统一 v2，兼容 prompt_version */
    @JsonAlias("prompt_version")
    private String promptVersion;
    /** 子形象编号（对应 expectedAppearances 下标），兼容 appearance_id */
    @JsonAlias("appearance_id")
    private Integer appearanceId;

    /** 角色外观完整视觉描述（单字符串） */
    private String descriptions;
    /** 场景/道具标题（场景用作 sceneId；道具用作形态名） */
    private String title;

    /** 出图主提示词（场景：完整中文单段；道具：增强后的中文提示词），直接喂给图模型；同时回写到 form.introduction 列 */
    private String prompt;

    /** 提示词类型：场景固定 aid_scene_four_view；道具为 text_to_image / reference_image，兼容 prompt_type */
    @JsonAlias("prompt_type")
    private String promptType;
    /** 画布比例，兼容 aspect_ratio */
    @JsonAlias("aspect_ratio")
    private String aspectRatio;

    /** 四视图画面描述（north / south / west / east） */
    private SceneViewpoints viewpoints;

    /** 图片用途说明，兼容 image_usage */
    @JsonAlias("image_usage")
    private String imageUsage;

    /** 引用历史 sceneId（首次生成为 null） */
    private String reference;

    /** 四视图画面描述子结构 */
    @Data
    public static class SceneViewpoints {
        /** 主视/起势（左上） */
        private String north;
        /** 反打/回望（右上） */
        private String south;
        /** 左立面（左下） */
        private String west;
        /** 右立面（右下） */
        private String east;
    }
}

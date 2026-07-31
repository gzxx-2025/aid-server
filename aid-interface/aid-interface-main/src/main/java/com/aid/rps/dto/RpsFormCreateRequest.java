package com.aid.rps.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.aid.common.aid.oss.annotation.MediaUrl;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建从表形态请求DTO
 *
 * @author 视觉AID
 */
@Data
public class RpsFormCreateRequest {

    /** 项目ID */
    @NotNull(message = "项目ID不能为空")
    private Long projectId;

    /** 剧集ID（电影模式传0） */
    @NotNull(message = "剧集ID不能为空")
    private Long episodeId;

    /** 关联的主表ID */
    @NotNull(message = "主表资产ID不能为空")
    private Long assetId;

    /** 图片URL（可选；兼容老前端保留字段，新逻辑不再写入 form.image_url） */
    @MediaUrl
    private String imageUrl;

    /** 形态名称（必填；前端语义=形态名而非资产名） */
    @Size(max = 100, message = "形态名称不能超过100个字符")
    private String name;

    /** 形象变更原因（如：初始形象、战斗装束、出浴状态） */
    @Size(max = 200, message = "形象变更原因不能超过200个字符")
    private String changeReason;

    /** 来源类型：本接口仅创建 form 形态定义，未填默认按 upload 处理 */
    private String sourceType;
    /** 角色提示词（可直接传 JSON 字符串；平铺字段模式下由后端根据平铺字段自动组装） */
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
    /** 场景概要 / 用途说明 */
    private String summary;

    /** 场景 / 道具详细视觉描述 */
    private String introduction;

    /** 场景是否有人群: 0=无 1=有，兼容 has_crowd */
    @JsonAlias("has_crowd")
    private Integer hasCrowd;

    /** 人群类型描述，兼容 crowd_description */
    @JsonAlias("crowd_description")
    private String crowdDescription;

    /** 角色可落位区域数组，兼容 available_slots */
    @JsonAlias("available_slots")
    private List<String> availableSlots;
    // summary / introduction 与 scene 共用，无需重复声明
}

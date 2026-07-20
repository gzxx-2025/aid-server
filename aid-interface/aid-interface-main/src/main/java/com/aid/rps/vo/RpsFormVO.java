package com.aid.rps.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 资产从表形态VO。
 *
 * @author 视觉AID
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RpsFormVO {

    /** 主键 */
    private Long id;

    /** 所属主资产类型: character / scene / prop */
    private String assetType;

    /** 形态名称 */
    private String name;

    /** 形象变更原因 */
    private String changeReason;

    /** 创建来源: manual-手动创建, auto-自动提取 */
    private String createSource;

    /**
     * 主图URL（出参拼域名）。
     * 含义为"当前使用中的图片 URL"：优先取同 form 下 is_use=1 的 form_image，
     * 如无在用图则回退到同 form 最新一张 form_image；不存在任何 form_image 时为 null。
     * 多张同时使用中时本字段仅回首张 is_use=1 的 URL，完整列表见 images。
     */
    @MediaUrl
    private String imageUrl;

    /** 视觉描述状态: pending-待生成, completed-已完成 */
    private String visualDescStatus;

    /**
     * 是否允许为当前 form 执行"自动生图"。
     * 规则：promptText 能解析出有效提示词（纯文本非空 或 JSON中 descriptions 非空）则 true。
     */
    private Boolean canAutoGenerateImage;

    /** 提示词候选数量 */
    private Integer promptVariantCount;

    /** 同 form 下有效图片总数（del_flag='0'） */
    private Integer imageCount;

    /**
     * 当前使用中的图片ID。
     * 支持多张同时 is_use=1，本字段只回首张命中，完整使用中集合请用 images 列表中 isUse=1 的项过滤。
     */
    private Long currentImageId;

    /** 同 form 下的图片实例列表（无 form_image 时返回空列表） */
    private List<RpsFormImageVO> images;
    /** 角色外观完整视觉描述，仅 character 返回 */
    private String descriptions;

    /** 子形象编号（对应 expectedAppearances 数组下标，0=默认），仅 character 返回 */
    private Integer appearanceId;
    /** 概要说明（场景用途 / 道具简介），scene / prop 返回 */
    private String summary;

    /** 详细视觉描述（场景环境 / 道具外观），scene / prop 返回 */
    private String introduction;
    /** 是否有人群: 0=无 1=有，仅 scene 返回 */
    private Integer hasCrowd;

    /** 人群类型描述，仅 scene 返回 */
    private String crowdDescription;

    /** 角色可落位位置列表，仅 scene 返回。空数组 [] 表示明确无槽位，字段缺失表示未配置 */
    private List<String> availableSlots;
}

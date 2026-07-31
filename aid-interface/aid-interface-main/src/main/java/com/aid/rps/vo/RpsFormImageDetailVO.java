package com.aid.rps.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 形态图片实例详情 VO（用于 form-image/list / form-image/update 出参）。
 *
 * 相比 {@link RpsFormImageVO}，本 VO 额外冗余归属字段（formId / assetId / assetType / assetName / formName），
 * 便于前端"图片视角"列表查询时无需再回查 form / 主资产即可定位归属。
 *
 * @author 视觉AID
 */
@Data
@Builder
public class RpsFormImageDetailVO
{
    /** 图片实例ID */
    private Long id;
    /** 关联形态ID */
    private Long formId;

    /** 关联形态名称 */
    private String formName;

    /** 关联主资产ID */
    private Long assetId;

    /** 关联主资产名称 */
    private String assetName;

    /** 主资产类型：character / scene / prop */
    private String assetType;

    /** 项目ID（与主资产同步） */
    private Long projectId;

    /** 剧集ID（与主资产同步；电影模式为 0） */
    private Long episodeId;
    /** 图片名称 */
    private String name;

    /** 图片URL（出参拼域名） */
    @MediaUrl
    private String imageUrl;

    /** 来源类型：ai_auto / ai_builder / ai_manual / upload / official / migrate */
    private String sourceType;

    /** 提示词下标（0-based） */
    private Integer descriptionIndex;

    /** 提示词快照 */
    private String promptSnapshot;

    /** 是否使用中（0/1） */
    private Integer isUse;

    /** 图片状态：pending / processing / completed / failed */
    private String imageStatus;

    /** 失败原因（imageStatus=failed 时） */
    private String failReason;

    /**
     * 参考图列表（已反序列化）。
     * 注：{@code @MediaUrl} 注解 Target 仅 FIELD/METHOD，不支持 TYPE_USE，本字段元素不会被自动拼域名；
     * 若前端需要完整 URL，存入时确保已是完整 URL 即可。
     */
    private List<String> referenceImages;

    /** 排序号 */
    private Integer sortOrder;

    /**
     * 是否可拆分四宫格。
     * 判定口径：{@code is_split_source=0} 且 {@code is_split_child=0} 且所属主资产 {@code assetType='scene'} 才为 true。
     * 即：仅场景类型、用户原图（既未被拆过、也不是拆分产物）可拆；已拆源图、拆分子图、非场景图一律 false。
     * 不依据 {@code source_type} 判定（拆分子图入库 source_type 同为 upload，无法据此区分）。
     */
    private Boolean canSplit;
}

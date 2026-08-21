package com.aid.asset.vo;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Builder;
import lombok.Data;
import com.aid.aid.vo.StyleCategoryVO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 合并资产列表项 VO（个人 + 官方）。
 * 通过 {@code sourceFlag} 区分来源，便于前端判断该条是否可编辑 / 删除：
 * custom = 个人资产（可改可删，操作 aid_user_comic_asset）；
 * official = 官方资产（只读，来自 aid_comic_asset）。
 *
 * @author 视觉AID
 */
@Data
@Builder
@Schema(description = "个人与官方素材合并列表项")
public class MergedAssetVO {

    /** 主键 ID（按 sourceFlag 区分属于哪张表） */
    private Long id;

    /** 来源标识：custom 个人(可编辑/删除) / official 官方(只读) */
    private String sourceFlag;

    /** 资产类型 */
    private String assetType;

    /** 资产名称 */
    private String assetName;

    /** 提示词内容 */
    private String promptText;

    /** 主图 URL（出参拼域名） */
    @MediaUrl
    private String imageUrl;

    /** 风格分类标签；个人素材固定为空数组 */
    private List<StyleCategoryVO> categories;

    /** 是否为推荐风格；个人素材固定为false */
    private Boolean isRecommended;

    /** 展示排序号 */
    private Long sortOrder;
}

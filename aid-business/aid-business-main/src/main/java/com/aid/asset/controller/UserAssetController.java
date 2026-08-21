package com.aid.asset.controller;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.aid.domain.AidComicAsset;
import com.aid.asset.dto.OfficialAssetQueryRequest;
import com.aid.asset.service.IUserAssetBusinessService;
import com.aid.asset.vo.OfficialAssetVO;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户资产Controller
 * 提供给C端用户使用的官方素材查询接口
 * aid_comic_asset 仅存储可复用素材，不存储角色/场景/道具主资产
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/asset")
@Tag(name = "C端官方素材")
public class UserAssetController extends BaseController
{
    @Resource
    private IUserAssetBusinessService userAssetBusinessService;

    /**
     * 查询官方素材列表
     * 查询 aid_comic_asset 表中的素材库数据，支持按类型和名称筛选
     * 该表只存储风格、姿势、表情、效果、文件等可复用素材，不存储角色/场景/道具主资产
     */
    @PostMapping("/official/query")
    @Operation(summary = "查询官方素材", description = "支持类型、名称与风格分类筛选；推荐风格优先并稳定排序")
    public AjaxResult officialQuery(@RequestBody(required = false) OfficialAssetQueryRequest request)
    {
        List<AidComicAsset> list = userAssetBusinessService.queryOfficialAssetList(request);
        return success(list.stream().map(this::convertToOfficialVO).toList());
    }

    /**
     * 将实体转换为官方资产VO
     */
    private OfficialAssetVO convertToOfficialVO(AidComicAsset asset)
    {
        return OfficialAssetVO.builder()
                .id(asset.getId())
                .assetType(asset.getAssetType())
                .assetName(asset.getAssetName())
                .promptText(asset.getPromptText())
                .imageUrl(asset.getImageUrl())
                .categories(asset.getCategories())
                .isRecommended(Boolean.TRUE.equals(asset.getIsRecommended()))
                .sortOrder(asset.getSortOrder())
                .build();
    }

    /**
     * 查询风格分类选项。
     */
    @PostMapping("/style/category/list")
    @Operation(summary = "查询风格分类", description = "返回稳定code与中文label，首项all为虚拟全部分类")
    public AjaxResult styleCategoryList()
    {
        return success(userAssetBusinessService.listStyleCategoryOptions());
    }
}

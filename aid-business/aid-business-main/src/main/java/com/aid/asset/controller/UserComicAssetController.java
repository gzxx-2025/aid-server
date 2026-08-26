package com.aid.asset.controller;

import com.aid.asset.dto.MergedAssetPageRequest;
import com.aid.asset.dto.UserComicAssetCreateRequest;
import com.aid.asset.dto.UserComicAssetDeleteRequest;
import com.aid.asset.dto.UserComicAssetDetailRequest;
import com.aid.asset.dto.UserComicAssetListRequest;
import com.aid.asset.dto.UserComicAssetUpdateRequest;
import com.aid.asset.service.IUserComicAssetService;
import com.aid.common.annotation.Anonymous;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.satoken.utils.LoginHelper;
import com.aid.common.utils.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * C端用户自定义参考资产Controller
 * 仅处理 aid_user_comic_asset 表中的用户参考素材（如参考人物图、参考场景图、风格、姿势、表情等）
 * 不涉及正式角色/场景/道具（aid_role_prop_scene）
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/api/user/asset/custom")
@Tag(name = "C端个人参考素材")
public class UserComicAssetController extends BaseController {

    @Resource
    private IUserComicAssetService userComicAssetService;

    /**
     * 创建用户参考资产
     */
    @PostMapping("/create")
    public AjaxResult create(@Valid @RequestBody UserComicAssetCreateRequest request) {
        Long userId = SecurityUtils.getUserId();
        Long id = userComicAssetService.createAsset(request, userId);
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        return AjaxResult.success("创建成功", data);
    }

    /**
     * 分页查询用户参考资产列表
     */
    @PostMapping("/list")
    public AjaxResult list(@RequestBody(required = false) UserComicAssetListRequest request) {
        Long userId = SecurityUtils.getUserId();
        Map<String, Object> data = userComicAssetService.listAsset(request, userId);
        return AjaxResult.success("查询成功", data);
    }

    /**
     * 合并分页查询「个人 + 官方」资产（官方推荐、个人、官方非推荐）。
     * 每条带 sourceFlag：custom 个人(可编辑/删除) / official 官方(只读)，便于前端判断操作权限。
     */
    @PostMapping("/page")
    @Anonymous
    @Operation(summary = "合并分页查询个人与官方素材",
            description = "未登录时仅返回官方素材；登录后按官方推荐、个人素材、官方非推荐顺序返回；支持风格分类筛选")
    public AjaxResult page(@RequestBody(required = false) MergedAssetPageRequest request) {
        Long userId = LoginHelper.getUserId();
        Map<String, Object> data = userComicAssetService.pageMergedAssets(request, userId);
        return AjaxResult.success("查询成功", data);
    }

    /**
     * 查询用户参考资产详情
     */
    @PostMapping("/detail")
    public AjaxResult detail(@RequestBody(required = false) UserComicAssetDetailRequest request) {
        Long userId = SecurityUtils.getUserId();
        return AjaxResult.success("查询成功", userComicAssetService.detailAsset(request, userId));
    }

    /**
     * 修改用户参考资产
     */
    @PostMapping("/update")
    public AjaxResult update(@RequestBody(required = false) UserComicAssetUpdateRequest request) {
        Long userId = SecurityUtils.getUserId();
        userComicAssetService.updateAsset(request, userId);
        return AjaxResult.success("修改成功", true);
    }

    /**
     * 删除用户参考资产（删除记录并清理其 OSS 图片文件）
     */
    @PostMapping("/delete")
    public AjaxResult delete(@RequestBody(required = false) UserComicAssetDeleteRequest request) {
        Long userId = SecurityUtils.getUserId();
        userComicAssetService.deleteAsset(request, userId);
        return AjaxResult.success("删除成功", true);
    }

    /**
     * 查询C端允许的参考资产类型字典
     */
    @PostMapping("/type/list")
    public AjaxResult typeList() {
        return AjaxResult.success("查询成功", userComicAssetService.listAllowedTypes());
    }
}

package com.aid.asset.service;

import com.aid.asset.dto.MergedAssetPageRequest;
import com.aid.asset.dto.UserComicAssetCreateRequest;
import com.aid.asset.dto.UserComicAssetDeleteRequest;
import com.aid.asset.dto.UserComicAssetDetailRequest;
import com.aid.asset.dto.UserComicAssetListRequest;
import com.aid.asset.dto.UserComicAssetUpdateRequest;
import com.aid.asset.vo.UserComicAssetTypeVO;
import com.aid.asset.vo.UserComicAssetVO;

import java.util.List;
import java.util.Map;

/**
 * C端用户自定义参考资产业务Service
 *
 * @author 视觉AID
 */
public interface IUserComicAssetService {

    /**
     * 创建用户参考资产
     *
     * @param request 创建请求
     * @param userId  当前用户ID
     * @return 新增的资产ID
     */
    Long createAsset(UserComicAssetCreateRequest request, Long userId);

    /**
     * 分页查询用户参考资产列表
     *
     * @param request 查询条件
     * @param userId  当前用户ID
     * @return 分页结果：total + list
     */
    Map<String, Object> listAsset(UserComicAssetListRequest request, Long userId);

    /**
     * 合并分页查询「个人 + 官方」资产（个人在前、官方在后）。
     *
     * @param request 查询条件
     * @param userId  当前用户ID
     * @return 分页结果：total / pageNum / pageSize / list
     */
    Map<String, Object> pageMergedAssets(MergedAssetPageRequest request, Long userId);

    /**
     * 查询用户参考资产详情
     *
     * @param request 查询请求
     * @param userId  当前用户ID
     * @return 资产详情VO
     */
    UserComicAssetVO detailAsset(UserComicAssetDetailRequest request, Long userId);

    /**
     * 修改用户参考资产
     *
     * @param request 修改请求
     * @param userId  当前用户ID
     */
    void updateAsset(UserComicAssetUpdateRequest request, Long userId);

    /**
     * 删除用户参考资产（删除记录并清理其 OSS 图片文件，仅限本人资产）
     *
     * @param request 删除请求
     * @param userId  当前用户ID
     */
    void deleteAsset(UserComicAssetDeleteRequest request, Long userId);

    /**
     * 查询C端允许的参考资产类型字典
     *
     * @return 类型字典列表
     */
    List<UserComicAssetTypeVO> listAllowedTypes();
}

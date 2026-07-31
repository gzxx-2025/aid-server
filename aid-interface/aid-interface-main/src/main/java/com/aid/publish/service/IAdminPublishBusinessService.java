package com.aid.publish.service;

import java.util.List;

import com.aid.aid.domain.vo.AidPublishItemVo;
import com.aid.aid.domain.vo.AidPublishUserVo;
import com.aid.aid.domain.vo.AidPublishWhitelistVo;
import com.aid.publish.dto.AdminPublishActionRequest;
import com.aid.publish.dto.AdminPublishListRequest;
import com.aid.publish.dto.AdminPublishPermissionRequest;
import com.aid.publish.dto.AdminPublishUserSearchRequest;
import com.aid.publish.dto.AdminWhitelistAddRequest;
import com.aid.publish.dto.AdminWhitelistQueryRequest;
import com.aid.publish.dto.AdminWhitelistRemoveRequest;

/**
 * 后台发布管理业务Service接口
 *
 * @author 视觉AID
 */
public interface IAdminPublishBusinessService
{
    /**
     * 查询发布管理列表（审核通过未发布 + 已发布）
     *
     * @param request 查询条件
     * @return 发布管理列表
     */
    List<AidPublishItemVo> selectPublishList(AdminPublishListRequest request);

    /**
     * 上架作品（审核通过未发布 → 已发布）
     *
     * @param request  操作请求（原因可选）
     * @param operator 操作人
     */
    void forceOnline(AdminPublishActionRequest request, String operator);

    /**
     * 下架作品（已发布 → 未发布，审核状态保留）
     *
     * @param request  操作请求（原因必填）
     * @param operator 操作人
     */
    void forceOffline(AdminPublishActionRequest request, String operator);

    /**
     * 回撤审核（撤销审核通过并同步下架，状态转审核失败）
     *
     * @param request  操作请求（原因必填）
     * @param operator 操作人
     */
    void revokeAudit(AdminPublishActionRequest request, String operator);

    /**
     * 查询发布白名单列表（联表用户信息）
     *
     * @param request 查询条件
     * @return 白名单列表
     */
    List<AidPublishWhitelistVo> selectWhitelist(AdminWhitelistQueryRequest request);

    /**
     * 添加发布白名单
     *
     * @param request  添加请求
     * @param operator 操作人
     */
    void addWhitelist(AdminWhitelistAddRequest request, String operator);

    /**
     * 移除发布白名单
     *
     * @param request  移除请求
     * @param operator 操作人
     */
    void removeWhitelist(AdminWhitelistRemoveRequest request, String operator);

    /**
     * 按 昵称/邮箱/手机号 搜索用户（供白名单添加与权限设置）
     *
     * @param request 搜索请求
     * @return 用户列表
     */
    List<AidPublishUserVo> searchUsers(AdminPublishUserSearchRequest request);

    /**
     * 设置用户级发布权限
     *
     * @param request  权限设置请求
     * @param operator 操作人
     */
    void setUserPublishPermission(AdminPublishPermissionRequest request, String operator);
}

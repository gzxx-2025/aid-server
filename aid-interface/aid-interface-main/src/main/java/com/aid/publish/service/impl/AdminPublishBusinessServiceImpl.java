package com.aid.publish.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidPublishWhitelist;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.vo.AidPublishItemVo;
import com.aid.aid.domain.vo.AidPublishUserVo;
import com.aid.aid.domain.vo.AidPublishWhitelistVo;
import com.aid.aid.service.IAidComicAuditRecordService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidPublishWhitelistService;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.core.service.ISysUserService;
import com.aid.enums.AuditActionEnum;
import com.aid.enums.AuditTargetTypeEnum;
import com.aid.enums.ProjectStatusEnum;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.publish.dto.AdminPublishActionRequest;
import com.aid.publish.dto.AdminPublishListRequest;
import com.aid.publish.dto.AdminPublishPermissionRequest;
import com.aid.publish.dto.AdminPublishUserSearchRequest;
import com.aid.publish.dto.AdminWhitelistAddRequest;
import com.aid.publish.dto.AdminWhitelistQueryRequest;
import com.aid.publish.dto.AdminWhitelistRemoveRequest;
import com.aid.publish.service.IAdminPublishBusinessService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 后台发布管理业务Service实现
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AdminPublishBusinessServiceImpl implements IAdminPublishBusinessService
{
    /** 删除标志：正常（未删除） */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 是否公开：是 */
    private static final String IS_PUBLIC_YES = "1";

    /** 是否公开：否 */
    private static final String IS_PUBLIC_NO = "0";

    /** 用户级发布权限：允许 */
    private static final Integer USER_PUBLISH_ENABLED = 1;

    /** 用户级发布权限：禁止 */
    private static final Integer USER_PUBLISH_DISABLED = 0;

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IAidComicAuditRecordService aidComicAuditRecordService;

    @Autowired
    private IAidPublishWhitelistService aidPublishWhitelistService;

    @Autowired
    private IAidUserProfileService aidUserProfileService;

    @Autowired
    private ISysUserService sysUserService;

    /** 微信公众号推送：发布成功/审核回撤结果通知内容归属用户（内部吞异常，不影响主流程） */
    @Autowired
    private IWechatNotifyService wechatNotifyService;

    /**
     * 查询发布管理列表（审核通过未发布 + 已发布）
     *
     * @param request 查询条件
     * @return 发布管理列表
     */
    @Override
    public List<AidPublishItemVo> selectPublishList(AdminPublishListRequest request)
    {
        return aidComicProjectService.selectPublishItemVoList(request.getPublishState(),
                request.getProjectName(), request.getProjectType(), request.getKeyword());
    }

    /**
     * 上架作品（审核通过未发布 → 已发布）
     *
     * @param request  操作请求（原因可选）
     * @param operator 操作人
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceOnline(AdminPublishActionRequest request, String operator)
    {
        AidComicProject project = loadProject(request.getId());
        // 上架前提：必须审核通过
        if (!Objects.equals(project.getStatus(), ProjectStatusEnum.AUDIT_PASSED.getValue()))
        {
            log.info("上架失败，项目未审核通过: projectId={}, status={}", request.getId(), project.getStatus());
            throw new ServiceException("请先通过审核");
        }
        // 已发布幂等拦截
        if (Objects.equals(IS_PUBLIC_YES, project.getIsPublic()))
        {
            log.info("上架失败，作品已是发布状态: projectId={}", request.getId());
            throw new ServiceException("作品已发布");
        }
        // 置为公开并记录发布时间与操作原因
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, request.getId());
        updateWrapper.set(AidComicProject::getIsPublic, IS_PUBLIC_YES);
        updateWrapper.set(AidComicProject::getPublishTime, DateUtils.getNowDate());
        updateWrapper.set(AidComicProject::getStatusReason, request.getReason());
        updateWrapper.set(AidComicProject::getUpdateBy, operator);
        updateWrapper.set(AidComicProject::getUpdateTime, DateUtils.getNowDate());
        aidComicProjectService.update(updateWrapper);
        // 写入审核流水（后台上架，状态不变）
        aidComicAuditRecordService.saveAuditRecord(AuditTargetTypeEnum.PROJECT.getValue(), request.getId(),
                project.getUserId(), AuditActionEnum.FORCE_ONLINE.getValue(),
                project.getStatus(), project.getStatus(), request.getReason(), operator);
        // 微信公众号推送：发布成功（推送服务内部吞异常，不影响主流程）
        wechatNotifyService.notifyContentAudit(AuditTargetTypeEnum.PROJECT.getValue(), request.getId(),
                IWechatNotifyService.AUDIT_EVENT_PUBLISHED, null);
    }

    /**
     * 下架作品（已发布 → 未发布，审核状态保留）
     *
     * @param request  操作请求（原因必填）
     * @param operator 操作人
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceOffline(AdminPublishActionRequest request, String operator)
    {
        if (StrUtil.isBlank(request.getReason()))
        {
            throw new ServiceException("请填写原因");
        }
        AidComicProject project = loadProject(request.getId());
        // 未发布幂等拦截
        if (!Objects.equals(IS_PUBLIC_YES, project.getIsPublic()))
        {
            log.info("下架失败，作品未发布: projectId={}", request.getId());
            throw new ServiceException("作品未发布");
        }
        // 关闭公开并写入下架原因（审核状态保留，发布时间不清空）
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, request.getId());
        updateWrapper.set(AidComicProject::getIsPublic, IS_PUBLIC_NO);
        updateWrapper.set(AidComicProject::getStatusReason, request.getReason());
        updateWrapper.set(AidComicProject::getUpdateBy, operator);
        updateWrapper.set(AidComicProject::getUpdateTime, DateUtils.getNowDate());
        aidComicProjectService.update(updateWrapper);
        // 写入审核流水（后台下架，状态不变）
        aidComicAuditRecordService.saveAuditRecord(AuditTargetTypeEnum.PROJECT.getValue(), request.getId(),
                project.getUserId(), AuditActionEnum.FORCE_OFFLINE.getValue(),
                project.getStatus(), project.getStatus(), request.getReason(), operator);
    }

    /**
     * 回撤审核（撤销审核通过并同步下架，状态转审核失败）
     *
     * @param request  操作请求（原因必填）
     * @param operator 操作人
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAudit(AdminPublishActionRequest request, String operator)
    {
        if (StrUtil.isBlank(request.getReason()))
        {
            throw new ServiceException("请填写原因");
        }
        AidComicProject project = loadProject(request.getId());
        // 回撤前提：当前必须是审核通过状态
        if (!Objects.equals(project.getStatus(), ProjectStatusEnum.AUDIT_PASSED.getValue()))
        {
            log.info("回撤失败，项目非审核通过状态: projectId={}, status={}", request.getId(), project.getStatus());
            throw new ServiceException("非审核通过状态");
        }
        Integer beforeStatus = project.getStatus();
        Integer afterStatus = ProjectStatusEnum.AUDIT_FAILED.getValue();
        // 状态转审核失败并同步下架，回撤原因写入状态原因（用户可见后修改重新提审）
        LambdaUpdateWrapper<AidComicProject> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidComicProject::getId, request.getId());
        updateWrapper.set(AidComicProject::getStatus, afterStatus);
        updateWrapper.set(AidComicProject::getIsPublic, IS_PUBLIC_NO);
        updateWrapper.set(AidComicProject::getStatusReason, request.getReason());
        updateWrapper.set(AidComicProject::getUpdateBy, operator);
        updateWrapper.set(AidComicProject::getUpdateTime, DateUtils.getNowDate());
        aidComicProjectService.update(updateWrapper);
        // 写入审核流水（审核回撤）
        aidComicAuditRecordService.saveAuditRecord(AuditTargetTypeEnum.PROJECT.getValue(), request.getId(),
                project.getUserId(), AuditActionEnum.REVOKE.getValue(),
                beforeStatus, afterStatus, request.getReason(), operator);
        // 微信公众号推送：审核回撤（推送服务内部吞异常，不影响主流程）
        wechatNotifyService.notifyContentAudit(AuditTargetTypeEnum.PROJECT.getValue(), request.getId(),
                IWechatNotifyService.AUDIT_EVENT_REVOKED, request.getReason());
    }

    /**
     * 查询发布白名单列表（联表用户信息）
     *
     * @param request 查询条件
     * @return 白名单列表
     */
    @Override
    public List<AidPublishWhitelistVo> selectWhitelist(AdminWhitelistQueryRequest request)
    {
        return aidPublishWhitelistService.selectWhitelistVoList(request.getKeyword());
    }

    /**
     * 添加发布白名单
     *
     * @param request  添加请求
     * @param operator 操作人
     */
    @Override
    public void addWhitelist(AdminWhitelistAddRequest request, String operator)
    {
        // 校验用户存在
        SysUser user = sysUserService.selectUserById(request.getUserId());
        if (Objects.isNull(user))
        {
            log.info("添加白名单失败，用户不存在: userId={}", request.getUserId());
            throw new ServiceException("用户不存在");
        }
        // 已在名单内幂等拦截
        if (aidPublishWhitelistService.existsByUserId(request.getUserId()))
        {
            log.info("添加白名单失败，用户已在名单内: userId={}", request.getUserId());
            throw new ServiceException("用户已在名单");
        }
        AidPublishWhitelist entity = new AidPublishWhitelist();
        entity.setUserId(request.getUserId());
        entity.setRemark(request.getRemark());
        entity.setCreateBy(operator);
        entity.setCreateTime(DateUtils.getNowDate());
        aidPublishWhitelistService.save(entity);
    }

    /**
     * 移除发布白名单
     *
     * @param request  移除请求
     * @param operator 操作人
     */
    @Override
    public void removeWhitelist(AdminWhitelistRemoveRequest request, String operator)
    {
        AidPublishWhitelist entity = aidPublishWhitelistService.getById(request.getId());
        if (Objects.isNull(entity))
        {
            log.info("移除白名单失败，记录不存在: id={}", request.getId());
            throw new ServiceException("记录不存在");
        }
        aidPublishWhitelistService.removeById(request.getId());
        log.info("发布白名单已移除: id={}, userId={}, operator={}", request.getId(), entity.getUserId(), operator);
    }

    /**
     * 按 昵称/邮箱/手机号 搜索用户（供白名单添加与权限设置）
     *
     * @param request 搜索请求
     * @return 用户列表（昵称按「昵称(ID)」展示）
     */
    @Override
    public List<AidPublishUserVo> searchUsers(AdminPublishUserSearchRequest request)
    {
        List<AidPublishUserVo> users = aidPublishWhitelistService.searchPublishUsers(request.getKeyword().trim());
        // 昵称统一格式化为「昵称(ID)」，便于同昵称用户区分
        for (AidPublishUserVo user : users)
        {
            String nickName = StrUtil.isBlank(user.getNickName()) ? "" : user.getNickName();
            user.setNickName(nickName + "(" + user.getUserId() + ")");
        }
        return users;
    }

    /**
     * 设置用户级发布权限
     *
     * @param request  权限设置请求
     * @param operator 操作人
     */
    @Override
    public void setUserPublishPermission(AdminPublishPermissionRequest request, String operator)
    {
        // 仅允许 0/1 两种取值
        if (!Objects.equals(USER_PUBLISH_ENABLED, request.getPublishEnabled())
                && !Objects.equals(USER_PUBLISH_DISABLED, request.getPublishEnabled()))
        {
            throw new ServiceException("权限取值非法");
        }
        AidUserProfile profile = aidUserProfileService.getByUserId(request.getUserId());
        if (Objects.isNull(profile))
        {
            log.info("设置发布权限失败，用户扩展信息不存在: userId={}", request.getUserId());
            throw new ServiceException("用户不存在");
        }
        // 查询字段精简说明：仅更新 publish_enabled 与审计字段
        LambdaUpdateWrapper<AidUserProfile> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(AidUserProfile::getId, profile.getId());
        updateWrapper.set(AidUserProfile::getPublishEnabled, request.getPublishEnabled());
        updateWrapper.set(AidUserProfile::getUpdateBy, operator);
        updateWrapper.set(AidUserProfile::getUpdateTime, DateUtils.getNowDate());
        aidUserProfileService.update(updateWrapper);
        log.info("用户发布权限已更新: userId={}, publishEnabled={}, operator={}",
                request.getUserId(), request.getPublishEnabled(), operator);
    }

    /**
     * 加载未删除项目，不存在时抛出业务异常
     *
     * @param projectId 项目ID
     * @return 项目实体
     */
    private AidComicProject loadProject(Long projectId)
    {
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL));
        if (Objects.isNull(project))
        {
            log.info("发布管理操作失败，项目不存在: projectId={}", projectId);
            throw new ServiceException("项目不存在");
        }
        return project;
    }
}

package com.aid.aid.controller;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.aid.common.annotation.Log;
import com.aid.common.constant.CacheConstants;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.enums.BusinessType;
import com.aid.aid.domain.AidUserProfile;
import com.aid.aid.domain.dto.BalanceAdjustDto;
import com.aid.aid.domain.vo.AidUserProfileVo;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.core.service.ISysUserService;
import com.aid.common.utils.poi.ExcelUtil;
import com.aid.common.core.page.TableDataInfo;

/**
 * 用户扩展信息Controller
 *
 * @author 视觉AID
 */
@Slf4j
@RestController
@RequestMapping("/aid/extenduserprofile")
public class AidUserProfileController extends BaseController
{
    @Autowired
    private IAidUserProfileService aidUserProfileService;

    /** 统一账户变更执行器：余额调整复用计费模块，自动写流水（不在本类直接改余额） */
    @Autowired
    private IAccountUpdateService accountUpdateService;

    /** 系统用户服务：复用状态变更 / 逻辑删除能力（sys_user） */
    @Autowired
    private ISysUserService userService;

    /** Redis 缓存：用于扫描并清理在线会话 token，实现"踢下线" */
    @Autowired
    private RedisCache redisCache;

    /**
     * 查询用户扩展信息列表（联表 sys_user，返回昵称/手机号/状态等聚合信息）
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:list')")
    @GetMapping("/list")
    public TableDataInfo list(AidUserProfileVo query)
    {
        startPage();
        List<AidUserProfileVo> list = aidUserProfileService.selectUserProfileVoList(query);
        return getDataTable(list);
    }

    /**
     * 管理员调整用户余额（增加/扣减）。
     * 统一走计费模块账户变更执行器：userId 级锁内执行，自动写入余额变动流水（changeType=admin_adjust）。
     * 扣减时校验余额充足，不足返回"余额不足"。请求体使用对象接参，禁止使用 Map。
     *
     * @param dto 余额调整请求（userId/amount/adjustType/reason）
     * @return 调整后结果提示
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:edit')")
    @Log(title = "用户余额调整", businessType = BusinessType.UPDATE)
    @PostMapping("/adjustBalance")
    public AjaxResult adjustBalance(@RequestBody BalanceAdjustDto dto)
    {
        // 入参校验：用户、金额、方向必填
        if (Objects.isNull(dto) || Objects.isNull(dto.getUserId()))
        {
            log.info("余额调整缺少用户ID");
            return AjaxResult.error("参数缺失");
        }
        if (Objects.isNull(dto.getAmount()) || dto.getAmount().signum() <= 0)
        {
            log.info("余额调整金额非法, userId={}, amount={}", dto.getUserId(), dto.getAmount());
            return AjaxResult.error("金额有误");
        }
        if (StrUtil.isBlank(dto.getAdjustType()))
        {
            log.info("余额调整方向缺失, userId={}", dto.getUserId());
            return AjaxResult.error("方向不能为空");
        }
        // 方向决定正负：add 增加 / deduct 扣减
        boolean isAdd = Objects.equals("add", dto.getAdjustType());
        boolean isDeduct = Objects.equals("deduct", dto.getAdjustType());
        if (!isAdd && !isDeduct)
        {
            log.info("余额调整方向非法, userId={}, adjustType={}", dto.getUserId(), dto.getAdjustType());
            return AjaxResult.error("方向不支持");
        }
        BigDecimal delta = isAdd ? dto.getAmount() : dto.getAmount().negate();
        // 幂等追踪号：管理员操作流水号
        String traceId = "admin_adjust_" + UUID.randomUUID().toString().replace("-", "");
        // 业务描述：管理员 + 原因，写入流水便于审计
        String bizName = "管理员调整" + (StrUtil.isBlank(dto.getReason()) ? "" : "：" + dto.getReason());
        accountUpdateService.adminAdjust(dto.getUserId(), delta, traceId, bizName);
        return AjaxResult.success("调整成功");
    }

    /**
     * 封禁 / 解封用户（变更 sys_user 状态）。
     * status="1"（停用=封禁）时，登录侧已校验状态会阻止其再次登录（见 C 端登录策略），
     * 这里额外清理其在线会话 token，实现"立即踢下线"；status="0" 仅恢复状态。
     *
     * @param body 请求体（userId 必填，status 必填：0正常 1停用）
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:edit')")
    @Log(title = "用户封禁状态", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody SysUser body)
    {
        if (Objects.isNull(body) || Objects.isNull(body.getUserId()))
        {
            return AjaxResult.error("参数缺失");
        }
        if (!Objects.equals("0", body.getStatus()) && !Objects.equals("1", body.getStatus()))
        {
            return AjaxResult.error("状态不支持");
        }
        SysUser update = new SysUser();
        update.setUserId(body.getUserId());
        update.setStatus(body.getStatus());
        int rows = userService.updateUserStatus(update);
        // 封禁时立即踢下线
        if (rows > 0 && Objects.equals("1", body.getStatus()))
        {
            int kicked = kickOfflineByUserId(body.getUserId());
            log.info("用户封禁并踢下线, userId={}, kickedSessions={}", body.getUserId(), kicked);
        }
        return toAjax(rows);
    }

    /**
     * 删除用户（管理员）。
     * 采用逻辑删除（sys_user.del_flag=2 + aid_user_profile.del_flag=2），并立即踢下线。
     * 逻辑删除后登录侧校验 delFlag 会阻止该账号再次登录，余额/订单等历史数据保留以备审计。
     *
     * @param userId 目标用户ID
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:remove')")
    @Log(title = "删除用户", businessType = BusinessType.DELETE)
    @DeleteMapping("/user/{userId}")
    public AjaxResult deleteUser(@PathVariable("userId") Long userId)
    {
        if (Objects.isNull(userId))
        {
            return AjaxResult.error("参数缺失");
        }
        // 先踢下线，再逻辑删除，避免删除后会话仍可短暂访问
        int kicked = kickOfflineByUserId(userId);
        // 逻辑删除扩展信息（del_flag=2）
        AidUserProfile profile = aidUserProfileService.getByUserId(userId);
        if (Objects.nonNull(profile))
        {
            AidUserProfile upd = new AidUserProfile();
            upd.setId(profile.getId());
            upd.setDelFlag("2");
            aidUserProfileService.updateAidUserProfile(upd);
        }
        // 逻辑删除用户（del_flag=2）
        int rows = userService.deleteUserById(userId);
        log.info("管理员删除用户, userId={}, kickedSessions={}", userId, kicked);
        return toAjax(rows);
    }

    /**
     * 按用户ID清理其全部在线会话 token（踢下线）。
     * 与管理端在线用户监控一致，扫描 {@code login_tokens:*}，反序列化为 LoginUser 后按 userId 匹配并删除。
     *
     * @param userId 目标用户ID
     * @return 清理的会话数量
     */
    private int kickOfflineByUserId(Long userId)
    {
        if (Objects.isNull(userId))
        {
            return 0;
        }
        int count = 0;
        try
        {
            Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            if (Objects.isNull(keys) || keys.isEmpty())
            {
                return 0;
            }
            for (String key : keys)
            {
                LoginUser loginUser = redisCache.getCacheObject(key);
                if (Objects.nonNull(loginUser)
                        && Objects.nonNull(loginUser.getUserId())
                        && Objects.equals(userId, loginUser.getUserId()))
                {
                    redisCache.deleteObject(key);
                    count++;
                }
            }
        }
        catch (Exception e)
        {
            log.warn("踢下线清理 token 异常, userId={}, err={}", userId, e.getMessage());
        }
        return count;
    }

    /**
     * 导出用户扩展信息列表
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:export')")
    @Log(title = "用户扩展信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AidUserProfile aidUserProfile)
    {
        List<AidUserProfile> list = aidUserProfileService.selectAidUserProfileList(aidUserProfile);
        ExcelUtil<AidUserProfile> util = new ExcelUtil<AidUserProfile>(AidUserProfile.class);
        util.exportExcel(response, list, "用户扩展信息数据");
    }

    /**
     * 获取用户扩展信息详细信息
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aidUserProfileService.selectAidUserProfileById(id));
    }

    /**
     * 新增用户扩展信息
     * 用户扩展表涉及余额 / 实名，禁止 UI 层手动新增。
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:add')")
    @Log(title = "用户扩展信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AidUserProfile aidUserProfile)
    {
        return AjaxResult.error(403, "用户扩展信息禁止手动新增");
    }

    /**
     * 修改用户扩展信息
     * 敏感字段（余额/冻结/实名/会员等级/累计充值消耗）禁止 UI 篡改，
     * 仅允许修改非敏感字段（如备注、性别等）。
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:edit')")
    @Log(title = "用户扩展信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AidUserProfile aidUserProfile)
    {
        // 敏感字段置 null，防止被一并 update 写入（MP 默认策略不更新 null 字段）
        aidUserProfile.setBalance(null);
        aidUserProfile.setFrozenBalance(null);
        aidUserProfile.setIsReal(null);
        aidUserProfile.setRealName(null);
        aidUserProfile.setIdCard(null);
        aidUserProfile.setMemberLevel(null);
        aidUserProfile.setMemberExpireTime(null);
        aidUserProfile.setTotalRecharge(null);
        aidUserProfile.setTotalConsumption(null);
        aidUserProfile.setUserId(null);
        return toAjax(aidUserProfileService.updateAidUserProfile(aidUserProfile));
    }

    /**
     * 删除用户扩展信息
     * 禁止物理删除用户扩展信息。
     */
    @PreAuthorize("@ss.hasPermi('aid:extenduserprofile:remove')")
    @Log(title = "用户扩展信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return AjaxResult.error(403, "用户扩展信息禁止手动删除");
    }
}

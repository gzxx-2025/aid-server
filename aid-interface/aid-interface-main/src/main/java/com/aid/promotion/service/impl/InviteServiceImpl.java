package com.aid.promotion.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.aid.aid.domain.AidInviteCode;
import com.aid.aid.domain.AidInviteRelation;
import com.aid.aid.domain.dto.InviteRebateRowDTO;
import com.aid.aid.domain.dto.InvitedUserRowDTO;
import com.aid.aid.service.IAidInviteCodeService;
import com.aid.aid.service.IAidInviteRebateRecordService;
import com.aid.aid.service.IAidInviteRelationService;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ip.IpUtils;
import com.aid.core.service.ISysUserService;
import com.aid.promotion.constant.PromotionConstants;
import com.aid.promotion.domain.InviteConfig;
import com.aid.promotion.dto.InvitePageRequest;
import com.aid.promotion.service.IInviteService;
import com.aid.promotion.service.IPromotionConfigService;
import com.aid.promotion.vo.InviteCodeCheckVO;
import com.aid.promotion.vo.InviteInfoVO;
import com.aid.promotion.vo.InviteRebateItemVO;
import com.aid.promotion.vo.InvitedUserVO;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 邀请Service实现
 * 邀请码懒生成（全局唯一，并发冲突自动重试）；
 * 邀请关系仅在注册瞬间绑定（注册事务内，回滚即解除），注册后禁止补绑；
 * 绑定链路全静默：任何校验不通过只记日志，不影响注册。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InviteServiceImpl implements IInviteService
{
    private final IPromotionConfigService promotionConfigService;

    private final IAidInviteCodeService aidInviteCodeService;

    private final IAidInviteRelationService aidInviteRelationService;

    private final IAidInviteRebateRecordService aidInviteRebateRecordService;

    private final ISysUserService sysUserService;

    /** 邀请码随机源（强随机，防止邀请码可预测被遍历） */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 邀请码格式校验（字符集与长度须与生成规则一致） */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "[" + PromotionConstants.INVITE_CODE_CHARS + "]{" + PromotionConstants.INVITE_CODE_LENGTH + "}");

    /** 用户状态：正常 */
    private static final String USER_STATUS_NORMAL = "0";

    /** 删除标志：存在 */
    private static final String DEL_FLAG_EXIST = "0";

    /** 返佣记录状态中文名：已发放 */
    private static final String STATUS_NAME_GRANTED = "已发放";

    /** 返佣记录状态中文名：已撤回 */
    private static final String STATUS_NAME_REVOKED = "已撤回";

    @Override
    public InviteCodeCheckVO checkInviteCode(String rawCode)
    {
        InviteCodeCheckVO vo = new InviteCodeCheckVO();
        vo.setValid(false);
        // 活动关闭时直接告知，前端可隐藏邀请码入口
        InviteConfig config = promotionConfigService.getInviteConfig();
        if (!config.isEnabled())
        {
            vo.setReason("邀请活动未开启");
            return vo;
        }
        // 归一化 + 格式校验
        String code = normalizeCode(rawCode);
        if (StrUtil.isBlank(code))
        {
            vo.setReason("邀请码无效");
            return vo;
        }
        // 查邀请码归属
        AidInviteCode codeRecord = aidInviteCodeService.getByCode(code);
        if (Objects.isNull(codeRecord))
        {
            vo.setReason("邀请码无效");
            return vo;
        }
        // 邀请人必须真实存在且状态正常（停用/删除的账号邀请码同步失效）
        SysUser inviter = selectNormalUser(codeRecord.getUserId());
        if (Objects.isNull(inviter))
        {
            vo.setReason("邀请码无效");
            return vo;
        }
        vo.setValid(true);
        vo.setInviterNickName(inviter.getNickName());
        vo.setInviterAvatar(inviter.getAvatar());
        return vo;
    }

    @Override
    public void bindOnRegister(Long inviteeUserId, String rawCode, String channel)
    {
        try
        {
            // 未携带邀请码：正常注册，无需处理
            if (StrUtil.isBlank(rawCode) || Objects.isNull(inviteeUserId))
            {
                return;
            }
            // 活动关闭：不绑定关系（开关只影响新关系，存量关系由返佣开关控制）
            InviteConfig config = promotionConfigService.getInviteConfig();
            if (!config.isEnabled())
            {
                log.info("邀请活动未开启，忽略邀请码, inviteeUserId={}", inviteeUserId);
                return;
            }
            // 格式非法：静默忽略（不阻断注册）
            String code = normalizeCode(rawCode);
            if (StrUtil.isBlank(code))
            {
                log.info("邀请码格式非法，忽略, inviteeUserId={}, rawCode={}", inviteeUserId, rawCode);
                return;
            }
            // 邀请码不存在：静默忽略
            AidInviteCode codeRecord = aidInviteCodeService.getByCode(code);
            if (Objects.isNull(codeRecord))
            {
                log.info("邀请码不存在，忽略, inviteeUserId={}, code={}", inviteeUserId, code);
                return;
            }
            // 防自邀：邀请码归属人与新用户是同一人（理论上注册瞬间不可能，硬防御）
            if (Objects.equals(codeRecord.getUserId(), inviteeUserId))
            {
                log.info("检测到自我邀请，忽略, userId={}, code={}", inviteeUserId, code);
                return;
            }
            // 邀请人必须状态正常（停用/删除账号不能继续拉新）
            SysUser inviter = selectNormalUser(codeRecord.getUserId());
            if (Objects.isNull(inviter))
            {
                log.info("邀请人状态异常，忽略绑定, inviterUserId={}, inviteeUserId={}", codeRecord.getUserId(), inviteeUserId);
                return;
            }
            // 一个用户只能被邀请一次（DB 唯一索引兜底）
            AidInviteRelation existing = aidInviteRelationService.getByInvitee(inviteeUserId);
            if (Objects.nonNull(existing))
            {
                log.info("用户已存在邀请关系，忽略, inviteeUserId={}", inviteeUserId);
                return;
            }

            // 建立邀请关系（在注册事务内落库：注册回滚则关系一并回滚）
            AidInviteRelation relation = new AidInviteRelation();
            relation.setInviterUserId(codeRecord.getUserId());
            relation.setInviteeUserId(inviteeUserId);
            relation.setInviteCode(code);
            relation.setRegisterChannel(channel);
            relation.setRegisterIp(resolveRequestIp());
            relation.setStatus(PromotionConstants.RELATION_STATUS_NORMAL);
            relation.setTotalRebate(BigDecimal.ZERO);
            relation.setDelFlag(DEL_FLAG_EXIST);
            relation.setCreateBy("system");
            relation.setCreateTime(new Date());
            aidInviteRelationService.save(relation);
            log.info("邀请关系绑定成功, inviterUserId={}, inviteeUserId={}, code={}, channel={}",
                    codeRecord.getUserId(), inviteeUserId, code, channel);
        }
        catch (DuplicateKeyException e)
        {
            // 并发下同一用户重复绑定被唯一索引拦截：符合预期，静默
            log.info("邀请关系并发重复绑定，已忽略, inviteeUserId={}", inviteeUserId);
        }
        catch (Exception e)
        {
            // 邀请绑定失败绝不阻断注册主流程
            log.error("邀请关系绑定异常, inviteeUserId={}, rawCode={}", inviteeUserId, rawCode, e);
        }
    }

    @Override
    public InviteInfoVO getMyInviteInfo(Long userId)
    {
        InviteConfig config = promotionConfigService.getInviteConfig();
        InviteInfoVO vo = new InviteInfoVO();
        vo.setEnabled(config.isEnabled());
        // 活动关闭：不生成邀请码，仅返回开关状态供前端隐藏入口
        if (!config.isEnabled())
        {
            return vo;
        }
        vo.setInviteCode(getOrCreateInviteCode(userId));
        vo.setRebateRatio(config.getRebateRatio());
        vo.setRebateMaxPerOrder(config.getRebateMaxPerOrder());
        // 已邀请人数（含关系被禁用的，如实展示）
        vo.setInvitedCount(aidInviteRelationService.countByInviter(userId));
        // 累计返佣只统计已发放（撤回的不计）
        vo.setTotalRebate(aidInviteRebateRecordService.sumGrantedRebate(userId));
        return vo;
    }

    @Override
    public AjaxResult pageInvitedUsers(Long userId, InvitePageRequest request)
    {
        int pageNum = Objects.isNull(request) ? 1 : request.resolvePageNum();
        int pageSize = Objects.isNull(request) ? 10 : request.resolvePageSize();

        // 开启分页（紧邻联表查询，确保 PageHelper 仅拦截这一条）
        PageHelper.startPage(pageNum, pageSize);
        List<InvitedUserRowDTO> rows = aidInviteRelationService.selectInvitedUserRows(userId);
        long total = new PageInfo<>(rows).getTotal();

        List<InvitedUserVO> voList = new ArrayList<>();
        for (InvitedUserRowDTO row : rows)
        {
            InvitedUserVO vo = new InvitedUserVO();
            vo.setNickName(row.getNickName());
            vo.setAvatar(row.getAvatar());
            vo.setTotalRebate(scale2(row.getTotalRebate()));
            vo.setRegisterTime(row.getRegisterTime());
            voList.add(vo);
        }
        return AjaxResult.success()
                .put("total", total)
                .put("data", voList);
    }

    @Override
    public AjaxResult pageRebateRecords(Long userId, InvitePageRequest request)
    {
        int pageNum = Objects.isNull(request) ? 1 : request.resolvePageNum();
        int pageSize = Objects.isNull(request) ? 10 : request.resolvePageSize();

        // 开启分页（紧邻联表查询，确保 PageHelper 仅拦截这一条）
        PageHelper.startPage(pageNum, pageSize);
        List<InviteRebateRowDTO> rows = aidInviteRebateRecordService.selectRebateRows(userId);
        long total = new PageInfo<>(rows).getTotal();

        List<InviteRebateItemVO> voList = new ArrayList<>();
        for (InviteRebateRowDTO row : rows)
        {
            InviteRebateItemVO vo = new InviteRebateItemVO();
            vo.setNickName(row.getNickName());
            vo.setOrderCredits(scale2(row.getOrderCredits()));
            vo.setRebateRatio(row.getRebateRatio());
            vo.setRebateAmount(scale2(row.getRebateAmount()));
            vo.setStatus(row.getStatus());
            vo.setStatusName(PromotionConstants.REBATE_STATUS_REVOKED.equals(row.getStatus())
                    ? STATUS_NAME_REVOKED : STATUS_NAME_GRANTED);
            vo.setCreateTime(row.getCreateTime());
            voList.add(vo);
        }
        return AjaxResult.success()
                .put("total", total)
                .put("data", voList);
    }

    /**
     * 懒生成用户邀请码：已有直接返回；没有则生成全局唯一码落库。
     * 并发防护：撞 uk_user_id（同用户并发）时复用已插入的码；撞 uk_invite_code（码冲突）时换码重试。
     */
    private String getOrCreateInviteCode(Long userId)
    {
        // 已生成过直接复用（一人一码，永久有效）
        AidInviteCode existing = aidInviteCodeService.getByUserId(userId);
        if (Objects.nonNull(existing))
        {
            return existing.getInviteCode();
        }
        for (int attempt = 0; attempt < PromotionConstants.INVITE_CODE_MAX_RETRY; attempt++)
        {
            String code = randomCode();
            // 先查一次减少唯一索引冲突概率（最终一致性由 DB 唯一索引保证）
            if (Objects.nonNull(aidInviteCodeService.getByCode(code)))
            {
                continue;
            }
            AidInviteCode record = new AidInviteCode();
            record.setUserId(userId);
            record.setInviteCode(code);
            record.setDelFlag(DEL_FLAG_EXIST);
            record.setCreateBy("system");
            record.setCreateTime(new Date());
            try
            {
                aidInviteCodeService.save(record);
                return code;
            }
            catch (DuplicateKeyException e)
            {
                // 撞 uk_user_id：同用户并发生成，复用先插入的那条
                AidInviteCode concurrent = aidInviteCodeService.getByUserId(userId);
                if (Objects.nonNull(concurrent))
                {
                    return concurrent.getInviteCode();
                }
                // 撞 uk_invite_code：换一个码继续重试
                log.info("邀请码冲突重试, userId={}, attempt={}", userId, attempt + 1);
            }
        }
        // 连续冲突超过上限（理论上 32^8 空间几乎不可能），提示稍后再试
        log.error("邀请码生成连续冲突超过上限, userId={}", userId);
        throw new ServiceException("邀请码生成失败");
    }

    /**
     * 生成 8 位随机邀请码（大写字母+数字，去除 0/O/1/I 易混淆字符）
     */
    private String randomCode()
    {
        StringBuilder sb = new StringBuilder(PromotionConstants.INVITE_CODE_LENGTH);
        for (int i = 0; i < PromotionConstants.INVITE_CODE_LENGTH; i++)
        {
            sb.append(PromotionConstants.INVITE_CODE_CHARS
                    .charAt(RANDOM.nextInt(PromotionConstants.INVITE_CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 邀请码归一化：去空格 + 转大写；格式不符返回 null
     */
    private String normalizeCode(String rawCode)
    {
        if (StrUtil.isBlank(rawCode))
        {
            return null;
        }
        String code = rawCode.trim().toUpperCase();
        return CODE_PATTERN.matcher(code).matches() ? code : null;
    }

    /**
     * 查询状态正常且未删除的用户；不满足返回 null
     */
    private SysUser selectNormalUser(Long userId)
    {
        if (Objects.isNull(userId))
        {
            return null;
        }
        SysUser user = sysUserService.selectUserById(userId);
        if (Objects.isNull(user))
        {
            return null;
        }
        // 停用或已删除的账号视为无效邀请人
        if (!Objects.equals(USER_STATUS_NORMAL, user.getStatus())
                || !Objects.equals(DEL_FLAG_EXIST, user.getDelFlag()))
        {
            return null;
        }
        return user;
    }

    /**
     * 取当前请求来源IP（无请求上下文时返回空串，不抛异常）
     */
    private String resolveRequestIp()
    {
        try
        {
            return IpUtils.getIpAddr();
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /** 金额规整为 2 位小数；null 视为 0 */
    private BigDecimal scale2(BigDecimal v)
    {
        if (Objects.isNull(v))
        {
            return BigDecimal.ZERO;
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}

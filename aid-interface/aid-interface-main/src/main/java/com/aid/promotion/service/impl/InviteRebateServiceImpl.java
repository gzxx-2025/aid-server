package com.aid.promotion.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidInviteRebateRecord;
import com.aid.aid.domain.AidInviteRelation;
import com.aid.aid.domain.AidPayOrder;
import com.aid.aid.service.IAidInviteRebateRecordService;
import com.aid.aid.service.IAidInviteRelationService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.common.core.domain.entity.SysUser;
import com.aid.core.service.ISysUserService;
import com.aid.promotion.constant.PromotionConstants;
import com.aid.promotion.domain.InviteConfig;
import com.aid.promotion.service.IInviteRebateService;
import com.aid.promotion.service.IPromotionConfigService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 邀请充值返佣Service实现
 * 发放顺序：先入账（reward，traceId 幂等）再写返佣记录 + 累计统计，
 * 任一步失败只记错误日志，支付回调重试可自愈（入账幂等跳过、记录补写）。
 * 比例/上限实时读配置，发放时快照进返佣记录便于审计。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InviteRebateServiceImpl implements IInviteRebateService
{
    private final IPromotionConfigService promotionConfigService;

    private final IAidInviteRelationService aidInviteRelationService;

    private final IAidInviteRebateRecordService aidInviteRebateRecordService;

    private final IAccountUpdateService accountUpdateService;

    private final ISysUserService sysUserService;

    /** 用户状态：正常 */
    private static final String USER_STATUS_NORMAL = "0";

    /** 删除标志：存在 */
    private static final String DEL_FLAG_EXIST = "0";

    /** 百分比换算基数 */
    private static final BigDecimal PERCENT_BASE = new BigDecimal("100");

    @Override
    public void grantRechargeRebate(AidPayOrder order)
    {
        try
        {
            // 订单信息不完整直接跳过（防御）
            if (Objects.isNull(order) || Objects.isNull(order.getUserId()) || StrUtil.isBlank(order.getOrderNo()))
            {
                return;
            }
            // 到账积分为返佣基数，无积分不返佣
            BigDecimal credits = order.getCredits();
            if (Objects.isNull(credits) || credits.signum() <= 0)
            {
                return;
            }
            // 活动关闭或比例为 0：不返佣（对存量关系同样生效，实现"随时可停"）
            InviteConfig config = promotionConfigService.getInviteConfig();
            if (!config.isEnabled() || Objects.isNull(config.getRebateRatio()) || config.getRebateRatio().signum() <= 0)
            {
                return;
            }
            // 充值人必须存在邀请关系
            AidInviteRelation relation = aidInviteRelationService.getByInvitee(order.getUserId());
            if (Objects.isNull(relation))
            {
                return;
            }
            // 关系被风控禁用：不返佣
            if (!Objects.equals(PromotionConstants.RELATION_STATUS_NORMAL, relation.getStatus()))
            {
                log.info("邀请关系已禁用，跳过返佣, relationId={}, orderNo={}", relation.getId(), order.getOrderNo());
                return;
            }
            // 数据异常防御：邀请人与充值人不能是同一人
            if (Objects.equals(relation.getInviterUserId(), order.getUserId()))
            {
                log.warn("邀请关系数据异常（自邀），跳过返佣, relationId={}, orderNo={}", relation.getId(), order.getOrderNo());
                return;
            }
            // 邀请人必须状态正常（停用/删除账号不再享受返佣）
            SysUser inviter = sysUserService.selectUserById(relation.getInviterUserId());
            if (Objects.isNull(inviter)
                    || !Objects.equals(USER_STATUS_NORMAL, inviter.getStatus())
                    || !Objects.equals(DEL_FLAG_EXIST, inviter.getDelFlag()))
            {
                log.info("邀请人状态异常，跳过返佣, inviterUserId={}, orderNo={}", relation.getInviterUserId(), order.getOrderNo());
                return;
            }
            // 同一订单只返佣一次（orderNo 唯一索引兜底）
            if (Objects.nonNull(aidInviteRebateRecordService.getByOrderNo(order.getOrderNo())))
            {
                return;
            }
            // 返佣 = 到账积分 × 比例(%)，向下取 2 位小数（宁少勿多）
            BigDecimal ratio = config.getRebateRatio();
            BigDecimal rebate = credits.multiply(ratio).divide(PERCENT_BASE, 2, RoundingMode.DOWN);
            // 单笔上限（配置 >0 时生效）
            BigDecimal maxPerOrder = config.getRebateMaxPerOrder();
            if (Objects.nonNull(maxPerOrder) && maxPerOrder.signum() > 0 && rebate.compareTo(maxPerOrder) > 0)
            {
                rebate = maxPerOrder;
            }
            // 计算结果为 0（小额充值×低比例）不发放
            if (rebate.signum() <= 0)
            {
                return;
            }

            // 1) 先入账：reward 按 traceId 幂等，支付回调重试不会重复发放
            String traceId = order.getOrderNo() + PromotionConstants.REBATE_TRACE_SUFFIX;
            accountUpdateService.reward(relation.getInviterUserId(), rebate,
                    traceId, PromotionConstants.BIZ_TYPE_INVITE_REBATE, PromotionConstants.BIZ_NAME_INVITE_REBATE);

            // 2) 写返佣记录（比例/基数/金额快照，orderNo 唯一幂等）
            AidInviteRebateRecord record = new AidInviteRebateRecord();
            record.setInviterUserId(relation.getInviterUserId());
            record.setInviteeUserId(order.getUserId());
            record.setOrderNo(order.getOrderNo());
            record.setOrderCredits(credits);
            record.setPayPrice(order.getPayPrice());
            record.setRebateRatio(ratio);
            record.setRebateAmount(rebate);
            record.setStatus(PromotionConstants.REBATE_STATUS_GRANTED);
            record.setDelFlag(DEL_FLAG_EXIST);
            record.setCreateBy("system");
            record.setCreateTime(new Date());
            aidInviteRebateRecordService.save(record);

            // 3) 冗余累计到关系表（失败仅影响展示统计，错误日志兜底）
            aidInviteRelationService.changeTotalRebate(relation.getId(), rebate);
            log.info("邀请返佣发放成功, orderNo={}, inviterUserId={}, inviteeUserId={}, rebate={}",
                    order.getOrderNo(), relation.getInviterUserId(), order.getUserId(), rebate);
        }
        catch (DuplicateKeyException e)
        {
            // 并发回调下 orderNo 唯一索引拦截：返佣已由先到的线程处理，静默
            log.info("邀请返佣并发重复，已忽略, orderNo={}", order.getOrderNo());
        }
        catch (Exception e)
        {
            // 返佣失败绝不阻断支付回调；入账幂等 + 记录唯一，回调重试可自愈
            log.error("邀请返佣处理异常, orderNo={}", Objects.isNull(order) ? "" : order.getOrderNo(), e);
        }
    }

    @Override
    public void revokeRebateOnRefund(AidPayOrder order)
    {
        try
        {
            // 订单信息不完整直接跳过（防御）
            if (Objects.isNull(order) || StrUtil.isBlank(order.getOrderNo()))
            {
                return;
            }
            // 该订单没有返佣记录：无需扣回
            AidInviteRebateRecord record = aidInviteRebateRecordService.getByOrderNo(order.getOrderNo());
            if (Objects.isNull(record))
            {
                return;
            }
            // 已撤回过：幂等跳过
            if (!Objects.equals(PromotionConstants.REBATE_STATUS_GRANTED, record.getStatus()))
            {
                return;
            }
            BigDecimal rebate = record.getRebateAmount();
            if (Objects.isNull(rebate) || rebate.signum() <= 0)
            {
                return;
            }

            // 1) 从邀请人余额扣回（adminAdjust 带余额下限保护 + traceId 幂等）
            String traceId = order.getOrderNo() + PromotionConstants.REBATE_REVOKE_TRACE_SUFFIX;
            try
            {
                accountUpdateService.adminAdjust(record.getInviterUserId(), rebate.negate(),
                        traceId, "邀请返佣退款扣回");
            }
            catch (Exception e)
            {
                // 邀请人余额不足等场景：不阻断退款，记录保持 granted 待人工处理
                log.error("邀请返佣退款扣回失败，需人工处理, orderNo={}, inviterUserId={}, rebate={}",
                        order.getOrderNo(), record.getInviterUserId(), rebate, e);
                return;
            }

            // 2) 返佣记录置为已撤回
            record.setStatus(PromotionConstants.REBATE_STATUS_REVOKED);
            record.setUpdateBy("system");
            record.setUpdateTime(new Date());
            aidInviteRebateRecordService.updateById(record);

            // 3) 关系累计返佣同步扣减（失败仅影响展示统计）
            AidInviteRelation relation = aidInviteRelationService.getByInvitee(record.getInviteeUserId());
            if (Objects.nonNull(relation))
            {
                aidInviteRelationService.changeTotalRebate(relation.getId(), rebate.negate());
            }
            log.info("邀请返佣退款扣回成功, orderNo={}, inviterUserId={}, rebate={}",
                    order.getOrderNo(), record.getInviterUserId(), rebate);
        }
        catch (Exception e)
        {
            // 扣回失败绝不阻断退款主流程
            log.error("邀请返佣退款扣回异常, orderNo={}", Objects.isNull(order) ? "" : order.getOrderNo(), e);
        }
    }
}

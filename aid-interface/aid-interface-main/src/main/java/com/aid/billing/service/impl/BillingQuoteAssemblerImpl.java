package com.aid.billing.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.service.BillingQuoteAssembler;
import com.aid.billing.util.BillingSettlementPolicy;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;

/** 报价展示语义的唯一组装实现。 */
@Service
public class BillingQuoteAssemblerImpl implements BillingQuoteAssembler
{
    @Override
    public BillingQuoteVO single(String quoteType, AiModelConfigVo modelConfig,
                                 BillingCalcResult result, int quantity)
    {
        BigDecimal amount = BillingConstants.normalizeAccountAmount(
                result.getAmount().multiply(BigDecimal.valueOf(quantity)));
        String meterType = result.getSnapshot() == null ? null : result.getSnapshot().getMeterType();
        boolean estimated = BillingSettlementPolicy.isEstimated(meterType,
                modelConfig.getBillingMode(), modelConfig.getBillingRuleJson());
        boolean free = Boolean.TRUE.equals(modelConfig.getIsFree());
        BillingQuoteVO vo = new BillingQuoteVO();
        vo.setQuoteType(quoteType);
        vo.setModelCode(modelConfig.getModelCode());
        vo.setIsFree(free);
        vo.setMatched(Boolean.TRUE);
        vo.setSkuCode(result.getSkuCode());
        vo.setSkuName(result.getSkuName());
        vo.setMeterType(meterType);
        vo.setUnit(resolveUnit(meterType, modelConfig.getModelType()));
        vo.setUnitName(resolveUnitName(vo.getUnit()));
        vo.setMatchConditions(result.getSnapshot() == null
                ? null : result.getSnapshot().getMatchedRuleConditions());
        vo.setQuantity(quantity);
        vo.setAmount(free ? BigDecimal.ZERO : amount);
        vo.setPreHoldAmount(free ? BigDecimal.ZERO : amount);
        vo.setEstimated(estimated && !free);
        vo.setDetermined(!estimated || free);
        vo.setDisplayText(displayText(free, estimated, amount));
        return vo;
    }

    @Override
    public BillingQuoteVO aggregate(String quoteType, List<BillingQuoteVO> source)
    {
        List<BillingQuoteVO> items = source == null ? List.of() : new ArrayList<>(source);
        if (items.isEmpty())
        {
            throw new ServiceException("暂无可报价项");
        }
        BigDecimal total = items.stream().map(BillingQuoteVO::getPreHoldAmount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        total = BillingConstants.normalizeAccountAmount(total);
        boolean free = !items.isEmpty()
                && items.stream().allMatch(item -> Boolean.TRUE.equals(item.getIsFree()));
        boolean estimated = items.stream().anyMatch(item -> Boolean.TRUE.equals(item.getEstimated()));
        BillingQuoteVO vo = new BillingQuoteVO();
        vo.setQuoteType(quoteType);
        vo.setMatched(items.stream().allMatch(item -> Boolean.TRUE.equals(item.getMatched())));
        vo.setIsFree(free);
        vo.setAmount(free ? BigDecimal.ZERO : total);
        vo.setPreHoldAmount(free ? BigDecimal.ZERO : total);
        vo.setEstimated(estimated && !free);
        vo.setDetermined(!estimated || free);
        vo.setDisplayText(displayText(free, estimated, total));
        vo.setItems(items);
        return vo;
    }

    @Override
    public BillingQuoteVO zero(String quoteType, String displayText)
    {
        BillingQuoteVO vo = new BillingQuoteVO();
        vo.setQuoteType(quoteType);
        vo.setIsFree(Boolean.TRUE);
        vo.setMatched(Boolean.TRUE);
        vo.setQuantity(0);
        vo.setAmount(BigDecimal.ZERO);
        vo.setPreHoldAmount(BigDecimal.ZERO);
        vo.setDetermined(Boolean.TRUE);
        vo.setEstimated(Boolean.FALSE);
        vo.setDisplayText(displayText == null || displayText.isBlank()
                ? "本轮无模型费用" : displayText);
        vo.setItems(List.of());
        return vo;
    }

    @Override
    public BillingQuoteVO asEstimated(BillingQuoteVO quote)
    {
        if (quote == null)
        {
            return null;
        }
        if (quote.getItems() != null)
        {
            quote.getItems().forEach(this::asEstimated);
        }
        if (!Boolean.TRUE.equals(quote.getIsFree()))
        {
            quote.setEstimated(Boolean.TRUE);
            quote.setDetermined(Boolean.FALSE);
            BigDecimal amount = quote.getPreHoldAmount() == null
                    ? BigDecimal.ZERO : quote.getPreHoldAmount();
            quote.setDisplayText(displayText(false, true, amount));
        }
        return quote;
    }

    private String displayText(boolean free, boolean estimated, BigDecimal amount)
    {
        return free ? "免费" : (estimated ? "预扣约 " : "预计扣除 ")
                + amount.stripTrailingZeros().toPlainString() + " 积分";
    }

    private String resolveUnit(String meterType, String modelType)
    {
        if ("TOKEN".equals(meterType)) return "TOKEN";
        if ("PER_IMAGE".equals(meterType)) return "IMAGE";
        if ("PER_SECOND".equals(meterType)) return "SECOND";
        if ("PER_CHAR".equals(meterType)) return "CHAR";
        return "image".equalsIgnoreCase(modelType) ? "IMAGE" : "CALL";
    }

    private String resolveUnitName(String unit)
    {
        return switch (unit)
        {
            case "TOKEN" -> "Token";
            case "IMAGE" -> "张";
            case "SECOND" -> "秒";
            case "CHAR" -> "字符";
            default -> "次";
        };
    }
}

package com.aid.providerbalance.service;

import com.aid.common.exception.ServiceException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 仅消费官方账户适配器的明确余额字段；禁止递归猜测数值或跨单位相加。 */
final class OfficialBalanceReader {
    private OfficialBalanceReader() { }

    static BigDecimal read(Map<String, Object> data, String currency, long now) {
        if (data == null) throw new ServiceException("官方接口未返回余额数据");
        if (data.containsKey("balanceInfos")) {
            Object raw = data.get("balanceInfos");
            if (!(raw instanceof List<?> rows)) throw new ServiceException("官方余额明细格式异常");
            BigDecimal selected = null;
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> row)) throw new ServiceException("官方余额明细格式异常");
                if (currency != null && currency.equalsIgnoreCase(String.valueOf(row.get("currency")))) {
                    if (selected != null) throw new ServiceException("官方余额币种重复");
                    selected = amount(row.get("total_balance"));
                }
            }
            if (selected == null) throw new ServiceException("官方接口未返回所配置币种的余额，请检查余额单位");
            return selected;
        }
        requireUnit(currency, data.get("unit"));
        if (data.containsKey("resource_pack_subscribe_infos")) {
            return resourceBalance(data, now);
        }
        return amount(data.get("balance"));
    }

    private static BigDecimal resourceBalance(Map<String, Object> data, long now) {
        Object raw = data.get("resource_pack_subscribe_infos");
        if (Boolean.FALSE.equals(data.get("balanceAvailable")) || !(raw instanceof List<?> rows) || rows.isEmpty()) {
            throw new ServiceException("官方未返回可计量资源包，不能据此判断账户余额为零");
        }
        BigDecimal total = BigDecimal.ZERO;
        String dimension = null;
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) throw new ServiceException("资源包格式异常");
            String status = String.valueOf(row.get("status"));
            if (List.of("expired", "toBeOnline").contains(status)) continue;
            if (!List.of("online", "runOut").contains(status)) throw new ServiceException("资源包状态无法识别");
            if (amount(row.get("effective_time")).compareTo(BigDecimal.valueOf(now)) > 0
                    || amount(row.get("invalid_time")).compareTo(BigDecimal.valueOf(now)) <= 0) continue;
            Object name = row.get("resource_pack_name");
            Object type = row.get("resource_pack_type");
            if (!(name instanceof String) || ((String) name).isBlank() || !(type instanceof String)
                    || !List.of("decreasing_total", "constant_period").contains(type)) {
                throw new ServiceException("资源包计量口径不明确");
            }
            String current = name + "/" + type;
            if (dimension != null && !dimension.equals(current)) {
                throw new ServiceException("存在不同种类资源包，不能混合汇总；请使用模拟余额或余额不足错误提醒");
            }
            dimension = current;
            total = total.add(amount(row.get("remaining_quantity")));
        }
        return total;
    }

    static void requireUnit(String configured, Object actual) {
        if (!(actual instanceof String unit) || unit.isBlank()
                || configured == null || !configured.equalsIgnoreCase(unit)) {
            throw new ServiceException("余额单位与官方接口不一致，请按官方单位设置阈值");
        }
    }

    private static BigDecimal amount(Object value) {
        try {
            if (!(value instanceof Number) && !(value instanceof String)) throw new NumberFormatException();
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw new ServiceException("官方接口余额金额缺失或格式异常");
        }
    }
}

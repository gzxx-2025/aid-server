package com.aid.media.provider;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONUtil;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.billing.model.BillingSnapshot;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【测试日志·上线必删】媒体生成「C端接口 ↔ 模型请求 ↔ SKU ↔ token ↔ 扣费」串联落盘工具。
 * <p>
 * 用途：本地核对各供应商（如 Vidu）定价/SKU/入参出参是否正确。落盘文件与 {@link ModelIoDump} 同目录：
 * <ul>
 *   <li>SUBMIT：提交前落一条——C端接口(反射读当前HTTP请求) + 命中SKU + 定价快照 + 预估token + 预扣积分 + 计费入参；</li>
 *   <li>SETTLE：结算后落一条——实际token/张数/时长 + 实际扣费 + 退款；</li>
 *   <li>关联：{@link #tag()} 供 {@link ModelIoDump} 前缀 [trace|task|C]，使 model_request.log 与本文件按同一 taskId 对齐。</li>
 * </ul>
 * 本文件只用于调试串联，不是账务事实源；最终资金变动必须以 aid_balance_log 为准，
 * 再结合业务任务、计费快照和 provider usage 交叉核对。
 * 关联上下文用 ThreadLocal 承载，仅在提交线程（直连请求线程）有效；上线前请全局搜索「测试日志·上线必删」删除本类及其调用点。
 */
public final class TestBillingTraceLog {

    // 落盘文件（与 model_request.log 同目录，便于对照排查）
    private static final String FILE = "D:/aid_pro/aid-master/model_billing_trace.log";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Token 官方原价统一按每百万 Token 计。 */
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    // 单调自增，保证同一毫秒内 traceId 也不重复
    private static final AtomicLong SEQ = new AtomicLong(0);

    // 提交线程关联上下文：traceId / taskId / C端接口 等
    private static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

    private TestBillingTraceLog() {
    }

    // 关联上下文载体
    private static final class Ctx {
        private String traceId;
        private Long taskId;
        private String cEndpoint;
    }

    /**
     * 提交前埋点：绑定本线程关联标记，并落一条 SUBMIT 明细（供 Provider 的 ModelIoDump 关联）。
     */
    public static void begin(AidMediaTask task) {
        try {
            if (task == null) {
                return;
            }
            Ctx ctx = new Ctx();
            // traceId 唯一标识本次提交，贯穿 SUBMIT / model_request.log / SETTLE
            ctx.traceId = "T" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
            ctx.taskId = task.getId();
            // C 端接口：反射读取当前 HTTP 请求，异步/排队线程读不到时为 null
            ctx.cEndpoint = resolveEndpoint();
            CTX.set(ctx);
            writeSubmit(task, ctx);
        } catch (Exception ignore) {
            // 测试工具，任何异常都不得影响主流程
        }
    }

    /**
     * 供 {@link ModelIoDump} 前缀：返回 [trace|task|C] 关联串，无上下文时返回空串。
     */
    public static String tag() {
        Ctx ctx = CTX.get();
        if (ctx == null) {
            return "";
        }
        return "[trace=" + ctx.traceId + "|task=" + ctx.taskId
                + "|C=" + (ctx.cEndpoint == null ? "-" : ctx.cEndpoint) + "] ";
    }

    /**
     * 提交结束后清理本线程关联上下文（放在 finally 调用）。
     */
    public static void end() {
        CTX.remove();
    }

    /**
     * 反射读取当前 HTTP 请求的「方法 + URI + queryString」。
     * 用反射而非直接依赖 servlet-api，避免给 interface-main 引入新的编译期依赖。
     */
    private static String resolveEndpoint() {
        try {
            Object attrs = RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            Object req = attrs.getClass().getMethod("getRequest").invoke(attrs);
            if (req == null) {
                return null;
            }
            String method = String.valueOf(req.getClass().getMethod("getMethod").invoke(req));
            String uri = String.valueOf(req.getClass().getMethod("getRequestURI").invoke(req));
            Object qs = req.getClass().getMethod("getQueryString").invoke(req);
            return method + " " + uri + (qs == null ? "" : "?" + qs);
        } catch (Exception e) {
            return null;
        }
    }

    // 落一条 SUBMIT/预扣 明细
    private static void writeSubmit(AidMediaTask task, Ctx ctx) {
        BillingSnapshot s = parse(task.getBillingSnapshotJson());
        StringBuilder sb = new StringBuilder();
        sb.append("C端接口    : ").append(ctx.cEndpoint == null ? "非HTTP线程(异步/排队拉起)" : ctx.cEndpoint).append('\n');
        sb.append("traceId    : ").append(ctx.traceId).append("   (model_request.log 同 traceId 可对上)").append('\n');
        sb.append("taskId     : ").append(task.getId()).append("   userId=").append(task.getUserId()).append('\n');
        appendBusinessContext(sb, task);
        sb.append("provider   : ").append(task.getProtocol())
                .append("   model=").append(task.getModelName())
                .append("   mediaType=").append(task.getMediaType()).append('\n');
        if (s == null) {
            appendMissingSnapshotNotice(sb, task);
        } else {
            sb.append("计费口径    : meterType=").append(s.getMeterType())
                    .append("   billingMode=").append(s.getBillingMode())
                    .append("   billingVersion=").append(s.getBillingVersion()).append('\n');
            sb.append("命中SKU     : skuCode=").append(s.getSkuCode())
                    .append("   skuName=").append(s.getSkuName()).append('\n');
            sb.append("定价快照    : unitPrice=").append(s.getUnitPrice())
                    .append("  skuPackagePrice=").append(s.getSkuPackagePrice())
                    .append("  pricePerSecond=").append(s.getPricePerSecond())
                    .append("  inputPer1M=").append(s.getInputPricePerMillion())
                    .append("  outputPer1M=").append(s.getOutputPricePerMillion()).append('\n');
            sb.append("数量维度    : expectedImageCount=").append(s.getExpectedImageCount())
                    .append("  expectedDurationSeconds=").append(s.getExpectedDurationSeconds()).append('\n');
            sb.append("预估Token   : in=").append(s.getEstimatedInputTokens())
                    .append("  out=").append(s.getEstimatedOutputTokens()).append('\n');
            appendPriceFormula(sb, s, false);
            sb.append("预扣积分    : preHold=").append(s.getPreHoldAmount())
                    .append("  frozenAmount=").append(task.getFrozenAmount()).append('\n');
            sb.append("计费入参    : ").append(s.getRequestParams()).append('\n');
            sb.append("SKU命中条件 : ").append(s.getMatchedRuleConditions()).append('\n');
        }
        append("SUBMIT/预扣", sb.toString());
    }

    /**
     * 结算后埋点：落一条 SETTLE 明细（实际 token/张数/时长、实际扣费、退款）。
     * 结算发生在回调/轮询线程，靠 taskId 与 SUBMIT 对齐。
     */
    public static void settle(AidMediaTask task) {
        try {
            if (task == null) {
                return;
            }
            BillingSnapshot s = parse(task.getBillingSnapshotJson());
            StringBuilder sb = new StringBuilder();
            sb.append("taskId     : ").append(task.getId()).append("   userId=").append(task.getUserId()).append('\n');
            appendBusinessContext(sb, task);
            sb.append("provider   : ").append(task.getProtocol()).append("   model=").append(task.getModelName()).append('\n');
            if (s != null) {
                sb.append("命中SKU     : skuCode=").append(s.getSkuCode())
                        .append("   skuName=").append(s.getSkuName())
                        .append("   meterType=").append(s.getMeterType()).append('\n');
                sb.append("实际Token   : in=").append(s.getActualInputTokens())
                        .append("  out=").append(s.getActualOutputTokens()).append('\n');
                sb.append("实际产出    : actualImageCount=").append(s.getActualImageCount())
                        .append("  actualDurationSeconds=").append(s.getActualDurationSeconds()).append('\n');
                appendPriceFormula(sb, s, true);
                sb.append("金额结算    : preHold=").append(s.getPreHoldAmount())
                        .append("  actual=").append(s.getActualAmount())
                        .append("  refund=").append(s.getRefundAmount())
                        .append("  extraRequired=").append(s.getExtraChargeRequired())
                        .append("  extraActual=").append(s.getExtraChargeActual()).append('\n');
            } else {
                appendMissingSnapshotNotice(sb, task);
            }
            sb.append("任务金额    : frozenAmount=").append(task.getFrozenAmount())
                    .append("  actualCost=").append(task.getActualCost()).append('\n');
            append("SETTLE/结算", sb.toString());
        } catch (Exception ignore) {
            // 测试工具，任何异常都不得影响结算主流程
        }
    }

    /**
     * 退款埋点：任务失败/无结果时落一条 REFUND 明细（预扣全额退回），与 SUBMIT 靠 taskId 对齐。
     * 发生在提交线程或回调/轮询线程均可，不依赖 ThreadLocal 上下文。
     */
    public static void refund(AidMediaTask task) {
        try {
            if (task == null) {
                return;
            }
            BillingSnapshot s = parse(task.getBillingSnapshotJson());
            StringBuilder sb = new StringBuilder();
            sb.append("taskId     : ").append(task.getId()).append("   userId=").append(task.getUserId()).append('\n');
            appendBusinessContext(sb, task);
            sb.append("provider   : ").append(task.getProtocol()).append("   model=").append(task.getModelName()).append('\n');
            if (s != null) {
                sb.append("命中SKU     : skuCode=").append(s.getSkuCode())
                        .append("   skuName=").append(s.getSkuName())
                        .append("   meterType=").append(s.getMeterType()).append('\n');
                appendPriceFormula(sb, s, false);
            } else {
                appendMissingSnapshotNotice(sb, task);
            }
            sb.append("金额退款    : preHold=").append(task.getFrozenAmount())
                    .append("  actual=0  refund=").append(task.getFrozenAmount()).append('\n');
            sb.append("失败原因    : ").append(task.getErrorMessage() == null ? "-" : task.getErrorMessage()).append('\n');
            append("REFUND/退款", sb.toString());
        } catch (Exception ignore) {
            // 测试工具，任何异常都不得影响退款主流程
        }
    }

    /**
     * 输出业务计费链路标识，区分临时日志 traceId 与数据库 billing_trace_id。
     */
    private static void appendBusinessContext(StringBuilder sb, AidMediaTask task) {
        sb.append("账务trace   : ").append(task.getBillingTraceId()).append("   (aid_balance_log.related_id)").append('\n');
        sb.append("关联业务    : projectId=").append(task.getProjectId())
                .append("  episodeId=").append(task.getEpisodeId())
                .append("  bizTaskType=").append(task.getBizTaskType())
                .append("  bizTaskId=").append(task.getBizTaskId()).append('\n');
    }

    /**
     * 无快照不等于未扣费，计费豁免子任务需要回到父业务任务核对。
     */
    private static void appendMissingSnapshotNotice(StringBuilder sb, AidMediaTask task) {
        sb.append("计费快照    : <空>（可能是计费豁免子任务或匿名请求，不能据此判定未扣费）").append('\n');
        sb.append("核对提示    : 按 bizTaskType=").append(task.getBizTaskType())
                .append("、bizTaskId=").append(task.getBizTaskId())
                .append(" 查父业务任务，再以 aid_balance_log 为准").append('\n');
    }

    /**
     * 输出当前统一价格公式：官方原价（元）× 模型基础倍率（积分/元）× 单模型倍率。
     */
    private static void appendPriceFormula(StringBuilder sb, BillingSnapshot snapshot, boolean settled) {
        BigDecimal modelMultiplier = positiveOrOne(snapshot.getModelBillingMultiplier());
        BigDecimal globalMultiplier = positiveOrOne(snapshot.getGlobalBillingMultiplier());
        BigDecimal finalMultiplier = modelMultiplier.multiply(globalMultiplier);
        sb.append("价格口径    : SKU/Token单价均为官方原价（元），结果金额为积分").append('\n');
        sb.append("倍率公式    : final=").append(finalMultiplier)
                .append(" = global(").append(globalMultiplier).append("积分/元)")
                .append(" × model(").append(modelMultiplier).append(")").append('\n');
        if (!settled) {
            BigDecimal baseAmount = snapshot.getBaseAmount();
            sb.append("预扣公式    : ").append(baseAmount).append("元 × ")
                    .append(globalMultiplier).append(" × ").append(modelMultiplier)
                    .append(" = ").append(snapshot.getPreHoldAmount()).append("积分").append('\n');
            return;
        }
        BigDecimal actualBase = resolveActualBaseAmount(snapshot);
        BigDecimal calculatedCredits = actualBase == null ? null : actualBase.multiply(finalMultiplier);
        sb.append("结算公式    : ").append(actualBase).append("元 × ")
                .append(globalMultiplier).append(" × ").append(modelMultiplier)
                .append(" = ").append(calculatedCredits).append("积分（四舍五入、补扣或封顶前）").append('\n');
    }

    /**
     * 根据实际 Token、张数或时长还原结算官方原价；无法还原时返回 null，避免制造错误证据。
     */
    private static BigDecimal resolveActualBaseAmount(BillingSnapshot snapshot) {
        String meterType = snapshot.getMeterType();
        BigDecimal inputMediaAmount = valueOrZero(snapshot.getInputMediaAmount());
        if ("TOKEN".equals(meterType)) {
            if (snapshot.getActualInputTokens() == null && snapshot.getActualOutputTokens() == null) {
                return null;
            }
            BigDecimal inputCost = BigDecimal.valueOf(intOrZero(snapshot.getActualInputTokens()))
                    .multiply(valueOrZero(snapshot.getInputPricePerMillion()))
                    .divide(MILLION, 6, RoundingMode.HALF_UP);
            BigDecimal outputCost = BigDecimal.valueOf(intOrZero(snapshot.getActualOutputTokens()))
                    .multiply(valueOrZero(snapshot.getOutputPricePerMillion()))
                    .divide(MILLION, 6, RoundingMode.HALF_UP);
            return inputCost.add(outputCost).add(inputMediaAmount);
        }
        if ("PER_IMAGE".equals(meterType) && snapshot.getActualImageCount() != null) {
            return valueOrZero(snapshot.getUnitPrice())
                    .multiply(BigDecimal.valueOf(snapshot.getActualImageCount()))
                    .add(inputMediaAmount);
        }
        if ("PER_SECOND".equals(meterType) && snapshot.getActualDurationSeconds() != null) {
            return valueOrZero(snapshot.getPricePerSecond())
                    .multiply(BigDecimal.valueOf(snapshot.getActualDurationSeconds()))
                    .add(inputMediaAmount);
        }
        return snapshot.getBaseAmount();
    }

    private static BigDecimal positiveOrOne(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : value;
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    // 解析计费快照 JSON，失败返回 null
    private static BillingSnapshot parse(String json) {
        if (CharSequenceUtil.isBlank(json)) {
            return null;
        }
        try {
            return JSONUtil.toBean(json, BillingSnapshot.class);
        } catch (Exception e) {
            return null;
        }
    }

    // 追加写文件（同步，写失败不影响主流程）
    private static synchronized void append(String phase, String body) {
        try (FileWriter w = new FileWriter(FILE, true)) {
            w.write("==================== [测试日志·上线必删] " + LocalDateTime.now().format(TS)
                    + " | " + phase + " ====================\n");
            w.write(body);
            w.write("\n");
        } catch (Exception ignore) {
            // 测试工具，写文件失败不影响主流程
        }
    }
}

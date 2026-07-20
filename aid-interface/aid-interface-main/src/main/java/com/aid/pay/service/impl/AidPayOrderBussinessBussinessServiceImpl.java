package com.aid.pay.service.impl;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.aid.aid.mapper.AidPayOrderMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aid.common.constant.PayConstants;
import com.aid.aid.domain.AidBalanceLog;
import com.aid.aid.domain.AidPayOrder;
import com.aid.aid.domain.AidRechargePackage;
import com.aid.aid.domain.AidUserProfile;
import com.aid.pay.dto.CreateOrderRequest;
import com.aid.pay.dto.PayOrderQueryRequest;
import com.aid.aid.service.IAidBalanceLogService;
import com.aid.pay.service.IAidPayOrderBussinessService;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.aid.service.IAidRechargePackageService;
import com.aid.aid.service.IAidUserProfileService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.promotion.service.IInviteRebateService;
import com.aid.pay.vo.CreateOrderVO;
import com.aid.pay.vo.PayOrderListVO;
import com.aid.pay.vo.PayOrderVO;
import com.aid.pay.vo.RechargePackageVO;
import com.aid.common.aid.alipay.core.AlipayTemplateFactory;
import com.aid.common.aid.alipay.entity.AlipayTradeResult;
import com.aid.common.aid.alipay.entity.AlipayRefundResult;
import com.aid.common.aid.wxpay.core.WxpayTemplateFactory;
import com.aid.common.aid.wxpay.entity.WxpayTradeResult;
import com.aid.common.aid.wxpay.entity.WxpayRefundResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 支付订单Service业务层处理
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AidPayOrderBussinessBussinessServiceImpl extends ServiceImpl<AidPayOrderMapper, AidPayOrder> implements IAidPayOrderBussinessService
{

    @Autowired
    private IAidRechargePackageService rechargePackageService;

    @Autowired
    private AlipayTemplateFactory alipayTemplateFactory;

    @Autowired
    private WxpayTemplateFactory wxpayTemplateFactory;

    @Autowired
    private IAidUserProfileService userProfileService;

    @Autowired
    private IAidBalanceLogService balanceLogService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private IAccountUpdateService accountUpdateService;

    @Autowired
    private IInviteRebateService inviteRebateService;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    /**
     * 关闭订单专属线程池：独立于公共线程池，避免支付渠道接口延迟拖累其他异步任务。
     * 核心 2 / 最大 4 / 队列 100，匹配"切换订单异步关闭"的低频特征；daemon 线程不阻塞 JVM 退出；
     * 队列满时回退到调用线程执行，宁可同步阻塞也不丢任务。
     */
    private final ExecutorService closeOrderExecutor = new ThreadPoolExecutor(
            2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new java.util.concurrent.ThreadFactory() {
                private final AtomicLong counter = new AtomicLong(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "pay-close-order-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    @PreDestroy
    public void shutdownCloseOrderExecutor() {
        log.info("关闭 pay-close-order 专属线程池...");
        closeOrderExecutor.shutdown();
        try {
            if (!closeOrderExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                closeOrderExecutor.shutdownNow();
                log.warn("pay-close-order 线程池强制关闭");
            }
        } catch (InterruptedException e) {
            closeOrderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    /**
     * 查询上架中的充值套餐列表（status=0 且未删除，按排序值升序）。
     * 查询字段为套餐展示全字段（VO 一一对应），新增展示字段时同步补充。
     *
     * @return 套餐VO列表
     */
    @Override
    public List<RechargePackageVO> listActivePackages() {
        LambdaQueryWrapper<AidRechargePackage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidRechargePackage::getStatus, "0")
                .eq(AidRechargePackage::getDelFlag, "0")
                .orderByAsc(AidRechargePackage::getSortOrder);
        List<AidRechargePackage> packages = rechargePackageService.list(queryWrapper);
        return packages.stream()
                .map(pkg -> RechargePackageVO.builder()
                        .id(pkg.getId())
                        .packageName(pkg.getPackageName())
                        .credits(pkg.getCredits())
                        .originalPrice(pkg.getOriginalPrice())
                        .discount(pkg.getDiscount())
                        .payPrice(pkg.getPayPrice())
                        .icon(pkg.getIcon())
                        .description(pkg.getDescription())
                        .build())
                .toList();
    }

    /**
     * 创建订单：用户级锁串行受理（防并发重复建单），订单落库走短事务，
     * 渠道下单（外部 HTTP）在事务外执行——渠道失败时订单保留为待支付，
     * 用户重试下单会自动关闭旧单重建，或走继续支付补取二维码。
     *
     * @param request 创建订单请求
     * @param userId  用户ID
     * @param clientIp 客户端IP
     * @return 创建订单结果
     */
    @Override
    public CreateOrderVO createOrder(CreateOrderRequest request, Long userId, String clientIp) {

        if (!PayConstants.CHANNEL_ALIPAY.equals(request.getPayType())
                && !PayConstants.CHANNEL_WXPAY.equals(request.getPayType())) {
            log.error("暂不支持的支付方式: payType={}", request.getPayType());
            throw new ServiceException("暂不支持该支付方式");
        }

        AidRechargePackage pkg = rechargePackageService.getById(request.getPackageId());
        if (Objects.isNull(pkg)) {
            log.error("套餐不存在或已下架: packageId={}", request.getPackageId());
            throw new ServiceException("套餐不存在");
        }

        if (!"0".equals(pkg.getStatus())) {
            log.error("套餐已下架: packageId={}", request.getPackageId());
            throw new ServiceException("套餐已下架");
        }

        // 用户级下单锁：并发双击/多端同时下单时串行处理，避免产生多笔待支付订单
        RLock lock = redissonClient.getLock(PayConstants.LOCK_CREATE_ORDER + userId);
        boolean locked;
        try {
            locked = lock.tryLock(PayConstants.LOCK_WAIT_TIME, PayConstants.LOCK_LEASE_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("下单锁等待被中断: userId={}", userId, e);
            throw new ServiceException("下单失败，请重试");
        }
        if (!locked) {
            log.info("下单锁竞争失败: userId={}", userId);
            throw new ServiceException("操作频繁，请稍候");
        }
        try {
            // 短事务：关旧单 + 建新单一并提交；渠道 HTTP 不入事务，避免长事务占用连接
            AidPayOrder order = transactionTemplate.execute(status -> {
                AidPayOrder pendingOrder = getPendingOrder(userId);
                if (Objects.nonNull(pendingOrder)) {
                    log.info("用户存在待支付订单，自动关闭后重新创建: userId={}, pendingOrderNo={}",
                            userId, pendingOrder.getOrderNo());
                    closeOrderOnSwitch(pendingOrder);
                }

                Date expireTime = Date.from(LocalDateTime.now()
                        .plusMinutes(PayConstants.ORDER_EXPIRE_MINUTES)
                        .atZone(ZoneId.systemDefault())
                        .toInstant());

                AidPayOrder created = new AidPayOrder();
                created.setOrderNo(generateOrderNo());
                created.setUserId(userId);
                created.setPackageId(pkg.getId());
                created.setProductName(pkg.getPackageName());
                created.setCredits(pkg.getCredits());
                created.setOriginalPrice(pkg.getOriginalPrice());
                created.setDiscount(pkg.getDiscount());
                created.setPayPrice(pkg.getPayPrice());
                created.setPayChannel(request.getPayType());
                created.setPayStatus(PayConstants.STATUS_PENDING);
                created.setExpireTime(expireTime);
                created.setClientIp(clientIp);
                created.setDelFlag("0");
                created.setCreateTime(DateUtils.getNowDate());
                save(created);
                return created;
            });
            if (Objects.isNull(order)) {
                log.error("订单落库失败: userId={}, packageId={}", userId, request.getPackageId());
                throw new ServiceException("下单失败，请重试");
            }

            String orderNo = order.getOrderNo();
            log.info("订单创建成功: orderNo={}, userId={}, packageId={}, payPrice={}",
                    orderNo, userId, pkg.getId(), pkg.getPayPrice());

            String qrCode;
            if (PayConstants.CHANNEL_ALIPAY.equals(request.getPayType())) {
                qrCode = alipayTemplateFactory.precreatePay(orderNo, pkg.getPayPrice(), pkg.getPackageName());
            } else {
                qrCode = wxpayTemplateFactory.nativePay(orderNo, pkg.getPayPrice(), pkg.getPackageName());
            }

            return CreateOrderVO.builder()
                    .orderNo(orderNo)
                    .qrCode(qrCode)
                    .build();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 获取用户待支付订单（未超时）
     *
     * @param userId 用户ID
     * @return 待支付订单，不存在返回null
     */
    private AidPayOrder getPendingOrder(Long userId) {
        LambdaQueryWrapper<AidPayOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidPayOrder::getUserId, userId)
                .eq(AidPayOrder::getPayStatus, PayConstants.STATUS_PENDING)
                .eq(AidPayOrder::getDelFlag, "0")
                .gt(AidPayOrder::getExpireTime, DateUtils.getNowDate()) // 未超时
                .orderByDesc(AidPayOrder::getCreateTime)
                .last("LIMIT 1");
        return getOne(queryWrapper);
    }

    /**
     * 继续支付（重新获取支付二维码）
     *
     * @param orderNo 订单号
     * @param userId  用户ID
     * @return 支付二维码信息
     */
    @Override
    public CreateOrderVO repayOrder(String orderNo, Long userId) {
        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            log.info("继续支付失败-订单不存在: orderNo={}, userId={}", orderNo, userId);
            throw new ServiceException("订单不存在");
        }

        if (!Objects.equals(order.getUserId(), userId)) {
            log.error("订单归属校验失败: orderNo={}, orderUserId={}, requestUserId={}",
                    orderNo, order.getUserId(), userId);
            throw new ServiceException("无权操作该订单");
        }

        if (!PayConstants.STATUS_PENDING.equals(order.getPayStatus())) {
            log.info("继续支付失败-订单状态异常: orderNo={}, payStatus={}", orderNo, order.getPayStatus());
            throw new ServiceException("订单状态异常");
        }

        if (isOrderExpired(order)) {
            log.info("继续支付失败-订单已超时: orderNo={}, expireTime={}", orderNo, order.getExpireTime());
            throw new ServiceException("订单已超时");
        }

        String qrCode;
        String payChannel = order.getPayChannel();

        if (PayConstants.CHANNEL_ALIPAY.equals(payChannel)) {
            qrCode = alipayTemplateFactory.precreatePay(orderNo, order.getPayPrice(), order.getProductName());
        } else if (PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
            qrCode = wxpayTemplateFactory.nativePay(orderNo, order.getPayPrice(), order.getProductName());
        } else {
            log.error("继续支付失败-支付渠道非法: orderNo={}, payChannel={}", orderNo, payChannel);
            throw new ServiceException("不支持的支付方式");
        }

        log.info("继续支付-重新获取二维码: orderNo={}, userId={}, payChannel={}", orderNo, userId, payChannel);

        return CreateOrderVO.builder()
                .orderNo(orderNo)
                .qrCode(qrCode)
                .build();
    }

    /**
     * 取消订单
     *
     * @param orderNo 订单号
     * @param userId  用户ID
     */
    @Override
    public void cancelOrder(String orderNo, Long userId) {
        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            log.info("取消订单失败-订单不存在: orderNo={}, userId={}", orderNo, userId);
            throw new ServiceException("订单不存在");
        }

        if (!Objects.equals(order.getUserId(), userId)) {
            log.error("订单归属校验失败: orderNo={}, orderUserId={}, requestUserId={}",
                    orderNo, order.getUserId(), userId);
            throw new ServiceException("无权操作该订单");
        }

        if (!PayConstants.STATUS_PENDING.equals(order.getPayStatus())) {
            log.info("取消订单失败-订单状态异常: orderNo={}, payStatus={}", orderNo, order.getPayStatus());
            throw new ServiceException("订单状态异常");
        }

        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());
        update(updateWrapper);

        String payChannel = order.getPayChannel();
        try {
            if (PayConstants.CHANNEL_ALIPAY.equals(payChannel)) {
                alipayTemplateFactory.close(orderNo);
            } else if (PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
                wxpayTemplateFactory.close(orderNo);
            }
        } catch (Exception e) {
            // 关闭失败不影响主流程，记录日志即可
            log.warn("关闭支付渠道订单失败: orderNo={}, payChannel={}", orderNo, payChannel, e);
        }

        log.info("订单取消成功: orderNo={}, userId={}", orderNo, userId);
    }

    /**
     * 根据订单号查询订单
     *
     * @param orderNo 订单号
     * @return 订单信息
     */
    @Override
    public AidPayOrder getOrderByOrderNo(String orderNo) {
        if (StrUtil.isBlank(orderNo)) {
            return null;
        }

        LambdaQueryWrapper<AidPayOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidPayOrder::getOrderNo, orderNo)
                .eq(AidPayOrder::getDelFlag, "0");

        return getOne(queryWrapper);
    }

    /**
     * 查询订单状态
     *
     * @param orderNo 订单号
     * @param userId  用户ID
     * @return 订单状态信息
     */
    @Override
    public PayOrderVO queryOrderStatus(String orderNo, Long userId) {
        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            log.error("订单不存在: orderNo={}", orderNo);
            throw new ServiceException("订单不存在");
        }

        if (!Objects.equals(order.getUserId(), userId)) {
            log.error("订单归属校验失败: orderNo={}, orderUserId={}, requestUserId={}",
                    orderNo, order.getUserId(), userId);
            throw new ServiceException("无权查看该订单");
        }

        // 主动查单：解决"已付款但回调丢失/延迟"导致前端轮询永远拿到 PENDING 的问题。
        // 待支付时主动向渠道查单——
        //   - 已超时：必查（顺带触发关单/补入账）；
        //   - 未超时：Redis 节流，同一订单 QUERY_THROTTLE_SECONDS 秒内最多查一次，避免触发渠道查单 QPS 限制。
        if (PayConstants.STATUS_PENDING.equals(order.getPayStatus())) {
            boolean expired = isOrderExpired(order);
            boolean allowQuery = expired || tryAcquireQueryThrottle(orderNo);
            if (allowQuery) {
                log.info("主动同步订单状态: orderNo={}, expired={}", orderNo, expired);
                try {
                    syncOrderStatus(orderNo);
                    order = getOrderByOrderNo(orderNo);
                } catch (Exception e) {
                    log.warn("同步订单状态失败: orderNo={}", orderNo, e);
                }
            }
        }

        boolean canRepay = PayConstants.STATUS_PENDING.equals(order.getPayStatus()) && !isOrderExpired(order);

        return PayOrderVO.builder()
                .orderNo(order.getOrderNo())
                .productName(order.getProductName())
                .credits(order.getCredits())
                .payPrice(order.getPayPrice())
                .payStatus(order.getPayStatus())
                .payTime(order.getPayTime())
                .createTime(order.getCreateTime())
                .canRepay(canRepay)
                .build();
    }

    /**
     * 判断订单是否超时
     *
     * @param order 订单信息
     * @return 是否超时
     */
    private boolean isOrderExpired(AidPayOrder order) {
        Date createTime = order.getCreateTime();
        if (createTime == null) {
            return false;
        }
        // 超时条件：createTime + ORDER_EXPIRE_MINUTES < now
        long expireTime = createTime.getTime() + (long) PayConstants.ORDER_EXPIRE_MINUTES * 60 * 1000;
        return System.currentTimeMillis() > expireTime;
    }

    /**
     * 主动查单节流：尝试获取节流许可。
     *
     * @param orderNo 订单号
     * @return 是否允许本次主动查单
     */
    private boolean tryAcquireQueryThrottle(String orderNo) {
        try {
            String key = PayConstants.QUERY_THROTTLE_PREFIX + orderNo;
            return redissonClient.getBucket(key)
                    .trySet("1", PayConstants.QUERY_THROTTLE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("查单节流判定异常，放行本次查单: orderNo={}", orderNo, e);
            return true;
        }
    }

    /**
     * 处理支付宝回调
     *
     * @param params 回调参数
     * @return 处理结果(success/fail)
     */
    @Override
    public String handleAlipayNotify(Map<String, String> params) {
        log.info("收到支付宝回调: orderNo={}", params.get("out_trade_no"));

        boolean verifyResult = alipayTemplateFactory.verifyNotify(params);
        if (!verifyResult) {
            // 验签失败不打完整 params（含 sign/buyer_id 等敏感字段），只保留 orderNo 和 trade_status 供排障。
            log.error("支付宝回调验签失败: orderNo={}, tradeStatus={}",
                    params.get("out_trade_no"), params.get("trade_status"));
            return "fail";
        }

        // 校验通知来源，避免误处理其他应用的合法通知。
        if (!alipayTemplateFactory.verifyNotifySource(params)) {
            log.error("支付宝回调来源校验失败: orderNo={}", params.get("out_trade_no"));
            return "fail";
        }

        String orderNo = params.get("out_trade_no");
        if (StrUtil.isBlank(orderNo)) {
            log.error("订单号为空");
            return "fail";
        }

        String lockKey = PayConstants.LOCK_PAY_NOTIFY + orderNo;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(PayConstants.LOCK_WAIT_TIME, PayConstants.LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取锁失败，稍后重试: orderNo={}", orderNo);
                return "fail";
            }

            return transactionTemplate.execute(status -> {
                try {
                    return doHandleAlipayNotify(params, orderNo);
                } catch (Exception e) {
                    status.setRollbackOnly();
                    log.error("支付宝回调业务处理异常，事务已回滚: orderNo={}", orderNo, e);
                    return "fail";
                }
            });

        } catch (InterruptedException e) {
            log.error("获取锁异常: orderNo={}", orderNo, e);
            Thread.currentThread().interrupt();
            return "fail";
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行支付宝回调业务逻辑
     *
     * @param params  回调参数
     * @param orderNo 订单号
     * @return 处理结果(success/fail)
     */
    public String doHandleAlipayNotify(Map<String, String> params, String orderNo) {
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");

        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            log.error("订单不存在: orderNo={}", orderNo);
            return "fail";
        }
        if (PayConstants.STATUS_PAID.equals(order.getPayStatus())) {
            log.info("订单已处理，跳过: orderNo={}", orderNo);
            return "success";
        }

        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            log.info("交易状态非成功，跳过: orderNo={}, tradeStatus={}", orderNo, tradeStatus);
            return "success";
        }

        // 金额一致性校验，防止篡改回调金额套取大额充值
        String notifyTotalAmount = params.get("total_amount");
        if (StrUtil.isBlank(notifyTotalAmount)) {
            log.error("支付宝回调缺少 total_amount: orderNo={}", orderNo);
            return "fail";
        }
        try {
            java.math.BigDecimal notifyAmount = new java.math.BigDecimal(notifyTotalAmount.trim());
            java.math.BigDecimal expectAmount = order.getPayPrice();
            if (expectAmount == null
                    || notifyAmount.compareTo(expectAmount.setScale(2, java.math.RoundingMode.HALF_UP)) != 0) {
                log.error("支付宝回调金额不匹配: orderNo={}, notifyAmount={}, expectAmount={}",
                        orderNo, notifyAmount, expectAmount);
                return "fail";
            }
        } catch (NumberFormatException nfe) {
            log.error("支付宝回调 total_amount 格式非法: orderNo={}, totalAmount={}", orderNo, notifyTotalAmount);
            return "fail";
        }

        // 防丢账：先入账（幂等）再标记已支付，避免标记已支付后入账失败导致永久不到账。
        boolean rechargeLogExists = checkRechargeLogExists(orderNo);
        if (!rechargeLogExists) {
            addBalanceToUser(order);
            log.info("充值成功: orderNo={}, userId={}, credits={}", orderNo, order.getUserId(), order.getCredits());
        } else {
            log.info("充值日志已存在，跳过充值: orderNo={}, userId={}", orderNo, order.getUserId());
        }

        // 邀请返佣：被邀请人充值到账后给邀请人返积分（内部全静默 + traceId/orderNo 双幂等，回调重试可自愈）
        inviteRebateService.grantRechargeRebate(order);

        // 渠道已确认收款：待支付与已关闭（关单后晚到的成功回调）均转已支付终态，保证与渠道对账一致
        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .in(AidPayOrder::getPayStatus, PayConstants.STATUS_PENDING, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_PAID)
                .set(AidPayOrder::getTradeNo, tradeNo)
                .set(AidPayOrder::getPayTime, DateUtils.getNowDate())
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());
        if (!update(updateWrapper)) {
            log.warn("订单状态未更新为已支付(可能已被并发处理)，但积分已入账: orderNo={}", orderNo);
        }

        return "success";
    }

    /**
     * 处理微信支付回调。
     *
     * @param serial    证书序列号
     * @param nonce     随机串
     * @param timestamp 时间戳
     * @param signature 签名
     * @param body      请求体
     * @return 处理结果
     */
    @Override
    public Map<String, Object> handleWxpayNotify(String serial, String nonce, String timestamp, String signature, String body) {
        log.info("收到微信支付回调");

        WxpayTradeResult tradeResult = wxpayTemplateFactory.parseNotify(serial, nonce, timestamp, signature, body);
        if (!tradeResult.getSuccess()) {
            log.error("微信回调解析失败: {}", tradeResult.getMsg());
            return buildWxpayFailResult(tradeResult.getMsg());
        }

        String orderNo = tradeResult.getOutTradeNo();
        if (StrUtil.isBlank(orderNo)) {
            log.error("订单号为空");
            return buildWxpayFailResult("订单号为空");
        }

        String lockKey = PayConstants.LOCK_PAY_NOTIFY + orderNo;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(PayConstants.LOCK_WAIT_TIME, PayConstants.LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取锁失败，稍后重试: orderNo={}", orderNo);
                return buildWxpayFailResult("系统繁忙");
            }

            return transactionTemplate.execute(status -> {
                try {
                    return doHandleWxpayNotify(tradeResult, orderNo);
                } catch (Exception e) {
                    status.setRollbackOnly();
                    log.error("微信回调业务处理异常，事务已回滚: orderNo={}", orderNo, e);
                    return buildWxpayFailResult("系统异常");
                }
            });

        } catch (InterruptedException e) {
            log.error("获取锁异常: orderNo={}", orderNo, e);
            Thread.currentThread().interrupt();
            return buildWxpayFailResult("系统异常");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行微信支付回调业务逻辑
     */
    public Map<String, Object> doHandleWxpayNotify(WxpayTradeResult tradeResult, String orderNo) {
        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            log.error("订单不存在: orderNo={}", orderNo);
            return buildWxpayFailResult("订单不存在");
        }
        if (PayConstants.STATUS_PAID.equals(order.getPayStatus())) {
            log.info("订单已处理，跳过: orderNo={}", orderNo);
            return buildWxpaySuccessResult();
        }

        String tradeState = tradeResult.getTradeState();
        if (!"SUCCESS".equals(tradeState)) {
            log.info("交易状态非成功，跳过: orderNo={}, tradeState={}", orderNo, tradeState);
            return buildWxpaySuccessResult();
        }

        // 金额一致性校验（微信回调金额单位为"分"，订单 payPrice 为"元"）
        Integer notifyAmountFen = tradeResult.getTotalAmount();
        if (notifyAmountFen == null) {
            log.error("微信回调缺少 totalAmount: orderNo={}", orderNo);
            return buildWxpayFailResult("回调金额缺失");
        }
        java.math.BigDecimal expectAmount = order.getPayPrice();
        if (expectAmount == null
                || new java.math.BigDecimal(notifyAmountFen)
                        .compareTo(expectAmount.multiply(new java.math.BigDecimal(100))
                                .setScale(0, java.math.RoundingMode.HALF_UP)) != 0) {
            log.error("微信回调金额不匹配: orderNo={}, notifyAmountFen={}, expectAmount(元)={}",
                    orderNo, notifyAmountFen, expectAmount);
            return buildWxpayFailResult("回调金额不匹配");
        }

        // 防丢账：先入账（幂等）再标记已支付。
        // "先标记已支付再入账"存在风险：若标记已支付后入账失败，后续回调/查单会因"订单已支付"
        // 短路而永不补账，导致用户付款却永久不到账（尤其 syncOrderStatus 经 self-invocation 调用时无事务保护）。
        // 入账与本方法均在订单级分布式锁内串行执行；recharge 以 (orderNo, recharge) 幂等去重，重复调用不会重复入账。
        boolean rechargeLogExists = checkRechargeLogExists(orderNo);
        if (!rechargeLogExists) {
            addBalanceToUser(order);
            log.info("微信充值成功: orderNo={}, userId={}, credits={}", orderNo, order.getUserId(), order.getCredits());
        } else {
            log.info("充值日志已存在，跳过充值: orderNo={}, userId={}", orderNo, order.getUserId());
        }

        // 邀请返佣：被邀请人充值到账后给邀请人返积分（内部全静默 + traceId/orderNo 双幂等，回调重试可自愈）
        inviteRebateService.grantRechargeRebate(order);

        // 渠道已确认收款：待支付与已关闭（关单后晚到的成功回调）均转已支付终态，保证与渠道对账一致
        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .in(AidPayOrder::getPayStatus, PayConstants.STATUS_PENDING, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_PAID)
                .set(AidPayOrder::getTradeNo, tradeResult.getTransactionId())
                .set(AidPayOrder::getPayTime, DateUtils.getNowDate())
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());
        if (!update(updateWrapper)) {
            log.warn("订单状态未更新为已支付(可能已被并发处理)，但积分已入账: orderNo={}", orderNo);
        }

        return buildWxpaySuccessResult();
    }

    /**
     * 构建微信成功响应
     */
    private Map<String, Object> buildWxpaySuccessResult() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("code", "SUCCESS");
        result.put("message", "成功");
        return result;
    }

    /**
     * 构建微信失败响应
     */
    private Map<String, Object> buildWxpayFailResult(String message) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("code", "FAIL");
        result.put("message", message);
        return result;
    }

    /**
     * 关闭超时订单
     *
     * @param orderNo 订单号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeOrder(String orderNo) {
        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            return;
        }

        if (!PayConstants.STATUS_PENDING.equals(order.getPayStatus())) {
            return;
        }

        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());

        update(updateWrapper);
        log.info("订单已关闭: orderNo={}", orderNo);
    }

    /**
     * 关闭待支付订单（用户切换订单场景）
     *
     * @param order 待支付订单
     */
    private void closeOrderOnSwitch(AidPayOrder order) {
        String orderNo = order.getOrderNo();

        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getRemark, "用户切换订单，主动关闭")
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());
        update(updateWrapper);

        // 渠道关单是网络调用，交给专属线程池异步执行，不阻塞下单主流程
        String payChannel = order.getPayChannel();
        String asyncOrderNo = orderNo;
        CompletableFuture.runAsync(() -> {
            try {
                if (PayConstants.CHANNEL_ALIPAY.equals(payChannel)) {
                    alipayTemplateFactory.close(asyncOrderNo);
                    log.info("切换订单-支付宝渠道关闭成功: orderNo={}", asyncOrderNo);
                } else if (PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
                    wxpayTemplateFactory.close(asyncOrderNo);
                    log.info("切换订单-微信渠道关闭成功: orderNo={}", asyncOrderNo);
                }
            } catch (Exception e) {
                log.warn("切换订单-异步关闭支付渠道订单失败: orderNo={}, payChannel={}", asyncOrderNo, payChannel, e);
            }
        }, closeOrderExecutor);

        log.info("用户切换订单，待支付订单已关闭: orderNo={}", orderNo);
    }

    /**
     * 查询用户订单列表
     *
     * @param request 查询请求
     * @param userId  用户ID
     * @return 订单列表
     */
    @Override
    public AjaxResult queryOrderList(PayOrderQueryRequest request, Long userId) {
        PageHelper.startPage(request.getPageNum(), request.getPageSize());

        LambdaQueryWrapper<AidPayOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidPayOrder::getUserId, userId)
                .eq(AidPayOrder::getDelFlag, "0")
                .eq(StrUtil.isNotBlank(request.getPayStatus()), AidPayOrder::getPayStatus, request.getPayStatus())
                .orderByDesc(AidPayOrder::getCreateTime);

        List<AidPayOrder> orderList = list(queryWrapper);

        List<PayOrderListVO> voList = orderList.stream()
                .map(order -> PayOrderListVO.builder()
                        .orderNo(order.getOrderNo())
                        .productName(order.getProductName())
                        .credits(order.getCredits())
                        .originalPrice(order.getOriginalPrice())
                        .discount(order.getDiscount())
                        .payPrice(order.getPayPrice())
                        .payChannel(order.getPayChannel())
                        .payStatus(order.getPayStatus())
                        .payTime(order.getPayTime())
                        .createTime(order.getCreateTime())
                        .build())
                .toList();

        PageInfo<AidPayOrder> pageInfo = new PageInfo<>(orderList);
        return AjaxResult.success()
                .put("total", (int) pageInfo.getTotal())
                .put("data", voList);
    }

    /**
     * 同步订单状态（主动查询并处理）
     * 根据订单的支付渠道自动选择支付宝或微信支付查询接口
     *
     * @param orderNo 订单号
     * @return 处理结果
     */
    @Override
    public AjaxResult syncOrderStatus(String orderNo) {
        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            return AjaxResult.error("订单不存在");
        }

        if (PayConstants.STATUS_PAID.equals(order.getPayStatus())) {
            return AjaxResult.success("订单已支付，无需处理");
        }

        String lockKey = PayConstants.LOCK_PAY_NOTIFY + orderNo;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean locked = lock.tryLock(PayConstants.LOCK_WAIT_TIME, PayConstants.LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                return AjaxResult.error("订单正在处理中，请稍后重试");
            }

            order = getOrderByOrderNo(orderNo);
            if (PayConstants.STATUS_PAID.equals(order.getPayStatus())) {
                return AjaxResult.success("订单已支付，无需处理");
            }

            String payChannel = order.getPayChannel();
            if (PayConstants.CHANNEL_ALIPAY.equals(payChannel)) {
                return doSyncAlipayOrder(order);
            } else if (PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
                return doSyncWxpayOrder(order);
            } else {
                return AjaxResult.error("不支持的支付渠道: " + payChannel);
            }

        } catch (InterruptedException e) {
            log.error("获取锁异常: orderNo={}", orderNo, e);
            Thread.currentThread().interrupt();
            return AjaxResult.error("系统繁忙，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 订单退款（后台运营操作）。
     */
    @Override
    public AjaxResult refundOrder(String orderNo, String refundReason) {
        if (StrUtil.isBlank(orderNo)) {
            return AjaxResult.error("订单号不能为空");
        }
        String reason = StrUtil.isBlank(refundReason) ? "后台运营退款" : refundReason.trim();

        AidPayOrder order = getOrderByOrderNo(orderNo);
        if (Objects.isNull(order)) {
            return AjaxResult.error("订单不存在");
        }
        if (PayConstants.STATUS_REFUNDED.equals(order.getPayStatus())) {
            return AjaxResult.error("订单已退款，请勿重复操作");
        }
        if (!PayConstants.STATUS_PAID.equals(order.getPayStatus())) {
            return AjaxResult.error("仅已支付订单可退款，当前状态: " + order.getPayStatus());
        }

        String lockKey = PayConstants.LOCK_PAY_NOTIFY + orderNo;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean locked = lock.tryLock(PayConstants.LOCK_WAIT_TIME, PayConstants.LOCK_LEASE_TIME, TimeUnit.SECONDS);
            if (!locked) {
                return AjaxResult.error("订单正在处理中，请稍后重试");
            }

            // 锁内双重检查状态
            order = getOrderByOrderNo(orderNo);
            if (PayConstants.STATUS_REFUNDED.equals(order.getPayStatus())) {
                return AjaxResult.error("订单已退款，请勿重复操作");
            }
            if (!PayConstants.STATUS_PAID.equals(order.getPayStatus())) {
                return AjaxResult.error("仅已支付订单可退款，当前状态: " + order.getPayStatus());
            }

            java.math.BigDecimal payPrice = order.getPayPrice();
            if (payPrice == null || payPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                return AjaxResult.error("订单金额异常，无法退款");
            }

            // 预校验：用户当前积分余额需足以扣回（防止给已消费积分的用户退款造成资损）
            java.math.BigDecimal credits = order.getCredits();
            if (credits != null && credits.compareTo(java.math.BigDecimal.ZERO) > 0) {
                AidUserProfile profile = accountUpdateService.getProfile(order.getUserId());
                java.math.BigDecimal balance = profile != null && profile.getBalance() != null
                        ? profile.getBalance() : java.math.BigDecimal.ZERO;
                if (balance.compareTo(credits) < 0) {
                    log.warn("退款预校验失败-用户积分余额不足以扣回: orderNo={}, userId={}, balance={}, needDeduct={}",
                            orderNo, order.getUserId(), balance, credits);
                    return AjaxResult.error("用户当前积分余额不足（可能已消费），无法退款，请人工核对");
                }
            }

            // 退款依据：先向渠道查单，确认"渠道侧确实已支付"，并以渠道返回的实际支付金额为准；
            // 若发现渠道侧已退款，则只同步本地状态，绝不重复发起退款。
            String payChannel = order.getPayChannel();
            java.math.BigDecimal refundAmount; // 实际退款金额（元），以渠道实际支付金额为准
            boolean channelSuccess;
            String channelMsg;

            if (PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
                WxpayTradeResult tr = wxpayTemplateFactory.query(orderNo);
                if (!Boolean.TRUE.equals(tr.getSuccess())) {
                    log.error("微信查单失败，拒绝退款: orderNo={}, msg={}", orderNo, tr.getMsg());
                    return AjaxResult.error("渠道查单失败，暂不可退款，请稍后重试");
                }
                String tradeState = tr.getTradeState();
                if ("REFUND".equals(tradeState)) {
                    log.info("微信渠道已退款，同步本地状态: orderNo={}", orderNo);
                    boolean updated = applyRefundedState(order, reason);
                    notifyRefundAfterUnlock(updated, lock, order, reason, payPrice);
                    return AjaxResult.success("订单在渠道侧已退款，已同步本地状态");
                }
                if (!"SUCCESS".equals(tradeState)) {
                    log.error("微信查单未确认支付成功，拒绝退款: orderNo={}, tradeState={}", orderNo, tradeState);
                    return AjaxResult.error("渠道未确认该订单已支付，不可退款");
                }
                Integer paidFen = tr.getTotalAmount();
                if (paidFen == null) {
                    return AjaxResult.error("渠道未返回支付金额，暂不可退款，请稍后重试");
                }
                java.math.BigDecimal paidYuan = new java.math.BigDecimal(paidFen).movePointLeft(2);
                if (paidYuan.compareTo(payPrice.setScale(2, java.math.RoundingMode.HALF_UP)) != 0) {
                    log.error("微信实际支付金额与订单金额不一致，拦截退款: orderNo={}, paidYuan={}, payPrice={}",
                            orderNo, paidYuan, payPrice);
                    return AjaxResult.error("订单金额与渠道实际支付金额不一致，已拦截，请人工核对");
                }
                refundAmount = paidYuan;

                WxpayRefundResult r = wxpayTemplateFactory.refund(orderNo, refundAmount, refundAmount, reason);
                channelSuccess = Boolean.TRUE.equals(r.getSuccess());
                channelMsg = r.getMsg();

            } else if (PayConstants.CHANNEL_ALIPAY.equals(payChannel)) {
                AlipayTradeResult tr = alipayTemplateFactory.query(orderNo);
                if (!Boolean.TRUE.equals(tr.getSuccess())) {
                    log.error("支付宝查单失败，拒绝退款: orderNo={}, msg={}", orderNo, tr.getMsg());
                    return AjaxResult.error("渠道查单失败，暂不可退款，请稍后重试");
                }
                String ts = tr.getTradeStatus();
                if (!"TRADE_SUCCESS".equals(ts) && !"TRADE_FINISHED".equals(ts) && !"TRADE_CLOSED".equals(ts)) {
                    log.error("支付宝查单未确认支付成功，拒绝退款: orderNo={}, tradeStatus={}", orderNo, ts);
                    return AjaxResult.error("渠道未确认该订单已支付，不可退款");
                }
                AlipayRefundResult rq = alipayTemplateFactory.refundQuery(orderNo, null);
                if (Boolean.TRUE.equals(rq.getSuccess()) && rq.getRefundAmount() != null
                        && rq.getRefundAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
                    log.info("支付宝渠道已退款，同步本地状态: orderNo={}, refundAmount={}", orderNo, rq.getRefundAmount());
                    boolean updated = applyRefundedState(order, reason);
                    notifyRefundAfterUnlock(updated, lock, order, reason, rq.getRefundAmount());
                    return AjaxResult.success("订单在渠道侧已退款，已同步本地状态");
                }
                if ("TRADE_CLOSED".equals(ts)) {
                    log.error("支付宝交易已关闭但未查到退款记录，转人工核对: orderNo={}", orderNo);
                    return AjaxResult.error("订单在渠道已关闭但未查到退款记录，请人工核对");
                }
                java.math.BigDecimal paid = tr.getTotalAmount();
                if (paid == null) {
                    return AjaxResult.error("渠道未返回支付金额，暂不可退款，请稍后重试");
                }
                if (paid.compareTo(payPrice.setScale(2, java.math.RoundingMode.HALF_UP)) != 0) {
                    log.error("支付宝实际支付金额与订单金额不一致，拦截退款: orderNo={}, paid={}, payPrice={}",
                            orderNo, paid, payPrice);
                    return AjaxResult.error("订单金额与渠道实际支付金额不一致，已拦截，请人工核对");
                }
                refundAmount = paid;

                AlipayRefundResult r = alipayTemplateFactory.refund(orderNo, refundAmount, reason);
                channelSuccess = Boolean.TRUE.equals(r.getSuccess());
                channelMsg = r.getSubMsg() != null ? r.getSubMsg() : r.getMsg();
            } else {
                return AjaxResult.error("不支持的支付渠道: " + payChannel);
            }

            if (!channelSuccess) {
                log.error("渠道退款失败: orderNo={}, payChannel={}, msg={}", orderNo, payChannel, channelMsg);
                return AjaxResult.error("退款失败: " + (channelMsg != null ? channelMsg : "渠道返回失败"));
            }

            // 渠道退款成功 → 扣回积分（幂等）+ 订单置为已退款
            boolean updated = applyRefundedState(order, reason);
            notifyRefundAfterUnlock(updated, lock, order, reason, refundAmount);

            log.info("订单退款成功: orderNo={}, payChannel={}, amount={}, userId={}",
                    orderNo, payChannel, refundAmount, order.getUserId());
            return AjaxResult.success("退款成功");

        } catch (InterruptedException e) {
            log.error("退款获取锁异常: orderNo={}", orderNo, e);
            Thread.currentThread().interrupt();
            return AjaxResult.error("系统繁忙，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 落库"已退款"终态：幂等扣回已发放积分 + 订单状态置为 refunded。
     * 供两条路径复用：①主动退款渠道返回成功后；②查单发现渠道侧已退款后同步本地。
     * 积分扣回采用 best-effort（钱已退出去，扣积分失败不回滚订单状态，仅记严重日志待人工核对）；
     * adminAdjust 以 (orderNo+_RFD, admin_adjust) 为幂等键，重复调用不会重复扣减。
     *
     * @return true表示本地订单首次更新为已退款
     */
    private boolean applyRefundedState(AidPayOrder order, String reason) {
        String orderNo = order.getOrderNo();
        java.math.BigDecimal credits = order.getCredits();
        if (credits != null && credits.compareTo(java.math.BigDecimal.ZERO) > 0) {
            try {
                accountUpdateService.adminAdjust(
                        order.getUserId(),
                        credits.negate(),
                        orderNo + PayConstants.REFUND_DEDUCT_TRACE_SUFFIX,
                        PayConstants.BALANCE_BIZ_NAME_REFUND + ": " + orderNo);
            } catch (Exception e) {
                log.error("退款已出款但积分扣回失败，需人工核对: orderNo={}, userId={}, credits={}",
                        orderNo, order.getUserId(), credits, e);
            }
        }

        // 邀请返佣扣回：该订单曾给邀请人返佣的，退款时同步撤回（内部全静默 + 幂等，扣回失败仅记日志待人工处理）
        inviteRebateService.revokeRebateOnRefund(order);

        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .eq(AidPayOrder::getPayStatus, PayConstants.STATUS_PAID)
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_REFUNDED)
                .set(AidPayOrder::getRemark, "退款: " + reason)
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());
        return update(updateWrapper);
    }

    private void notifyRefundAfterUnlock(boolean updated, RLock lock, AidPayOrder order, String reason,
                                         java.math.BigDecimal refundAmount) {
        if (!updated) {
            return;
        }
        // 本地退款终态已落库，先释放支付订单锁，避免微信网络请求占用锁租期。
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
        wechatNotifyService.notifyOrderRefund(order.getUserId(), order.getId(), order.getProductName(),
                order.getOrderNo(), reason, refundAmount);
    }

    /**
     * 定时兜底：扫描"待支付且已超时"的订单，主动向渠道查单并入账/关单。
     * 回调丢失时由此兜底补齐——已付补入账、未付关单。
     * 只扫描最近一段时间内创建的超时订单（避免全表扫描），逐单复用
     * {@link #syncOrderStatus(String)}（自带锁/幂等/金额校验）。
     */
    @Override
    public void autoSyncPendingExpiredOrders() {
        // 仅处理最近 3 天内创建、已超时、仍待支付的订单
        Date now = DateUtils.getNowDate();
        Date scanFrom = new Date(now.getTime() - 3L * 24 * 60 * 60 * 1000);

        LambdaQueryWrapper<AidPayOrder> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidPayOrder::getPayStatus, PayConstants.STATUS_PENDING)
                .eq(AidPayOrder::getDelFlag, "0")
                .lt(AidPayOrder::getExpireTime, now)   // 已超时
                .gt(AidPayOrder::getCreateTime, scanFrom)
                .orderByAsc(AidPayOrder::getCreateTime)
                .last("LIMIT 200");
        List<AidPayOrder> orders = list(queryWrapper);
        if (orders == null || orders.isEmpty()) {
            return;
        }

        log.info("兜底查单：扫描到待处理超时订单 {} 笔", orders.size());
        int paid = 0;
        int closed = 0;
        for (AidPayOrder order : orders) {
            try {
                syncOrderStatus(order.getOrderNo());
                AidPayOrder latest = getOrderByOrderNo(order.getOrderNo());
                if (latest != null && PayConstants.STATUS_PAID.equals(latest.getPayStatus())) {
                    paid++;
                } else if (latest != null && PayConstants.STATUS_CLOSED.equals(latest.getPayStatus())) {
                    closed++;
                }
            } catch (Exception e) {
                log.warn("兜底查单处理单笔订单失败: orderNo={}", order.getOrderNo(), e);
            }
        }
        log.info("兜底查单完成：补入账 {} 笔，关单 {} 笔", paid, closed);
    }

    /**
     * 执行支付宝订单同步
     */
    private AjaxResult doSyncAlipayOrder(AidPayOrder order) {
        String orderNo = order.getOrderNo();
        boolean isExpired = isOrderExpired(order);

        AlipayTradeResult tradeResult = alipayTemplateFactory.query(orderNo);

        String tradeStatus = tradeResult.getTradeStatus();

        if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
            doSyncOrderPay(order, tradeResult);
            return AjaxResult.success("同步成功，用户已充值");
        }

        if (isExpired) {
            // 超时 + 官方待支付 → 关闭订单
            if ("WAIT_BUYER_PAY".equals(tradeStatus)) {
                closeExpiredOrder(order);
                return AjaxResult.success("订单已超时，已自动关闭");
            }
            // 超时 + 官方已关闭(TRADE_CLOSED) → 关闭订单
            if ("TRADE_CLOSED".equals(tradeStatus)) {
                closeExpiredOrder(order);
                return AjaxResult.success("订单已超时且官方已关闭，已自动关闭");
            }
            // 超时 + 订单在官方不存在(sub_code=ACQ.TRADE_NOT_EXIST) → 关闭订单
            if (!tradeResult.getSuccess() && "ACQ.TRADE_NOT_EXIST".equals(tradeResult.getSubCode())) {
                closeExpiredOrder(order);
                return AjaxResult.success("订单已超时且不存在，已自动关闭");
            }
        }

        if (!tradeResult.getSuccess()) {
            log.error("支付宝查询失败: orderNo={}, code={}, subCode={}, msg={}", orderNo, tradeResult.getCode(), tradeResult.getSubCode(), tradeResult.getMsg());
            return AjaxResult.error("支付宝查询失败: " + tradeResult.getSubMsg());
        }

        return AjaxResult.success("交易状态: " + tradeStatus);
    }

    /**
     * 执行微信支付订单同步
     */
    private AjaxResult doSyncWxpayOrder(AidPayOrder order) {
        String orderNo = order.getOrderNo();
        boolean isExpired = isOrderExpired(order);

        WxpayTradeResult tradeResult = wxpayTemplateFactory.query(orderNo);

        String tradeState = tradeResult.getTradeState();

        if ("SUCCESS".equals(tradeState)) {
            doSyncOrderPay(order, tradeResult);
            return AjaxResult.success("同步成功，用户已充值");
        }

        if (isExpired) {
            // 超时 + 官方待支付(NOTPAY) → 关闭订单
            if ("NOTPAY".equals(tradeState)) {
                closeExpiredOrder(order);
                return AjaxResult.success("订单已超时，已自动关闭");
            }
            // 超时 + 官方已关闭(CLOSED) → 关闭订单
            if ("CLOSED".equals(tradeState)) {
                closeExpiredOrder(order);
                return AjaxResult.success("订单已超时且官方已关闭，已自动关闭");
            }
            // 超时 + 订单在官方不存在(code=ORDERNOTEXIST) → 关闭订单
            if (!tradeResult.getSuccess() && "ORDERNOTEXIST".equals(tradeResult.getCode())) {
                closeExpiredOrder(order);
                return AjaxResult.success("订单已超时且不存在，已自动关闭");
            }
        }

        if (!tradeResult.getSuccess()) {
            log.error("微信支付查询失败: orderNo={}, code={}, msg={}", orderNo, tradeResult.getCode(), tradeResult.getMsg());
            return AjaxResult.error("微信支付查询失败: " + tradeResult.getMsg());
        }

        return AjaxResult.success("交易状态: " + tradeState + " - " + tradeResult.getTradeStateDesc());
    }

    /**
     * 关闭超时订单
     *
     * @param order 订单信息
     */
    private void closeExpiredOrder(AidPayOrder order) {
        String orderNo = order.getOrderNo();

        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());
        update(updateWrapper);

        String payChannel = order.getPayChannel();
        try {
            if (PayConstants.CHANNEL_ALIPAY.equals(payChannel)) {
                alipayTemplateFactory.close(orderNo);
            } else if (PayConstants.CHANNEL_WXPAY.equals(payChannel)) {
                wxpayTemplateFactory.close(orderNo);
            }
        } catch (Exception e) {
            log.warn("关闭支付渠道订单失败: orderNo={}, payChannel={}", orderNo, payChannel, e);
        }

        log.info("超时订单已关闭: orderNo={}", orderNo);
    }

    /**
     * 执行同步订单支付逻辑（支付宝）。
     * 不加事务：入账走 IAccountUpdateService 独立事务且幂等，状态更新为单条条件 UPDATE，
     * 类内自调用场景下事务注解本就不生效，显式去掉避免误导。
     *
     * @param order       订单信息
     * @param tradeResult 支付宝查询结果
     */
    public void doSyncOrderPay(AidPayOrder order, AlipayTradeResult tradeResult) {
        String orderNo = order.getOrderNo();

        // 主动查单入账前同样校验金额一致性（与回调路径口径一致，防御纵深）。
        // 支付宝查询返回的 totalAmount 单位为"元"。
        java.math.BigDecimal queryAmount = tradeResult.getTotalAmount();
        java.math.BigDecimal expectAmount = order.getPayPrice();
        if (queryAmount == null || expectAmount == null
                || queryAmount.compareTo(expectAmount.setScale(2, java.math.RoundingMode.HALF_UP)) != 0) {
            log.error("支付宝查单金额不匹配，拒绝入账: orderNo={}, queryAmount={}, expectAmount={}",
                    orderNo, queryAmount, expectAmount);
            throw new ServiceException("订单金额不一致");
        }

        boolean rechargeLogExists = checkRechargeLogExists(orderNo);

        if (!rechargeLogExists) {
            addBalanceToUser(order);
            log.info("同步订单支付成功-已充值: orderNo={}, userId={}, credits={}",
                    orderNo, order.getUserId(), order.getCredits());
        } else {
            log.info("同步订单支付成功-充值日志已存在: orderNo={}, userId={}", orderNo, order.getUserId());
        }

        // 邀请返佣：主动查单补入账路径同样触发（幂等，回调丢失时兜底补发）
        inviteRebateService.grantRechargeRebate(order);

        Date payTime = tradeResult.getGmtPayment() != null ? tradeResult.getGmtPayment() : DateUtils.getNowDate();
        updateOrderStatusToPaid(order, tradeResult.getTradeNo(), payTime);
    }

    /**
     * 执行同步订单支付逻辑（微信支付）。
     * 不加事务：入账走 IAccountUpdateService 独立事务且幂等，状态更新为单条条件 UPDATE，
     * 类内自调用场景下事务注解本就不生效，显式去掉避免误导。
     *
     * @param order       订单信息
     * @param tradeResult 微信支付查询结果
     */
    public void doSyncOrderPay(AidPayOrder order, WxpayTradeResult tradeResult) {
        String orderNo = order.getOrderNo();

        // 主动查单入账前同样校验金额一致性（与回调路径口径一致，防御纵深）。
        // 微信查询返回的 totalAmount 单位为"分"，订单 payPrice 单位为"元"。
        Integer queryAmountFen = tradeResult.getTotalAmount();
        java.math.BigDecimal expectAmount = order.getPayPrice();
        if (queryAmountFen == null || expectAmount == null
                || new java.math.BigDecimal(queryAmountFen)
                        .compareTo(expectAmount.multiply(new java.math.BigDecimal(100))
                                .setScale(0, java.math.RoundingMode.HALF_UP)) != 0) {
            log.error("微信查单金额不匹配，拒绝入账: orderNo={}, queryAmountFen={}, expectAmount(元)={}",
                    orderNo, queryAmountFen, expectAmount);
            throw new ServiceException("订单金额不一致");
        }

        boolean rechargeLogExists = checkRechargeLogExists(orderNo);

        if (!rechargeLogExists) {
            addBalanceToUser(order);
            log.info("同步微信订单支付成功-已充值: orderNo={}, userId={}, credits={}, transactionId={}",
                    orderNo, order.getUserId(), order.getCredits(), tradeResult.getTransactionId());
        } else {
            log.info("同步微信订单支付成功-充值日志已存在: orderNo={}, userId={}", orderNo, order.getUserId());
        }

        // 邀请返佣：主动查单补入账路径同样触发（幂等，回调丢失时兜底补发）
        inviteRebateService.grantRechargeRebate(order);

        Date payTime = tradeResult.getSuccessTime() != null ? tradeResult.getSuccessTime() : DateUtils.getNowDate();
        updateOrderStatusToPaid(order, tradeResult.getTransactionId(), payTime);
    }

    /**
     * 检查充值日志是否已存在（幂等性检查）
     *
     * @param orderNo 订单号
     * @return 是否存在
     */
    private boolean checkRechargeLogExists(String orderNo) {
        LambdaQueryWrapper<AidBalanceLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AidBalanceLog::getRelatedId, orderNo)
                .eq(AidBalanceLog::getBizType, PayConstants.BALANCE_BIZ_TYPE_RECHARGE)
                .eq(AidBalanceLog::getDelFlag, "0");
        return balanceLogService.count(queryWrapper) > 0;
    }

    /**
     * 更新订单状态为已支付
     *
     * @param order     订单信息
     * @param tradeNo   交易流水号
     * @param payTime   支付时间
     */
    private void updateOrderStatusToPaid(AidPayOrder order, String tradeNo, Date payTime) {
        // 渠道已确认收款：待支付与已关闭（关单后晚到的成功确认）均转已支付终态，保证与渠道对账一致
        LambdaUpdateWrapper<AidPayOrder> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidPayOrder::getId, order.getId())
                .in(AidPayOrder::getPayStatus, PayConstants.STATUS_PENDING, PayConstants.STATUS_CLOSED)
                .set(AidPayOrder::getPayStatus, PayConstants.STATUS_PAID)
                .set(AidPayOrder::getTradeNo, tradeNo)
                .set(AidPayOrder::getPayTime, payTime)
                .set(AidPayOrder::getUpdateTime, DateUtils.getNowDate());

        boolean updateResult = update(updateWrapper);
        if (!updateResult) {
            log.warn("订单状态更新失败(可能已处理): orderNo={}", order.getOrderNo());
        }
    }
    /**
     * 增加用户余额：统一委托 IAccountUpdateService.recharge
     * 保证同一用户串行 + 独立事务 + 统一流水，避免与消费/退款并发争抢账户行锁。
     *
     * @param order 订单信息
     */
    private void addBalanceToUser(AidPayOrder order) {
        Long userId = order.getUserId();
        java.math.BigDecimal credits = order.getCredits();

        if (Objects.isNull(credits) || credits.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            log.info("充值金额非法，跳过入账: orderNo={}, credits={}", order.getOrderNo(), credits);
            return;
        }

        // 订单号作为 traceId，内部按 (relatedId, changeType=recharge) 做幂等校验
        accountUpdateService.recharge(
                userId,
                credits,
                order.getOrderNo(),
                PayConstants.BALANCE_BIZ_TYPE_RECHARGE,
                PayConstants.BALANCE_BIZ_NAME_RECHARGE);

        log.info("用户余额入账完成: userId={}, orderNo={}, credits={}",
                userId, order.getOrderNo(), credits);
    }

    /**
     * 生成订单号，格式：前缀 + yyyyMMddHHmmss + 6位随机数。
     *
     * @return 订单号
     */
    private String generateOrderNo() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(100000, 999999);
        return PayConstants.ORDER_NO_PREFIX + timestamp + random;
    }
}

'use client'

import ModalTitleWatermark from '@/components/ModalTitleWatermark'
import { useAuthPublicConfig } from '@/hooks/useAuthPublicConfig'
import { useUserStore } from '@/stores/user'
import { Modal,message } from 'antd'
import type { CSSProperties } from 'react'
import { useCallback,useEffect,useRef,useState } from 'react'
import groupAvatar from '~/assets/img/home/Group-avtor.svg'
import topUpNor from '~/assets/img/icon/topUp-nor.svg'
import topUpSel from '~/assets/img/icon/topUp-sel.svg'
import type { RechargeOrderCreateData,RechargeOrderRow,RechargePackageItem } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import {
rechargeOrderCancel,
rechargeOrderCreate,
rechargeOrderList,
rechargeOrderQuery,
rechargeOrderRepay,
rechargePackageList
} from '~/utils/businessApi'
import { payQrToImageSrc } from '~/utils/payQrImage'
import './recharge/recharge-modal.css'
import { RechargeConsumeDrawer } from './recharge/RechargeConsumeDrawer'
import { formatCreditAmount, formatPrice } from './recharge/rechargeFormat'
import {
isPendingOrderConflictError,
nextFrame,
payFlagsFromConfig,
resolvePendingOrderNoForModal,
useStateRef,
type PayChannel,
type PayFlags
} from './recharge/rechargeModalSupport'
import { RechargeOrdersDrawer,type RechargeOrderTabKey } from './recharge/RechargeOrdersDrawer'
import { RechargePayModal } from './recharge/RechargePayModal'

export interface RechargeModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onPaid?: () => void
}

/** 充值中心弹窗：选套餐下单 → 扫码支付轮询 → 订单管理 / 消耗明细 */
export function RechargeModal({ open, onOpenChange, onPaid }: RechargeModalProps) {
  const token = useUserStore((s) => s.token)
  const user = useUserStore((s) => s.user)
  const {
    alipayEnabled,
    wxpayEnabled,
    anyPaymentEnabled,
    defaultPayType,
    membershipAgreementUrl,
    loadPublicConfig
  } = useAuthPublicConfig()

  // 回调 / 定时器内读最新 props 与支付开关
  const onOpenChangeRef = useRef(onOpenChange)
  onOpenChangeRef.current = onOpenChange
  const onPaidRef = useRef(onPaid)
  onPaidRef.current = onPaid
  const payFlagsRef = useRef<PayFlags>({
    alipayEnabled,
    wxpayEnabled,
    anyPaymentEnabled,
    defaultPayType
  })
  payFlagsRef.current = { alipayEnabled, wxpayEnabled, anyPaymentEnabled, defaultPayType }

  const [packages, packagesRef, setPackages] = useStateRef<RechargePackageItem[]>([])
  const [selectedPackageId, selectedPackageIdRef, setSelectedPackageId] = useStateRef<
    number | null
  >(null)
  const [payType, payTypeRef, setPayType] = useStateRef<PayChannel>('alipay')
  const [creatingOrder, setCreatingOrder] = useState(false)
  const [showPayModal, showPayModalRef, setShowPayModal] = useStateRef(false)
  /** 正在发起「立即购买」的套餐 id */
  const [buyingPackageId, setBuyingPackageId] = useState<number | null>(null)

  const [qrImageSrc, setQrImageSrc] = useState('')
  const [, currentOrderNoRef, setCurrentOrderNo] = useStateRef('')
  const [currentAmount, setCurrentAmount] = useState<number | null>(null)
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const pollSessionRef = useRef(0)
  const pollRequestPendingRef = useRef(false)
  const qrSeqRef = useRef(0)

  const [showOrders, setShowOrders] = useState(false)
  /** 消耗明细 Drawer */
  const [showConsume, setShowConsume] = useState(false)
  const [orderTab, orderTabRef, setOrderTab] = useStateRef<RechargeOrderTabKey>('all')
  const [orders, ordersRef, setOrders] = useStateRef<RechargeOrderRow[]>([])
  const [, setOrdersLoading] = useState(false)
  const [repayingOrderNo, setRepayingOrderNo] = useState('')
  const [cancellingOrderNo, setCancellingOrderNo] = useState('')
  /** 订单列表中高亮（去支付跳转） */
  const [highlightOrderNo, setHighlightOrderNo] = useState('')
  /** 从订单管理「立即支付」进入扫码页时携带的订单行（用于金额/Credits 展示，因未选套餐） */
  const [repayOrderRow, repayOrderRowRef, setRepayOrderRow] = useStateRef<RechargeOrderRow | null>(
    null
  )
  /** 二维码有效期内不展示「刷新」，过期后展示 */
  const [showRefreshQrButton, setShowRefreshQrButton] = useState(false)
  const qrExpiryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const rechargeProfileLoggedIn = !!token

  /** 主标题：优先展示 username（与 localStorage user-info 一致） */
  const rechargeProfileNameLine = (() => {
    if (!rechargeProfileLoggedIn) return '未登录'
    if (!user) return '用户'
    const name = user.nickName?.trim()
    if (name) return name
    if (user.email?.trim()) return user.email.trim()
    if (user.id) return `ID${user.id}`
    return '用户'
  })()

  /** 悬停完整信息：便于区分同名或核对账号 */
  const rechargeProfileTooltip = (() => {
    if (!user) return ''
    const parts: string[] = []
    if (user.username?.trim()) parts.push(user.username.trim())
    if (user.id) parts.push(`ID${user.id}`)
    if (user.email?.trim()) parts.push(user.email.trim())
    return parts.join(' · ')
  })()

  const selectedPackage = packages.find((p) => p.id === selectedPackageId) ?? null

  /** 扫码弹窗内展示金额（下单后以订单为准） */
  const payModalAmountText = (() => {
    if (currentAmount != null && !Number.isNaN(Number(currentAmount))) {
      return String(currentAmount)
    }
    if (repayOrderRow) {
      return formatPrice(Number(repayOrderRow.payPrice))
    }
    if (selectedPackage) return String(selectedPackage.payPrice)
    return '--'
  })()

  const payModalCreditsDisplay = (() => {
    if (repayOrderRow) {
      const c = repayOrderRow.credits
      const n = Number(c)
      if (!Number.isFinite(n)) return '—'
      return Number.isInteger(n) ? String(n) : formatPrice(n)
    }
    const c = selectedPackage?.credits
    if (c == null) return '—'
    const n = Number(c)
    if (!Number.isFinite(n)) return '—'
    return Number.isInteger(n) ? String(n) : formatPrice(n)
  })()

  const payModalUnitPrice = (() => {
    if (repayOrderRow) {
      return formatPrice(Number(repayOrderRow.payPrice))
    }
    const p = selectedPackage?.payPrice
    if (p == null) return '--'
    return formatPrice(Number(p))
  })()

  function resolveActivePayType(flags: PayFlags): PayChannel | null {
    if (payTypeRef.current === 'alipay' && flags.alipayEnabled) return 'alipay'
    if (payTypeRef.current === 'wxpay' && flags.wxpayEnabled) return 'wxpay'
    return flags.defaultPayType
  }

  function ensurePayTypeSynced(flags: PayFlags) {
    const next = resolveActivePayType(flags)
    if (next) setPayType(next)
  }

  const stopPolling = useCallback(() => {
    pollSessionRef.current += 1
    pollRequestPendingRef.current = false
    if (pollTimerRef.current) {
      clearInterval(pollTimerRef.current)
      pollTimerRef.current = null
    }
  }, [])

  const clearQrExpiryTimer = useCallback(() => {
    if (qrExpiryTimerRef.current) {
      clearTimeout(qrExpiryTimerRef.current)
      qrExpiryTimerRef.current = null
    }
  }, [])

  /** 接口返回的 pendingRemainSeconds 倒计时结束后才显示「刷新二维码」；未返回则默认 180 秒 */
  function scheduleQrExpiry(remainSeconds: number | null | undefined) {
    clearQrExpiryTimer()
    setShowRefreshQrButton(false)
    const sec = remainSeconds != null && remainSeconds > 0 ? remainSeconds : 180
    qrExpiryTimerRef.current = setTimeout(() => {
      setShowRefreshQrButton(true)
      qrExpiryTimerRef.current = null
    }, sec * 1000)
  }

  function handleClose() {
    stopPolling()
    clearQrExpiryTimer()
    setShowRefreshQrButton(false)
    setHighlightOrderNo('')
    setRepayOrderRow(null)
    setShowPayModal(false)
    onOpenChange(false)
  }

  function handlePayModalClose() {
    stopPolling()
    clearQrExpiryTimer()
    setShowRefreshQrButton(false)
    qrSeqRef.current++
    setQrImageSrc('')
    setCurrentOrderNo('')
    setCurrentAmount(null)
    setRepayOrderRow(null)
    setShowPayModal(false)
  }

  function openMemberAgreement() {
    if (!membershipAgreementUrl) {
      message.warning('暂未配置会员协议')
      return
    }
    window.open(membershipAgreementUrl, '_blank', 'noopener,noreferrer')
  }

  async function loadPackages(): Promise<RechargePackageItem[]> {
    try {
      const list = await rechargePackageList()
      setPackages(list)
      return list
    } catch (e: any) {
      message.error(e?.msg ?? e?.message ?? '获取充值套餐失败')
      return packagesRef.current
    }
  }

  function getSelectedPackageLatest(): RechargePackageItem | null {
    return packagesRef.current.find((p) => p.id === selectedPackageIdRef.current) ?? null
  }

  function startPolling() {
    stopPolling()
    const orderNo = currentOrderNoRef.current
    if (!orderNo) return
    const session = pollSessionRef.current
    pollTimerRef.current = setInterval(async () => {
      if (pollRequestPendingRef.current || session !== pollSessionRef.current) return
      pollRequestPendingRef.current = true
      try {
        const order = await rechargeOrderQuery(orderNo)
        if (session !== pollSessionRef.current || currentOrderNoRef.current !== orderNo) return
        setCurrentAmount(order.payPrice)
        if (order.payStatus === 'paid') {
          stopPolling()
          void useUserStore.getState().fetchProfile()
          message.success('充值成功')
          setShowPayModal(false)
          onPaidRef.current?.()
          onOpenChangeRef.current(false)
        } else if (order.payStatus === 'failed' || order.payStatus === 'closed') {
          stopPolling()
          message.warning('订单已失效，请重新发起支付')
        }
      } catch {
        // 轮询时静默，避免刷屏
      } finally {
        if (session === pollSessionRef.current) pollRequestPendingRef.current = false
      }
    }, 2500)
  }

  async function applyQrAndStartPolling(res: RechargeOrderCreateData, seq: number) {
    if (seq !== qrSeqRef.current) return
    setCurrentOrderNo(res.orderNo || '')
    setCurrentAmount(
      res.pendingPayPrice ??
        repayOrderRowRef.current?.payPrice ??
        getSelectedPackageLatest()?.payPrice ??
        null
    )
    setQrImageSrc(res.qrCode ? await payQrToImageSrc(res.qrCode) : '')
    if (seq !== qrSeqRef.current) return
    scheduleQrExpiry(res.pendingRemainSeconds)
    startPolling()
  }

  async function syncQrForSelection() {
    if (!showPayModalRef.current || !selectedPackageIdRef.current) return
    const flags = payFlagsRef.current
    ensurePayTypeSynced(flags)
    const channel = resolveActivePayType(flags)
    if (!channel) {
      message.warning('暂无可用支付方式，请稍后再试')
      return
    }
    const seq = ++qrSeqRef.current
    setCreatingOrder(true)
    try {
      const res = await rechargeOrderCreate({
        packageId: selectedPackageIdRef.current,
        payType: channel
      })
      if (seq !== qrSeqRef.current) return

      if (res.orderNo && res.qrCode) {
        await applyQrAndStartPolling(res, seq)
        return
      }

      if (res.pendingOrderNo) {
        const r2 = await rechargeOrderRepay(res.pendingOrderNo)
        if (seq !== qrSeqRef.current) return
        if (r2.orderNo && r2.qrCode) {
          await applyQrAndStartPolling(r2, seq)
          setShowOrders(false)
          return
        }
      }

      message.warning('未获取到支付二维码，请稍后重试')
      setQrImageSrc('')
    } catch (e: any) {
      if (seq === qrSeqRef.current) {
        if (await tryHandlePendingOrderConflict(e)) {
          setQrImageSrc('')
        } else {
          message.error(e?.msg ?? e?.message ?? '创建订单失败')
          setQrImageSrc('')
        }
      }
    } finally {
      if (seq === qrSeqRef.current) setCreatingOrder(false)
    }
  }

  function openPendingOrderModal(orderNo: string, contentOverride?: string) {
    const content =
      contentOverride?.trim() ||
      `您有一笔待支付订单（${orderNo}），请先完成支付或取消订单后再订阅。`
    Modal.confirm({
      className: 'recharge-confirm-modal',
      wrapClassName: 'create-flow-modal recharge-confirm-wrap',
      title: '待支付订单',
      content,
      okText: '去支付',
      cancelText: '取消',
      centered: true,
      onOk: () => {
        void goToOrderAndHighlight(orderNo)
      }
    })
  }

  /** 返回 true 表示已用 Modal 处理，不应再 toast 报错，且不应打开扫码弹窗 */
  async function tryHandlePendingOrderConflict(e: any): Promise<boolean> {
    if (!isPendingOrderConflictError(e)) return false
    setShowPayModal(false)
    const orderNo = await resolvePendingOrderNoForModal(e)
    const contentBase =
      String(e?.msg ?? '').trim() || '您有待支付订单，请先完成支付或取消后再订阅。'
    if (orderNo) {
      openPendingOrderModal(orderNo, contentBase)
    } else {
      Modal.confirm({
        className: 'recharge-confirm-modal',
        wrapClassName: 'create-flow-modal recharge-confirm-wrap',
        title: '待支付订单',
        content: contentBase,
        okText: '去支付',
        cancelText: '取消',
        centered: true,
        async onOk() {
          setOrderTab('pending')
          setShowOrders(true)
          const rows = await loadOrders('pending')
          await nextFrame()
          const first = rows.find((r) => r.payStatus === 'pending')
          if (first?.orderNo) await goToOrderAndHighlight(first.orderNo)
        }
      })
    }
    return true
  }

  async function goToOrderAndHighlight(orderNo: string) {
    setOrderTab('pending')
    setHighlightOrderNo(orderNo)
    setShowOrders(true)
    await loadOrders('pending')
    await nextFrame()
    const safe =
      typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(orderNo) : orderNo.replace(/"/g, '\\"')
    const el = document.querySelector(`[data-order-no="${safe}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    window.setTimeout(() => {
      setHighlightOrderNo('')
    }, 3500)
  }

  async function handleBuyNow(pkg: RechargePackageItem) {
    const flags = payFlagsRef.current
    ensurePayTypeSynced(flags)
    const channel = resolveActivePayType(flags)
    if (!channel) {
      message.warning('暂无可用支付方式，请稍后再试')
      return
    }
    setSelectedPackageId(pkg.id)
    setPayType(channel)
    setBuyingPackageId(pkg.id)
    try {
      const res = await rechargeOrderCreate({
        packageId: pkg.id,
        payType: channel
      })

      const hasNewQr = !!(res.orderNo && res.qrCode)
      if (res.pendingOrderNo && !hasNewQr) {
        setShowPayModal(false)
        openPendingOrderModal(res.pendingOrderNo)
        return
      }

      if (hasNewQr) {
        setShowPayModal(true)
        await nextFrame()
        const seq = ++qrSeqRef.current
        setCreatingOrder(true)
        try {
          await applyQrAndStartPolling(res, seq)
        } finally {
          setCreatingOrder(false)
        }
        return
      }

      message.warning('未获取到支付信息，请稍后重试')
    } catch (e: any) {
      if (await tryHandlePendingOrderConflict(e)) return
      message.error(e?.msg ?? e?.message ?? '创建订单失败')
    } finally {
      setBuyingPackageId(null)
    }
  }

  async function refreshRepayQr() {
    if (!currentOrderNoRef.current) {
      message.warning('订单信息异常，请关闭后重试')
      return
    }
    const seq = ++qrSeqRef.current
    setCreatingOrder(true)
    try {
      const res = await rechargeOrderRepay(currentOrderNoRef.current)
      if (seq !== qrSeqRef.current) return
      if (res.orderNo && res.qrCode) {
        await applyQrAndStartPolling(res, seq)
        return
      }
      if (res.pendingOrderNo) {
        const r2 = await rechargeOrderRepay(res.pendingOrderNo)
        if (seq !== qrSeqRef.current) return
        if (r2.orderNo && r2.qrCode) {
          await applyQrAndStartPolling(r2, seq)
          return
        }
      }
      message.warning('未获取到支付二维码，请稍后重试')
    } catch (e: any) {
      if (seq === qrSeqRef.current) {
        message.error(e?.msg ?? e?.message ?? '刷新二维码失败')
      }
    } finally {
      if (seq === qrSeqRef.current) setCreatingOrder(false)
    }
  }

  function handleRefreshQr() {
    if (!showPayModalRef.current) {
      message.warning('请先完成支付流程')
      return
    }
    if (repayOrderRowRef.current) {
      void refreshRepayQr()
      return
    }
    if (!selectedPackageIdRef.current) {
      message.warning('请先完成支付流程')
      return
    }
    void syncQrForSelection()
  }

  async function loadOrders(tab: RechargeOrderTabKey): Promise<RechargeOrderRow[]> {
    setOrdersLoading(true)
    try {
      const payStatusMap: Record<RechargeOrderTabKey, string | undefined> = {
        all: undefined,
        pending: 'pending',
        paid: 'paid',
        closed: 'closed'
      }
      const payStatus = payStatusMap[tab]
      const data = await rechargeOrderList({
        pageNum: 1,
        pageSize: 20,
        ...(payStatus ? { payStatus } : {})
      })
      setOrders(data.rows)
      return data.rows
    } catch (e: any) {
      message.error(e?.msg ?? e?.message ?? '获取订单列表失败')
      return ordersRef.current
    } finally {
      setOrdersLoading(false)
    }
  }

  /** 订单列表「立即支付」：与「立即购买」一致，先打开扫码弹窗 → 请求继续支付 → 再展示二维码并轮询 */
  async function handleRepay(row: RechargeOrderRow) {
    const cfg = await loadPublicConfig()
    const flags = payFlagsFromConfig(cfg)
    ensurePayTypeSynced(flags)
    if (!flags.anyPaymentEnabled) {
      message.warning('暂无可用支付方式，请稍后再试')
      return
    }

    setRepayingOrderNo(row.orderNo)
    setRepayOrderRow(row)
    setShowOrders(false)
    setShowPayModal(true)
    await nextFrame()
    const seq = ++qrSeqRef.current
    setCreatingOrder(true)
    try {
      const res = await rechargeOrderRepay(row.orderNo)
      if (seq !== qrSeqRef.current) return
      if (res.orderNo && res.qrCode) {
        await applyQrAndStartPolling(res, seq)
        message.success('已获取支付二维码')
        return
      }
      if (res.pendingOrderNo) {
        const r2 = await rechargeOrderRepay(res.pendingOrderNo)
        if (seq !== qrSeqRef.current) return
        if (r2.orderNo && r2.qrCode) {
          await applyQrAndStartPolling(r2, seq)
          message.success('已获取支付二维码')
          return
        }
      }
      message.warning('未获取到支付二维码，请稍后重试')
      handlePayModalClose()
    } catch (e: any) {
      if (seq === qrSeqRef.current) {
        message.error(e?.msg ?? e?.message ?? '继续支付失败')
        handlePayModalClose()
      }
    } finally {
      if (seq === qrSeqRef.current) setCreatingOrder(false)
      setRepayingOrderNo('')
    }
  }

  function handleCancelOrder(orderNo: string) {
    Modal.confirm({
      className: 'recharge-confirm-modal',
      wrapClassName: 'create-flow-modal recharge-confirm-wrap',
      title: '确认取消支付',
      content: '确定要取消该待支付订单吗？取消后需重新下单支付。',
      okText: '确定',
      cancelText: '取消',
      centered: true,
      async onOk() {
        setCancellingOrderNo(orderNo)
        try {
          await rechargeOrderCancel(orderNo)
          message.success('取消成功')
          await loadOrders(orderTabRef.current)
        } catch (e: any) {
          message.error(e?.msg ?? e?.message ?? '取消订单失败')
          throw e
        } finally {
          setCancellingOrderNo('')
        }
      }
    })
  }

  function handleOrderTabChange(key: RechargeOrderTabKey) {
    setOrderTab(key)
    void loadOrders(key)
  }

  // 对应原 watch(() => props.open, ..., { immediate: true })：打开初始化 / 关闭清理
  useEffect(() => {
    if (!open) {
      stopPolling()
      clearQrExpiryTimer()
      setShowRefreshQrButton(false)
      setHighlightOrderNo('')
      setRepayOrderRow(null)
      qrSeqRef.current++
      setQrImageSrc('')
      setCurrentOrderNo('')
      setCurrentAmount(null)
      setShowPayModal(false)
      return
    }
    void (async () => {
      const cfg = await loadPublicConfig()
      ensurePayTypeSynced(payFlagsFromConfig(cfg))
      void useUserStore.getState().fetchProfile()
      const list = await loadPackages()
      if (list.length) {
        setSelectedPackageId(list[0].id)
      } else {
        setSelectedPackageId(null)
      }
      await loadOrders(orderTabRef.current)
    })()
    // 与原 watch(open) 对齐：仅在 open 变化时触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 对应原 watch(showOrders)：抽屉打开时刷新订单列表
  useEffect(() => {
    if (showOrders) void loadOrders(orderTabRef.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showOrders])

  // 对应原 watch(payType)：扫码弹窗内切换支付方式则按新渠道重新下单出码
  useEffect(() => {
    if (showPayModalRef.current && selectedPackageIdRef.current && !repayOrderRowRef.current) {
      void syncQrForSelection()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payType])

  // 对应原 onBeforeUnmount：卸载时停止轮询与二维码过期定时器
  useEffect(() => {
    return () => {
      stopPolling()
      clearQrExpiryTimer()
    }
  }, [stopPolling, clearQrExpiryTimer])

  const packagesStyle = {
    '--recharge-package-bg-nor': `url("${assetUrl(topUpNor)}")`,
    '--recharge-package-bg-sel': `url("${assetUrl(topUpSel)}")`
  } as CSSProperties

  return (
    <>
      {/* 第一步：选套餐（支付渠道在扫码弹窗内切换） */}
      <Modal
        open={open}
        width={960}
        footer={null}
        centered
        className="recharge-modal recharge-modal-branded recharge-main-modal"
        wrapClassName="recharge-modal-wrap"
        zIndex={1000}
        onCancel={handleClose}
        title={<ModalTitleWatermark title="充值中心" watermark="REFILL" />}
      >
        <div className="recharge-modal__body recharge-modal__body--step1">
          <div
            className={`recharge-modal__profile${
              !rechargeProfileLoggedIn ? ' recharge-modal__profile--guest' : ''
            }`}
          >
            <div className="recharge-modal__profile-avatar" aria-hidden="true">
              <img src={assetUrl(groupAvatar)} alt="" width={48} height={48} />
            </div>
            <div className="recharge-modal__profile-text">
              <div className="recharge-modal__profile-name" title={rechargeProfileTooltip}>
                {rechargeProfileNameLine}
              </div>
              <p className="recharge-modal__profile-desc">购买credits，解锁更多创作能力</p>
            </div>
          </div>

          <div className="recharge-modal__packages" style={packagesStyle}>
            {packages.map((pkg) => (
              <div key={pkg.id} className="recharge-package">
                <div className="recharge-package__inner">
                  <div className="recharge-package__text">
                    <div className="recharge-package__price-block">
                      <span className="recharge-package__price">¥ {formatPrice(pkg.payPrice)}</span>
                      {pkg.originalPrice > pkg.payPrice && (
                        <span className="recharge-package__origin">
                          ¥ {formatPrice(pkg.originalPrice)}
                        </span>
                      )}
                    </div>
                    <span className="recharge-package__points">
                      获得 <span>{formatCreditAmount(pkg.credits)}</span> credits
                    </span>
                    <div className="recharge-package__grow" aria-hidden="true" />
                  </div>
                  <button
                    type="button"
                    className="recharge-package__buy"
                    disabled={buyingPackageId === pkg.id || !anyPaymentEnabled}
                    onClick={() => handleBuyNow(pkg)}
                  >
                    {!anyPaymentEnabled
                      ? '暂不可用'
                      : buyingPackageId === pkg.id
                        ? '处理中…'
                        : '立即购买'}
                  </button>
                </div>
              </div>
            ))}
          </div>

          {!anyPaymentEnabled && (
            <div className="recharge-modal__pay-off" role="status">
              支付通道暂未开放，请稍后再试
            </div>
          )}

          <footer className="recharge-modal__footer-bottom">
            <div className="recharge-modal__footer-links">
              <button
                type="button"
                className="recharge-modal__footer-link"
                onClick={() => setShowOrders(true)}
              >
                订单管理
              </button>
              <span className="recharge-modal__footer-divider" aria-hidden="true">
                |
              </span>
              <button
                type="button"
                className="recharge-modal__footer-link"
                onClick={() => setShowConsume(true)}
              >
                消耗明细
              </button>
            </div>
            <p className="recharge-modal__footer-agree">
              <span className="recharge-modal__footer-agree-text">购买前请阅读并同意</span>
              <button
                type="button"
                className="recharge-modal__footer-agree-link"
                onClick={openMemberAgreement}
              >
                《会员协议》
              </button>
            </p>
          </footer>
        </div>

        <RechargeOrdersDrawer
          open={showOrders}
          onClose={() => setShowOrders(false)}
          orderTab={orderTab}
          onTabChange={handleOrderTabChange}
          orders={orders}
          highlightOrderNo={highlightOrderNo}
          repayingOrderNo={repayingOrderNo}
          cancellingOrderNo={cancellingOrderNo}
          onRepay={(row) => void handleRepay(row)}
          onCancelOrder={handleCancelOrder}
        />

        <RechargeConsumeDrawer open={showConsume} onClose={() => setShowConsume(false)} />
      </Modal>

      {/* 第二步：扫码支付（无标题栏，金额摘要 + 支付方式图标 + 二维码） */}
      <RechargePayModal
        open={showPayModal}
        payType={payType}
        onPayTypeChange={setPayType}
        alipayEnabled={alipayEnabled}
        wxpayEnabled={wxpayEnabled}
        anyPaymentEnabled={anyPaymentEnabled}
        amountText={payModalAmountText}
        creditsDisplay={payModalCreditsDisplay}
        unitPrice={payModalUnitPrice}
        qrImageSrc={qrImageSrc}
        showRefreshQrButton={showRefreshQrButton}
        creatingOrder={creatingOrder}
        onRefreshQr={handleRefreshQr}
        onClose={handlePayModalClose}
      />
    </>
  )
}
export default RechargeModal

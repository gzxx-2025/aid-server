'use client'

import { Modal } from 'antd'
import type { CSSProperties } from 'react'
import { assetUrl } from '~/utils/assetUrl'
/** 扫码弹窗：微信（资源文件名为 wx-icon.svg，与稿 we-icon 对应） */
import iconWxPay from '~/assets/img/icon/wx-icon.svg'
import iconZfg from '~/assets/img/icon/zfg-icon.svg'

/** 第二步扫码支付弹窗蒙层毛玻璃（第一步充值中心不加） */
const rechargePayModalMaskStyle: CSSProperties = {
  WebkitBackdropFilter: 'blur(10px)',
  backdropFilter: 'blur(10px)',
  backgroundColor: 'rgba(8, 12, 22, 0.52)'
}

export interface RechargePayModalProps {
  open: boolean
  payType: 'wxpay' | 'alipay'
  onPayTypeChange: (payType: 'wxpay' | 'alipay') => void
  alipayEnabled: boolean
  wxpayEnabled: boolean
  anyPaymentEnabled: boolean
  /** 支付金额文案（下单后以订单为准） */
  amountText: string
  /** 商品内容 Credits 文案 */
  creditsDisplay: string
  /** 商品单价文案 */
  unitPrice: string
  qrImageSrc: string
  showRefreshQrButton: boolean
  creatingOrder: boolean
  onRefreshQr: () => void
  onClose: () => void
}

/** 第二步：扫码支付（无标题栏，金额摘要 + 支付方式图标 + 二维码） */
export function RechargePayModal({
  open,
  payType,
  onPayTypeChange,
  alipayEnabled,
  wxpayEnabled,
  anyPaymentEnabled,
  amountText,
  creditsDisplay,
  unitPrice,
  qrImageSrc,
  showRefreshQrButton,
  creatingOrder,
  onRefreshQr,
  onClose
}: RechargePayModalProps) {
  return (
    <Modal
      open={open}
      width={520}
      footer={null}
      title={null}
      closable={false}
      centered
      className="recharge-modal recharge-modal-branded recharge-pay-modal"
      wrapClassName="recharge-modal-wrap recharge-pay-modal-wrap"
      styles={{ mask: rechargePayModalMaskStyle }}
      zIndex={1100}
      mask={{ closable: false }}
      onCancel={onClose}
    >
      <div className="recharge-pay-modal__body">
        <button
          type="button"
          className="recharge-pay-modal__close"
          aria-label="关闭"
          onClick={onClose}
        >
          <span aria-hidden="true">×</span>
        </button>

        <div className="recharge-pay-modal__summary">
          <div className="recharge-pay-modal__summary-amount">
            <span className="recharge-pay-modal__summary-label">支付金额：¥</span>
            <span className="recharge-pay-modal__summary-num">{amountText}</span>
            <span className="recharge-pay-modal__summary-yuan">元</span>
          </div>
          <ul className="recharge-pay-modal__summary-list">
            <li>· 商品内容：{creditsDisplay} Credits</li>
            <li>· 商品数量：1个</li>
            <li>· 商品单价：¥{unitPrice}</li>
          </ul>
        </div>

        <div className="recharge-pay-modal__pay-row">
          <span className="recharge-pay-modal__pay-label">支付方式</span>
          {anyPaymentEnabled ? (
            <div className="recharge-pay-modal__methods">
              {alipayEnabled && (
                <button
                  type="button"
                  className={`recharge-pay-method${payType === 'alipay' ? ' recharge-pay-method--active' : ''}`}
                  onClick={() => onPayTypeChange('alipay')}
                >
                  <img
                    src={assetUrl(iconZfg)}
                    alt=""
                    className="recharge-pay-method__icon"
                    width={22}
                    height={22}
                  />
                  支付宝
                </button>
              )}
              {wxpayEnabled && (
                <button
                  type="button"
                  className={`recharge-pay-method${payType === 'wxpay' ? ' recharge-pay-method--active' : ''}`}
                  onClick={() => onPayTypeChange('wxpay')}
                >
                  <img
                    src={assetUrl(iconWxPay)}
                    alt=""
                    className="recharge-pay-method__icon"
                    width={22}
                    height={22}
                  />
                  微信支付
                </button>
              )}
            </div>
          ) : (
            <p className="recharge-pay-modal__pay-unavailable">暂无可用支付方式，请稍后再试</p>
          )}
        </div>

        {anyPaymentEnabled && (
          <p className="recharge-pay-modal__lead-hint">
            {payType === 'wxpay' ? '请使用微信扫码支付' : '请使用支付宝扫码支付'}
          </p>
        )}

        <div
          className={`recharge-modal__qr-wrap recharge-modal__qr-wrap--pay${showRefreshQrButton ? ' recharge-modal__qr-wrap--expired' : ''}`}
        >
          {qrImageSrc ? (
            <>
              <img src={qrImageSrc} alt="支付二维码" className="recharge-modal__qr" />
              <div className="recharge-modal__scan-line" aria-hidden="true" />
              {showRefreshQrButton && (
                <button
                  type="button"
                  className="recharge-pay-qr-refresh-overlay"
                  disabled={creatingOrder}
                  aria-busy={creatingOrder}
                  onClick={onRefreshQr}
                >
                  <span className="recharge-pay-qr-refresh-overlay__text">
                    {creatingOrder ? '刷新中…' : '刷新二维码'}
                  </span>
                </button>
              )}
            </>
          ) : (
            <div className="recharge-modal__qr-empty">
              {creatingOrder ? '正在生成支付二维码…' : '请稍候'}
            </div>
          )}
        </div>

        <p className="recharge-pay-modal__disclaimer">充值包服务属于虚拟商品，不支持无理由退款</p>
      </div>
    </Modal>
  )
}

export default RechargePayModal

'use client'

import { useCallback,useRef,useState } from 'react'
import { resolveClientApiUrl } from '~/utils/api'
import { maybeDecryptApiPayload,prepareEncryptedRequest,shouldEncryptApiPath } from '~/utils/apiCrypto'
import { waitForTacReady } from '~/utils/tacAssets'

declare global {
  interface Window {
    TAC?: new (config: unknown, style?: unknown) => {
      init: () => void
      destroyWindow: () => void
      reloadCaptcha: () => void
    }
    CaptchaConfig?: new (config: unknown) => {
      requestCaptchaData: () => Promise<unknown>
      validCaptcha: (id: string, data: unknown, captcha: unknown, tac: unknown) => void
      domBindEl: unknown
      addRequestChain: (chain: unknown) => void
      insertRequestChain: (index: number, chain: unknown) => void
    }
  }
}

export interface OpenBehaviorCaptchaOptions {
  bindEl: string | HTMLElement
  captchaType?: string | null
  onSuccess: (token: string) => void | Promise<void>
  onCancel?: () => void
}

export interface OpenBehaviorCaptchaResult {
  ok: boolean
  /** onSuccess 内业务请求失败时携带原始错误，供调用方展示提示 */
  error?: unknown
}

/**
 * 打开 tianai 行为验证码（滑块/旋转等），校验成功后回调一次性 captcha-token。
 * 依赖登录页引入的 `/tac/css/tac.css` 与 `/tac/js/tac.min.js`（缺失时由 waitForTacReady 兜底注入）。
 */
export function useBehaviorCaptcha() {
  const [opening, setOpening] = useState(false)
  const openingRef = useRef(false)
  const activeInstanceRef = useRef<{ destroyWindow: () => void } | null>(null)

  const setOpeningState = useCallback((value: boolean) => {
    openingRef.current = value
    setOpening(value)
  }, [])

  const destroyActive = useCallback(() => {
    try {
      activeInstanceRef.current?.destroyWindow()
    } catch {
      /* ignore */
    }
    activeInstanceRef.current = null
  }, [])

  /** 读取最新 opening 值（避免回调闭包读到过期 state） */
  const isOpening = useCallback(() => openingRef.current, [])

  const openBehaviorCaptcha = useCallback(
    async (options: OpenBehaviorCaptchaOptions): Promise<OpenBehaviorCaptchaResult> => {
      if (typeof window === 'undefined') return { ok: false }

      try {
        await waitForTacReady()
      } catch (e: unknown) {
        console.error('[TAC] 静态资源未加载', e)
        return { ok: false }
      }

      setOpeningState(true)
      destroyActive()

      const bindEl =
        typeof options.bindEl === 'string' ? options.bindEl : (options.bindEl as HTMLElement)
      const genUrl = resolveClientApiUrl('/captcha/gen')
      const checkUrl = resolveClientApiUrl('/captcha/check')
      const preferredType = options.captchaType?.trim()

      return new Promise<OpenBehaviorCaptchaResult>((resolve) => {
        let settled = false
        const finish = (result: OpenBehaviorCaptchaResult) => {
          if (settled) return
          settled = true
          setOpeningState(false)
          resolve(result)
        }

        const CaptchaConfigCtor = window.CaptchaConfig
        if (!CaptchaConfigCtor || !window.TAC) {
          finish({ ok: false })
          return
        }

        const baseConfig = {
          bindEl,
          requestCaptchaDataUrl: genUrl,
          validCaptchaUrl: checkUrl,
          timeToTimestamp: true,
          validSuccess: async (
            res: { data?: { token?: string } },
            _c: unknown,
            tac: { destroyWindow: () => void; reloadCaptcha: () => void }
          ) => {
            const token = res?.data?.token
            if (!token) {
              tac.reloadCaptcha()
              return
            }
            tac.destroyWindow()
            activeInstanceRef.current = null
            try {
              await options.onSuccess(token)
              finish({ ok: true })
            } catch (e) {
              finish({ ok: false, error: e })
            }
          },
          validFail: (_res: unknown, _c: unknown, tac: { reloadCaptcha: () => void }) => {
            tac.reloadCaptcha()
          },
          btnRefreshFun: (_el: unknown, tac: { reloadCaptcha: () => void }) => {
            tac.reloadCaptcha()
          },
          btnCloseFun: (_el: unknown, tac: { destroyWindow: () => void }) => {
            tac.destroyWindow()
            activeInstanceRef.current = null
            options.onCancel?.()
            finish({ ok: false })
          }
        }

        const config = new CaptchaConfigCtor(baseConfig) as InstanceType<typeof CaptchaConfigCtor> & {
          doSendRequest: (req: {
            url: string
            method?: string
            headers?: Record<string, string>
            data?: unknown
          }) => Promise<unknown>
        }

        // /captcha/check 在加密开启时走信封协议；TAC 的 preRequest 不支持 async，故包装 doSendRequest
        if (shouldEncryptApiPath('/captcha/check')) {
          const nativeDoSend = config.doSendRequest.bind(config)
          config.doSendRequest = async (req) => {
            if (req.url === checkUrl && String(req.method || 'POST').toUpperCase() === 'POST') {
              const enc = await prepareEncryptedRequest({ body: req.data ?? {} })
              req.headers = { ...(req.headers || {}), ...enc.headers }
              req.data = enc.body
              const raw = await nativeDoSend(req)
              return maybeDecryptApiPayload(raw, enc.aesKey)
            }
            return nativeDoSend(req)
          }
        }

        config.insertRequestChain(0, {
          preRequest(name: string, req: { data?: Record<string, unknown> }) {
            if (name === 'requestCaptchaData' && preferredType) {
              if (!req.data || typeof req.data !== 'object') req.data = {}
              req.data.type = preferredType
            }
            return true
          }
        })

        try {
          // 不传 styleConfig，使用 tac.min.js 内置默认主题与样式
          const tac = new window.TAC!(config)
          activeInstanceRef.current = tac
          tac.init()
        } catch {
          finish({ ok: false })
        }
      })
    },
    [destroyActive, setOpeningState]
  )

  return {
    opening,
    isOpening,
    openBehaviorCaptcha,
    destroyActive
  }
}

'use client'

import { message } from 'antd'
import { useEffect,useRef } from 'react'
import { userVoicePreview } from '~/utils/businessApi'
import { htmlToPlainText } from '~/utils/htmlPlain'
import { resolveVoicePreviewPlayUrl } from '~/utils/voicePreviewPlayUrl'
import { resolveDubbingPanelKey } from './derived'
import { buildTtsPreviewSignature,loadAudioDurationSec } from './helpers'
import type { DubbingModalCtx } from './types'
import { useMirrored } from './useMirrored'

type TtsPreviewCacheEntry = {
  signature: string
  playUrl: string
  durationSec: number
}

async function resolveVoiceModelForPreview(
  voiceLibraryId: number,
  voiceModelId: number,
  timbreCode: string
): Promise<{ modelId: number; timbreCode?: string }> {
  let modelId = voiceModelId > 0 ? voiceModelId : 0
  let resolvedTimbreCode = timbreCode.trim() || undefined
  if (modelId <= 0 && voiceLibraryId > 0) {
    const { userVoiceLibraryList } = await import('~/utils/businessApi')
    const res = await userVoiceLibraryList({ pageNum: 1, pageSize: 200 })
    const hit = res.data.find((row: { id?: number }) => Number(row?.id) === voiceLibraryId)
    modelId = Number((hit as { modelId?: number } | undefined)?.modelId)
    resolvedTimbreCode =
      String((hit as { voiceCode?: string } | undefined)?.voiceCode || '').trim() ||
      resolvedTimbreCode
  }
  if (!Number.isFinite(modelId) || modelId <= 0) {
    throw new Error('音色模型无效，请重新选择音色')
  }
  return { modelId, timbreCode: resolvedTimbreCode }
}

/**
 * 试听（TTS）音频：单实例互斥播放；组件卸载时停止并释放 Audio 实例
 * （参考 hooks/useReferenceAudioPreview 的单实例模式）
 */
export function useDubbingTtsPreview(
  ctx: DubbingModalCtx,
  options: { voicePreviewEstimatedMaxChars: number }
) {
  const loadingByPanelKey = useMirrored<Record<string, boolean>>({})
  const playingByPanelKey = useMirrored<Record<string, boolean>>({})
  const durationByPanelKey = useMirrored<Record<string, number | null>>({})
  const cacheByPanelKeyRef = useRef<Record<string, TtsPreviewCacheEntry>>({})
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const audioPanelKeyRef = useRef<string | null>(null)

  const optionsRef = useRef(options)
  optionsRef.current = options

  function stopTtsPreviewPlayback() {
    if (audioRef.current) {
      audioRef.current.pause()
      audioRef.current.onended = null
      audioRef.current.onerror = null
      audioRef.current.removeAttribute('src')
      audioRef.current.load()
      audioRef.current = null
    }
    if (audioPanelKeyRef.current) {
      const next = { ...playingByPanelKey.get() }
      delete next[audioPanelKeyRef.current]
      playingByPanelKey.set(next)
      audioPanelKeyRef.current = null
    }
  }

  async function playTtsPreviewUrl(panelKey: string, playUrl: string, durationSec: number) {
    stopTtsPreviewPlayback()
    playingByPanelKey.set({ ...playingByPanelKey.get(), [panelKey]: true })
    audioPanelKeyRef.current = panelKey
    durationByPanelKey.set({ ...durationByPanelKey.get(), [panelKey]: durationSec })
    const play = new Audio(playUrl)
    audioRef.current = play
    play.onended = () => stopTtsPreviewPlayback()
    play.onerror = () => {
      stopTtsPreviewPlayback()
      message.warning('试听播放失败')
    }
    try {
      await play.play()
    } catch {
      stopTtsPreviewPlayback()
      message.warning('自动播放被浏览器拦截，请再点击一次试听')
    }
  }

  async function onPreviewListen() {
    const S = ctx.state
    const panelKey = resolveDubbingPanelKey(ctx, S.currentSceneIndex.get())
    if (loadingByPanelKey.get()[panelKey]) return
    const plain = htmlToPlainText(S.draftDialogue.get()).trim()
    if (!plain) {
      message.warning('请输入内容')
      return
    }
    const previewMaxChars = optionsRef.current.voicePreviewEstimatedMaxChars
    if (plain.length > previewMaxChars) {
      message.warning(`试听仅支持前${previewMaxChars}字`)
      return
    }
    const lid = S.draftVoiceLibraryId.get()
    const voiceModelId = S.draftVoiceModelId.get()
    if ((!lid || lid <= 0) && (!voiceModelId || voiceModelId <= 0)) {
      message.warning('请选择音色')
      return
    }
    const previewText = plain
    const timbreCode = S.draftTimbreCode.get().trim()
    const signature = buildTtsPreviewSignature(previewText, lid, voiceModelId, timbreCode)
    const cached = cacheByPanelKeyRef.current[panelKey]
    if (cached?.signature === signature && cached.playUrl) {
      await playTtsPreviewUrl(panelKey, cached.playUrl, cached.durationSec)
      return
    }

    loadingByPanelKey.set({ ...loadingByPanelKey.get(), [panelKey]: true })
    durationByPanelKey.set({ ...durationByPanelKey.get(), [panelKey]: null })
    try {
      const { modelId, timbreCode: resolvedTimbreCode } = await resolveVoiceModelForPreview(
        lid,
        voiceModelId,
        timbreCode
      )

      const preview = await userVoicePreview({
        text: previewText,
        voiceModelId: modelId,
        timbreCode: resolvedTimbreCode
      })
      const playUrl = resolveVoicePreviewPlayUrl(preview)
      if (!playUrl) throw new Error('未返回试听音频')

      let sec: number
      const durationMs = Number(preview.durationMs)
      if (Number.isFinite(durationMs) && durationMs > 0) {
        sec = durationMs / 1000
      } else {
        sec = await loadAudioDurationSec(playUrl)
      }

      cacheByPanelKeyRef.current = {
        ...cacheByPanelKeyRef.current,
        [panelKey]: { signature, playUrl, durationSec: sec }
      }
      await playTtsPreviewUrl(panelKey, playUrl, sec)
    } catch (e: unknown) {
      const err = e as { message?: string; msg?: string }
      message.error(err?.message || err?.msg || '试听失败，请稍后重试')
    } finally {
      const next = { ...loadingByPanelKey.get() }
      delete next[panelKey]
      loadingByPanelKey.set(next)
    }
  }

  /** 弹窗打开时清空试听状态（原 watch(open) 分支） */
  function resetTtsPreviewState() {
    loadingByPanelKey.set({})
    playingByPanelKey.set({})
    durationByPanelKey.set({})
    cacheByPanelKeyRef.current = {}
  }

  // 组件卸载：停止并释放 Audio 实例
  useEffect(() => {
    return () => {
      stopTtsPreviewPlayback()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const currentPanelKey = resolveDubbingPanelKey(ctx, ctx.state.currentSceneIndex.get())
  const ttsPreviewLoading = !!loadingByPanelKey.value[currentPanelKey]
  const ttsPreviewPlaying = !!playingByPanelKey.value[currentPanelKey]
  const ttsPreviewDurationSec = durationByPanelKey.value[currentPanelKey] ?? null

  return {
    ttsPreviewLoading,
    ttsPreviewPlaying,
    ttsPreviewDurationSec,
    onPreviewListen,
    stopTtsPreviewPlayback,
    resetTtsPreviewState
  }
}

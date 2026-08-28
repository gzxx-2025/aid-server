'use client'

/**
 * 参考音频单实例试听（素材条 / 已导入素材 / 音色 Tab 共用）
 */
import { message } from 'antd'
import { useCallback,useEffect,useRef,useState } from 'react'

export function useReferenceAudioPreview() {
  const [playingId, setPlayingIdState] = useState<string | null>(null)
  const playingIdRef = useRef<string | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)

  const setPlayingId = useCallback((v: string | null) => {
    playingIdRef.current = v
    setPlayingIdState(v)
  }, [])

  const ensureAudio = useCallback((): HTMLAudioElement => {
    if (!audioRef.current && typeof window !== 'undefined') {
      const audio = new Audio()
      audio.addEventListener('ended', () => {
        setPlayingId(null)
      })
      audio.addEventListener('pause', () => {
        if (audioRef.current && !audioRef.current.ended && audioRef.current.paused) {
          /* 手动 pause 时由调用方清 playingId */
        }
      })
      audioRef.current = audio
    }
    if (!audioRef.current) {
      throw new Error('Audio unavailable')
    }
    return audioRef.current
  }, [setPlayingId])

  const stop = useCallback(() => {
    const audio = audioRef.current
    if (audio) {
      audio.pause()
      audio.removeAttribute('src')
      try {
        audio.load()
      } catch {
        /* ignore */
      }
    }
    setPlayingId(null)
  }, [setPlayingId])

  const play = useCallback(
    async (url: string, id: string) => {
      const src = String(url || '').trim()
      if (!src) {
        message.warning('暂无可试听地址')
        return
      }
      if (playingIdRef.current === id) {
        stop()
        return
      }
      try {
        const a = ensureAudio()
        stop()
        setPlayingId(id)
        a.src = src
        await a.play()
      } catch {
        setPlayingId(null)
        message.warning('试听加载失败，请检查网络或文件地址')
      }
    },
    [ensureAudio, setPlayingId, stop]
  )

  const isPlaying = useCallback((id: string): boolean => {
    return playingIdRef.current === id
  }, [])

  useEffect(() => {
    return () => stop()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return {
    playingId,
    play,
    stop,
    isPlaying
  }
}

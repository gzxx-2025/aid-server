'use client'

import { useEffect, useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { Modal, Slider, message } from 'antd'
import { CloseOutlined, CloudUploadOutlined, AudioMutedOutlined } from '@ant-design/icons'
import iconStartRaw from '~/assets/img/icon/icon_start.svg'
import musicIconRaw from '~/assets/img/icon/music-nor.svg'
import { assetUrl } from '~/utils/assetUrl'
import { userAssetOfficialQuery } from '~/utils/businessApi'
import { uploadAudioToOssWithToast } from '~/utils/ossUpload'
import type { UserAssetOfficialRow } from '~/types/business-api'
import './MusicPickerModal.css'

const iconStartUrl = assetUrl(iconStartRaw)
const musicIconUrl = assetUrl(musicIconRaw)

export type MusicLibraryItem = {
  id: string
  name: string
  audioUrl: string
  coverUrl?: string
}

export type MusicPickerConfirmPayload =
  | { type: 'none'; volume: number }
  | { type: 'library'; id: string; name: string; url: string; volume: number }
  | { type: 'local'; name: string; url: string; volume: number }

const NO_MUSIC_ID = '__none__'

export interface MusicPickerModalProps {
  open: boolean
  initialMusicName?: string
  /** 0~2，与时间轴音量一致 */
  initialVolume?: number
  onOpenChange: (open: boolean) => void
  onConfirm: (payload: MusicPickerConfirmPayload) => void
}

function volumeToPercent(volume: number) {
  return Math.max(0, Math.min(100, Math.round((volume / 2) * 100)))
}

function percentToVolume(percent: number) {
  return Math.max(0, Math.min(2, Number(((percent / 100) * 2).toFixed(2))))
}

function resolveBgmAudioUrl(row: UserAssetOfficialRow): string {
  const prompt = String(row.promptText || '').trim()
  const image = String(row.imageUrl || '').trim()
  if (/^https?:\/\//i.test(prompt) && /\.(mp3|wav|m4a|aac|ogg|flac)(\?|$)/i.test(prompt)) return prompt
  if (/^https?:\/\//i.test(prompt) && !/\.(png|jpe?g|webp|gif|svg)(\?|$)/i.test(prompt)) return prompt
  if (/^https?:\/\//i.test(image) && /\.(mp3|wav|m4a|aac|ogg|flac)(\?|$)/i.test(image)) return image
  if (/^https?:\/\//i.test(image) && !/\.(png|jpe?g|webp|gif|svg)(\?|$)/i.test(image)) return image
  return prompt || image
}

function resolveBgmCoverUrl(row: UserAssetOfficialRow, audioUrl: string): string | undefined {
  const image = String(row.imageUrl || '').trim()
  if (!image || image === audioUrl) return undefined
  if (/\.(png|jpe?g|webp|gif|svg)(\?|$)/i.test(image)) return image
  return undefined
}

export function MusicPickerModal({
  open,
  initialMusicName,
  initialVolume,
  onOpenChange,
  onConfirm
}: MusicPickerModalProps) {
  const [musicLibrary, setMusicLibrary] = useState<MusicLibraryItem[]>([])
  const [, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const uploadingRef = useRef(false)
  const [selectedId, setSelectedId] = useState<string>(NO_MUSIC_ID)
  const [playingId, setPlayingIdState] = useState<string | null>(null)
  const playingIdRef = useRef<string | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [volumePercent, setVolumePercent] = useState(13)
  const volumePercentRef = useRef(volumePercent)
  volumePercentRef.current = volumePercent

  function setPlayingId(v: string | null) {
    playingIdRef.current = v
    setPlayingIdState(v)
  }

  async function loadMusicLibrary(): Promise<MusicLibraryItem[]> {
    setLoading(true)
    try {
      const rows = await userAssetOfficialQuery({ assetType: 'bgm' })
      const list = rows
        .map((row) => {
          const audioUrl = resolveBgmAudioUrl(row)
          if (!audioUrl) return null
          return {
            id: String(row.id),
            name: row.assetName || '未命名音乐',
            audioUrl,
            coverUrl: resolveBgmCoverUrl(row, audioUrl)
          } satisfies MusicLibraryItem
        })
        .filter(Boolean) as MusicLibraryItem[]
      setMusicLibrary(list)
      return list
    } catch {
      setMusicLibrary([])
      return []
    } finally {
      setLoading(false)
    }
  }

  function stopAudio() {
    const a = audioRef.current
    if (a) {
      a.pause()
      a.src = ''
    }
    setPlayingId(null)
  }

  function emitConfirm(payload: MusicPickerConfirmPayload) {
    stopAudio()
    onConfirm(payload)
    onOpenChange(false)
  }

  function confirmNoMusic() {
    setSelectedId(NO_MUSIC_ID)
    emitConfirm({ type: 'none', volume: percentToVolume(volumePercentRef.current) })
  }

  function confirmMusic(item: MusicLibraryItem) {
    setSelectedId(item.id)
    emitConfirm({
      type: 'library',
      id: item.id,
      name: item.name,
      url: item.audioUrl,
      volume: percentToVolume(volumePercentRef.current)
    })
  }

  function onPreviewClick(item: MusicLibraryItem) {
    const a = audioRef.current
    if (!a) return
    if (playingIdRef.current === item.id) {
      a.pause()
      setPlayingId(null)
      return
    }
    stopAudio()
    setPlayingId(item.id)
    a.src = item.audioUrl
    a.volume = Math.max(0, Math.min(1, volumePercentRef.current / 100))
    a.play().catch(() => {
      message.warning('试听加载失败，请检查网络或替换为有效音频地址')
      setPlayingId(null)
    })
  }

  function onAudioEnded() {
    setPlayingId(null)
  }

  function triggerUpload() {
    if (uploadingRef.current) return
    fileInputRef.current?.click()
  }

  async function onFileSelected(e: ChangeEvent<HTMLInputElement>) {
    const input = e.target
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    if (!file.type.startsWith('audio/') && !/\.(mp3|wav|m4a|aac|ogg|flac)$/i.test(file.name)) {
      message.warning('请选择音频文件')
      return
    }
    // 必须走 OSS 上传拿到可持久化的 CDN URL；blob: 地址落库后会被 @MediaUrl 错误拼成 cdn/blob:...
    uploadingRef.current = true
    setUploading(true)
    try {
      const url = await uploadAudioToOssWithToast(file)
      if (!url) return
      setSelectedId(`local-${Date.now()}`)
      emitConfirm({
        type: 'local',
        name: file.name,
        url,
        volume: percentToVolume(volumePercentRef.current)
      })
    } finally {
      uploadingRef.current = false
      setUploading(false)
    }
  }

  useEffect(() => {
    if (open) {
      stopAudio()
      setVolumePercent(volumeToPercent(typeof initialVolume === 'number' ? initialVolume : 0.25))
      loadMusicLibrary().then((list) => {
        const name = initialMusicName?.trim()
        if (!name || name === '无音乐') {
          setSelectedId(NO_MUSIC_ID)
          return
        }
        const found = list.find((x) => x.name === name)
        setSelectedId(found?.id ?? NO_MUSIC_ID)
      })
    } else {
      stopAudio()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function onCancel() {
    stopAudio()
    onOpenChange(false)
  }

  useEffect(() => {
    return () => stopAudio()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <Modal
      open={open}
      width={860}
      footer={null}
      destroyOnHidden
      wrapClassName="create-flow-modal music-picker-wrap"
      className="music-picker-modal"
      centered
      zIndex={11000}
      onCancel={onCancel}
      closeIcon={<CloseOutlined className="music-picker-close" />}
    >
      <div className="music-picker-shell">
        <h2 className="music-picker-title">背景音乐</h2>

        <div className="music-picker-list">
          <div
            className={`music-card music-card-none${selectedId === NO_MUSIC_ID ? ' is-selected' : ''}`}
          >
            <div className="music-card-icon-wrap music-card-icon-wrap-none">
              <AudioMutedOutlined className="music-card-none-icon" />
            </div>
            <div className="music-card-text">
              <div className="music-card-name">无音乐</div>
              <div className="music-card-tags">不添加背景音乐</div>
            </div>
            <button
              type="button"
              className={`music-card-select-btn${selectedId === NO_MUSIC_ID ? ' is-active' : ''}`}
              onClick={(e) => {
                e.stopPropagation()
                confirmNoMusic()
              }}
            >
              选择
            </button>
          </div>

          {musicLibrary.map((item) => (
            <div
              key={item.id}
              className={`music-card${selectedId === item.id ? ' is-selected' : ''}`}
            >
              <div
                className={`music-card-icon-wrap${playingId === item.id ? ' is-audio-playing' : ''}`}
                onClick={(e) => {
                  e.stopPropagation()
                  onPreviewClick(item)
                }}
              >
                {item.coverUrl ? (
                  <img className="music-card-cover" src={item.coverUrl} alt={item.name} />
                ) : (
                  <img className="music-card-cover music-card-cover-fallback" src={musicIconUrl} alt="" />
                )}
                <div className="music-card-icon-mask" />
                {playingId === item.id ? (
                  <div className="music-card-play-inner music-card-pause">
                    <span className="pause-bars" />
                  </div>
                ) : (
                  <div className="music-card-play-inner">
                    <img className="music-card-play-icon" src={iconStartUrl} alt="试听" width={24} height={24} />
                  </div>
                )}
              </div>
              <div className="music-card-text">
                <div className="music-card-name">{item.name}</div>
                <div className="music-card-tags">官方音乐库</div>
              </div>
              <button
                type="button"
                className={`music-card-select-btn${selectedId === item.id ? ' is-active' : ''}`}
                onClick={(e) => {
                  e.stopPropagation()
                  confirmMusic(item)
                }}
              >
                选择
              </button>
            </div>
          ))}
        </div>

        <div className="music-picker-footer">
          <div className="music-picker-volume-row">
            <span className="music-picker-volume-label">音量</span>
            <Slider
              value={volumePercent}
              onChange={(v: number) => setVolumePercent(v)}
              min={0}
              max={100}
              step={1}
              className="music-picker-volume-slider"
            />
            <span className="music-picker-volume-value">{volumePercent}%</span>
          </div>
          <button
            type="button"
            className="music-picker-upload"
            disabled={uploading}
            onClick={triggerUpload}
          >
            <CloudUploadOutlined className="music-picker-upload-icon" />
            <span className="music-picker-upload-text">{uploading ? '上传中…' : '上传本地音频'}</span>
          </button>
          <input
            ref={fileInputRef}
            className="music-picker-file-input"
            type="file"
            accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac"
            disabled={uploading}
            onChange={onFileSelected}
          />
        </div>
      </div>

      <audio ref={audioRef} className="music-picker-audio" onEnded={onAudioEnded} />
    </Modal>
  )
}

export default MusicPickerModal

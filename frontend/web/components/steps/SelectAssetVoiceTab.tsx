'use client'

import { Spin } from 'antd'
import { useEffect,useState } from 'react'
import iconStartRaw from '~/assets/img/icon/icon_start.svg'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { useReferenceAudioPreview } from '~/composables/useReferenceAudioPreview'
import { assetUrl } from '~/utils/assetUrl'
import { userVoiceLibraryList } from '~/utils/businessApi'
import './SelectAssetVoiceTab.css'
const iconStartUrl = assetUrl(iconStartRaw)

export type OfficialVoicePick = {
  id: string
  name: string
  gender: string
  ageLabel: string
  avatar: string
  previewUrl: string
  voiceLibraryId?: number
}

export interface SelectAssetVoiceTabProps {
  open: boolean
  /** 已选用的官方音色 id 集合（用于按钮态） */
  selectedIds: Set<string>
  onSelect: (voice: OfficialVoicePick) => void
}

const GENDER_MAP: Record<string, string> = { female: '女性', male: '男性', neutral: '中性' }
const AGE_MAP: Record<string, string> = {
  child: '儿童',
  teen: '少年',
  young: '青年',
  adult: '成年',
  middle: '中年',
  elderly: '老年'
}

export function SelectAssetVoiceTab({ open, selectedIds, onSelect }: SelectAssetVoiceTabProps) {
  const [voices, setVoices] = useState<OfficialVoicePick[]>([])
  const [loading, setLoading] = useState(false)
  const { playingId, play, stop } = useReferenceAudioPreview()

  async function loadVoices() {
    setLoading(true)
    try {
      const res = await userVoiceLibraryList({ pageNum: 1, pageSize: 100 })
      const rows = Array.isArray(res?.data) ? res.data : []
      setVoices(
        rows.map((item: any) => ({
          id: String(item.id),
          name: item.voiceName || '未命名',
          gender: GENDER_MAP[item.gender] || item.gender || '未知',
          ageLabel: AGE_MAP[item.ageRange] || item.ageRange || '未知',
          avatar: item.avatarUrl || `https://api.dicebear.com/7.x/avataaars/png?seed=${item.id}`,
          previewUrl: item.sampleUrl || '',
          voiceLibraryId: Number(item.id) > 0 ? Number(item.id) : undefined
        }))
      )
    } catch {
      setVoices([])
    } finally {
      setLoading(false)
    }
  }

  function onAvatarClick(v: OfficialVoicePick) {
    void play(v.previewUrl, v.id)
  }

  function handleSelect(v: OfficialVoicePick) {
    onSelect(v)
  }

  useEffect(() => {
    if (open) {
      stop()
      void loadVoices()
    } else {
      stop()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  return (
    <div className="saim-voice-tab">
      {loading ? (
        <div className="saim-voice-empty">
          <Spin size="large" />
          <p className="saim-voice-empty__text">正在加载官方音色…</p>
        </div>
      ) : voices.length === 0 ? (
        <div className="saim-voice-empty">
          <p className="saim-voice-empty__text">暂无官方音色</p>
        </div>
      ) : (
        <div className="saim-voice-list">
          {voices.map((v) => (
            <div
              key={v.id}
              className={`saim-voice-card${selectedIds.has(v.id) ? ' is-selected' : ''}`}
            >
              <div
                className={`saim-voice-card__avatar-wrap${playingId === v.id ? ' is-audio-playing' : ''}`}
                onClick={(e) => {
                  e.stopPropagation()
                  onAvatarClick(v)
                }}
              >
                <ShimmerImage
                  src={v.avatar}
                  alt={v.name}
                  imgClass="saim-voice-card__avatar"
                  wrapperClass="saim-voice-card__shimmer"
                  objectFit="cover"
                  revealDirection="fade"
                  minShimmerMs={280}
                />
                <div className="saim-voice-card__avatar-mask" />
                {playingId === v.id ? (
                  <div className="saim-voice-card__play-inner saim-voice-card__pause">
                    <span className="saim-voice-card__eq" aria-hidden="true">
                      <span className="saim-eq-bar saim-eq-bar-1" />
                      <span className="saim-eq-bar saim-eq-bar-2" />
                      <span className="saim-eq-bar saim-eq-bar-3" />
                    </span>
                  </div>
                ) : (
                  <div className="saim-voice-card__play-inner">
                    <img className="saim-voice-card__play-icon" src={iconStartUrl} alt="试听" width={24} height={24} />
                  </div>
                )}
              </div>
              <div className="saim-voice-card__text">
                <div className="saim-voice-card__name">{v.name}</div>
                <div className="saim-voice-card__tags">
                  {v.gender}/{v.ageLabel}
                </div>
              </div>
              <button
                type="button"
                className={`saim-voice-card__select-btn${selectedIds.has(v.id) ? ' is-active' : ''}`}
                onClick={(e) => {
                  e.stopPropagation()
                  handleSelect(v)
                }}
              >
                {selectedIds.has(v.id) ? '已选择' : '选择TA'}
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default SelectAssetVoiceTab

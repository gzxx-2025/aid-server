'use client'

import { useEffect,useRef } from 'react'
import { useCreationStore } from '~/stores/creation'
import { userEpisodeDetail } from '~/utils/businessApi'
import { applyEpisodeRowToCreationStore } from '~/utils/hydrateCreationStoreFromProjectDetail'
import { isProjectPublished } from '~/utils/projectAudit'
type PreviewPublicationStateOptions = {
  pageReady: boolean
  isPreviewStep: boolean
}

/**
 * 成品预览的审核/发布状态入口（原 composables/usePreviewPublicationState.ts）。
 * 项目公开态来自 project/detail；剧集审核态在进入预览时按 episode/detail 恢复，保证刷新后仍可靠。
 */
export function usePreviewPublicationState(options: PreviewPublicationStateOptions) {
  const currentProjectIsPublic = useCreationStore((s) => s.currentProjectIsPublic)
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const currentEpisodeStatus = useCreationStore((s) => s.currentEpisodeStatus)
  const currentProjectStatus = useCreationStore((s) => s.currentProjectStatus)
  const currentEpisodeStatusReason = useCreationStore((s) => s.currentEpisodeStatusReason)
  const currentProjectStatusReason = useCreationStore((s) => s.currentProjectStatusReason)

  const episodeLoadGenerationRef = useRef(0)
  const loadedEpisodeKeyRef = useRef('')

  const isPublished = isProjectPublished(currentProjectIsPublic)

  const auditFailureReason = (() => {
    if (!options.isPreviewStep) return ''
    const isSeries = currentProjectType === 'series'
    const status = isSeries ? currentEpisodeStatus : currentProjectStatus
    if (status !== 5) return ''
    const reason = isSeries ? currentEpisodeStatusReason : currentProjectStatusReason
    return String(reason || '').trim() || '未提供具体失败原因'
  })()

  const { pageReady, isPreviewStep } = options
  useEffect(() => {
    const generation = ++episodeLoadGenerationRef.current
    if (!pageReady || !isPreviewStep || currentProjectType !== 'series') return
    const pid = Number(currentProjectId)
    const eid = Number(currentEpisodeId)
    if (!Number.isFinite(pid) || pid <= 0 || !Number.isFinite(eid) || eid <= 0) return

    const episodeKey = `${pid}:${eid}`
    if (loadedEpisodeKeyRef.current === episodeKey) return
    loadedEpisodeKeyRef.current = episodeKey

    void userEpisodeDetail({ id: eid })
      .then((episode) => {
        if (generation !== episodeLoadGenerationRef.current) return
        const store = useCreationStore.getState()
        if (
          store.currentProjectId !== pid ||
          store.currentEpisodeId !== eid ||
          store.currentProjectType !== 'series'
        ) {
          return
        }
        applyEpisodeRowToCreationStore(store, episode)
      })
      .catch(() => {
        if (
          generation === episodeLoadGenerationRef.current &&
          loadedEpisodeKeyRef.current === episodeKey
        ) {
          loadedEpisodeKeyRef.current = ''
        }
      })
  }, [pageReady, isPreviewStep, currentProjectType, currentProjectId, currentEpisodeId])

  return {
    isPublished,
    auditFailureReason
  }
}

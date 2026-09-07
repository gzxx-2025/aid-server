'use client'

import { useEffect, useState } from 'react'
import { useCreationStore } from '@/stores/creation'
import { resolveStudioPreferredModelCode } from '@/utils/studio/studioComposerDefaultModel'

/** 按生成配置 / 智能体默认解析 Composer 优先模型编码。 */
export function useStudioPreferredModelCode(sceneCode?: string | null): string {
  const projectId = useCreationStore((state) => state.currentProjectId)
  const episodeId = useCreationStore((state) => state.currentEpisodeId)
  const [preferredModelCode, setPreferredModelCode] = useState('')

  useEffect(() => {
    let active = true
    const code = String(sceneCode || '').trim()
    const pid = Number(projectId)
    if (!code || !Number.isFinite(pid) || pid <= 0) {
      setPreferredModelCode('')
      return () => { active = false }
    }
    void resolveStudioPreferredModelCode({
      projectId: pid,
      episodeId,
      sceneCode: code
    }).then((next) => {
      if (active) setPreferredModelCode(next)
    })
    return () => { active = false }
  }, [episodeId, projectId, sceneCode])

  return preferredModelCode
}

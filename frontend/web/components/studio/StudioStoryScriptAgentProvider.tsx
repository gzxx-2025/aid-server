'use client'

import { createContext, useContext, useMemo, type ReactNode } from 'react'
import { useStoryScriptAgent } from '@/hooks/useStoryScriptAgent'
import { useCreationStore } from '@/stores/creation'
import { useStudioFlowRuntime } from './flow/StudioFlowRuntimeProvider'

export type StudioStoryScriptAgentController = ReturnType<typeof useStoryScriptAgent>

export interface StudioStoryScriptAgentContextValue {
  projectId: number
  episodeId: number | null
  agent: StudioStoryScriptAgentController
}

const StudioStoryScriptAgentContext = createContext<StudioStoryScriptAgentContextValue | null>(null)

export function StudioStoryScriptAgentProvider({ children }: { children: ReactNode }) {
  const runtime = useStudioFlowRuntime()
  const globalSetting = useCreationStore((state) => state.formData.globalSetting)
  const workTitle = useCreationStore((state) => state.workTitle)
  const projectId = runtime?.projectId ?? null
  const episodeId = runtime?.episodeId ?? null
  const projectStyle = useMemo(() => {
    const selectedName = String(globalSetting.selectedStyle?.name || '').trim()
    return [selectedName || globalSetting.style, globalSetting.selectedStyle?.promptText]
      .map((value) => String(value || '').trim())
      .filter(Boolean)
      .join('\n')
      .slice(0, 2000)
  }, [globalSetting.selectedStyle?.name, globalSetting.selectedStyle?.promptText, globalSetting.style])
  const agent = useStoryScriptAgent({
    projectId,
    episodeId,
    projectTitle: workTitle || globalSetting.title || '未命名作品',
    projectStyle,
    enabled: Boolean(runtime && !runtime.readOnly),
    catalogScope: 'all'
  })
  const value = useMemo<StudioStoryScriptAgentContextValue | null>(
    () => runtime ? { projectId: runtime.projectId, episodeId: runtime.episodeId, agent } : null,
    [agent, runtime]
  )

  return (
    <StudioStoryScriptAgentContext.Provider value={value}>
      {children}
    </StudioStoryScriptAgentContext.Provider>
  )
}

export function useStudioStoryScriptAgent(): StudioStoryScriptAgentContextValue | null {
  return useContext(StudioStoryScriptAgentContext)
}

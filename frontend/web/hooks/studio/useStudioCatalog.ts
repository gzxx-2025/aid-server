'use client'

import { useCallback, useEffect, useState } from 'react'
import type { AgentInfoVO, UserModelListItem } from '@/types/business-api'
import type { StudioMediaKind } from '@/types/studio'
import type { UserSkillDefinition } from '@/types/user-skill'
import { aidAgentList, userModelList } from '@/utils/business/model'
import { userSkillRuntimeCatalog } from '@/utils/business/skill'

type CatalogState<T> = { data: T[]; loading: boolean; error: string }

const modelCache = new Map<StudioMediaKind, UserModelListItem[]>()
const modelInflight = new Map<StudioMediaKind, Promise<UserModelListItem[]>>()
let skillCache: UserSkillDefinition[] | null = null
let skillInflight: Promise<UserSkillDefinition[]> | null = null
let agentCache: AgentInfoVO[] | null = null
let agentInflight: Promise<AgentInfoVO[]> | null = null

function loadModels(mediaKind: StudioMediaKind): Promise<UserModelListItem[]> {
  if (process.env.NODE_ENV === 'test') return Promise.resolve([])
  const cached = modelCache.get(mediaKind)
  if (cached) return Promise.resolve(cached)
  const running = modelInflight.get(mediaKind)
  if (running) return running
  const request = userModelList({ modelType: mediaKind })
    .then((items) => {
      modelCache.set(mediaKind, items)
      return items
    })
    .finally(() => modelInflight.delete(mediaKind))
  modelInflight.set(mediaKind, request)
  return request
}

function loadSkills(): Promise<UserSkillDefinition[]> {
  if (process.env.NODE_ENV === 'test') return Promise.resolve([])
  if (skillCache) return Promise.resolve(skillCache)
  if (skillInflight) return skillInflight
  skillInflight = userSkillRuntimeCatalog()
    .then((items) => {
      skillCache = items
      return items
    })
    .finally(() => { skillInflight = null })
  return skillInflight
}

function loadAgents(): Promise<AgentInfoVO[]> {
  if (process.env.NODE_ENV === 'test') return Promise.resolve([])
  if (agentCache) return Promise.resolve(agentCache)
  if (agentInflight) return agentInflight
  agentInflight = aidAgentList()
    .then((groups) => {
      agentCache = groups.flatMap((group) => group.agents ?? [])
        .filter((agent) => agent.agentCode && agent.name)
      return agentCache
    })
    .finally(() => { agentInflight = null })
  return agentInflight
}

function useCatalog<T>(
  loader: () => Promise<T[]>,
  initial: T[] = [],
  enabled = true
): CatalogState<T> {
  const [state, setState] = useState<CatalogState<T>>({
    data: initial,
    loading: enabled,
    error: ''
  })
  useEffect(() => {
    if (!enabled) return
    let active = true
    Promise.resolve().then(() => {
      if (active) setState((current) => ({ ...current, loading: true, error: '' }))
      return loader()
    }).then((data) => {
      if (active) setState({ data, loading: false, error: '' })
    }).catch(() => {
      if (active) setState({ data: [], loading: false, error: '数据加载失败' })
    })
    return () => { active = false }
  }, [enabled, loader])
  return state
}

export function useStudioModels(
  mediaKind: StudioMediaKind = 'image',
  options?: { enabled?: boolean }
): CatalogState<UserModelListItem> {
  const loader = useCallback(() => loadModels(mediaKind), [mediaKind])
  return useCatalog(loader, modelCache.get(mediaKind) ?? [], options?.enabled !== false)
}

export function useStudioSkills(): CatalogState<UserSkillDefinition> {
  return useCatalog(loadSkills, skillCache ?? [])
}

export function useStudioAgents(): CatalogState<AgentInfoVO> {
  return useCatalog(loadAgents, agentCache ?? [])
}

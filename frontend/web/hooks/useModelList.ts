'use client'

import { useRef,useState } from 'react'
import type { AiModelType,UserModelListItem } from '~/types/business-api'
import { userModelList,userModelListByFuncCodes } from '~/utils/businessApi'
import { modelsFromListByFuncGroups,pickFirstNonEmptyModelPool,uniqueTrimmedCodes } from '~/utils/modelListByFuncBatch'

interface UseModelListOptions<T> {
  modelType?: AiModelType
  /** model/list 生成模式细分，如 image_to_video */
  generateMode?: string
  /** v2.34.0：按功能编码查询模型列表，优先级高于 modelType */
  funcCode?: string
  /** funcCode 无数据时依次尝试的备选编码 */
  funcCodeFallbacks?: readonly string[]
  /** 创作流 scope：专业版出片池重映射 */
  projectId?: number | (() => number | null | undefined)
  episodeId?: number | (() => number | null | undefined)
  fallback?: T[]
  mapItem?: (item: UserModelListItem) => T
  onError?: (error: unknown) => void
  keepFallbackOnEmpty?: boolean
}

/**
 * 原 Vue 版返回可写 ref（modelList / rawModelList）；React 版以 ref+state 镜像实现，
 * setModelList / setRawModelList 供调用方（如批量 listByFunc 预取）imperative 回填，
 * getModelList / getRawModelList 供事件回调同步读取最新值。
 */
export function useModelList<T = UserModelListItem>(options: UseModelListOptions<T>) {
  const {
    modelType,
    generateMode,
    funcCode,
    funcCodeFallbacks = [],
    projectId,
    episodeId,
    fallback = [],
    mapItem,
    onError,
    keepFallbackOnEmpty = true
  } = options

  const [modelList, setModelListState] = useState<T[]>(() => [...fallback])
  const modelListRef = useRef<T[]>(modelList)
  const setModelList = (v: T[]) => {
    modelListRef.current = v
    setModelListState(v)
  }
  const getModelList = () => modelListRef.current

  /** listByFunc 原始列表，供 capability 解析 */
  const [rawModelList, setRawModelListState] = useState<UserModelListItem[]>([])
  const rawModelListRef = useRef<UserModelListItem[]>(rawModelList)
  const setRawModelList = (v: UserModelListItem[]) => {
    rawModelListRef.current = v
    setRawModelListState(v)
  }
  const getRawModelList = () => rawModelListRef.current

  const [loading, setLoading] = useState(false)

  function resolveScope() {
    const pid = typeof projectId === 'function' ? projectId() : projectId
    const eid = typeof episodeId === 'function' ? episodeId() : episodeId
    const project = Number(pid)
    if (!Number.isFinite(project) || project <= 0) return undefined
    const out: { projectId: number; episodeId?: number } = { projectId: project }
    if (eid != null && Number.isFinite(Number(eid))) out.episodeId = Number(eid)
    return out
  }

  async function loadByFuncCodes(codes: readonly string[]): Promise<UserModelListItem[]> {
    const unique = uniqueTrimmedCodes(codes)
    if (!unique.length) return []
    try {
      const groups = await userModelListByFuncCodes(unique, resolveScope())
      if (funcCode) {
        const primary = modelsFromListByFuncGroups(groups, funcCode)
        if (primary.length > 0) return primary
      }
      return pickFirstNonEmptyModelPool(groups, unique)
    } catch {
      return []
    }
  }

  async function loadModels() {
    setLoading(true)
    try {
      let list: UserModelListItem[] = []
      if (funcCode || funcCodeFallbacks.length > 0) {
        const codes = [
          ...(funcCode ? [funcCode] : []),
          ...funcCodeFallbacks.filter((c) => c !== funcCode)
        ]
        list = await loadByFuncCodes(codes)
      }
      if (list.length === 0 && modelType) {
        list = await userModelList({
          modelType,
          ...(generateMode ? { generateMode } : {})
        })
      } else if (list.length === 0 && !funcCode && funcCodeFallbacks.length === 0) {
        list = await userModelList({
          ...(modelType ? { modelType } : {}),
          ...(generateMode ? { generateMode } : {})
        })
      }

      setRawModelList(list)
      const mapped = mapItem ? list.map(mapItem) : (list as unknown as T[])
      if (mapped.length > 0) {
        setModelList(mapped)
      } else if (!keepFallbackOnEmpty) {
        setModelList([])
        setRawModelList([])
      }
      return mapped
    } catch (e: unknown) {
      if (fallback.length > 0) {
        setModelList([...fallback])
      }
      setRawModelList([])
      onError?.(e)
      return [...modelListRef.current]
    } finally {
      setLoading(false)
    }
  }

  return {
    modelList,
    setModelList,
    getModelList,
    rawModelList,
    setRawModelList,
    getRawModelList,
    loading,
    loadModels
  }
}

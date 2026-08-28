'use client'

import { userModelList,userModelListByFunc } from '~/utils/businessApi'
import { resolveFormImageModelCodeForTab } from '~/utils/extractAgentBiz'
import {
FORM_GENERATE_SCENE_CODE_BY_TYPE,
FORM_IMAGE_SCENE_CODE_BY_TYPE,
resolveProjectGenImageSubmitFields,
resolveProjectGenLlmSubmitFields
} from '~/utils/projectGenConfig'
import type { ScpCtx,TabKey } from './types'

/** 批量弹窗 resolution 归一化为接口档位（1K / 2K / 4K） */
export function normalizeImageResolution(raw: string | undefined | null): string | undefined {
  const s = String(raw || '').trim()
  if (!s) return undefined
  const lower = s.toLowerCase()
  if (lower === '1k') return '1K'
  if (lower === '2k') return '2K'
  if (lower === '4k') return '4K'
  return s
}

export interface ScpSubmitFieldsApi {
  /** 形态图生图（image）modelCode：仅读本作品分桶的 extractImageModelCodes */
  resolveStoredExtractImageModelCode: (tab: TabKey) => string | undefined
  resolveSubmitImageModelCode: (tab: TabKey, explicitFromModal?: string) => Promise<string | undefined>
  /** 形态文案 form/generate：读项目生成配置（main_*_form），不用并行提取的 extractModelCodes */
  resolveFormTextSubmitFields: (tab: TabKey) => Promise<{ agentCode: string; modelCode?: string }>
  resolveFormImageApiSubmitFields: (
    tab: TabKey,
    opts?: { modelFromModal?: string; resolutionFromModal?: string; agentFromModal?: string }
  ) => Promise<{ agentCode: string; modelCode?: string; resolution?: string; aspectRatio?: string }>
  ensureDefaultTextModelCode: () => Promise<string>
}

export function useScpSubmitFields(ctx: ScpCtx): ScpSubmitFieldsApi {
  /** 形态图生图（image）modelCode：仅读本作品分桶的 extractImageModelCodes */
  function resolveStoredExtractImageModelCode(tab: TabKey): string | undefined {
    const code = String(ctx.store().extractImageModelCodes[tab] || '').trim()
    return code || undefined
  }

  async function resolveSubmitImageModelCode(
    tab: TabKey,
    explicitFromModal?: string
  ): Promise<string | undefined> {
    const raw =
      String(explicitFromModal || '').trim() || resolveStoredExtractImageModelCode(tab) || ''
    return resolveFormImageModelCodeForTab(tab, raw, userModelListByFunc)
  }

  /** 形态文案 form/generate：读项目生成配置（main_*_form），不用并行提取的 extractModelCodes */
  async function resolveFormTextSubmitFields(tab: TabKey) {
    const projectId = ctx.resolveCurrentProjectId()
    const sceneCode = FORM_GENERATE_SCENE_CODE_BY_TYPE[tab]
    const fields = await resolveProjectGenLlmSubmitFields(projectId, sceneCode)
    const agentCode = fields.agentCode
    if (!agentCode) {
      const label = tab === 'scene' ? '场景' : tab === 'character' ? '角色' : '道具'
      throw new Error(`请先在「生成配置」中为「${label}形态」配置智能体`)
    }
    return {
      agentCode,
      ...(fields.modelCode ? { modelCode: fields.modelCode } : {})
    }
  }

  async function resolveFormImageApiSubmitFields(
    tab: TabKey,
    opts?: { modelFromModal?: string; resolutionFromModal?: string; agentFromModal?: string }
  ) {
    const projectId = ctx.resolveCurrentProjectId()
    const sceneCode = FORM_IMAGE_SCENE_CODE_BY_TYPE[tab]
    // 仅批量弹窗显式选模时覆盖；自动生成/重新生成必须走「生成配置」，
    // 禁止用 extractImageModelCodes 历史值盖住用户刚保存的模型。
    const manualModel = String(opts?.modelFromModal || '').trim()
    const validatedModel = manualModel
      ? await resolveSubmitImageModelCode(tab, manualModel)
      : undefined
    const fields = await resolveProjectGenImageSubmitFields(projectId, sceneCode, {
      ...(validatedModel ? { modelCode: validatedModel } : {}),
      ...(opts?.resolutionFromModal
        ? { resolution: normalizeImageResolution(opts.resolutionFromModal) }
        : {})
    })
    let agentCode = fields.agentCode
    if (!agentCode) {
      agentCode = String(opts?.agentFromModal || '').trim()
    }
    if (!agentCode) {
      const label = tab === 'scene' ? '场景' : tab === 'character' ? '角色' : '道具'
      throw new Error(`请先在「生成配置」中为「${label}图」配置智能体`)
    }
    return {
      agentCode,
      ...(fields.modelCode ? { modelCode: fields.modelCode } : {}),
      ...(fields.resolution ? { resolution: fields.resolution } : {}),
      ...(fields.aspectRatio ? { aspectRatio: fields.aspectRatio } : {})
    }
  }

  async function ensureDefaultTextModelCode(): Promise<string> {
    if (ctx.defaultTextModelCode.get()) return ctx.defaultTextModelCode.get()
    const list = await userModelList({ modelType: 'text' })
    const first = list[0]
    if (!first?.modelCode) {
      throw new Error('未获取到可用文本模型，请先在模型管理中配置 text 模型')
    }
    ctx.defaultTextModelCode.set(first.modelCode)
    return ctx.defaultTextModelCode.get()
  }

  return {
    resolveStoredExtractImageModelCode,
    resolveSubmitImageModelCode,
    resolveFormTextSubmitFields,
    resolveFormImageApiSubmitFields,
    ensureDefaultTextModelCode
  }
}

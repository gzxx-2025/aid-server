'use client'

import { getRouteLikeSnapshot } from '~/composables/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { UserStoryboardUpdateRequest } from '~/types/business-api'
import {
userStoryboardCreate,
userStoryboardDelete,
userStoryboardSort,
userStoryboardUpdate
} from '~/utils/businessApi'
import { htmlToPlainPreserveLineBreaks } from '~/utils/htmlPlain'
import { buildStoryboardCreateRequest } from '~/utils/storyboardCreateRequest'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
/剧集上下文时提示（分镜工作台接口均依赖 projectId + episodeId） */
export const STORYBOARD_WORKBENCH_NEED_PROJECT_MSG =
  '缺少项目信息，请从「我的作品」打开作品后再操作分镜'

/** 删除分镜接口单次最多条数（与 `接口.md` 一致） */
export const STORYBOARD_DELETE_MAX_BATCH = 200

/** 分镜 id 为纯数字字符串时表示已落库，可调用工作台 delete/save/sort */
export function parseServerStoryboardId(panelId: string): number | null {
  if (!panelId || typeof panelId !== 'string') return null
  if (!/^\d+$/.test(panelId)) return null
  const n = Number(panelId)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** 富文本 HTML → 接口 storyScript 纯文本；全空返回 undefined（可选字段不传） */
export function scriptHtmlToStoryScriptApi(html: string | undefined): string | undefined {
  if (html == null) return undefined
  const t = htmlToPlainPreserveLineBreaks(html).trim()
  return t.length > 0 ? t : undefined
}

/**
 * 分镜工作台：新增 / 删除 / 保存 / 排序（与 `接口.md` 分镜模块一致）
 */
export function useStoryboardWorkbenchMutations() {
  async function getProjectEpisodeContext() {
    // 原 vue-router useRoute() 为响应式对象（调用时读到最新 query）；
    // React 侧在调用时取路由快照 + Zustand 最新 state（含 setCurrentProjectType action），
    // 避免回调闭包持有过期的 episodeId / projectId 快照
    return resolveStoryScriptSaveContext(useCreationStore.getState(), getRouteLikeSnapshot())
  }

  async function createRemote(title?: string, sourceStoryboardId?: number) {
    const ctx = await getProjectEpisodeContext()
    if (!ctx) return null
    return userStoryboardCreate(buildStoryboardCreateRequest(ctx, title, sourceStoryboardId))
  }

  async function deleteRemote(panelId: string) {
    const id = parseServerStoryboardId(panelId)
    if (id == null) return
    await userStoryboardDelete({ ids: [id] })
  }

  /** 批量软删除已落库分镜；按 200 条分批请求，返回实际删除总条数 */
  async function deleteRemoteBatch(panelIds: string[]): Promise<number> {
    const parsedIds = panelIds.map(parseServerStoryboardId)
    if (parsedIds.some((id) => id == null)) {
      throw new Error('存在未同步的分镜，请刷新后重试')
    }
    const ids = [...new Set(parsedIds.filter((id): id is number => id != null))]
    if (ids.length === 0) return 0
    let total = 0
    for (let i = 0; i < ids.length; i += STORYBOARD_DELETE_MAX_BATCH) {
      const chunk = ids.slice(i, i + STORYBOARD_DELETE_MAX_BATCH)
      total += await userStoryboardDelete({ ids: chunk })
    }
    return total
  }

  async function saveRemote(payload: UserStoryboardUpdateRequest) {
    await userStoryboardUpdate(payload)
  }

  /** 列表项均为服务端 id 时，按当前顺序提交 sortedIds */
  async function sortRemoteToMatchPanels(panels: StoryboardPanel[]) {
    const ids = panels.map((p) => parseServerStoryboardId(p.id)).filter((x): x is number => x != null)
    if (ids.length === 0 || ids.length !== panels.length) return
    await userStoryboardSort({ sortedIds: ids })
  }

  /** 组装 save 请求（含 sortOrder） */
  function buildSavePayload(panel: StoryboardPanel, index: number): UserStoryboardUpdateRequest | null {
    const id = parseServerStoryboardId(panel.id)
    if (id == null) return null
    const body: UserStoryboardUpdateRequest = {
      id,
      sortOrder: index + 1,
      title: panel.title
    }
    const story = scriptHtmlToStoryScriptApi(panel.scriptContent)
    if (story !== undefined) body.storyScript = story
    const d = panel.dialogueText != null ? String(panel.dialogueText).trim() : ''
    if (d) body.dialogueText = d
    return body
  }

  return {
    getProjectEpisodeContext,
    createRemote,
    deleteRemote,
    deleteRemoteBatch,
    saveRemote,
    sortRemoteToMatchPanels,
    buildSavePayload,
    parseServerStoryboardId,
    scriptHtmlToStoryScriptApi
  }
}

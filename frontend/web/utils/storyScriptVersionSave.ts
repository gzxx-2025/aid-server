'use client'

import { Modal } from 'antd'
import type { ScriptDetailByProjectRequest, ScriptDetailRow } from '~/types/business-api'
import { userScriptDetailByProject, userScriptSave } from '~/utils/businessApi'
import {
  clearStoryScriptLocalSnapshot,
  flushStoryScriptAutoSave,
  getStoryScriptWriteBaseline,
  isStoryScriptConflictError,
  setStoryScriptServerBaseline
} from '~/utils/storyScriptPersistence'

function chooseConflictResolution(): Promise<'keep-local' | 'use-server'> {
  return new Promise((resolve) => {
    Modal.confirm({
      title: '剧本内容冲突',
      content: '服务器上的剧本已在其他页面更新，请选择保留当前内容重新保存，或加载服务器内容。',
      okText: '保留本地并重试',
      cancelText: '加载服务器内容',
      closable: false,
      maskClosable: false,
      onOk: () => resolve('keep-local'),
      onCancel: () => resolve('use-server')
    })
  })
}

/**
 * 显式保存新版本。发生冲突时基于服务器最新基线重试，或按用户选择加载服务器内容。
 */
export async function saveStoryScriptAsNewVersion(
  ctx: ScriptDetailByProjectRequest,
  originalText: string,
  applyServerContent: (row: ScriptDetailRow | null) => void
): Promise<ScriptDetailRow | null> {
  const flushResult = await flushStoryScriptAutoSave(ctx)
  if (flushResult === 'conflict') return null
  if (flushResult === 'error') throw new Error('当前修改尚未同步')

  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const row = await userScriptSave({
        ...ctx,
        originalText,
        ...getStoryScriptWriteBaseline(ctx)
      })
      setStoryScriptServerBaseline(ctx, row)
      clearStoryScriptLocalSnapshot(ctx)
      return row
    } catch (error: unknown) {
      if (!isStoryScriptConflictError(error)) throw error

      const latest = await userScriptDetailByProject(ctx, { force: true })
      const latestText = String(latest?.originalText ?? '')
      setStoryScriptServerBaseline(ctx, latest)
      if (latestText === originalText) {
        // 正文相同仍需重试 /save，确保显式保存确实产生新版本。
        continue
      }

      const choice = await chooseConflictResolution()
      if (choice === 'use-server') {
        clearStoryScriptLocalSnapshot(ctx)
        applyServerContent(latest)
        return null
      }
    }
  }
  throw new Error('剧本内容持续冲突')
}

import type { StoryScriptSaveStatus } from '~/utils/storyScriptPersistence'

/** 剧本编辑器右下角自动保存状态文案 */
export function formatStoryScriptAutosaveHint(
  status: StoryScriptSaveStatus,
  lastSavedAt: number | null
): string {
  if (status === 'dirty' || status === 'saving') return '自动保存中'
  if (status === 'error') return '保存失败'
  if (status === 'conflict') return '内容冲突'
  if (!lastSavedAt) return '已保存'
  const time = new Date(lastSavedAt).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
  return `${time} 已保存`
}

'use client'

import { useCallback } from 'react'
import { message, Modal } from 'antd'
import type { EditorTextSelection } from '@/utils/quill/editorTextSelection'
import { useCreationStore } from '@/stores/creation'
import {
  htmlPureTextCharCount,
  htmlToPlainText,
  isHtmlContentEmpty,
  scriptApiTextToEditorHtml,
  STORY_SCRIPT_MAX_CHARS_MOVIE,
  STORY_SCRIPT_MAX_CHARS_SERIES
} from '@/utils/htmlPlain'
import { saveStoryScriptAsNewVersion } from '@/utils/storyScriptVersionSave'
import { selectStudioFlowStepNode } from '@/utils/studio/studioFlowNodeSelection'
import { dispatchStudioFlowAutoPipelinePrepare } from '@/utils/studio/studioFlowAutoPipelineEvents'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'

export function useStudioFlowApplyScript(projectId: number | null, episodeId: number | null) {
  const runtime = useStudioFlowRuntime()

  return useCallback(
    (raw: string, _references?: EditorTextSelection[]) => {
      if (!projectId) {
        message.warning('缺少项目信息，无法带入剧本')
        return
      }
      const nextHtml = scriptApiTextToEditorHtml(raw)
      if (!nextHtml) {
        message.warning('Agent 文档暂无可带入内容')
        return
      }
      const store = useCreationStore.getState()
      const projectType = store.currentProjectType
      const maxLength =
        projectType === 'series'
          ? STORY_SCRIPT_MAX_CHARS_SERIES
          : STORY_SCRIPT_MAX_CHARS_MOVIE
      if (htmlPureTextCharCount(nextHtml) > maxLength) {
        message.warning(`生成内容超过当前剧本 ${maxLength.toLocaleString('zh-CN')} 字上限，请先精简`)
        return
      }

      const apply = async () => {
        const scopeEpisodeId = projectType === 'movie' ? 0 : Number(episodeId ?? store.currentEpisodeId ?? 0)
        const originalText = htmlToPlainText(nextHtml)
        try {
          await saveStoryScriptAsNewVersion(
            { projectId, episodeId: scopeEpisodeId },
            originalText,
            (row) => {
              if (row?.originalText) {
                store.updateFormData({
                  storyScript: { content: scriptApiTextToEditorHtml(String(row.originalText)) }
                })
              } else {
                store.updateFormData({ storyScript: { content: nextHtml } })
              }
            }
          )
          await runtime?.refresh({ taskIntent: 'read' })
          selectStudioFlowStepNode('story-script')
          dispatchStudioFlowAutoPipelinePrepare({
            projectId,
            episodeId: scopeEpisodeId
          })
          message.success('剧本已带入画布，请在对话面板确认后开始全流程创作')
        } catch (error) {
          message.error(error instanceof Error ? error.message : '剧本保存失败，请重试')
        }
      }

      const currentContent = store.formData.storyScript?.content
      if (isHtmlContentEmpty(currentContent)) {
        void apply()
        return
      }
      Modal.confirm({
        title: '替换当前剧本？',
        content: '带入 Agent 文档会覆盖当前集/作品的剧本内容，并在画布上创建或更新剧本节点。',
        okText: '确认带入',
        cancelText: '取消',
        className: 'create-flow-modal',
        onOk: () => apply()
      })
    },
    [episodeId, projectId, runtime]
  )
}

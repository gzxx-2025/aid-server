'use client'

/** 原 pages/create/story-script.vue（definePageMeta layout:'create'）：剧本创作步骤薄页面 */

import { Suspense, useMemo, useRef, useState } from 'react'
import { message, Modal } from 'antd'
import { StoryScript, type StoryScriptHandle } from '~/components/steps/StoryScript'
import StoryScriptAgentPanel from '~/components/steps/story-script-agent/StoryScriptAgentPanel'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useStoryScriptAgent } from '~/hooks/useStoryScriptAgent'
import { useCreationStore } from '~/stores/creation'
import {
  htmlToPlainText,
  htmlPureTextCharCount,
  isHtmlContentEmpty,
  scriptApiTextToEditorHtml,
  STORY_SCRIPT_MAX_CHARS_MOVIE,
  STORY_SCRIPT_MAX_CHARS_SERIES
} from '~/utils/htmlPlain'
import {
  formatEditorSelectionLocation,
  type EditorTextReplacement,
  type EditorTextSelection
} from '~/utils/quill/editorTextSelection'
import {
  editorTextSelectionKey,
  normalizeStoryScriptAgentReferences,
  parseStoryScriptAgentReplacements,
  storyScriptAgentReferencesOverlap
} from '~/utils/storyScriptAgentReference'

function StoryScriptStepClient() {
  const storyScriptRef = useRef<StoryScriptHandle | null>(null)
  const [agentReferenceState, setAgentReferenceState] = useState<{
    projectId: number | null
    episodeId: number | null
    selections: EditorTextSelection[]
  } | null>(null)
  const route = useRouteLike()
  const content = useCreationStore((s) => s.formData.storyScript.content)
  const globalSetting = useCreationStore((s) => s.formData.globalSetting)
  const currentProjectId = useCreationStore((s) => s.currentProjectId)
  const currentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const workTitle = useCreationStore((s) => s.workTitle)
  const routeProjectId = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  const projectId =
    Number.isFinite(routeProjectId) && routeProjectId > 0
      ? routeProjectId
      : currentProjectId && currentProjectId > 0
        ? currentProjectId
        : null
  const storeMatchesProject = projectId != null && currentProjectId === projectId
  const routeEpisodeId = Number(route.query.episodeId)
  const episodeId =
    storeMatchesProject && currentProjectType === 'movie'
      ? 0
      : route.query.episodeId != null && Number.isFinite(routeEpisodeId) && routeEpisodeId >= 0
        ? routeEpisodeId
        : storeMatchesProject && currentEpisodeId != null && currentEpisodeId > 0
          ? currentEpisodeId
          : null
  const agentReferences =
    agentReferenceState?.projectId === projectId && agentReferenceState.episodeId === episodeId
      ? agentReferenceState.selections
      : []
  const projectStyle = useMemo(
    () => {
      const selectedName = String(globalSetting.selectedStyle?.name || '').trim()
      return [selectedName || globalSetting.style, globalSetting.selectedStyle?.promptText]
        .map((value) => String(value || '').trim())
        .filter(Boolean)
        .join('\n')
        .slice(0, 2000)
    },
    [globalSetting.selectedStyle?.name, globalSetting.selectedStyle?.promptText, globalSetting.style]
  )
  const agent = useStoryScriptAgent({
    projectId,
    episodeId,
    projectTitle: workTitle || globalSetting.title || '未命名作品',
    projectStyle
  })

  function onUpdate(v: string) {
    useCreationStore.getState().updateFormData({ storyScript: { content: v } })
  }

  function addAgentReference(selection: EditorTextSelection) {
    const existing = agentReferences
    if (
      existing.some(
        (reference) => editorTextSelectionKey(reference) === editorTextSelectionKey(selection)
      )
    ) {
      agent.setOpen(true)
      return
    }
    if (existing.some((reference) => storyScriptAgentReferencesOverlap(reference, selection))) {
      message.warning('该选段与已有批注重叠，请重新选择不重叠的内容')
      return
    }
    setAgentReferenceState({
      projectId,
      episodeId,
      selections: normalizeStoryScriptAgentReferences([...existing, selection])
    })
    agent.setOpen(true)
  }

  function applyAgentScript(raw: string, references?: EditorTextSelection[]) {
    const normalizedReferences = normalizeStoryScriptAgentReferences(references)
    if (normalizedReferences.length) {
      let replacements: EditorTextReplacement[]
      if (normalizedReferences.length === 1) {
        replacements = [
          {
            selection: normalizedReferences[0],
            replacement: htmlToPlainText(scriptApiTextToEditorHtml(raw))
          }
        ]
      } else {
        const parsed = parseStoryScriptAgentReplacements(raw, normalizedReferences.length)
        if (!parsed) {
          message.warning('Agent 未返回完整的多批注修改结果，请补充要求后重新生成')
          return
        }
        replacements = parsed.map((item) => ({
          selection: normalizedReferences[item.referenceIndex],
          replacement: htmlToPlainText(scriptApiTextToEditorHtml(item.replacement))
        }))
      }
      const applySelections = () => {
        const result =
          storyScriptRef.current?.applyAgentSelectionEdits(replacements) ?? 'unavailable'
        if (result === 'applied') {
          message.success(
            normalizedReferences.length > 1
              ? `已应用 ${normalizedReferences.length} 个剧本批注`
              : '已替换所选剧本段落'
          )
          return
        }
        if (result === 'stale') {
          message.warning('至少一个原选段已发生变化，请重新选择后再带入')
          return
        }
        if (result === 'limit') {
          message.warning('替换后将超过当前剧本字数上限')
          return
        }
        if (result === 'empty') {
          message.warning('Agent 暂未返回可替换内容')
          return
        }
        message.warning('编辑器尚未就绪，请稍后重试')
      }
      Modal.confirm({
        title:
          normalizedReferences.length > 1
            ? `应用 ${normalizedReferences.length} 个剧本批注？`
            : '替换选中的剧本段落？',
        content:
          normalizedReferences.length > 1
            ? '应用前会逐一核对所有选段原文；任一选段发生变化时，本次不会修改任何内容。'
            : `${formatEditorSelectionLocation(normalizedReferences[0])}，替换前会核对原文，避免覆盖已经修改的内容。`,
        okText: '确认替换',
        cancelText: '取消',
        onOk: applySelections
      })
      return
    }
    const nextHtml = scriptApiTextToEditorHtml(raw)
    if (!nextHtml) {
      message.warning('Agent 文档暂无可带入内容')
      return
    }
    const maxLength =
      currentProjectType === 'series'
        ? STORY_SCRIPT_MAX_CHARS_SERIES
        : STORY_SCRIPT_MAX_CHARS_MOVIE
    if (htmlPureTextCharCount(nextHtml) > maxLength) {
      message.warning(`生成内容超过当前剧本 ${maxLength.toLocaleString('zh-CN')} 字上限，请先精简`)
      return
    }
    const apply = () => {
      onUpdate(nextHtml)
      message.success('已带入当前剧本')
    }
    if (isHtmlContentEmpty(content)) {
      apply()
      return
    }
    Modal.confirm({
      title: '替换当前剧本？',
      content: '带入 Agent 文档会覆盖编辑器内已有内容。',
      okText: '确认带入',
      cancelText: '取消',
      onOk: apply
    })
  }

  return (
    <div className="story-script-agent-layout">
      <div className="story-script-agent-layout__editor">
        <StoryScript
          ref={storyScriptRef}
          value={content}
          onChange={onUpdate}
          agentOpen={agent.open}
          onAgentToggle={() => agent.setOpen(!agent.open)}
          onAgentReference={addAgentReference}
        />
      </div>
      <StoryScriptAgentPanel
        runtimeFeedbackEnabled
        key={`${projectId || 'no-project'}:${episodeId ?? 'no-episode'}`}
        open={agent.open}
        onClose={() => agent.setOpen(false)}
        skills={agent.skills}
        selectedSkillCode={agent.selectedSkillCode}
        onSkillChange={agent.selectSkill}
        skillsLoading={agent.skillsLoading}
        skillsError={agent.skillsError}
        onSkillsRequest={() => { void agent.loadSkills() }}
        messages={agent.messages}
        messagesLoading={agent.messagesLoading}
        hasOlderMessages={agent.hasOlderMessages}
        olderMessagesLoading={agent.olderMessagesLoading}
        onLoadOlderMessages={agent.loadOlderMessages}
        conversationScopeKey={`${projectId || 'no-project'}:${episodeId ?? 'no-episode'}:${agent.selectedSkillCode || 'no-skill'}`}
        sending={agent.sending}
        paused={agent.paused}
        statusText={agent.statusText}
        lastError={agent.lastError}
        canRetry={agent.canRetry}
        canStop={agent.canStop}
        stopping={agent.stopping}
        onRetry={agent.retry}
        onStop={() => void agent.stop()}
        onPauseReceiving={agent.pauseReceiving}
        onResumeReceiving={agent.resumeReceiving}
        onSend={agent.send}
        onSubmitInputRequest={agent.submitInputRequest}
        references={agentReferences}
        onReferencesChange={(selections) =>
          setAgentReferenceState(
            selections.length
              ? {
                  projectId,
                  episodeId,
                  selections: normalizeStoryScriptAgentReferences(selections)
                }
              : null
          )
        }
        onApplyScript={applyAgentScript}
      />
    </div>
  )
}

export default function StoryScriptStepPage() {
  return (
    <Suspense fallback={null}>
      <StoryScriptStepClient />
    </Suspense>
  )
}

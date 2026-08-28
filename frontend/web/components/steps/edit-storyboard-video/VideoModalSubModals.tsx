'use client'

import { ImportScriptModal } from '../ImportScriptModal'
import { SelectAssetImageModal } from '../SelectAssetImageModal'
import { StoryboardScriptModal } from '../StoryboardScriptModal'
import type { VideoModalCtx } from './types'

interface SubModalsProps {
  ctx: VideoModalCtx
  currentProjectId: number | null | undefined
  currentEpisodeId: number | null | undefined
  onSaveScript: (payload: { title: string; content: string }) => void
  onScriptTitleChange: (title: string) => void
}

/** 弹窗内嵌套的选择/导入/脚本子弹窗（原模板尾部各子弹窗段） */
export function VideoModalSubModals({
  ctx,
  currentProjectId,
  currentEpisodeId,
  onSaveScript,
  onScriptTitleChange
}: SubModalsProps) {
  const projectId = Number(currentProjectId) || 0
  const episodeId = Number(currentEpisodeId) || 0

  return (
    <>
      {/* 首尾帧生视频：首帧/尾帧图片选择 */}
      <SelectAssetImageModal
        open={ctx.selectEdgeFrameModalOpen.value}
        type="reference"
        enableVoiceTab
        videoModel={ctx.activeVideoRawModel()}
        projectId={projectId}
        episodeId={episodeId}
        stepTabName={ctx.referenceStepTabName()}
        stepPanelImages={ctx.currentPanelStoryboardImages()}
        storyboardScriptGroups={ctx.storyboardScriptAssetGroups()}
        onOpenChange={(v) => ctx.selectEdgeFrameModalOpen.set(v)}
        onConfirm={ctx.onSelectEdgeFrameConfirm}
      />
      {/* 图生视频：导入参考图弹窗 */}
      <SelectAssetImageModal
        open={ctx.selectReferenceModalOpen.value}
        type="reference"
        enableVoiceTab
        videoModel={ctx.activeVideoRawModel()}
        projectId={projectId}
        episodeId={episodeId}
        stepTabName={ctx.referenceStepTabName()}
        stepPanelImages={ctx.currentPanelStoryboardImages()}
        storyboardScriptGroups={ctx.storyboardScriptAssetGroups()}
        onOpenChange={(v) => ctx.selectReferenceModalOpen.set(v)}
        onConfirm={ctx.onSelectReferenceConfirm}
      />
      {/* 多参生视频：场景/角色/道具/其他 资产选择弹窗 */}
      <SelectAssetImageModal
        open={ctx.selectAssetModalOpen.value}
        type={ctx.selectAssetModalType.value}
        stepTabName={ctx.referenceStepTabName()}
        stepPanelImages={ctx.currentPanelStoryboardImages()}
        storyboardScriptGroups={ctx.storyboardScriptAssetGroups()}
        onOpenChange={(v) => ctx.selectAssetModalOpen.set(v)}
        onConfirm={ctx.onSelectAssetConfirm}
      />
      {/* 多参生视频：导入参考图（场景/角色/道具分类选择） */}
      <SelectAssetImageModal
        open={ctx.selectMultiParamReferenceModalOpen.value}
        type="multiParamReference"
        enableVoiceTab
        videoModel={ctx.activeVideoRawModel()}
        projectId={projectId}
        episodeId={episodeId}
        stepTabName={ctx.referenceStepTabName()}
        stepPanelImages={ctx.currentPanelStoryboardImages()}
        storyboardScriptGroups={ctx.storyboardScriptAssetGroups()}
        onOpenChange={(v) => ctx.selectMultiParamReferenceModalOpen.set(v)}
        onConfirm={ctx.onSelectMultiParamReferenceConfirm}
      />
      {/* 资源库导入视频（沿用 ImportScriptModal，仅支持视频，非视频提示文件类型错误） */}
      <ImportScriptModal
        open={ctx.showVideoLibraryModal.value}
        title="导入视频"
        acceptAssetType="video"
        onOpenChange={(v) => ctx.showVideoLibraryModal.set(v)}
        onImport={ctx.handleVideoLibraryImport}
      />
      {/* 姿态图/表情图/特效图：直达素材库对应子库 */}
      <ImportScriptModal
        open={ctx.showMaterialFromLibraryModal.value}
        title="导入图片"
        multiple
        acceptAssetType="image"
        initialTab="material"
        initialMaterialCategory={ctx.materialLibraryCategoryKey.value}
        onOpenChange={(v) => ctx.showMaterialFromLibraryModal.set(v)}
        onImportMultiple={ctx.handleMaterialLibraryOtherImport}
      />

      <StoryboardScriptModal
        key={`sb-vid-${ctx.currentSceneIndex.value}-${ctx.scriptEditorKey.value}`}
        open={ctx.showStoryboardScriptModal.value}
        panelTitle={ctx.scriptRowLabel()}
        initialContent={
          (ctx.props().scenes[ctx.currentSceneIndex.value] as { scriptContent?: string } | undefined)
            ?.scriptContent ?? ''
        }
        onOpenChange={(v) => ctx.showStoryboardScriptModal.set(v)}
        onSave={onSaveScript}
        onTitleChange={onScriptTitleChange}
      />
    </>
  )
}

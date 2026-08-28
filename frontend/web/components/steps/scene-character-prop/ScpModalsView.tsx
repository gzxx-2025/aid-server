'use client'

import { Suspense } from 'react'
import { AsyncModalLoading } from '~/components/common/AsyncModalLoading'
import {
  buildFormEditorScopeKey,
  formEditorScopeKeyToSlotKey,
  parseBareFormSlotKey
} from '~/utils/step3FormEditorScopeKey'
import { BatchGenerateAssetModal } from '../BatchGenerateAssetModal'
import { ImportSceneImageModal } from '../ImportSceneImageModal'
import { SceneSettingModal } from '../SceneSettingModal'
import { VoiceTimbrePickerModal } from '../VoiceTimbrePickerModal'
import { EditSceneImageModalLazy } from './editSceneImageModalLoader'
import { resolveScpFormImageBillingRequest } from './scpBillingQuote'
import type { ScpCtx } from './types'
import {
  isRpsSettingPromptEditable,
  RPS_SETTING_PROMPT_READONLY_TIP
} from './scpSettingPromptUtils'

/** 第三步全部弹窗接线（与原模板 1382-1590 行一一对应；重型编辑弹窗 lazy 挂载） */
export function ScpModalsView({ ctx }: { ctx: ScpCtx }) {
  const sceneSetting = ctx.sceneSettings.value[ctx.currentSceneName()]
  const characterSettingForm =
    ctx.characterForms.value[ctx.currentCharacterIndex.value]?.[
      ctx.currentCharacterSettingFormIndex.value
    ]
  const propSettingForm =
    ctx.propForms.value[ctx.currentPropIndex.value]?.[ctx.currentPropSettingFormIndex.value]
  const characterSetting = characterSettingForm?.setting
  const propSetting = propSettingForm?.setting
  const characterFormSlot = ctx.showEditCharacterFormImageModal.value
    ? parseBareFormSlotKey(ctx.currentEditCharacterFormKey.value)
    : null
  const propFormSlot = ctx.showEditPropFormImageModal.value
    ? parseBareFormSlotKey(ctx.currentEditPropFormKey.value)
    : null

  return (
    <>
      {/* 场景设定编辑弹窗 */}
      <SceneSettingModal
        open={ctx.showSceneSettingModal.value}
        onOpenChange={(v) => ctx.showSceneSettingModal.set(v)}
        settingVariant="scene"
        promptOnly
        editable={isRpsSettingPromptEditable(sceneSetting)}
        readOnlyTip={RPS_SETTING_PROMPT_READONLY_TIP}
        sceneName={ctx.currentSceneName()}
        initialContent={sceneSetting?.content || ''}
        onSave={ctx.handleSaveSceneSetting}
        onSaveAndUpdate={ctx.handleSaveAndUpdateSceneImage}
      />

      {/* 导入场景图弹窗 */}
      <ImportSceneImageModal
        open={ctx.showImportSceneImageModal.value}
        onOpenChange={(v) => ctx.showImportSceneImageModal.set(v)}
        assetType="scene"
        title="导入场景"
        onImport={ctx.handleSceneImageImport}
      />

      {/* 编辑场景图弹窗（打开时再挂载，配合异步组件避免首屏拉取） */}
      {ctx.showEditSceneImageModal.value && ctx.currentEditSceneIndex.value >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditSceneImageModalLazy
            key={`scene-edit-${ctx.currentEditSceneIndex.value}`}
            open={ctx.showEditSceneImageModal.value}
            onOpenChange={(v) => ctx.showEditSceneImageModal.set(v)}
            editorScopeKey={`scene-${ctx.currentEditSceneIndex.value}`}
            sceneIndex={ctx.currentEditSceneIndex.value}
            initialImageIndex={ctx.currentEditImageIndex.value}
            scenes={ctx.localValue.value.scenes.map((name, index) => ({
              name,
              images: ctx.sceneImages.value[index] || [],
              setting: ctx.sceneSettings.value[name]?.content || ''
            }))}
            rpsAssetId={ctx.sceneAssetIds.value[ctx.currentEditSceneIndex.value] ?? null}
            rpsFormIds={ctx.sceneFormIdsByIndex.value[ctx.currentEditSceneIndex.value] ?? []}
            rpsAssetIdsByIndex={ctx.sceneAssetIds.value}
            rpsFormIdsByIndex={ctx.sceneFormIdsByIndex.value}
            manualSettingEditBlockedTooltip={(sceneIndex) => {
              const name = ctx.localValue.value.scenes[sceneIndex]
              return isRpsSettingPromptEditable(ctx.sceneSettings.value[name])
                ? null
                : RPS_SETTING_PROMPT_READONLY_TIP
            }}
            canAutoGenerateImage={(sceneIndex) => ctx.canAutoGenerateSceneImage(sceneIndex)}
            onAutoRegenerateImage={(sceneIndex, _imageIndex, image) => {
              const formId = Number((image as { rpsFormId?: unknown } | null)?.rpsFormId)
              return ctx.handleAutoGenerateScene(
                sceneIndex,
                Number.isFinite(formId) && formId > 0 ? formId : undefined
              )
            }}
            resolveAutoRegenerateBillingRequest={(sceneIndex, _imageIndex, image) => {
              const formId = Number((image as { rpsFormId?: unknown } | null)?.rpsFormId)
              return ctx.resolveSceneAutoGenerateBillingRequest(
                sceneIndex,
                Number.isFinite(formId) && formId > 0 ? formId : undefined
              )
            }}
            onUpdate={(sceneIndex, data) => void ctx.handleSceneImageUpdate(sceneIndex, data)}
          />
        </Suspense>
      ) : null}

      {/* 角色形态提示词编辑弹窗 */}
      <SceneSettingModal
        open={ctx.showCharacterSettingModal.value}
        onOpenChange={(v) => ctx.showCharacterSettingModal.set(v)}
        settingVariant="character"
        promptOnly
        editable={isRpsSettingPromptEditable(characterSetting)}
        readOnlyTip={RPS_SETTING_PROMPT_READONLY_TIP}
        sceneName={characterSettingForm?.name || ctx.currentCharacterName()}
        initialContent={characterSetting?.content || ''}
        onSave={ctx.handleSaveCharacterFormSetting}
      />

      {/* 道具形态提示词编辑弹窗 */}
      <SceneSettingModal
        open={ctx.showPropSettingModal.value}
        onOpenChange={(v) => ctx.showPropSettingModal.set(v)}
        settingVariant="prop"
        promptOnly
        editable={isRpsSettingPromptEditable(propSetting)}
        readOnlyTip={RPS_SETTING_PROMPT_READONLY_TIP}
        sceneName={propSettingForm?.name || ctx.currentPropName()}
        initialContent={propSetting?.content || ''}
        onSave={ctx.handleSavePropFormSetting}
      />

      {/* 导入角色图弹窗 */}
      <ImportSceneImageModal
        open={ctx.showImportCharacterImageModal.value}
        onOpenChange={(v) => ctx.showImportCharacterImageModal.set(v)}
        assetType="character"
        title="导入角色"
        onImport={ctx.handleCharacterImageImport}
      />

      {/* 编辑角色图弹窗 */}
      {ctx.showEditCharacterImageModal.value && ctx.currentEditCharacterIndex.value >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditSceneImageModalLazy
            key={`character-edit-${ctx.currentEditCharacterIndex.value}`}
            open={ctx.showEditCharacterImageModal.value}
            onOpenChange={(v) => ctx.showEditCharacterImageModal.set(v)}
            editorScopeKey={`character-${ctx.currentEditCharacterIndex.value}`}
            sceneIndex={ctx.currentEditCharacterIndex.value}
            initialImageIndex={ctx.currentEditCharacterImageIndex.value}
            scenes={ctx.localValue.value.characters.map((name, index) => ({
              name,
              images: ctx.characterImages.value[index] || [],
              setting: ctx.characterSettings.value[name]?.content || ''
            }))}
            imageType="character"
            rpsAssetId={ctx.characterAssetIds.value[ctx.currentEditCharacterIndex.value] ?? null}
            rpsFormIds={ctx.characterFormIdsByIndex.value[ctx.currentEditCharacterIndex.value] ?? []}
            rpsAssetIdsByIndex={ctx.characterAssetIds.value}
            rpsFormIdsByIndex={ctx.characterFormIdsByIndex.value}
            manualSettingEditBlockedTooltip={(characterIndex) => {
              const name = ctx.localValue.value.characters[characterIndex]
              return isRpsSettingPromptEditable(ctx.characterSettings.value[name])
                ? null
                : RPS_SETTING_PROMPT_READONLY_TIP
            }}
            onUpdate={(sceneIndex, data) => void ctx.handleCharacterImageUpdate(sceneIndex, data)}
          />
        </Suspense>
      ) : null}

      {/* 导入角色形态图弹窗 */}
      <ImportSceneImageModal
        open={ctx.showImportCharacterFormImageModal.value}
        onOpenChange={(v) => ctx.showImportCharacterFormImageModal.set(v)}
        assetType="character"
        title="导入角色"
        onImport={ctx.handleCharacterFormImageImport}
      />

      {/* 编辑角色形态图弹窗 */}
      {characterFormSlot ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditSceneImageModalLazy
            key={`character-form-edit-${characterFormSlot.assetIndex}-${characterFormSlot.formIndex}`}
            open={ctx.showEditCharacterFormImageModal.value}
            onOpenChange={(v) => ctx.showEditCharacterFormImageModal.set(v)}
            editorScopeKey={buildFormEditorScopeKey(
              'character',
              characterFormSlot.assetIndex,
              characterFormSlot.formIndex
            )}
            sceneIndex={characterFormSlot.formIndex}
            initialImageIndex={ctx.currentEditCharacterFormImageIndex.value}
            scenes={
              ctx.characterForms.value[characterFormSlot.assetIndex]?.map((form, formIndex) => ({
                name: form.name,
                images:
                  ctx.characterFormImages.value[
                    `${characterFormSlot.assetIndex}-${formIndex}`
                  ] || []
              })) || []
            }
            imageType="form"
            formParentAssetType="character"
            rpsAssetId={ctx.characterAssetIds.value[characterFormSlot.assetIndex] ?? null}
            rpsFormIds={ctx.characterFormIdsByIndex.value[characterFormSlot.assetIndex] ?? []}
            canAutoGenerateImage={(formIndex) =>
              ctx.canAutoGenerateCharacterFormImage(characterFormSlot.assetIndex, formIndex)
            }
            onAutoRegenerateImage={(formIndex) =>
              ctx.handleAutoGenerateCharacterForm(characterFormSlot.assetIndex, formIndex)
            }
            resolveAutoRegenerateBillingRequest={(formIndex) =>
              resolveScpFormImageBillingRequest(
                ctx,
                'character',
                characterFormSlot.assetIndex,
                formIndex
              )
            }
            onUpdate={(_formIndex, data, scopeKey) =>
              ctx.handleCharacterFormImageUpdate(
                formEditorScopeKeyToSlotKey(String(scopeKey || '')) ||
                  ctx.currentEditCharacterFormKey.get(),
                data
              )
            }
          />
        </Suspense>
      ) : null}

      {/* 导入道具图弹窗 */}
      <ImportSceneImageModal
        open={ctx.showImportPropImageModal.value}
        onOpenChange={(v) => ctx.showImportPropImageModal.set(v)}
        assetType="prop"
        title="导入道具"
        onImport={ctx.handlePropImageImport}
      />

      <BatchGenerateAssetModal
        open={ctx.showBatchGenerateModal.value}
        onOpenChange={(v) => ctx.showBatchGenerateModal.set(v)}
        type={ctx.batchGenerateType.value}
        mode={ctx.batchGenerateMode.value}
        items={ctx.batchGenerateItems()}
        defaultModelCode={ctx.batchGenerateDefaultModelCode()}
        onRefreshItems={ctx.refreshBatchGenerateAssetItems}
        onConfirm={(payload) => ctx.handleBatchGenerateConfirm(payload)}
      />

      {/* 编辑道具图弹窗 */}
      {ctx.showEditPropImageModal.value && ctx.currentEditPropIndex.value >= 0 ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditSceneImageModalLazy
            key={`prop-edit-${ctx.currentEditPropIndex.value}`}
            open={ctx.showEditPropImageModal.value}
            onOpenChange={(v) => ctx.showEditPropImageModal.set(v)}
            editorScopeKey={`prop-${ctx.currentEditPropIndex.value}`}
            sceneIndex={ctx.currentEditPropIndex.value}
            initialImageIndex={ctx.currentEditPropImageIndex.value}
            scenes={ctx.localValue.value.props.map((name, index) => ({
              name,
              images: ctx.propImages.value[index] || [],
              setting: ctx.propSettings.value[name]?.content || ''
            }))}
            imageType="prop"
            rpsAssetId={ctx.propAssetIds.value[ctx.currentEditPropIndex.value] ?? null}
            rpsFormIds={ctx.propFormIdsByIndex.value[ctx.currentEditPropIndex.value] ?? []}
            rpsAssetIdsByIndex={ctx.propAssetIds.value}
            rpsFormIdsByIndex={ctx.propFormIdsByIndex.value}
            manualSettingEditBlockedTooltip={(propIndex) => {
              const name = ctx.localValue.value.props[propIndex]
              return isRpsSettingPromptEditable(ctx.propSettings.value[name])
                ? null
                : RPS_SETTING_PROMPT_READONLY_TIP
            }}
            onUpdate={(sceneIndex, data) => void ctx.handlePropImageUpdate(sceneIndex, data)}
          />
        </Suspense>
      ) : null}

      {/* 导入道具形态图弹窗 */}
      <ImportSceneImageModal
        open={ctx.showImportPropFormImageModal.value}
        onOpenChange={(v) => ctx.showImportPropFormImageModal.set(v)}
        assetType="prop"
        title="导入道具"
        onImport={ctx.handlePropFormImageImport}
      />

      {/* 编辑道具形态图弹窗 */}
      {propFormSlot ? (
        <Suspense fallback={<AsyncModalLoading />}>
          <EditSceneImageModalLazy
            key={`prop-form-edit-${propFormSlot.assetIndex}-${propFormSlot.formIndex}`}
            open={ctx.showEditPropFormImageModal.value}
            onOpenChange={(v) => ctx.showEditPropFormImageModal.set(v)}
            editorScopeKey={buildFormEditorScopeKey(
              'prop',
              propFormSlot.assetIndex,
              propFormSlot.formIndex
            )}
            sceneIndex={propFormSlot.formIndex}
            initialImageIndex={ctx.currentEditPropFormImageIndex.value}
            scenes={
              ctx.propForms.value[propFormSlot.assetIndex]?.map((form, formIndex) => ({
                name: form.name,
                images: ctx.propFormImages.value[`${propFormSlot.assetIndex}-${formIndex}`] || []
              })) || []
            }
            imageType="form"
            formParentAssetType="prop"
            rpsAssetId={ctx.propAssetIds.value[propFormSlot.assetIndex] ?? null}
            rpsFormIds={ctx.propFormIdsByIndex.value[propFormSlot.assetIndex] ?? []}
            canAutoGenerateImage={(formIndex) =>
              ctx.canAutoGeneratePropFormImage(propFormSlot.assetIndex, formIndex)
            }
            onAutoRegenerateImage={(formIndex) =>
              ctx.handleAutoGeneratePropForm(propFormSlot.assetIndex, formIndex)
            }
            resolveAutoRegenerateBillingRequest={(formIndex) =>
              resolveScpFormImageBillingRequest(
                ctx,
                'prop',
                propFormSlot.assetIndex,
                formIndex
              )
            }
            onUpdate={(_formIndex, data, scopeKey) =>
              ctx.handlePropFormImageUpdate(
                formEditorScopeKeyToSlotKey(String(scopeKey || '')) ||
                  ctx.currentEditPropFormKey.get(),
                data
              )
            }
          />
        </Suspense>
      ) : null}
      <VoiceTimbrePickerModal
        open={ctx.showVoiceTimbrePickerModal.value}
        onOpenChange={(v) => ctx.showVoiceTimbrePickerModal.set(v)}
        initialVoiceName={ctx.voicePickerInitialName.value}
        onConfirm={(payload) => void ctx.handleVoiceTimbreConfirm(payload)}
      />
      <audio
        ref={ctx.voicePreviewAudioRef}
        className="voice-preview-audio"
        onEnded={ctx.handleVoicePreviewEnded}
        onPause={ctx.handleVoicePreviewPaused}
      />
    </>
  )
}

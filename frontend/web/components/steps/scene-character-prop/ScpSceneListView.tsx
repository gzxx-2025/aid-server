'use client'

import { Button, Input, Tooltip } from 'antd'
import { EnvironmentOutlined, LoadingOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { AssetCardCancelIcon } from '~/components/common/AssetCardCancelIcon'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { shouldShowAssetImageRegenerateAction } from '~/utils/assetImageActionMode'
import { isStep3ListEditImageDisabled, STEP3_EDIT_IMAGE_GENERATING_TOOLTIP } from '~/utils/step3EditImageGate'
import { getSceneName, getScenePrefix } from './scpRowUtils'
import { iconAutGenerate, iconDownload, iconEmptyFail, iconManual, iconPreview, iconReplace } from './scpIcons'
import { ScpPendingFormStrip } from './ScpPendingFormStrip'
import type { ScpCtx } from './types'

/** 场景 Tab：待生成形态横条 + 场景卡片列表（统一为原「手动」单列布局，含自动识别项） */
export function ScpSceneListView({ ctx }: { ctx: ScpCtx }) {
  const sceneGenerationStatus = ctx.sceneGenerationStatus.value
  const sceneImages = ctx.sceneImages.value

  return (
    <div className="scene-generation-view">
      {/* 提取完成、尚未生成形态：小卡片横滑列表 */}
      <ScpPendingFormStrip
        ctx={ctx}
        cards={ctx.pendingSceneFormCards()}
        keyPrefix="pending-scene"
        hint="以下场景需先「生成形态」后再编辑详情与配图"
        titleIcon={EnvironmentOutlined}
      />
      {/* 场景卡片列表（顶栏说明文案在 .scp-topbar-hint） */}
      <div className="scene-cards-container">
        {/* 场景列表：统一为原「手动」单列布局（含自动识别项） */}
        {ctx.visibleManualScenesList().length > 0 ? (
          <div className="scene-cards-manual">
            {ctx.visibleManualScenesList().map((scene) => (
              <div
                key={`manual-scene-${scene.index}`}
                className="scene-card-wrapper"
                data-scp-asset-tab="scene"
                data-scp-asset-index={scene.index}
              >
                {/* 头部横条：和卡片同级，占满一行 */}
                <div className="scene-card-header-bar">
                  <div className="scene-card-title-wrapper">
                    <span className="scene-card-title-prefix">{getScenePrefix(scene.name)}</span>
                    {ctx.editingSceneIndex.value === scene.index ? (
                      <Input
                        value={ctx.editingSceneName.value}
                        onChange={(e) => ctx.editingSceneName.set(e.target.value)}
                        size="small"
                        className="scene-name-input"
                        onBlur={() => ctx.handleSceneNameBlur(scene.index)}
                        onPressEnter={() => ctx.handleSceneNameBlur(scene.index)}
                        onClick={(e) => e.stopPropagation()}
                      />
                    ) : (
                      <span
                        className="scene-card-title-editable"
                        onClick={() => ctx.startEditSceneName(scene.index, scene.name)}
                      >
                        {getSceneName(scene.name)}
                      </span>
                    )}
                  </div>
                  <div className="scene-card-actions-header">
                    <Button size="small" onClick={() => ctx.handleEditSceneSetting(scene.index)}>
                      修改场景设定
                    </Button>
                    {isStep3ListEditImageDisabled(sceneGenerationStatus[scene.index]) ? (
                      <Tooltip title={STEP3_EDIT_IMAGE_GENERATING_TOOLTIP} mouseEnterDelay={0.2}>
                        <span className="scp-edit-image-btn-wrap">
                          <Button size="small" disabled>
                            编辑场景图
                          </Button>
                        </span>
                      </Tooltip>
                    ) : (
                      <Button size="small" onClick={() => ctx.handleEditSceneImage(scene.index)}>
                        编辑场景图
                      </Button>
                    )}
                    <Button size="small" onClick={() => ctx.handleCopyScene(scene.index)}>
                      复制场景
                    </Button>
                    <Button size="small" danger onClick={() => ctx.removeScene(scene.index)}>
                      删除场景
                    </Button>
                  </div>
                </div>
                {/* 卡片内容：定宽，和头部横条同级（含原「自动」列表的生成中/失败态） */}
                {sceneGenerationStatus[scene.index] === 'generating' ? (
                  <div className="character-form-content scene-image-form-content">
                    <div className="asset-visual-generating-block" role="status" aria-live="polite">
                      <div className="asset-visual-generating-block__shimmer" aria-hidden="true" />
                      <LoadingOutlined spin className="asset-visual-generating-block__icon" />
                      <p className="asset-visual-generating-block__text">正在生成场景图…</p>
                    </div>
                  </div>
                ) : sceneGenerationStatus[scene.index] === 'failed' ? (
                  <div className="character-form-content scene-image-form-content">
                    <div className="scene-card scene-card-failed character-form-generate-failed">
                      <div className="scene-card-failed-content">
                        <div className="scene-card-failed-icon">
                          <img src={iconEmptyFail} alt="" className="scene-card-failed-icon-image" />
                        </div>
                        <div className="scene-card-failed-text">生成失败</div>
                        <BillingQuoteConfirm
                          disabled={!ctx.canAutoGenerateSceneImage(scene.index)}
                          title="确认重新生成该场景图？"
                          resolveRequest={() =>
                            ctx.resolveSceneAutoGenerateBillingRequest(scene.index)
                          }
                          onConfirm={() => ctx.handleAutoGenerateScene(scene.index)}
                        >
                          <Button
                            type="primary"
                            className="scene-card-failed-retry"
                            disabled={!ctx.canAutoGenerateSceneImage(scene.index)}
                          >
                            重新生成
                          </Button>
                        </BillingQuoteConfirm>
                      </div>
                    </div>
                  </div>
                ) : sceneImages[scene.index] && sceneImages[scene.index].length > 0 ? (
                  <div className="scene-card-list">
                    {sceneImages[scene.index].map((img: any, imgIdx: number) => (
                      <div
                        key={`manual-scene-${scene.index}-img-${imgIdx}`}
                        className="scene-card scene-card-auto"
                      >
                        <div className="scene-card-header-with-image">
                          {ctx.editingImageTitleIndex.value === `${scene.index}-${imgIdx}` ? (
                            <Input
                              value={ctx.editingImageTitle.value}
                              onChange={(e) => ctx.editingImageTitle.set(e.target.value)}
                              size="small"
                              className="image-title-input"
                              onBlur={() => ctx.handleImageTitleBlur(scene.index, imgIdx)}
                              onPressEnter={() => ctx.handleImageTitleBlur(scene.index, imgIdx)}
                              onClick={(e) => e.stopPropagation()}
                            />
                          ) : (
                            <span
                              className="scene-card-title-editable"
                              onClick={() =>
                                ctx.startEditImageTitle(
                                  scene.index,
                                  imgIdx,
                                  img.title || `场景图${imgIdx + 1}`
                                )
                              }
                            >
                              {img.title || `场景图${imgIdx + 1}`}
                            </span>
                          )}
                          <AssetCardCancelIcon
                            label="取消场景"
                            onClick={() => ctx.handleDeleteSceneImageByIndex(scene.index, imgIdx)}
                          />
                        </div>
                        <div className="scene-card-image-body">
                          <ShimmerImage
                            src={img.url}
                            imgClass="scene-main-image"
                            objectFit="cover"
                            revealDirection="fade"
                            onClick={() => ctx.handleEditSceneImageWithIndex(scene.index, imgIdx)}
                          />
                        </div>
                        <div className="scene-card-image-footer asset-action-footer">
                          <Button
                            icon={<img src={iconPreview} alt="" className="footer-action-icon" />}
                            onClick={() => ctx.handlePreviewSceneImageByIndex(scene.index, imgIdx)}
                          >
                            预览
                          </Button>
                          {shouldShowAssetImageRegenerateAction(
                            img,
                            ctx.canAutoGenerateSceneImage(scene.index)
                          ) ? (
                            <BillingQuoteConfirm
                              title="确认重新生成该场景图？"
                              resolveRequest={() => {
                                const formId = Number(
                                  (img as { rpsFormId?: unknown }).rpsFormId
                                )
                                return ctx.resolveSceneAutoGenerateBillingRequest(
                                  scene.index,
                                  Number.isFinite(formId) && formId > 0 ? formId : undefined
                                )
                              }}
                              onConfirm={() =>
                                ctx.handleSceneImageMiddleActionByIndex(scene.index, imgIdx)
                              }
                            >
                              <Button
                                icon={
                                  <img
                                    src={iconAutGenerate}
                                    alt=""
                                    className="footer-action-icon"
                                  />
                                }
                              >
                                重新生成
                              </Button>
                            </BillingQuoteConfirm>
                          ) : (
                            <Button
                              icon={
                                <img src={iconReplace} alt="" className="footer-action-icon" />
                              }
                              onClick={() =>
                                ctx.handleSceneImageMiddleActionByIndex(scene.index, imgIdx)
                              }
                            >
                              替换
                            </Button>
                          )}
                          <Button
                            icon={<img src={iconDownload} alt="" className="footer-action-icon" />}
                            onClick={() => ctx.handleDownloadSceneImageByIndex(scene.index, imgIdx)}
                          >
                            下载
                          </Button>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="character-form-content scene-image-form-content">
                    <div className="character-form-card manual-generate-card">
                      <div className="asset-generate-card__actions">
                        <BillingQuoteConfirm
                          disabled={
                            !ctx.canAutoGenerateSceneImage(scene.index) ||
                            sceneGenerationStatus[scene.index] === 'generating'
                          }
                          title="确认生成该场景图？"
                          resolveRequest={() =>
                            ctx.resolveSceneAutoGenerateBillingRequest(scene.index)
                          }
                          onConfirm={() => ctx.handleAutoGenerateScene(scene.index)}
                        >
                          <Button
                            type="primary"
                            className="asset-generate-card__action asset-generate-card__action--primary"
                            disabled={
                              !ctx.canAutoGenerateSceneImage(scene.index) ||
                              sceneGenerationStatus[scene.index] === 'generating'
                            }
                          >
                            <img
                              src={iconAutGenerate}
                              alt=""
                              className="asset-generate-card__action-icon"
                              aria-hidden="true"
                            />
                            自动生成
                          </Button>
                        </BillingQuoteConfirm>
                        <div className="asset-generate-card__or">或</div>
                        <Button
                          className="asset-generate-card__action"
                          onClick={() => ctx.handleImportSceneImage(scene.index)}
                        >
                          <img
                            src={iconManual}
                            alt=""
                            className="asset-generate-card__action-icon"
                            aria-hidden="true"
                          />
                          图片导入
                        </Button>
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  )
}

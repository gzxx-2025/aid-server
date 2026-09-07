'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Dropdown, Input, Modal, message } from 'antd'
import type { MenuProps } from 'antd'
import {
  DeleteOutlined,
  HomeOutlined,
  SaveOutlined,
  UnorderedListOutlined,
  WarningOutlined
} from '@ant-design/icons'
import agentIconRaw from '~/assets/img/home/agent-icon.svg'
import { useRouter } from 'next/navigation'
import { useStudioUiStore } from '@/stores/studioUi'
import { useCreationStore } from '@/stores/creation'
import { CREATE_FLOW_STEP_ORDER } from '@/utils/createFlowRoutes'
import { useStudioFlowRuntime } from '@/components/studio/flow/StudioFlowRuntimeProvider'
import { buildStudioStepsFlowHref } from '@/utils/studio/studioFlowNavigation'
import { StudioFlowDeliveryMenu } from '@/components/studio/flow/StudioFlowDeliveryMenu'
import { filterStudioFlowCanvasVisibleNodes } from '@/utils/studio/studioFlowCanvasProjection'
import {
  hasStudioFlowPreviewStepSelected,
  isStudioFlowDeliveryReady
} from '@/utils/studio/studioFlowDelivery'
import { userProjectDelete, userProjectUpdate } from '@/utils/business/project'
import { assetUrl } from '@/utils/assetUrl'
import AidHoverLogo from '@/components/atoms/AidHoverLogo'
import '@/components/common/composer-flow.css'

const agentIconUrl = assetUrl(agentIconRaw)
const dropdownClassNames = { root: 'studio-canvas-menu-overlay studio-canvas-menu-overlay--canvas' }

export function StudioTopbar({
  onSave,
  readOnly = false
}: {
  onSave: () => void | Promise<void>
  readOnly?: boolean
}) {
  const router = useRouter()
  const flowRuntime = useStudioFlowRuntime()
  const setTitle = useStudioUiStore((state) => state.setTitle)
  const setRightCollapsed = useStudioUiStore((state) => state.setRightCollapsed)
  const nodes = useStudioUiStore((state) => state.nodes)
  const visibleFlowNodes = filterStudioFlowCanvasVisibleNodes(nodes)
  const selectedNodeIds = useStudioUiStore((state) => state.selectedNodeIds)
  const currentExportStatus = useCreationStore((state) => state.currentExportStatus)
  const currentFinalVideoUrl = useCreationStore((state) => state.currentFinalVideoUrl)
  const currentPendingVideoUrl = useCreationStore((state) => state.currentPendingVideoUrl)
  const workTitle = useCreationStore((state) => state.workTitle)
  const projectId = useCreationStore((state) => state.currentProjectId)
  const titleValue = workTitle
  const titleMeasureRef = useRef<HTMLSpanElement | null>(null)
  const [titleInputWidthPx, setTitleInputWidthPx] = useState(160)
  const workTitleSaveBaselineRef = useRef((workTitle || '').trim() || '未命名作品')
  const titleFocusedRef = useRef(false)
  const trimmedTitle = titleValue?.trim()
  const titleMeasureText = trimmedTitle && trimmedTitle.length > 0 ? trimmedTitle : '作品名称'

  const syncTitleInputWidth = useCallback(() => {
    window.setTimeout(() => {
      const el = titleMeasureRef.current
      if (!el) return
      setTitleInputWidthPx(Math.min(Math.max(el.offsetWidth + 28, 96), 560))
    }, 0)
  }, [])

  useEffect(() => {
    syncTitleInputWidth()
  }, [syncTitleInputWidth, titleValue])

  useEffect(() => {
    if (titleFocusedRef.current) return
    workTitleSaveBaselineRef.current = (workTitle || '').trim() || '未命名作品'
  }, [projectId, workTitle])
  const deliveryReady = isStudioFlowDeliveryReady(nodes, {
    exportStatus: currentExportStatus,
    finalVideoUrl: currentFinalVideoUrl,
    pendingVideoUrl: currentPendingVideoUrl
  })
  const hasExportedVideo =
    currentExportStatus === 2 ||
    Boolean(String(currentFinalVideoUrl || '').trim()) ||
    Boolean(String(currentPendingVideoUrl || '').trim())
  const deliveryHighlighted = deliveryReady && (
    hasStudioFlowPreviewStepSelected(nodes, selectedNodeIds) || hasExportedVideo
  )
  const dirtyRevision = useStudioUiStore((state) => state.dirtyRevision)
  const tasks = useStudioUiStore((state) => state.tasks)
  const running = tasks.filter((task) => task.status === 'running' || task.status === 'waiting').length
  const failed = nodes.filter((node) => node.data.status === 'failed').length

  const deleteWork = () => {
    if (!projectId) {
      message.warning('当前预览作品无法删除')
      return
    }
    Modal.confirm({
      title: '删除作品',
      content: `确认删除《${workTitle || '未命名作品'}》吗？`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      className: 'create-flow-modal',
      async onOk() {
        await userProjectDelete(projectId)
        message.success('删除成功')
        router.push('/works?from=studio')
      }
    })
  }

  const projectMenu: MenuProps = {
    className: 'studio-toolbar-more-menu studio-toolbar-more-menu--canvas',
    selectable: false,
    items: [
      {
        key: 'home',
        label: '返回主页',
        icon: <HomeOutlined />,
        onClick: () => router.push('/')
      },
      {
        key: 'works',
        label: '我的作品',
        icon: <UnorderedListOutlined />,
        onClick: () => router.push('/works?from=studio')
      },
      { type: 'divider' },
      {
        key: 'delete',
        label: '删除作品',
        icon: <DeleteOutlined />,
        danger: true,
        disabled: readOnly || !projectId,
        onClick: deleteWork
      }
    ]
  }

  const switchToSteps = () => {
    const proceed = async () => {
      const creation = useCreationStore.getState()
      const index = Math.min(Math.max(creation.currentStepIndex, 0), CREATE_FLOW_STEP_ORDER.length - 1)
      if (readOnly) return
      if (flowRuntime && !await flowRuntime.flushDirtyEditors()) {
        Modal.error({
          title: '剧本尚未保存',
          content: '剧本自动保存失败或存在内容冲突，请处理后再切换到步骤流程。',
          okText: '知道了',
          className: 'create-flow-modal'
        })
        return
      }
      await onSave()
      if (useStudioUiStore.getState().saveStatus === 'error') {
        Modal.error({
          title: '画布布局保存失败',
          content: useStudioUiStore.getState().saveError || '请重试后再切换到步骤流程。',
          okText: '知道了',
          className: 'create-flow-modal'
        })
        return
      }
      const from = typeof window === 'undefined'
        ? undefined
        : new URLSearchParams(window.location.search).get('from') || undefined
      router.push(buildStudioStepsFlowHref({
        step: CREATE_FLOW_STEP_ORDER[index],
        projectId: creation.currentProjectId,
        episodeId: creation.currentEpisodeId,
        from: from || 'studio'
      }))
    }
    if (!dirtyRevision) {
      void proceed()
      return
    }
    Modal.confirm({
      title: '保存画布布局并返回步骤流程？',
      content: '流程业务数据已经实时保存到服务端；这里只保存节点位置和视图布局。',
      okText: '保存并返回',
      cancelText: '留在画布',
      className: 'create-flow-modal',
      onOk: proceed
    })
  }

  const handleTitleChange = (value: string) => {
    if (readOnly) return
    useCreationStore.getState().setWorkTitle(value)
    setTitle(`AID Studio · ${(value.trim() || '未命名作品')}`)
    syncTitleInputWidth()
  }

  const handleTitleBlur = async () => {
    if (readOnly) return
    const store = useCreationStore.getState()
    const trimmed = (store.workTitle || '').trim() || '未命名作品'
    store.setWorkTitle(trimmed)
    setTitle(`AID Studio · ${trimmed}`)
    if (trimmed === workTitleSaveBaselineRef.current) {
      syncTitleInputWidth()
      return
    }
    if (!projectId) {
      workTitleSaveBaselineRef.current = trimmed
      store.updateFormData({
        globalSetting: { ...store.formData.globalSetting, title: trimmed }
      })
      syncTitleInputWidth()
      return
    }
    try {
      await userProjectUpdate({ id: projectId, projectName: trimmed })
      workTitleSaveBaselineRef.current = trimmed
      const storeNow = useCreationStore.getState()
      storeNow.updateFormData({
        globalSetting: { ...storeNow.formData.globalSetting, title: trimmed }
      })
    } catch (error: unknown) {
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '保存标题失败')
      useCreationStore.getState().setWorkTitle(workTitleSaveBaselineRef.current)
      setTitle(`AID Studio · ${workTitleSaveBaselineRef.current}`)
    } finally {
      syncTitleInputWidth()
    }
  }

  return (
    <header className="studio-topbar">
      <div className="studio-topbar__left">
        <div className="studio-topbar__project">
          <Dropdown menu={projectMenu} trigger={['click']} placement="bottomLeft" classNames={dropdownClassNames}>
            <button type="button" className="studio-topbar__aid-trigger" aria-label="作品菜单">
              <AidHoverLogo className="studio-topbar__aid-logo" alt="AID" />
            </button>
          </Dropdown>
          <div className="studio-topbar__title-wrap" style={{ width: `${titleInputWidthPx}px` }}>
            <span ref={titleMeasureRef} className="studio-title-input-measure" aria-hidden="true">
              {titleMeasureText}
            </span>
            <Input
              className="studio-title-input"
              value={titleValue}
              aria-label="Studio 作品名称"
              readOnly={readOnly}
              maxLength={100}
              title={readOnly ? '当前作品只读' : '点击修改作品名称'}
              placeholder="作品名称"
              onFocus={() => { titleFocusedRef.current = true }}
              onChange={(event) => handleTitleChange(event.target.value)}
              onBlur={() => {
                titleFocusedRef.current = false
                void handleTitleBlur()
              }}
            />
          </div>
        </div>
      </div>
      <div className="studio-topbar__center">
        <span className="studio-topbar__mode-tag">流程创作</span>
      </div>
      <div className="studio-topbar__right">
        <span className="studio-topbar__stats">{visibleFlowNodes.length} 节点 · {running} 进行中</span>
        {failed ? <span className="studio-diagnostic-banner"><WarningOutlined /> {failed} 个节点待处理</span> : null}
        <Button icon={<SaveOutlined />} disabled={readOnly} onClick={() => void onSave()}>保存</Button>
        <span className={deliveryHighlighted ? 'studio-topbar__delivery studio-topbar__delivery--active' : 'studio-topbar__delivery'}>
          <StudioFlowDeliveryMenu compact disabled={readOnly || !deliveryReady} />
        </span>
        <Button onClick={switchToSteps}>切换到步骤流程</Button>
        <button
          type="button"
          className="studio-topbar__ai-create composer-flow is-flowing"
          data-studio-guide="conversation"
          aria-label="打开 AI 创作"
          disabled={readOnly}
          onClick={() => setRightCollapsed(false)}
        >
          <img src={agentIconUrl} alt="" className="studio-topbar__ai-create-icon" />
          AI创作
        </button>
      </div>
    </header>
  )
}

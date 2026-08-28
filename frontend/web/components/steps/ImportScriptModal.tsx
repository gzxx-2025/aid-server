'use client'

import { useEffect, useRef, useState } from 'react'
import { Button, Modal, message } from 'antd'
import { UploadOutlined, RightOutlined, CloseOutlined } from '@ant-design/icons'
import AssetList from './ImportAssetList'
import ModalTitleWatermark from '~/components/ModalTitleWatermark'
import { useCreationStore } from '~/stores/creation'
import {
  userAssetCenterCategoryTree,
  userAssetCenterDetail,
  userProjectList
} from '~/utils/businessApi'
import type { AssetCenterCategoryTreeVO } from '~/types/business-api'
import {
  clearOfficialMaterialAllCache,
  clearPersonalCenterAllCache,
  episodeDisplayLabel,
  findAssetCenterEpisode,
  findAssetCenterProject,
  getEpisodeCategories,
  resolveImportModalCategoryCode
} from '~/utils/importAssetModalQuery'
import { scriptApiTextToEditorHtml } from '~/utils/htmlPlain'
import { useStackedModalZIndex } from '~/hooks/useStackedModalZIndex'
import {
  invokeImportModalCallback,
  type ImportModalCallback
} from '~/utils/importModalCallback'
import {
  ImportScriptSidebarTree,
  MATERIAL_CATEGORIES,
  type ImportScriptTab,
  type MaterialCategory
} from './import-script/ImportScriptSidebarTree'
import { openImportScriptFilePicker } from './import-script/importScriptFileSelect'
import {
  isAcceptedImportAsset,
  isImageAsset,
  isScriptAsset,
  isVideoAsset,
  resolveImportModalProjectState,
  type ImportScriptModalProps
} from './import-script/importScriptModalState'
import './import-script/import-script-modal.css'

export type { ImportScriptModalProps } from './import-script/importScriptModalState'

export function ImportScriptModal({
  open,
  title,
  zIndex,
  multiple = false,
  acceptAssetType = 'all',
  initialTab = null,
  initialMaterialCategory = null,
  beforeScriptImport,
  onOpenChange,
  onImport,
  onImportMultiple
}: ImportScriptModalProps) {
  const modalZIndex = useStackedModalZIndex(open, zIndex)
  const modalTitle =
    title ||
    (acceptAssetType === 'image'
      ? '导入图片'
      : acceptAssetType === 'video'
        ? '导入视频'
        : '导入剧本')
  const sidebarTitle =
    acceptAssetType === 'image'
      ? '导入图片'
      : acceptAssetType === 'video'
        ? '导入视频'
        : '导入文档'
  const storeCurrentEpisodeId = useCreationStore((s) => s.currentEpisodeId)
  const storeCurrentProjectId = useCreationStore((s) => s.currentProjectId)
  const storeWorkTitle = useCreationStore((s) => s.workTitle)

  const importModalEpisodeId =
    storeCurrentEpisodeId != null && storeCurrentEpisodeId >= 0 ? storeCurrentEpisodeId : 0

  const [selectedNode, setSelectedNode] = useState<string | null>(null)
  const [selectedCategory, setSelectedCategory] = useState<any>(null)
  const [activeTab, setActiveTab] = useState<ImportScriptTab>('current')
  const [expandedNodes, setExpandedNodes] = useState<Record<string, boolean>>({
    'material-library': true
  })
  const [selectedAsset, setSelectedAsset] = useState<any>(null)
  const [selectedAssets, setSelectedAssets] = useState<any[]>([])
  const selectedAssetIds = selectedAssets.map((a) => a.id).filter(Boolean) as string[]

  const [currentPath, setCurrentPath] = useState<string[]>([])
  const [projects, setProjects] = useState<Array<{ id: string; name: string }>>([])
  const [assetCenterTree, setAssetCenterTree] = useState<AssetCenterCategoryTreeVO[]>([])
  const [assetCenterTreeLoading, setAssetCenterTreeLoading] = useState(false)
  const assetCenterTreeLoadingRef = useRef(false)

  const [currentProjectId, setCurrentProjectId] = useState('')
  const selectedNodeRef = useRef<string | null>(null)
  selectedNodeRef.current = selectedNode

  const importListEpisodeId =
    activeTab === 'history' && selectedCategory?.episodeId != null
      ? Number(selectedCategory.episodeId)
      : importModalEpisodeId

  const { currentProject, displayProjectId, currentEpisodeCategories } =
    resolveImportModalProjectState({
      projects,
      selectedProjectId: currentProjectId,
      storeProjectId: storeCurrentProjectId,
      storeWorkTitle,
      episodeId: importModalEpisodeId,
      assetCenterTree,
      treeLoading: assetCenterTreeLoading
    })

  async function loadAssetCenterTree() {
    if (assetCenterTreeLoadingRef.current) return
    assetCenterTreeLoadingRef.current = true
    setAssetCenterTreeLoading(true)
    try {
      setAssetCenterTree(await userAssetCenterCategoryTree({}))
    } catch {
      setAssetCenterTree([])
    } finally {
      assetCenterTreeLoadingRef.current = false
      setAssetCenterTreeLoading(false)
    }
  }

  async function loadProjectsForModal() {
    const cid = useCreationStore.getState().currentProjectId
    const workTitle = useCreationStore.getState().workTitle
    try {
      const { rows } = await userProjectList({ pageNum: 1, pageSize: 200 })
      const list = rows.map((r) => ({
        id: String(r.id),
        name: r.projectName?.trim() || `项目${r.id}`
      }))
      if (cid && list.some((p) => p.id === String(cid))) {
        setProjects(list)
        setCurrentProjectId(String(cid))
      } else if (cid) {
        list.unshift({
          id: String(cid),
          name: workTitle || `项目${cid}`
        })
        setProjects(list)
        setCurrentProjectId(String(cid))
      } else {
        setProjects(list)
        if (list.length) setCurrentProjectId(list[0].id)
      }
    } catch {
      if (cid) {
        setProjects([{ id: String(cid), name: workTitle || `项目${cid}` }])
        setCurrentProjectId(String(cid))
      }
    }
  }

  // 原 watch(props.open)：打开时清缓存并拉取项目列表 + 分类树
  useEffect(() => {
    if (!open) {
      // A parent editor or route change can close this portal without using its own cancel button.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSelectedAsset(null)
      setSelectedAssets([])
      return
    }
    clearPersonalCenterAllCache()
    clearOfficialMaterialAllCache()
    void loadProjectsForModal()
    void loadAssetCenterTree()
  }, [open])

  // 原 watch(assetCenterTree)：树返回后展开当前项目并默认选中
  useEffect(() => {
    if (!open || !assetCenterTree.length) return
    const pid = displayProjectId
    if (activeTab === 'current' && pid) {
      setExpandedNodes((m) => ({ ...m, [`project-${pid}`]: true }))
      if (!selectedNode) {
        selectNode(`project-${pid}`, null, currentProject)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assetCenterTree])

  // 面包屑路径
  const breadcrumbPath = (() => {
    const path: string[] = []

    if (activeTab === 'material') {
      // 素材库标签页
      if (selectedNode === 'material-library') {
        path.push('素材库')
      } else if (selectedNode?.startsWith('material-')) {
        path.push('素材库')
        const categoryKey = selectedNode.replace('material-', '')
        const category = MATERIAL_CATEGORIES.find((c) => c.key === categoryKey)
        if (category) {
          path.push(category.label)
        }
      }
    } else if (activeTab === 'history') {
      if (selectedNode) {
        const parts = selectedNode.split('-')
        if (parts[0] === 'project' && parts.length === 2) {
          const projectId = Number(parts[1])
          const project = assetCenterTree.find((p) => p.projectId === projectId)
          if (project) path.push(project.projectName || `项目${projectId}`)
        } else if (
          parts.length >= 3 &&
          /^\d+$/.test(parts[0] ?? '') &&
          /^\d+$/.test(parts[1] ?? '')
        ) {
          const projectId = Number(parts[0])
          const episodeId = Number(parts[1])
          const categoryCode = parts.slice(2).join('-')
          const project = assetCenterTree.find((p) => p.projectId === projectId)
          const episode = project?.children?.find((ep) => (ep.episodeId ?? 0) === episodeId)
          const category = episode?.children?.find((c) => c.categoryCode === categoryCode)
          if (project) path.push(project.projectName || `项目${projectId}`)
          if (episode) {
            path.push(episodeDisplayLabel(episode))
          }
          if (category) path.push(category.categoryName || categoryCode)
        }
      }
    } else if (activeTab === 'current') {
      if (selectedNode) {
        if (selectedNode.startsWith('project-')) {
          path.push(currentProject.name)
        } else {
          const dash = selectedNode.indexOf('-')
          if (dash > 0) {
            path.push(currentProject.name)
            const categoryKey = selectedNode.slice(dash + 1)
            const category = currentEpisodeCategories.find((c) => c.categoryCode === categoryKey)
            if (category) path.push(category.categoryName || categoryKey)
          }
        }
      }
    }

    // 添加当前路径（文件夹导航路径）
    path.push(...currentPath)

    return path
  })()

  /** 面包屑当前级：仅当前级显示高亮色，其它级统一灰色 */
  const isBreadcrumbCurrent = (index: number) => {
    // index=-1 代表“个人资产库”
    if (index === -1) {
      return breadcrumbPath.length === 0
    }
    return index === breadcrumbPath.length - 1
  }

  // 监听标签页切换（原 watch(activeTab)，不含首帧）
  const prevTabRef = useRef(activeTab)
  useEffect(() => {
    if (prevTabRef.current === activeTab) return
    prevTabRef.current = activeTab
    const newTab = activeTab
    setSelectedNode(null)
    setSelectedCategory(null)
    setCurrentPath([])
    setSelectedAsset(null)
    setSelectedAssets([])

    // 根据标签页初始化展开状态
    if (newTab === 'material') {
      setExpandedNodes((m) => ({ ...m, 'material-library': true }))
      // 默认选中素材库
      selectMaterialLibrary()
    } else if (newTab === 'history') {
      setExpandedNodes((m) => ({ ...m, 'my-works': true }))
    } else if (newTab === 'current') {
      const pid = currentProjectId || String(useCreationStore.getState().currentProjectId ?? '')
      if (pid) {
        setExpandedNodes((m) => ({ ...m, [`project-${pid}`]: true }))
        selectNode(`project-${pid}`, null, currentProject)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab])

  // 选择素材库
  function selectMaterialLibrary() {
    setSelectedNode('material-library')
    setSelectedCategory({ key: 'material-library', label: '素材库' })
    setExpandedNodes((m) => ({ ...m, 'material-library': true }))
    setCurrentPath([])
    setSelectedAsset(null)
  }

  // 选择素材库分类
  function selectMaterialCategory(category: MaterialCategory) {
    setSelectedNode(`material-${category.key}`)
    setSelectedCategory(category)
    setCurrentPath([])
    setSelectedAsset(null)
    setSelectedAssets([])
  }

  /** 姿态图/表情图/特效图：打开即定位素材库 + 对应子库 */
  useEffect(() => {
    if (!open || initialTab !== 'material' || !initialMaterialCategory) return
    setActiveTab('material')
    setTimeout(() => {
      const cat = MATERIAL_CATEGORIES.find((c) => c.key === initialMaterialCategory)
      if (cat) {
        setExpandedNodes((m) => ({ ...m, 'material-library': true }))
        selectMaterialCategory(cat)
      }
    }, 0)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 原 onMounted：与 Store 当前作品对齐并默认选中
  useEffect(() => {
    const cid = useCreationStore.getState().currentProjectId
    if (cid) {
      const pid = String(cid)
      setCurrentProjectId(pid)
      setExpandedNodes((m) => ({ ...m, [`project-${pid}`]: true }))
      selectNode(`project-${pid}`, null, currentProject)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])


  function categoryToSelection(
    cat: AssetCenterCategoryTreeVO,
    projectId: number,
    episodeId: number
  ) {
    const code = cat.categoryCode || ''
    return {
      key: code,
      label: cat.categoryName || code,
      projectId,
      episodeId
    }
  }

  function selectCurrentCategory(cat: AssetCenterCategoryTreeVO) {
    const pid = Number(displayProjectId)
    if (!Number.isFinite(pid) || pid <= 0) return
    const epId = importModalEpisodeId
    const code = cat.categoryCode || ''
    setSelectedNode(`${pid}-${code}`)
    setSelectedCategory(categoryToSelection(cat, pid, epId))
    setSelectedAsset(null)
    setSelectedAssets([])
    setCurrentPath([])
  }

  // 切换节点展开
  function toggleNode(key: string) {
    setExpandedNodes((m) => ({ ...m, [key]: !m[key] }))
  }

  // 处理项目节点点击（只选中，不展开/折叠）
  function handleProjectClick(key: string, project?: any) {
    selectNode(key, null, project)
  }

  function handleHistoryProjectClick(project: AssetCenterCategoryTreeVO) {
    const key = `project-${project.projectId}`
    setSelectedNode(key)
    setSelectedCategory({ projectId: project.projectId, label: project.projectName })
    setSelectedAsset(null)
    setSelectedAssets([])
    setCurrentPath([])
  }

  function handleHistoryEpisodeClick(
    project: AssetCenterCategoryTreeVO,
    episode: AssetCenterCategoryTreeVO
  ) {
    const epId = episode.episodeId ?? 0
    const key = `episode-${project.projectId}-${epId}`
    setSelectedNode(key)
    setSelectedCategory({
      projectId: project.projectId,
      episodeId: epId,
      label: episode.episodeTitle || `第${episode.episodeNo ?? ''}集`
    })
    setSelectedAsset(null)
    setSelectedAssets([])
    setCurrentPath([])
  }

  function selectHistoryCategory(
    project: AssetCenterCategoryTreeVO,
    episode: AssetCenterCategoryTreeVO,
    cat: AssetCenterCategoryTreeVO
  ) {
    const epId = episode.episodeId ?? 0
    const code = cat.categoryCode || ''
    setSelectedNode(`${project.projectId}-${epId}-${code}`)
    setSelectedCategory({
      key: code,
      label: cat.categoryName || code,
      projectId: project.projectId,
      episodeId: epId
    })
    setSelectedAsset(null)
    setSelectedAssets([])
    setCurrentPath([])
  }

  // 选择节点
  function selectNode(key: string, category?: any, _project?: any) {
    setSelectedNode(key)
    if (category?.categoryCode) {
      const dash = key.indexOf('-')
      const pid = dash > 0 ? Number(key.slice(0, dash)) : Number(displayProjectId)
      setSelectedCategory(
        categoryToSelection(
          category,
          Number.isFinite(pid) ? pid : Number(displayProjectId),
          importModalEpisodeId
        )
      )
    } else {
      setSelectedCategory(category)
    }
    setSelectedAsset(null)
    setCurrentPath([])
  }

  /** 解析当前选中节点对应的项目数字 ID（供右侧列表 personal 查询） */
  function getProjectId(): string | null {
    const sn = selectedNodeRef.current
    if (!sn) return null
    if (sn.startsWith('project-')) {
      return sn.replace(/^project-/, '')
    }
    const i = sn.indexOf('-')
    if (i > 0) {
      const head = sn.slice(0, i)
      if (/^\d+$/.test(head)) return head
    }
    return null
  }

  function isScriptImportContext(): boolean {
    return acceptAssetType === 'script' || acceptAssetType === 'all'
  }

  async function confirmScriptImportIfNeeded(): Promise<boolean> {
    if (!isScriptImportContext() || !beforeScriptImport) return true
    try {
      return await beforeScriptImport()
    } catch {
      return false
    }
  }

  async function runImportCallback<T>(callback: ImportModalCallback<T> | undefined, payload: T) {
    const result = await invokeImportModalCallback(callback, payload)
    if (result.error) {
      const cause = result.error as { msg?: string; message?: string }
      message.error(cause?.msg || cause?.message || '导入失败，请重试')
    }
    return result.accepted
  }

  async function resolveSelectedScriptContent(asset: Record<string, any>): Promise<string> {
    const raw = asset.raw ?? {}
    const id = Number(raw.id ?? asset.id)
    const categoryCode =
      String(raw.categoryCode || '').trim() ||
      resolveImportModalCategoryCode(selectedNode, selectedCategory) ||
      String(raw.assetType || '').trim()

    if (Number.isFinite(id) && id > 0 && categoryCode) {
      const detail = await userAssetCenterDetail({ categoryCode, id })
      const content = detail.content ?? {}
      const text = [
        content.originalText,
        content.storyScript,
        content.dialogueText,
        content.ttsText,
        content.promptText,
        content.introduction,
        content.summary
      ].find((value) => typeof value === 'string' && value.trim())
      if (typeof text === 'string') return scriptApiTextToEditorHtml(text)
    }

    const directContent = String(asset.content || '').trim()
    if (directContent) {
      return /<[a-z][\s\S]*>/i.test(directContent)
        ? directContent
        : scriptApiTextToEditorHtml(directContent)
    }
    throw new Error('该资产暂无可导入的文本内容')
  }

  async function emitScriptImport(content: string, successMessage = '导入成功') {
    if (!(await confirmScriptImportIfNeeded())) return false
    if (!(await runImportCallback(onImport, content))) return false
    onOpenChange(false)
    message.success(successMessage)
    return true
  }

  // 选择文件（逻辑拆分至 import-script/importScriptFileSelect.ts）
  function handleSelectFile() {
    openImportScriptFilePicker({
      acceptAssetType,
      multiple,
      getProjectId,
      getImportEpisodeId: () => {
        const e = useCreationStore.getState().currentEpisodeId
        if (e != null && e >= 0) return e
        return 0
      },
      confirmScriptImportIfNeeded,
      emitImport: (content) => runImportCallback(onImport, content),
      emitImportMultiple: (assets) => runImportCallback(onImportMultiple, assets),
      closeModal: () => onOpenChange(false)
    })
  }

  // 取消
  function handleCancel() {
    onOpenChange(false)
    setSelectedAsset(null)
    setSelectedAssets([])
  }

  // 处理资产选择
  function handleAssetSelect(asset: any) {
    if (!isAcceptedImportAsset(asset, acceptAssetType)) {
      message.warning(
        isScriptImportContext()
          ? '剧本导入仅支持文本脚本'
          : `请选择有效的${acceptAssetType === 'image' ? '图片' : '视频'}资产`
      )
      return
    }
    if (multiple) {
      const idx = selectedAssets.findIndex((a) => a.id === asset.id)
      if (idx >= 0) {
        setSelectedAssets(selectedAssets.filter((a) => a.id !== asset.id))
      } else {
        setSelectedAssets([...selectedAssets, asset])
      }
    } else {
      // 再次点击同一项：取消选中（与批量生成分镜图列表一致）
      setSelectedAsset(selectedAsset?.id === asset.id ? null : asset)
    }
  }

  // 处理导航（进入文件夹）
  function handleNavigate(folderName: string) {
    setCurrentPath((p) => [...p, folderName])

    // 根据文件夹名称和当前标签页，同步选中左侧树节点
    if (activeTab === 'material') {
      // 素材库：根据文件夹名称找到对应的分类
      const category = MATERIAL_CATEGORIES.find((c) => c.label === folderName)
      if (category) {
        setSelectedNode(`material-${category.key}`)
        setSelectedCategory(category)
        // 确保素材库节点是展开的
        setExpandedNodes((m) => ({ ...m, 'material-library': true }))
      }
    } else if (activeTab === 'current') {
      const pid = displayProjectId
      const cat = currentEpisodeCategories.find(
        (c) => (c.categoryName || c.categoryCode) === folderName
      )
      if (cat && pid) {
        selectCurrentCategory(cat)
        setExpandedNodes((m) => ({ ...m, [`project-${pid}`]: true }))
      }
    } else if (activeTab === 'history') {
      if (selectedNode?.startsWith('project-')) {
        const projectId = Number(selectedNode.replace(/^project-/, ''))
        const project = findAssetCenterProject(assetCenterTree, projectId)
        const episodes = project?.children ?? []
        if (episodes.length === 1) {
          const episode = episodes[0]!
          const cat = getEpisodeCategories(episode).find(
            (c) => (c.categoryName || c.categoryCode) === folderName
          )
          if (project && cat) {
            selectHistoryCategory(project, episode, cat)
            setExpandedNodes((m) => ({
              ...m,
              [`project-${projectId}`]: true,
              [`episode-${projectId}-${episode.episodeId ?? 0}`]: true
            }))
            return
          }
        }
        const episode = episodes.find((ep) => episodeDisplayLabel(ep) === folderName)
        if (project && episode) {
          handleHistoryEpisodeClick(project, episode)
          setExpandedNodes((m) => ({
            ...m,
            [`project-${projectId}`]: true,
            [`episode-${projectId}-${episode.episodeId ?? 0}`]: true
          }))
        }
      } else if (selectedNode?.startsWith('episode-')) {
        const m = selectedNode.match(/^episode-(\d+)-(\d+)$/)
        if (m) {
          const projectId = Number(m[1])
          const episodeId = Number(m[2])
          const project = findAssetCenterProject(assetCenterTree, projectId)
          const episode = findAssetCenterEpisode(project, episodeId)
          const cat = episode?.children?.find(
            (c) => (c.categoryName || c.categoryCode) === folderName
          )
          if (project && episode && cat) {
            selectHistoryCategory(project, episode, cat)
            setExpandedNodes((mm) => ({
              ...mm,
              [`project-${projectId}`]: true,
              [`episode-${projectId}-${episodeId}`]: true
            }))
          }
        }
      }
    }
  }

  // 导航到根目录
  function navigateToRoot() {
    setCurrentPath([])
  }

  // 导航到面包屑指定位置
  function navigateToBreadcrumb(index: number) {
    // 计算需要保留的路径长度
    // 面包屑格式：个人资产库 > 项目名 > [分类] > 路径1 > 路径2...
    // 个人资产库不算在路径中，需要计算项目名和分类占用的位置
    if (selectedNode) {
      const parts = selectedNode.split('-')
      let basePathLength = 1 // 项目名或素材库

      if (activeTab === 'material') {
        // 素材库标签页
        if (selectedNode !== 'material-library') {
          // 有分类，分类也占一个位置
          basePathLength = 2
        }
      } else {
        // 其他标签页
        if (parts.length > 2 || (parts[0] === 'project' && parts.length === 2)) {
          // 有分类，分类也占一个位置
          basePathLength = 2
        }
      }

      const targetIndex = index - basePathLength
      if (targetIndex >= 0) {
        setCurrentPath((p) => p.slice(0, targetIndex + 1))
      } else {
        setCurrentPath([])
        // 如果回到根目录，同步选中状态
        navigateToRoot()
      }
    }
  }

  // 确认
  async function handleConfirm() {
    if (multiple) {
      if (selectedAssets.length === 0) {
        message.warning('请至少选择一项资产')
        return
      }
      if (selectedAssets.some((asset) => !isAcceptedImportAsset(asset, acceptAssetType))) {
        message.error('所选内容包含不支持的资产类型，请重新选择')
        return
      }
      const count = selectedAssets.length
      if (!(await runImportCallback(onImportMultiple, [...selectedAssets]))) return
      onOpenChange(false)
      setSelectedAssets([])
      message.success(`已选择 ${count} 项`)
    } else {
      if (!selectedAsset) {
        message.warning('请选择要导入的资产')
        return
      }
      if (!isAcceptedImportAsset(selectedAsset, acceptAssetType)) {
        message.error(
          isScriptImportContext()
            ? '剧本导入仅支持文本脚本'
            : `仅支持导入${acceptAssetType === 'image' ? '图片' : '视频'}`
        )
        return
      }
      if (isVideoAsset(selectedAsset)) {
        if (!(await runImportCallback(onImport, selectedAsset))) return
        onOpenChange(false)
        message.success('视频已导入')
        setSelectedAsset(null)
        return
      }
      if (isImageAsset(selectedAsset)) {
        if (!(await runImportCallback(onImport, selectedAsset))) return
        onOpenChange(false)
        message.success('图片已导入')
        setSelectedAsset(null)
      } else if (isScriptAsset(selectedAsset)) {
        try {
          const content = await resolveSelectedScriptContent(selectedAsset)
          await emitScriptImport(content)
        } catch (error: unknown) {
          const cause = error as { msg?: string; message?: string }
          message.error(cause?.msg || cause?.message || '读取剧本文本失败')
        }
      } else {
        message.warning('请选择要导入的资产')
      }
    }
  }

  const modalWidth =
    typeof window === 'undefined' ? 1200 : Math.min(1200, Math.max(320, window.innerWidth - 48))

  return (
    <Modal
      open={open}
      width={modalWidth}
      footer={null}
      title={null}
      closable={false}
      zIndex={modalZIndex}
      centered
      className="import-script-modal"
      wrapClassName="create-flow-modal import-script-modal-wrap"
      onCancel={handleCancel}
    >
      <div className="import-script-modal-shell">
        <header className="modal-header">
          <div className="header-top">
            <ModalTitleWatermark title={modalTitle} watermark="IMPORT" />
            <button
              type="button"
              className="import-script-close"
              aria-label="关闭"
              onClick={handleCancel}
            >
              <CloseOutlined />
            </button>
          </div>
          <div className="header-tabs">
            <div className="import-tab-bar__inner">
              <div className="import-tab-inner">
                <button
                  type="button"
                  className={`import-tab${activeTab === 'current' ? ' import-tab--active' : ''}`}
                  onClick={() => setActiveTab('current')}
                >
                  本作品资产
                </button>
                <button
                  type="button"
                  className={`import-tab${activeTab === 'history' ? ' import-tab--active' : ''}`}
                  onClick={() => setActiveTab('history')}
                >
                  历史作品资产
                </button>
                <button
                  type="button"
                  className={`import-tab${activeTab === 'material' ? ' import-tab--active' : ''}`}
                  onClick={() => setActiveTab('material')}
                >
                  素材库
                </button>
                <button
                  type="button"
                  className={`import-tab${activeTab === 'shared' ? ' import-tab--active' : ''}`}
                  onClick={() => setActiveTab('shared')}
                >
                  共享给我的资产
                </button>
              </div>
            </div>
          </div>
        </header>

        <div className="import-container">
      <div className="sidebar">
            <div className="sidebar-header">
              <h3 className="sidebar-title">{sidebarTitle}</h3>
            </div>
            <ImportScriptSidebarTree
              activeTab={activeTab}
              displayProjectId={displayProjectId}
              currentProject={currentProject}
              currentEpisodeCategories={currentEpisodeCategories}
              assetCenterTree={assetCenterTree}
              assetCenterTreeLoading={assetCenterTreeLoading}
              expandedNodes={expandedNodes}
              selectedNode={selectedNode}
              onToggleNode={toggleNode}
              onProjectClick={handleProjectClick}
              onSelectCurrentCategory={selectCurrentCategory}
              onHistoryProjectClick={handleHistoryProjectClick}
              onHistoryEpisodeClick={handleHistoryEpisodeClick}
              onSelectHistoryCategory={selectHistoryCategory}
              onSelectMaterialLibrary={selectMaterialLibrary}
              onSelectMaterialCategory={selectMaterialCategory}
            />
          </div>

          {/* 右侧：资产库 */}
          <div className="main-content">
            {/* 面包屑导航 */}
            <div className="breadcrumb">
              <span
                className={`breadcrumb-item ${
                  isBreadcrumbCurrent(-1) ? 'breadcrumb-current' : 'breadcrumb-muted'
                }`}
                onClick={navigateToRoot}
              >
                个人资产库
              </span>
              {breadcrumbPath.map((item, index) => (
                <span key={index} style={{ display: 'contents' }}>
                  <RightOutlined className="breadcrumb-separator" />
                  <span
                    className={`breadcrumb-item ${
                      isBreadcrumbCurrent(index) ? 'breadcrumb-current' : 'breadcrumb-muted'
                    }`}
                    onClick={() => navigateToBreadcrumb(index)}
                  >
                    {item}
                  </span>
                </span>
              ))}
            </div>

            {/* 内容区域 */}
            <div className="content-area">
              <AssetList
                category={selectedNode}
                type={activeTab}
                projectId={getProjectId()}
                episodeId={importListEpisodeId}
                assetCenterTree={assetCenterTree}
                currentPath={currentPath}
                selectedCategory={selectedCategory}
                multiple={multiple}
                selectedAssetIds={multiple ? selectedAssetIds : []}
                acceptAssetType={acceptAssetType}
                modalOpen={open}
                onSelect={handleAssetSelect}
                onNavigate={handleNavigate}
              />
            </div>
          </div>
        </div>

        {/* 底部操作栏 */}
        <div className="modal-footer">
          <div className="footer-left">
            <Button className="import-btn-dashed" onClick={handleSelectFile} icon={<UploadOutlined />}>
              选择本地文件
            </Button>
          </div>
          <div className="footer-right">
            <Button onClick={handleCancel}>
              <div className="text-gradient">取消</div>
            </Button>
            <Button
              type="primary"
              disabled={multiple ? selectedAssets.length === 0 : !selectedAsset}
              onClick={() => void handleConfirm()}
            >
              确定
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  )
}

export default ImportScriptModal

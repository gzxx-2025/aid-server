'use client'

import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import {
  UndoOutlined,
  RedoOutlined,
  CopyOutlined,
  DeleteOutlined,
  FileAddOutlined,
  HistoryOutlined,
  FileTextOutlined,
  CloseOutlined
} from '@ant-design/icons'
import { Button, Drawer, Input, Modal, Tooltip, message } from 'antd'
import type { InputRef } from 'antd'
import RichTextEditor from '~/components/common/RichTextEditor'
import ImportScriptModal from './ImportScriptModal'
import { htmlToPlainText, isHtmlContentEmpty } from '~/utils/htmlPlain'
import {
  decorateCharacterSettingHtml,
  decoratePropSettingHtml,
  decorateSceneSettingHtml
} from '~/utils/characterSettingProfile'
import './SceneSettingModal.css'

interface Props {
  open: boolean
  /** 资产展示名（场景名 / 角色名 / 道具名） */
  sceneName: string
  initialContent?: string
  /** 设定类型：决定标题与主按钮文案；场景保留「仅保存 + 保存并更新场景图」双操作 */
  settingVariant?: 'scene' | 'character' | 'prop'
  /** 形态提示词模式：直接编辑接口返回的提示词，不装饰为主资产结构化设定。 */
  promptOnly?: boolean
  /** 非 auto 形态只读；保存按钮由 tooltip 解释不可编辑原因。 */
  editable?: boolean
  readOnlyTip?: string
  onOpenChange: (value: boolean) => void
  /** 标题失焦/回车：与列表改名将调用的接口一致，由父级调用 userAssetRpsUpdateMain({ name }) */
  onSyncTitle?: (assetDisplayName: string) => void
  onSave?: (content: string) => void
  onSaveAndUpdate?: (content: string) => void
}

interface HistoryVersion {
  id: string
  content: string
  createdAt: string
}

function getAssetTitlePrefix(fullName: string, variant: 'scene' | 'character' | 'prop'): string {
  const m =
    variant === 'scene'
      ? fullName.match(/^(场景\d+):/)
      : variant === 'character'
        ? fullName.match(/^(角色\d+):/)
        : fullName.match(/^(道具\d+):/)
  return m ? m[1] + ':' : ''
}

function getAssetTitleSuffix(fullName: string, variant: 'scene' | 'character' | 'prop'): string {
  const re =
    variant === 'scene'
      ? /^场景\d+:\s*(.+)$/
      : variant === 'character'
        ? /^角色\d+:\s*(.+)$/
        : /^道具\d+:\s*(.+)$/
  const m = fullName.match(re)
  return m ? m[1] : fullName
}

function buildFullDisplayName(prefix: string, suffixTrimmed: string): string {
  if (!suffixTrimmed) return ''
  return prefix ? `${prefix} ${suffixTrimmed}` : suffixTrimmed
}

// 撤销/重做历史
const maxHistorySize = 50

export function SceneSettingModal({
  open,
  sceneName,
  initialContent = '',
  settingVariant = 'scene',
  promptOnly = false,
  editable = true,
  readOnlyTip = '手添加的提示词禁止修改',
  onOpenChange,
  onSyncTitle,
  onSave,
  onSaveAndUpdate
}: Props) {
  const titlePrefix = getAssetTitlePrefix((sceneName || '').trim(), settingVariant)

  const titleInputPlaceholder = (() => {
    const v = settingVariant
    const label = v === 'scene' ? '场景' : v === 'character' ? '角色' : '道具'
    return `${label}名称`
  })()

  const editingNameSuffixRef = useRef('')
  const [editingNameSuffix, setEditingNameSuffixState] = useState('')
  const setEditingNameSuffix = (v: string) => {
    editingNameSuffixRef.current = v
    setEditingNameSuffixState(v)
  }

  const resolvedFullAssetName = () =>
    buildFullDisplayName(titlePrefix, (editingNameSuffixRef.current || '').trim())

  /** 与列表一致：未编辑时展示从父级同步的后缀文案 */
  const titleSuffixDisplay = getAssetTitleSuffix((sceneName || '').trim(), settingVariant)

  const [editingTitleField, setEditingTitleField] = useState(false)
  const titleInputRef = useRef<InputRef | null>(null)

  function startTitleEdit() {
    setEditingNameSuffix(titleSuffixDisplay)
    setEditingTitleField(true)
    // 原 nextTick + $el.querySelector('input')：无 affix 时 antd 输入框根节点即 input 本身，
    // querySelector 取不到子 input（与原版行为一致），此处按原结构逐字平移
    requestAnimationFrame(() => {
      const root = titleInputRef.current?.nativeElement
      const el = root?.querySelector?.('input') as HTMLInputElement | undefined
      el?.focus()
      el?.select?.()
    })
  }

  function handleTitleSuffixCommit() {
    const full = resolvedFullAssetName()
    const current = (sceneName || '').trim()
    setEditingTitleField(false)
    if (!full) {
      message.warning('请填写名称')
      setEditingNameSuffix(getAssetTitleSuffix(current, settingVariant))
      return
    }
    if (full === current) {
      setEditingNameSuffix(getAssetTitleSuffix(current, settingVariant))
      return
    }
    onSyncTitle?.(full)
  }

  const showSceneDualSave = settingVariant === 'scene'

  const primarySyncLabel =
    promptOnly
      ? '保存形态图设定'
      : settingVariant === 'character'
        ? '保存并同步角色设定'
        : '保存并同步道具设定'

  /** 场景 / 角色 / 道具：小节标题转 Embed，禁止删改标题行 */
  const usesStructuredSettingLock =
    !promptOnly &&
    (settingVariant === 'character' || settingVariant === 'scene' || settingVariant === 'prop')

  const settingWarmTip = (() => {
    if (promptOnly) return ''
    if (settingVariant === 'character') {
      return '小节标题（角色介绍、基本信息、视觉关键词、性格标签、推荐色系、主要识别特征）请勿删除或修改'
    }
    if (settingVariant === 'scene') {
      return '小节标题（概要、场景视觉描述、角色可落位、人群）请勿删除或修改；角色可落位每行一条槽位；人群须写「有人群」或「无人群」，无人群时说明可留空。'
    }
    if (settingVariant === 'prop') {
      return '小节标题（道具概要、道具视觉描述）请勿删除或修改；概要为人读简介，视觉描述供生成配图。'
    }
    return ''
  })()

  const localContentRef = useRef(initialContent)
  const [localContent, setLocalContentState] = useState(initialContent)
  const setLocalContent = (v: string) => {
    localContentRef.current = v
    setLocalContentState(v)
  }
  const [showHistoryPanel, setShowHistoryPanel] = useState(false)
  const [showImportModal, setShowImportModal] = useState(false)
  const [selectedHistoryId, setSelectedHistoryId] = useState<string | null>(null)

  // 撤销/重做历史（逻辑读写走 ref，渲染值 canUndo/canRedo 随内容 state 变更一同刷新）
  const historyStackRef = useRef<string[]>([])
  const historyIndexRef = useRef(-1)

  // 保存到历史栈
  function saveToHistory(content: string) {
    if (historyStackRef.current[historyIndexRef.current] === content) {
      return
    }

    historyStackRef.current = historyStackRef.current.slice(0, historyIndexRef.current + 1)
    historyStackRef.current.push(content)

    if (historyStackRef.current.length > maxHistorySize) {
      historyStackRef.current.shift()
    } else {
      historyIndexRef.current = historyStackRef.current.length - 1
    }
  }

  // 历史版本列表
  const [historyVersions, setHistoryVersions] = useState<HistoryVersion[]>([])

  // 计算是否可以撤销/重做
  const canUndo = historyIndexRef.current > 0
  const canRedo = historyIndexRef.current < historyStackRef.current.length - 1

  // 监听初始内容变化（原 watch(() => props.initialContent, ..., { immediate: true })）
  useEffect(() => {
    const newVal = initialContent
    if (localContentRef.current !== newVal) {
      setLocalContent(newVal)
      if (historyStackRef.current.length === 0) {
        historyStackRef.current = [newVal]
        historyIndexRef.current = 0
      }
    }
     
  }, [initialContent])

  /** 打开/关闭设定弹窗 */
  useEffect(() => {
    if (!open) {
      setEditingTitleField(false)
      return
    }
    setEditingTitleField(false)
    setEditingNameSuffix(getAssetTitleSuffix((sceneName || '').trim(), settingVariant))
    // 原 nextTick：等编辑器同步完当前内容后再装饰小节标题
    const timer = window.setTimeout(() => {
      const raw = localContentRef.current || ''
      let decorated = raw
      if (usesStructuredSettingLock && settingVariant === 'character') {
        decorated = decorateCharacterSettingHtml(raw)
      } else if (usesStructuredSettingLock && settingVariant === 'scene') {
        decorated = decorateSceneSettingHtml(raw)
      } else if (usesStructuredSettingLock && settingVariant === 'prop') {
        decorated = decoratePropSettingHtml(raw)
      }
      if (decorated !== raw) {
        setLocalContent(decorated)
        saveToHistory(decorated)
      }
    }, 0)
    return () => window.clearTimeout(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 内容变化处理
  const handleContentChange = (value: string) => {
    setLocalContent(value)
    if (
      historyStackRef.current.length === 0 ||
      historyStackRef.current[historyIndexRef.current] !== localContentRef.current
    ) {
      saveToHistory(localContentRef.current)
    }
  }

  // 撤销
  const handleUndo = () => {
    if (!editable) return
    if (historyIndexRef.current > 0) {
      historyIndexRef.current--
      const content = historyStackRef.current[historyIndexRef.current]
      setLocalContent(content)
    }
  }

  // 重做
  const handleRedo = () => {
    if (!editable) return
    if (historyIndexRef.current < historyStackRef.current.length - 1) {
      historyIndexRef.current++
      const content = historyStackRef.current[historyIndexRef.current]
      setLocalContent(content)
    }
  }

  // 复制
  const handleCopy = async () => {
    if (isHtmlContentEmpty(localContentRef.current)) {
      message.warning('没有内容可复制')
      return
    }

    try {
      await navigator.clipboard.writeText(htmlToPlainText(localContentRef.current))
      message.success('已复制到剪贴板')
    } catch {
      message.error('复制失败')
    }
  }

  // 清空
  const handleClear = () => {
    if (!editable) return
    if (isHtmlContentEmpty(localContentRef.current)) {
      return
    }

    Modal.confirm({
      title: '确认清空',
      content: '确定要清空所有内容吗？此操作不可撤销。',
      onOk: () => {
        setLocalContent('')
        saveToHistory('')
        message.success('已清空')
      }
    })
  }

  // 选择历史版本
  const selectHistoryVersion = (version: HistoryVersion) => {
    setSelectedHistoryId(version.id)
  }

  // 恢复版本
  const restoreVersion = (version: HistoryVersion) => {
    if (!editable) return
    setLocalContent(version.content)
    saveToHistory(version.content)
    message.success('已恢复此版本')
    setShowHistoryPanel(false)
  }

  // 删除版本
  const deleteVersion = (id: string) => {
    setHistoryVersions((prev) => prev.filter((v) => v.id !== id))
    if (selectedHistoryId === id) {
      setSelectedHistoryId(null)
    }
    message.success('已删除')
  }

  // 格式化时间
  const formatTime = (time: string) => {
    const date = new Date(time)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }

  // 获取预览文本
  const getPreview = (content: string) => {
    const plain = htmlToPlainText(content)
    if (!plain) return '空内容'
    return plain.length > 100 ? plain.substring(0, 100) + '...' : plain
  }

  // 导入剧本
  const handleImport = (content: string) => {
    if (!editable) return
    setLocalContent(content)
    saveToHistory(content)
    message.success('导入成功')
    setShowImportModal(false)
  }

  // 仅保存
  const handleSaveOnly = () => {
    if (!editable) return
    onSave?.(localContentRef.current)
  }

  // 保存并更新场景图
  const handleSaveAndUpdate = () => {
    if (!editable) return
    onSaveAndUpdate?.(localContentRef.current)
  }

  // 取消
  const handleCancel = () => {
    onOpenChange(false)
  }

  return (
    <Modal
      open={open}
      width={1000}
      footer={null}
      title={null}
      closable={false}
      className={`scene-setting-modal${usesStructuredSettingLock ? ' scene-setting-modal--structured' : ''}`}
      wrapClassName="create-flow-modal scene-setting-modal-wrap"
      onCancel={handleCancel}
    >
      <div className="setting-modal-shell">
        <header className="setting-modal-header">
          <div className="modal-title-row" onMouseDown={(e) => e.stopPropagation()}>
            {titlePrefix ? <span className="modal-title-prefix">{titlePrefix}</span> : null}
            {editingTitleField ? (
              <Input
                ref={titleInputRef}
                value={editingNameSuffix}
                className="modal-title-input"
                size="small"
                variant="borderless"
                placeholder={titleInputPlaceholder}
                onChange={(e) => setEditingNameSuffix(e.target.value)}
                onBlur={handleTitleSuffixCommit}
                onPressEnter={(e) => {
                  e.preventDefault()
                  handleTitleSuffixCommit()
                }}
                onClick={(e) => e.stopPropagation()}
              />
            ) : (
              <span
                className="modal-title-editable"
                role="button"
                tabIndex={0}
                title={titleSuffixDisplay || '点击修改名称'}
                onClick={(e) => {
                  e.stopPropagation()
                  startTitleEdit()
                }}
                onKeyDown={(e: ReactKeyboardEvent) => {
                  if (e.key !== 'Enter') return
                  e.preventDefault()
                  startTitleEdit()
                }}
              >
                {titleSuffixDisplay || '（未命名）'}
              </span>
            )}
          </div>
          <button type="button" className="setting-modal-close" aria-label="关闭" onClick={handleCancel}>
            <CloseOutlined />
          </button>
        </header>

        <div className="setting-modal-body">
          <div className="modal-content">
            {/* 工具栏 */}
            <div className="toolbar">
              <div className="toolbar-left">
                <button className="toolbar-btn" onClick={handleUndo} disabled={!editable || !canUndo} title="撤销">
                  <UndoOutlined />
                  <span>撤销</span>
                </button>
                <button className="toolbar-btn" onClick={handleRedo} disabled={!editable || !canRedo} title="重做">
                  <RedoOutlined />
                  <span>重做</span>
                </button>
                <button className="toolbar-btn" onClick={handleCopy} title="复制">
                  <CopyOutlined />
                  <span>复制</span>
                </button>
                <button className="toolbar-btn" onClick={handleClear} disabled={!editable} title="清空">
                  <DeleteOutlined />
                  <span>清空</span>
                </button>
              </div>
              <div className="toolbar-right">
                <Button icon={<FileAddOutlined />} disabled={!editable} onClick={() => setShowImportModal(true)}>
                  导入文档
                </Button>
                <Button icon={<HistoryOutlined />} onClick={() => setShowHistoryPanel(true)}>
                  历史版本
                </Button>
              </div>
            </div>

            {/* 文本编辑器（Quill 无工具栏，存 HTML） */}
            <div className="editor-container">
              <RichTextEditor
                value={localContent}
                className={`script-editor${usesStructuredSettingLock ? ' script-editor--structured-setting' : ''}`}
                placeholder='可直接输入,或点击右上角"导入文档"按钮导入'
                minHeight="0"
                maxLength={10000}
                disabled={!editable}
                lockCharacterSettingKeys={usesStructuredSettingLock}
                onChange={handleContentChange}
              />
            </div>

            {settingWarmTip ? (
              <p className="setting-warm-tip" role="note">
                {settingWarmTip}
              </p>
            ) : null}
          </div>

          {/* 底部操作栏 */}
          <div className="modal-footer">
            <Button onClick={handleCancel}>
              <div className="text-gradient">取消</div>
            </Button>
            {showSceneDualSave ? (
              editable ? (
                <Button onClick={handleSaveOnly}>
                  <div className="text-gradient">仅保存</div>
                </Button>
              ) : (
                <Tooltip title={readOnlyTip}>
                  <span className="setting-save-disabled-tooltip">
                    <Button disabled>
                      <div className="text-gradient">仅保存</div>
                    </Button>
                  </span>
                </Tooltip>
              )
            ) : null}
            {showSceneDualSave ? (
              editable ? (
                <Button type="primary" onClick={handleSaveAndUpdate}>
                  保存并更新场景图
                </Button>
              ) : (
                <Tooltip title={readOnlyTip}>
                  <span className="setting-save-disabled-tooltip">
                    <Button type="primary" disabled>
                      保存并更新场景图
                    </Button>
                  </span>
                </Tooltip>
              )
            ) : (
              editable ? (
                <Button type="primary" onClick={handleSaveOnly}>
                  {primarySyncLabel}
                </Button>
              ) : (
                <Tooltip title={readOnlyTip}>
                  <span className="setting-save-disabled-tooltip">
                    <Button type="primary" disabled>
                      {primarySyncLabel}
                    </Button>
                  </span>
                </Tooltip>
              )
            )}
          </div>
        </div>
      </div>

      {/* 历史版本侧边栏 */}
      <Drawer
        open={showHistoryPanel}
        onClose={() => setShowHistoryPanel(false)}
        placement="right"
        size={400}
        rootClassName="scene-setting-history-drawer"
        title={
          <div className="drawer-title">
            <HistoryOutlined />
            <span>历史版本</span>
          </div>
        }
      >
        {historyVersions.length === 0 ? (
          <div className="empty-history">
            <FileTextOutlined className="empty-icon" />
            <p>暂无历史记录</p>
          </div>
        ) : (
          <div className="history-list">
            {historyVersions.map((version, index) => (
              <div
                key={version.id}
                className={`history-item${selectedHistoryId === version.id ? ' active' : ''}`}
                onClick={() => selectHistoryVersion(version)}
              >
                <div className="history-header">
                  <span className="history-title">版本 {historyVersions.length - index}</span>
                  <span className="history-time">{formatTime(version.createdAt)}</span>
                </div>
                <div className="history-preview">{getPreview(version.content)}</div>
                <div className="history-actions">
                  <Button
                    size="small"
                    type="link"
                    onClick={(e) => {
                      e.stopPropagation()
                      restoreVersion(version)
                    }}
                  >
                    恢复此版本
                  </Button>
                  <Button
                    size="small"
                    type="link"
                    danger
                    onClick={(e) => {
                      e.stopPropagation()
                      deleteVersion(version.id)
                    }}
                  >
                    删除
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Drawer>

      {/* 导入文档弹窗 */}
      <ImportScriptModal
        open={showImportModal}
        acceptAssetType="script"
        onOpenChange={setShowImportModal}
        onImport={handleImport}
      />
    </Modal>
  )
}

export default SceneSettingModal

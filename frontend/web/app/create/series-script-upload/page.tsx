'use client'

/**
 * 原 pages/create/series-script-upload.vue（definePageMeta layout: 'create'）。
 *
 * 创作壳已接线（app/create/layout.tsx → CreateFlowShell）：
 * - 侧栏 / 顶栏（剧集流程专用 chrome，见 utils/createFlowRoutes.isSeriesFlowChromePath）由壳层提供，
 *   本页仅渲染上传 / 解析 / 分集预览主体；
 * - html class `app-shell-create layout-create-flow` 已移交 app/create/layout.tsx 挂载；
 * - 本页未使用 createFlowShellContext（utils/createFlowInjection.ts），无 Context 降级需求。
 */

import { Suspense, useRef, useState } from 'react'
import type { ChangeEvent, DragEvent as ReactDragEvent } from 'react'
import { useRouter } from 'next/navigation'
import { message } from 'antd'
import { CloudUploadOutlined, CloseOutlined } from '@ant-design/icons'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import {
  creationStepAdvance,
  userScriptSplitConfirm,
  userScriptSplitPreview
} from '~/utils/businessApi'
import type { ScriptSplitPreviewVO } from '~/types/business-api'
import { CREATE_SERIES_EPISODE_LIST_PATH } from '~/utils/createFlowRoutes'
import { assertScriptPlainTextFile, validateScriptUploadFile } from '~/utils/scriptFileUpload'
import { noDataIconUrl as emptyImageIconRaw } from '~/utils/emptyImageIcon'
import { fetchUserProjectDetailOnce } from '~/utils/userProjectDetailOnce'
import { assetUrl } from '~/utils/assetUrl'
import starBlackIconRaw from '~/assets/img/icon/star_black.svg'
import './series-script-upload.css'

const emptyImageIconUrl = assetUrl(emptyImageIconRaw)
const starBlackIconUrl = assetUrl(starBlackIconRaw)

const MAX_SCRIPT_BYTES = 15 * 1024 * 1024

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb >= 10 ? kb.toFixed(0) : kb.toFixed(2)}KB`
  const mb = kb / 1024
  return `${mb.toFixed(2)}MB`
}

function readFileAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsText(file, 'UTF-8')
  })
}

function SeriesScriptUploadClient() {
  const router = useRouter()
  const route = useRouteLike()

  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [pendingFile, setPendingFile] = useState<File | null>(null)
  const [displayFileName, setDisplayFileName] = useState('')
  const [isDragging, setIsDragging] = useState(false)
  const [parsing, setParsing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [episodeKeyword, setEpisodeKeyword] = useState('第一集')
  const [scriptText, setScriptText] = useState('')
  const [previewData, setPreviewData] = useState<ScriptSplitPreviewVO | null>(null)
  const dragDepthRef = useRef(0)

  const hasFile = !!pendingFile

  const fileSizeLabel = pendingFile ? formatFileSize(pendingFile.size) : ''

  function pushRoute(path: string, query: Record<string, string | string[]>) {
    const params = new URLSearchParams()
    for (const [k, v] of Object.entries(query)) {
      if (Array.isArray(v)) {
        for (const item of v) params.append(k, item)
      } else {
        params.append(k, v)
      }
    }
    const qs = params.toString()
    router.push(qs ? `${path}?${qs}` : path)
  }

  function openPicker() {
    fileInputRef.current?.click()
  }

  function assignFile(file: File | undefined) {
    if (!file) return
    const formatError = validateScriptUploadFile(file)
    if (formatError) {
      message.warning(formatError)
      return
    }
    if (file.size > MAX_SCRIPT_BYTES) {
      message.warning('文件过大，请选择较小的 txt 文件')
      return
    }
    setPendingFile(file)
    setDisplayFileName(file.name)
    setPreviewData(null)
    setScriptText('')
  }

  function removeFile() {
    setPendingFile(null)
    setDisplayFileName('')
    setPreviewData(null)
    setScriptText('')
  }

  function backToFile() {
    setPreviewData(null)
    setScriptText('')
  }

  function onFileChange(e: ChangeEvent<HTMLInputElement>) {
    const input = e.target
    assignFile(input.files?.[0])
    input.value = ''
  }

  function onDragEnter(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    if (hasFile || previewData) return
    dragDepthRef.current += 1
    setIsDragging(true)
  }

  function onDragOver(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    if (hasFile || previewData) return
    setIsDragging(true)
  }

  function onDragLeave(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
    if (dragDepthRef.current === 0) setIsDragging(false)
  }

  function onDrop(e: ReactDragEvent<HTMLDivElement>) {
    e.preventDefault()
    dragDepthRef.current = 0
    setIsDragging(false)
    if (hasFile || previewData) return
    assignFile(e.dataTransfer?.files?.[0])
  }

  function buildFlowQuery(projectId: number, episodeId: number, projectType: string) {
    const q: Record<string, string> = {}
    for (const [k, v] of Object.entries(route.query)) {
      if (v === undefined || v === null) continue
      q[k] = Array.isArray(v) ? String(v[0] ?? '') : String(v)
    }
    q.projectId = String(projectId)
    q.id = String(projectId)
    q.episodeId = projectType === 'movie' ? '0' : String(episodeId)
    return q
  }

  function goEpisodeListSkip() {
    useCreationStore.getState().setSeriesFlowEnteredStoryScript(true)
    const q: Record<string, string | string[]> = {}
    for (const [k, v] of Object.entries(route.query)) {
      if (v === undefined || v === null) continue
      q[k] = Array.isArray(v) ? v.map((item) => String(item ?? '')) : v
    }
    pushRoute(CREATE_SERIES_EPISODE_LIST_PATH, q)
  }

  async function resolveSeriesProjectId(): Promise<number | null> {
    const routePid = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
    const store = useCreationStore.getState()
    const projectId =
      store.currentProjectId ?? (Number.isFinite(routePid) && routePid > 0 ? routePid : null)
    if (!projectId) return null

    let projectType = useCreationStore.getState().currentProjectType
    if (!projectType) {
      try {
        const detail = await fetchUserProjectDetailOnce(projectId)
        projectType = detail.projectType
        useCreationStore.getState().setCurrentProjectType(projectType)
      } catch {
        return null
      }
    }
    if (projectType !== 'series') {
      message.warning('仅剧集项目支持整篇分集导入')
      return null
    }
    return projectId
  }

  async function onPreviewSplit() {
    const file = pendingFile
    if (!file || parsing || confirming) return
    setParsing(true)
    try {
      const formatError = validateScriptUploadFile(file)
      if (formatError) {
        message.warning(formatError)
        return
      }
      try {
        await assertScriptPlainTextFile(file)
      } catch (e: unknown) {
        const err = e as { message?: string }
        message.error(err?.message || '内容非文本')
        return
      }

      const projectId = await resolveSeriesProjectId()
      if (!projectId) {
        message.error('缺少项目信息，请从首页创建剧集后进入')
        return
      }

      const text = (await readFileAsText(file)).trim()
      if (!text) {
        message.error('未能从文档中解析出文字，请检查文件内容')
        return
      }

      const keyword = episodeKeyword.trim() || '第一集'
      const preview = await userScriptSplitPreview({
        projectId,
        scriptText: text,
        episodeKeyword: keyword
      })
      if (!preview.totalEpisodes || !preview.items.length) {
        message.error('未识别分集词')
        return
      }

      setScriptText(text)
      setEpisodeKeyword(String(preview.episodeKeyword || keyword))
      setPreviewData(preview)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '分集预览失败，请稍后重试')
    } finally {
      setParsing(false)
    }
  }

  async function onConfirmSplit() {
    if (!previewData || confirming || parsing) return
    const text = scriptText.trim()
    if (!text) {
      message.error('剧本文本丢失，请重新解析')
      return
    }

    setConfirming(true)
    try {
      const projectId = await resolveSeriesProjectId()
      if (!projectId) {
        message.error('缺少项目信息，请从首页创建剧集后进入')
        return
      }

      const keyword = episodeKeyword.trim() || '第一集'
      const result = await userScriptSplitConfirm({
        projectId,
        scriptText: text,
        episodeKeyword: keyword
      })

      const first = result.episodes?.[0]
      const firstEpisodeId = Number(first?.episodeId)
      if (Number.isFinite(firstEpisodeId) && firstEpisodeId > 0) {
        useCreationStore
          .getState()
          .setCurrentProjectContext({ projectId, episodeId: firstEpisodeId })
      }

      try {
        await creationStepAdvance({
          projectId,
          episodeId:
            Number.isFinite(firstEpisodeId) && firstEpisodeId > 0 ? firstEpisodeId : undefined,
          completedStep: 2
        })
      } catch {
        /* 服务端未配置步骤时仍可进入分集列表 */
      }

      useCreationStore.getState().setSeriesFlowEnteredStoryScript(true)
      message.success(`已创建 ${result.totalEpisodes} 集`)
      pushRoute(
        CREATE_SERIES_EPISODE_LIST_PATH,
        buildFlowQuery(
          projectId,
          Number.isFinite(firstEpisodeId) && firstEpisodeId > 0 ? firstEpisodeId : 0,
          'series'
        )
      )
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '分集入库失败，请稍后重试')
    } finally {
      setConfirming(false)
    }
  }

  return (
    <div
      className={`series-script-upload${isDragging && !hasFile && !previewData ? ' is-dragging' : ''}`}
      onDragEnter={onDragEnter}
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      {/* 未选择文件：空状态 */}
      {!hasFile && !previewData ? (
        <div className="series-script-upload__panel">
          <div className="series-script-upload__icon" aria-hidden="true">
            <img src={emptyImageIconUrl} alt="" className="empty-image-icon empty-image-icon--xl" />
          </div>
          <p className="series-script-upload__hint">
            仅支持 .txt 纯文本格式，剧本字数不超过10万字，可拖拽至此处上传
          </p>
          <input
            ref={fileInputRef}
            type="file"
            className="series-script-upload__input"
            accept=".txt,text/plain"
            onChange={onFileChange}
          />
          <button type="button" className="series-script-upload__btn" onClick={openPicker}>
            <CloudUploadOutlined className="series-script-upload__btn-ico" />
            <span className="series-script-upload__btn-text">上传剧本</span>
          </button>
          <button type="button" className="series-script-upload__skip" onClick={goEpisodeListSkip}>
            暂不上传，直接进入剧集列表
          </button>
        </div>
      ) : !previewData ? (
        /* 已选文件：待解析 */
        <div className="series-script-upload__done">
          <div className="series-script-upload__file-card">
            <div className="series-script-upload__word-badge" aria-hidden="true">
              T
            </div>
            <div className="series-script-upload__file-info">
              <div className="series-script-upload__file-name">{displayFileName}</div>
              <div className="series-script-upload__file-meta">Txt·{fileSizeLabel}</div>
            </div>
            <button
              type="button"
              className="series-script-upload__close"
              aria-label="移除文件"
              onClick={removeFile}
            >
              <CloseOutlined />
            </button>
          </div>

          <label className="series-script-upload__keyword">
            <span className="series-script-upload__keyword-label">分集词样例</span>
            <input
              value={episodeKeyword}
              onChange={(e) => setEpisodeKeyword(e.target.value)}
              type="text"
              className="series-script-upload__keyword-input"
              placeholder="默认：第一集"
              maxLength={50}
            />
          </label>

          <p className="series-script-upload__guide">
            点击按钮预览自动分集结果（只解析不入库），确认后再创建各集剧本。
          </p>

          <button
            type="button"
            className="series-script-upload__parse"
            disabled={parsing}
            onClick={() => void onPreviewSplit()}
          >
            <img src={starBlackIconUrl} alt="" className="series-script-upload__parse-ico" />
            {parsing ? (
              <span className="series-script-upload__parse-loading">解析中…</span>
            ) : (
              <span>解析剧本</span>
            )}
          </button>
        </div>
      ) : (
        /* 分集预览确认 */
        <div className="series-script-upload__preview">
          <div className="series-script-upload__preview-head">
            <h2 className="series-script-upload__preview-title">分集预览</h2>
            <p className="series-script-upload__preview-meta">
              共 {previewData.totalEpisodes} 集
              {previewData.totalCharCount != null ? <> · {previewData.totalCharCount} 字</> : null}
              {previewData.episodeKeyword ? <> · 分集词「{previewData.episodeKeyword}」</> : null}
            </p>
          </div>

          <ul className="series-script-upload__preview-list">
            {previewData.items.map((item) => (
              <li key={item.episodeNo} className="series-script-upload__preview-item">
                <div className="series-script-upload__preview-item-title">
                  第{item.episodeNo}集 · {item.title}
                </div>
                <div className="series-script-upload__preview-item-desc">
                  {item.description || '暂无描述'}
                  {item.charCount != null ? <span>（{item.charCount} 字）</span> : null}
                </div>
              </li>
            ))}
          </ul>

          <div className="series-script-upload__preview-actions">
            <button
              type="button"
              className="series-script-upload__preview-back"
              disabled={confirming}
              onClick={backToFile}
            >
              重新选择
            </button>
            <button
              type="button"
              className="series-script-upload__parse"
              disabled={confirming}
              onClick={() => void onConfirmSplit()}
            >
              {confirming ? (
                <span className="series-script-upload__parse-loading">入库中…</span>
              ) : (
                <span>确认分集并入库</span>
              )}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

export default function SeriesScriptUploadPage() {
  return (
    <Suspense fallback={null}>
      <SeriesScriptUploadClient />
    </Suspense>
  )
}

'use client'

import { useRef, useState } from 'react'
import { Button, Input, Modal, message } from 'antd'
import { CloudUploadOutlined } from '@ant-design/icons'
import type { ScriptSplitPreviewVO } from '@/types/business-api'
import { useCreationStore } from '@/stores/creation'
import {
  userScriptSplitConfirm,
  userScriptSplitPreview
} from '@/utils/businessApi'
import { assertScriptPlainTextFile, validateScriptUploadFile } from '@/utils/scriptFileUpload'

const MAX_SCRIPT_BYTES = 15 * 1024 * 1024

function readFileAsText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsText(file, 'UTF-8')
  })
}

export function StudioFlowSeriesSplitWizard({
  open,
  projectId,
  onOpenChange,
  onCompleted
}: {
  open: boolean
  projectId: number
  onOpenChange: (open: boolean) => void
  onCompleted?: () => void
}) {
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [pendingFile, setPendingFile] = useState<File | null>(null)
  const [episodeKeyword, setEpisodeKeyword] = useState('第一集')
  const [scriptText, setScriptText] = useState('')
  const [previewData, setPreviewData] = useState<ScriptSplitPreviewVO | null>(null)
  const [parsing, setParsing] = useState(false)
  const [confirming, setConfirming] = useState(false)

  const reset = () => {
    setPendingFile(null)
    setEpisodeKeyword('第一集')
    setScriptText('')
    setPreviewData(null)
    setParsing(false)
    setConfirming(false)
  }

  const assignFile = (file: File | undefined) => {
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
    setPreviewData(null)
    setScriptText('')
  }

  const onPreviewSplit = async () => {
    const file = pendingFile
    if (!file || parsing || confirming) return
    setParsing(true)
    try {
      await assertScriptPlainTextFile(file)
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
    } catch (error) {
      message.error(error instanceof Error ? error.message : '分集预览失败，请稍后重试')
    } finally {
      setParsing(false)
    }
  }

  const onConfirmSplit = async () => {
    if (!previewData || confirming || parsing) return
    const text = scriptText.trim()
    if (!text) {
      message.error('剧本文本丢失，请重新解析')
      return
    }
    setConfirming(true)
    try {
      const keyword = episodeKeyword.trim() || '第一集'
      await userScriptSplitConfirm({
        projectId,
        scriptText: text,
        episodeKeyword: keyword
      })
      useCreationStore.getState().setSeriesFlowEnteredStoryScript(true)
      message.success(`已创建 ${previewData.totalEpisodes} 集，可在选集门进入各集创作`)
      onOpenChange(false)
      reset()
      onCompleted?.()
    } catch (error) {
      message.error(error instanceof Error ? error.message : '分集确认失败，请稍后重试')
    } finally {
      setConfirming(false)
    }
  }

  return (
    <Modal
      open={open}
      title="上传剧本并拆集"
      className="create-flow-modal"
      okText={previewData ? '确认创建分集' : '预览拆分结果'}
      cancelText="取消"
      confirmLoading={previewData ? confirming : parsing}
      onCancel={() => {
        onOpenChange(false)
        reset()
      }}
      onOk={() => void (previewData ? onConfirmSplit() : onPreviewSplit())}
    >
      <div className="studio-flow-series-split">
        <p>上传剧集总剧本 txt，按「第一集、第二集…」等关键词拆分为多集。</p>
        <input
          ref={fileInputRef}
          type="file"
          accept=".txt,text/plain"
          hidden
          onChange={(event) => {
            assignFile(event.target.files?.[0])
            event.target.value = ''
          }}
        />
        <Button icon={<CloudUploadOutlined />} onClick={() => fileInputRef.current?.click()}>
          {pendingFile ? pendingFile.name : '选择剧本文件'}
        </Button>
        <label className="studio-flow-series-split__field">
          <span>分集关键词</span>
          <Input
            value={episodeKeyword}
            placeholder="第一集"
            onChange={(event) => setEpisodeKeyword(event.target.value)}
          />
        </label>
        {previewData ? (
          <ul className="studio-flow-series-split__preview">
            {previewData.items.map((item) => (
              <li key={item.episodeNo}>
                <strong>第 {item.episodeNo} 集</strong>
                <span>{item.title || '未命名'}</span>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </Modal>
  )
}

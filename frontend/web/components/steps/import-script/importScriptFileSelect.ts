import { message } from 'antd'
import { useCreationStore } from '~/stores/creation'
import { userScriptUpload } from '~/utils/businessApi'
import { scriptApiTextToEditorHtml } from '~/utils/htmlPlain'
import {
  assertScriptPlainTextFile,
  SCRIPT_UPLOAD_ACCEPT,
  validateScriptUploadFile
} from '~/utils/scriptFileUpload'

export interface ImportScriptFilePickerOptions {
  acceptAssetType: 'image' | 'video' | 'script' | 'all'
  multiple: boolean
  /** 解析当前选中节点对应的项目数字 ID（供剧本上传） */
  getProjectId: () => string | null
  /** 与创作上下文一致的剧集 ID（>0 时随剧本上传传给接口） */
  getImportEpisodeId: () => number
  /** 导入剧本前确认（如覆盖当前编辑器内容）；返回 false 则取消导入 */
  confirmScriptImportIfNeeded: () => Promise<boolean>
  emitImport: (content: string | any) => Promise<boolean>
  emitImportMultiple: (assets: any[]) => Promise<boolean>
  closeModal: () => void
}

// 选择文件（原 handleSelectFile：本地文件 → 视频/图片走 OSS，剧本走 upload 接口或 FileReader 兜底）
export function openImportScriptFilePicker(opts: ImportScriptFilePickerOptions) {
  const input = document.createElement('input')
  input.type = 'file'
  if (opts.acceptAssetType === 'video') {
    input.accept = 'video/*'
  } else if (opts.acceptAssetType === 'image') {
    input.accept = 'image/*'
    if (opts.multiple) input.multiple = true
  } else {
    input.accept = SCRIPT_UPLOAD_ACCEPT
  }
  input.onchange = (e: any) => {
    const files = e.target.files as FileList | null
    if (!files?.length) return
    void (async () => {
      if (opts.acceptAssetType === 'video') {
        const file = files[0]
        if (!file.type.startsWith('video/')) {
          message.error('仅支持导入视频，请选择视频文件')
          return
        }
        const { uploadVideoToOssWithToast } = await import('~/utils/ossUpload')
        const url = await uploadVideoToOssWithToast(file)
        if (!url) return
        const name = file.name.replace(/\.[^/.]+$/, '') || '视频'
        if (!(await opts.emitImport({ type: 'video', url, name, title: name }))) return
        opts.closeModal()
        message.success('视频已导入')
        return
      }
      if (opts.acceptAssetType === 'image') {
        const imageFiles: File[] = []
        for (let i = 0; i < files.length; i++) {
          const file = files[i]
          if (!file.type.startsWith('image/')) {
            message.warning(`已跳过非图片：${file.name}`)
            continue
          }
          imageFiles.push(file)
        }
        if (!imageFiles.length) return
        const { uploadImagesToOssWithToast } = await import('~/utils/ossUpload')
        const urls = await uploadImagesToOssWithToast(imageFiles)
        if (!urls) return
        const now = new Date().toISOString()
        const items: any[] = urls.map((url, i) => {
          const file = imageFiles[i]!
          const base = file.name.replace(/\.[^/.]+$/, '') || `图片${i + 1}`
          return {
            id: `local-img-${Date.now()}-${i}`,
            type: 'image',
            url,
            thumbnail: url,
            name: base,
            title: base,
            updatedAt: now
          }
        })
        if (opts.multiple) {
          if (!(await opts.emitImportMultiple(items))) return
        } else {
          if (!(await opts.emitImport(items[0]))) return
        }
        opts.closeModal()
        message.success(`已导入 ${items.length} 张图片`)
        return
      }
      const file = files[0]
      const nameLower = (file.name || '').toLowerCase()
      const isScriptFile =
        opts.acceptAssetType === 'script' ||
        opts.acceptAssetType === 'all' ||
        nameLower.endsWith('.txt')
      if (isScriptFile) {
        const formatError = validateScriptUploadFile(file)
        if (formatError) {
          message.error(formatError)
          return
        }
        if (!(await opts.confirmScriptImportIfNeeded())) return
        const creationStore = useCreationStore.getState()
        const projectIdRaw =
          opts.getProjectId() ||
          (creationStore.currentProjectId ? String(creationStore.currentProjectId) : null)
        const projectId = projectIdRaw ? Number(projectIdRaw) : NaN
        if (Number.isFinite(projectId) && projectId > 0) {
          try {
            try {
              await assertScriptPlainTextFile(file)
            } catch (err: unknown) {
              const errObj = err as { message?: string }
              message.error(errObj?.message || '内容非文本')
              return
            }
            const importEpisodeId = opts.getImportEpisodeId()
            const row = await userScriptUpload({
              file,
              projectId,
              episodeId: importEpisodeId > 0 ? importEpisodeId : undefined
            })
            const html = scriptApiTextToEditorHtml(String(row.originalText ?? ''))
            if (!(await opts.emitImport(html))) return
            opts.closeModal()
            message.success('剧本上传成功')
          } catch (err: unknown) {
            const errObj = err as { msg?: string; message?: string }
            message.error(errObj?.msg || errObj?.message || '剧本上传失败')
          }
          return
        }
        const reader = new FileReader()
        reader.onload = (event: any) => {
          const content = event.target.result
          void (async () => {
            if (!(await opts.emitImport(content))) return
            opts.closeModal()
            message.success('导入成功')
          })()
        }
        reader.readAsText(file, 'UTF-8')
        return
      }
      const reader = new FileReader()
      reader.onload = (event: any) => {
        const content = event.target.result
        void (async () => {
          if (!(await opts.emitImport(content))) return
          opts.closeModal()
          message.success('导入成功')
        })()
      }
      reader.readAsText(file, 'UTF-8')
    })()
  }
  input.click()
}

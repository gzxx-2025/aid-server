import { message } from 'antd'
import { userReferenceAudioDelete,userReferenceAudioUpload } from '~/utils/businessApi'
import {
validateReferenceAudioAdd,
type ReferenceAudioCapability
} from '~/utils/referenceAudioCapability'
import { fromUploadedReferenceAudio,type ReferenceMediaItem } from '~/utils/referenceMediaItem'
import { resolveAudioRelativePath } from './assetGroups'

export interface UploadLocalReferenceAudiosOptions {
  files: File[]
  projectId: number
  episodeId?: number
  capability: ReferenceAudioCapability
  /** 每轮校验都取最新已选音频（上传过程中列表会增长） */
  getPendingAudioItems: () => ReferenceMediaItem[]
  appendItem: (media: ReferenceMediaItem) => void
}

export async function uploadLocalReferenceAudios(opts: UploadLocalReferenceAudiosOptions) {
  const projectId = Number(opts.projectId)
  if (!Number.isFinite(projectId) || projectId <= 0) {
    message.error('项目无效，无法上传参考音频')
    return
  }
  const { uploadAudioToOssWithToast } = await import('~/utils/ossUpload')
  let okCount = 0
  for (const file of opts.files) {
    const ext = String(file.name.split('.').pop() || '').toLowerCase()
    const preCheck = validateReferenceAudioAdd({
      capability: opts.capability,
      existing: opts.getPendingAudioItems(),
      next: { durationMs: undefined, audioFormat: ext }
    })
    if (!preCheck.ok) {
      message.warning('message' in preCheck ? preCheck.message : '参考音频不符合要求')
      break
    }
    const ossUrl = await uploadAudioToOssWithToast(file)
    if (!ossUrl) continue
    const audioUrl = resolveAudioRelativePath(ossUrl)
    const audioName = file.name.replace(/\.[^/.]+$/, '') || `参考音频${Date.now()}`
    try {
      const body: Parameters<typeof userReferenceAudioUpload>[0] = {
        projectId,
        audioName,
        audioUrl
      }
      const ep = Number(opts.episodeId)
      if (Number.isFinite(ep) && ep > 0) body.episodeId = ep
      const vo = await userReferenceAudioUpload(body)
      const media = fromUploadedReferenceAudio({
        id: Number(vo.id),
        audioName: vo.audioName || audioName,
        audioUrl: vo.audioUrl || ossUrl,
        durationMs: vo.durationMs,
        audioFormat: vo.audioFormat || ext
      })
      const postCheck = validateReferenceAudioAdd({
        capability: opts.capability,
        existing: opts.getPendingAudioItems(),
        next: { durationMs: media.durationMs, audioFormat: media.audioFormat }
      })
      if (!postCheck.ok) {
        try {
          await userReferenceAudioDelete({ id: Number(vo.id) })
        } catch {
          /* ignore rollback failure */
        }
        message.warning('message' in postCheck ? postCheck.message : '参考音频不符合要求')
        break
      }
      opts.appendItem(media)
      okCount += 1
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '参考音频登记失败')
      break
    }
  }
  if (okCount > 0) message.success(`已添加 ${okCount} 条参考音频`)
}

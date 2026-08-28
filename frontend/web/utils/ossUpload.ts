/**
 * OSS 上传业务封装（带 Toast），供各页面选择本地文件后写入可持久化 URL。
 * 接口见 components/steps/接口.md：/api/user/oss/upload（统一单/多文件入口）
 * 上传前按 /auth/public-config 的 upload 块做本地大小/扩展名校验。
 */
import { message } from 'antd'
import { loadPublicConfig } from '~/composables/useAuthPublicConfig'
import { ossRemoteUploadImages,ossRemoteUploadSingle } from '~/utils/businessApi'
import {
assertFilesAgainstUploadLimits,
checkFileAgainstUploadLimits
} from '~/utils/uploadLimitCheck'

export const OSS_DIR = {
  images: 'images',
  video: 'video',
  audio: 'audio',
  files: 'files',
  avatar: 'avatar'
} as const

async function resolveUploadConfig() {
  const config = await loadPublicConfig()
  return config?.upload ?? null
}

async function guardUploadFiles(files: File[]): Promise<boolean> {
  const upload = await resolveUploadConfig()
  const check = assertFilesAgainstUploadLimits(files, upload)
  if (check.ok === false) {
    message.error(check.message)
    return false
  }
  return true
}

export async function uploadImageToOssWithToast(file: File): Promise<string | null> {
  if (!(await guardUploadFiles([file]))) return null
  const close = message.loading('正在上传图片…', 0)
  try {
    const r = await ossRemoteUploadSingle(file, OSS_DIR.images)
    return r.url
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '图片上传失败')
    return null
  } finally {
    close()
  }
}

export async function uploadImagesToOssWithToast(files: File[]): Promise<string[] | null> {
  if (!files.length) return []
  if (!(await guardUploadFiles(files))) return null
  const close = message.loading(`正在上传 ${files.length} 张图片…`, 0)
  try {
    return await ossRemoteUploadImages(files, OSS_DIR.images)
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '图片上传失败')
    return null
  } finally {
    close()
  }
}

export async function uploadVideoToOssWithToast(file: File): Promise<string | null> {
  if (!(await guardUploadFiles([file]))) return null
  const close = message.loading('正在上传视频…', 0)
  try {
    const r = await ossRemoteUploadSingle(file, OSS_DIR.video)
    return r.url
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '视频上传失败')
    return null
  } finally {
    close()
  }
}

export async function uploadAudioToOssWithToast(file: File): Promise<string | null> {
  if (!(await guardUploadFiles([file]))) return null
  const close = message.loading('正在上传音频…', 0)
  try {
    const r = await ossRemoteUploadSingle(file, OSS_DIR.audio)
    return r.url
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '音频上传失败')
    return null
  } finally {
    close()
  }
}

export async function uploadFileToOssWithToast(file: File, customDir: string = OSS_DIR.files): Promise<string | null> {
  const upload = await resolveUploadConfig()
  const check = checkFileAgainstUploadLimits(file, upload)
  if (check.ok === false) {
    message.error(check.message)
    return null
  }
  const close = message.loading('正在上传文件…', 0)
  try {
    const r = await ossRemoteUploadSingle(file, customDir)
    return r.url
  } catch (e: unknown) {
    const err = e as { msg?: string; message?: string }
    message.error(err?.msg || err?.message || '文件上传失败')
    return null
  } finally {
    close()
  }
}

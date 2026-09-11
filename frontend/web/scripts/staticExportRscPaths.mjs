import { existsSync } from 'node:fs'
import { readdir, rename, rm, rmdir, stat } from 'node:fs/promises'
import path from 'node:path'

/**
 * Next 16 `output: 'export'` 客户端按 `convertSegmentPathToStaticExportFilename`
 * 请求扁平文件名：`__next.${segmentPath.replace(/\//g, '.')}.txt`。
 *
 * Windows 上 export 用 `path.relative` 带反斜杠写入嵌套目录，导致磁盘路径与请求 URL
 * 不一致（vercel/next.js#85374）。把嵌套段压成客户端期望的单文件名；已扁平则返回 null。
 *
 * @param {string} filePath
 * @returns {string | null}
 */
export function resolveFlattenedStaticExportRscPath(filePath) {
  const normalized = path.normalize(String(filePath || ''))
  if (!normalized || normalized === '.' || normalized === path.sep) return null

  const parts = normalized.split(path.sep)
  const idx = parts.findIndex((part) => part.startsWith('__next'))
  if (idx < 0 || idx >= parts.length - 1) return null

  const flatName = parts.slice(idx).join('.')
  if (!flatName.endsWith('.txt')) return null

  const parentParts = parts.slice(0, idx)
  if (parentParts.length === 0) {
    return path.isAbsolute(normalized)
      ? path.join(path.parse(normalized).root, flatName)
      : flatName
  }
  return path.join(...parentParts, flatName)
}

/**
 * @param {string} filePath
 * @returns {Promise<string | null>}
 */
export async function flattenStaticExportRscFile(filePath) {
  const targetPath = resolveFlattenedStaticExportRscPath(filePath)
  if (!targetPath || targetPath === filePath) return null
  if (!existsSync(filePath)) return null
  if (existsSync(targetPath)) {
    await rm(filePath, { force: true })
    return targetPath
  }
  await rename(filePath, targetPath)
  return targetPath
}

/**
 * @param {string} dir
 * @param {string[]} out
 */
async function walkTxtFiles(dir, out) {
  const entries = await readdir(dir, { withFileTypes: true })
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      await walkTxtFiles(full, out)
      continue
    }
    if (entry.isFile() && entry.name.endsWith('.txt')) out.push(full)
  }
}

/**
 * @param {string} dir
 */
async function removeEmptyDirs(dir) {
  if (!existsSync(dir)) return
  const entries = await readdir(dir, { withFileTypes: true })
  for (const entry of entries) {
    if (entry.isDirectory()) {
      await removeEmptyDirs(path.join(dir, entry.name))
    }
  }
  const left = await readdir(dir)
  if (left.length === 0) {
    // Node.js on Windows rejects rm(path, { recursive: false }) for directories
    // with EISDIR. rmdir is the cross-platform primitive for an empty directory.
    await rmdir(dir).catch((error) => {
      // A concurrent exporter may add a file after the emptiness check. In that
      // case the directory is no longer disposable and should simply be kept.
      if (error?.code !== 'ENOTEMPTY' && error?.code !== 'ENOENT') throw error
    })
  }
}

/**
 * 扫描静态导出根目录，把嵌套 `__next*` RSC 段文件压平为客户端 URL 形态（幂等）。
 *
 * @param {string} rootDir
 * @returns {Promise<{ renamed: number }>}
 */
export async function flattenStaticExportRscTree(rootDir) {
  const root = path.resolve(rootDir)
  const info = await stat(root).catch(() => null)
  if (!info?.isDirectory()) return { renamed: 0 }

  const files = []
  await walkTxtFiles(root, files)
  files.sort((a, b) => b.length - a.length)

  let renamed = 0
  for (const file of files) {
    const target = await flattenStaticExportRscFile(file)
    if (target) renamed += 1
  }

  await removeEmptyDirs(root)
  return { renamed }
}

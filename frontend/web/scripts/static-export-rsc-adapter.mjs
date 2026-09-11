/**
 * Next.js build adapter：在静态导出完成后把 RSC 段文件压成客户端请求路径。
 * @see https://github.com/vercel/next.js/issues/85374
 */
import path from 'node:path'
import { flattenStaticExportRscFile, flattenStaticExportRscTree } from './staticExportRscPaths.mjs'

/** @type {import('next').NextAdapter} */
const adapter = {
  name: 'fix-static-export-rsc-path-mismatch',
  async onBuildComplete({ outputs, projectDir }) {
    const files = outputs?.staticFiles ?? []
    for (const file of files) {
      if (!file?.filePath) continue
      await flattenStaticExportRscFile(file.filePath)
    }
    // 再扫一遍 out/，覆盖 staticFiles 列表可能漏掉的嵌套段文件
    await flattenStaticExportRscTree(path.join(projectDir, 'out'))
  }
}

export default adapter

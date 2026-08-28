import { readFileSync } from 'node:fs'

const LOCAL_CSS_IMPORT = /^@import\s+['"](.+\.css)['"];?\s*$/gm

/** Reads a CSS entry plus its local split files for source-level layout guards. */
export function readCssImportGraph(entryUrl: URL, visited = new Set<string>()): string {
  const key = entryUrl.href
  if (visited.has(key)) return ''
  visited.add(key)
  const source = readFileSync(entryUrl, 'utf-8')
  const imported = [...source.matchAll(LOCAL_CSS_IMPORT)]
    .map((match) => readCssImportGraph(new URL(match[1]!, entryUrl), visited))
    .join('\n')
  return `${source}\n${imported}`
}

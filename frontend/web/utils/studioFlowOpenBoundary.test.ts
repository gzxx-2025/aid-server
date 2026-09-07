import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const ROOTS = [
  'app/create/studio',
  'components/studio',
  'hooks/studio',
  'utils/studio'
]
const FILES = ['stores/studioUi.ts', 'types/studio.ts']

function walk(path: string): string[] {
  if (!statSync(path).isDirectory()) return [path]
  return readdirSync(path).flatMap((name) => walk(join(path, name)))
}

describe('open-source flow canvas boundary', () => {
  it('contains no free-canvas or director-workspace implementation', () => {
    const files = [...ROOTS.flatMap(walk), ...FILES]
      .filter((path) => /\.(?:ts|tsx|css)$/.test(path))
    const source = files.map((path) => readFileSync(path, 'utf8')).join('\n')

    expect(files.some((path) => /(?:free|director)/i.test(path))).toBe(false)
    expect(source).not.toMatch(/\bStudioMode\b|StudioModeGate|StudioFree|DirectorWorkspace/)
    expect(source).not.toMatch(/@react-three|@imgly|mode\s*===?\s*['"]free['"]|studioMode/)
    expect(source).not.toMatch(/publicProjectPreviewNavigation|PublishCasePlazaModal|usePreviewPublicationState|useCreateFlowPublishExport/)
  })
})

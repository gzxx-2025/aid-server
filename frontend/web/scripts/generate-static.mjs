import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)))
const nextBin = join(projectRoot, 'node_modules', 'next', 'dist', 'bin', 'next')
const exportDir = join(projectRoot, 'out')
const publicDir = join(projectRoot, 'dist', 'public')

const build = spawnSync(process.execPath, [nextBin, 'build'], {
  cwd: projectRoot,
  env: { ...process.env, NEXT_STATIC_EXPORT: '1' },
  stdio: 'inherit'
})

if (build.error) throw build.error
if (build.status !== 0) process.exit(build.status ?? 1)
if (!existsSync(join(exportDir, 'index.html'))) {
  throw new Error('Next 静态导出缺少 out/index.html')
}

// A successful process is not enough: every declared static route must be in the release.
function verifyRoutes(directory, segments = []) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory() && !entry.name.startsWith('[') && !entry.name.startsWith('@') && !entry.name.startsWith('_')) {
      verifyRoutes(join(directory, entry.name), entry.name.startsWith('(') ? segments : [...segments, entry.name])
    } else if (entry.isFile() && /^page\.(?:tsx|ts|jsx|js)$/.test(entry.name)) {
      const route = segments.join('/')
      if (!existsSync(join(exportDir, route, 'index.html')) && !existsSync(join(exportDir, `${route}.html`))) {
        throw new Error(`静态导出缺少公开路由文件: /${route}`)
      }
    }
  }
}
verifyRoutes(join(projectRoot, 'app'))

rmSync(publicDir, { recursive: true, force: true })
mkdirSync(publicDir, { recursive: true })
cpSync(exportDir, publicDir, { recursive: true })

const notFoundPath = join(publicDir, '404.html')
const fallbackSource = existsSync(notFoundPath)
  ? readFileSync(notFoundPath, 'utf8')
  : readFileSync(join(publicDir, 'index.html'), 'utf8')
writeFileSync(join(publicDir, '200.html'), fallbackSource, 'utf8')

console.log(`\nStatic release generated: ${publicDir}`)

import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
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

rmSync(publicDir, { recursive: true, force: true })
mkdirSync(publicDir, { recursive: true })
cpSync(exportDir, publicDir, { recursive: true })

const notFoundPath = join(publicDir, '404.html')
const fallbackSource = existsSync(notFoundPath)
  ? readFileSync(notFoundPath, 'utf8')
  : readFileSync(join(publicDir, 'index.html'), 'utf8')
const legacyDynamicRouteRedirect = `<script>(function(){var p=location.pathname.replace(/\\/+$/,'');var m=p.match(/^\\/case\\/([^/]+)$/);var target='';if(m){var q=new URLSearchParams(location.search);q.set('id',decodeURIComponent(m[1]));target='/case/?'+q.toString()+location.hash}else{m=p.match(/^\\/user\\/([^/]+)$/);if(m){var u=new URLSearchParams(location.search);u.set('id',decodeURIComponent(m[1]));target='/user/?'+u.toString()+location.hash}}if(target)location.replace(target)})();</script>`
const dynamicRouteRedirects = legacyDynamicRouteRedirect
const fallbackHtml = fallbackSource.includes('<head>')
  ? fallbackSource.replace('<head>', '<head>' + dynamicRouteRedirects)
  : dynamicRouteRedirects + fallbackSource

writeFileSync(join(publicDir, '200.html'), fallbackHtml, 'utf8')

console.log(`\nStatic release generated: ${publicDir}`)

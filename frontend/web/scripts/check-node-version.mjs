/** Next 16 与动态路由 static-paths-worker 需要 Node >= 20.9。 */
const [major, minor] = process.versions.node.split('.').map(Number)
const ok = major > 20 || (major === 20 && minor >= 9)

if (ok) process.exit(0)

console.error(
  `[aid-pc] 当前 Node ${process.version} 过低。本项目需要 Node >= 20.9.0。\n` +
    `nvm-windows 可执行：nvm use 22.22.0  或  nvm use 20.13.1\n` +
    `切换后必须重新 npm run dev。Node 12 会让 /case/:id 等动态路由的 Jest worker 直接崩溃。`
)
process.exit(1)

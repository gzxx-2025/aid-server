import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

/** 路径别名与 tsconfig.paths 保持一致（~ / @ → 根；~/composables → hooks） */
const root = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig({
  resolve: {
    alias: [
      { find: /^~\/composables\/(.*)$/, replacement: `${root}hooks/$1` },
      { find: /^~\/(.*)$/, replacement: `${root}$1` },
      { find: /^@\/(.*)$/, replacement: `${root}$1` }
    ]
  },
  test: {
    include: ['**/*.test.ts', '**/*.test.tsx'],
    exclude: ['node_modules/**', '.next/**']
  }
})

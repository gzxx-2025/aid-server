import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import reactHooks from "eslint-plugin-react-hooks";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    ".nuxt/**",
    ".tmp/**",
    "dist/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    "public/tac/**",
  ]),
  {
    plugins: {
      "react-hooks": reactHooks,
    },
    rules: {
      // 对齐原项目（aid-pc eslint.config.js）：any 允许但告警
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-unused-vars": [
        "warn",
        {
          "argsIgnorePattern": "^_",
          "caughtErrorsIgnorePattern": "^_",
          "ignoreRestSiblings": true,
          "varsIgnorePattern": "^_"
        }
      ],
      // react-hooks v6 编译器类规则降为告警：迁移采用的 Mirrored（渲染镜像 ref）
      // 模式会在渲染期读写 ref（对齐原 Vue ref 语义），属刻意设计而非缺陷
      "react-hooks/refs": "warn",
      "react-hooks/set-state-in-effect": "warn",
      "react-hooks/immutability": "warn",
      "react-hooks/purity": "warn",
      "react-hooks/use-memo": "warn",
      "react-hooks/preserve-manual-memoization": "warn",
    },
  },
]);

export default eslintConfig;

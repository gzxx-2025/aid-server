import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons';

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd());
  const backendHost = env.VITE_BACKEND_HOST || 'http://127.0.0.1:8080';

  return {
    // 部署上下文路径：生产部署在 /admin/ 子路径（.env.production），开发保持根路径
    base: env.VITE_APP_CONTEXT_PATH || '/',
    plugins: [
      react(),
      createSvgIconsPlugin({
        iconDirs: [resolve(process.cwd(), 'src/assets/icons/svg')],
        symbolId: 'icon-[name]'
      })
    ],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },
    css: {
      preprocessorOptions: {
        less: {
          javascriptEnabled: true,
          modifyVars: {
            // 这里可以改 antd 主题变量，但主要靠 ConfigProvider 的 token
          }
        }
      }
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      open: true,
      proxy: {
        [env.VITE_APP_BASE_API || '/dev-api']: {
          target: backendHost,
          changeOrigin: true,
          rewrite: (path) => path.replace(new RegExp('^' + (env.VITE_APP_BASE_API || '/dev-api')), '')
        }
      }
    },
    build: {
      outDir: 'dist',
      assetsDir: 'static',
      sourcemap: false,
      target: 'es2015',
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          chunkFileNames: 'static/js/[name]-[hash].js',
          entryFileNames: 'static/js/[name]-[hash].js',
          assetFileNames: 'static/[ext]/[name]-[hash].[ext]',
          manualChunks: {
            react: ['react', 'react-dom', 'react-router-dom'],
            antd: ['antd', '@ant-design/icons'],
            vendor: ['axios', 'dayjs', 'lodash-es', 'framer-motion']
          }
        }
      }
    }
  };
});

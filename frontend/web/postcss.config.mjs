const config = {
  plugins: {
    '@tailwindcss/postcss': {},
    /** 与原 Nuxt 项目一致的 px→rem 方案：根字号档位见 assets/css/root-font-size.css */
    'postcss-pxtorem': {
      rootValue: 16,
      unitPrecision: 5,
      propList: ['*'],
      minPixelValue: 12,
      mediaQuery: true,
      exclude(file) {
        if (!file) return false
        if (/node_modules/i.test(file)) return true
        // 根字号档位保持 px；侧栏与 compact 相关文件自行控制 rem 换算
        if (/root-font-size\.css$/i.test(file)) return true
        if (/home-new-sidebar\.css$/i.test(file)) return true
        if (/create-flow-compact-viewport\.css$/i.test(file)) return true
        if (/compact-viewport-btn-radius\.css$/i.test(file)) return true
        return false
      }
    }
  }
}

export default config

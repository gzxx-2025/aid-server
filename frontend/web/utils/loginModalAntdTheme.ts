import type { ThemeConfig } from 'antd'

/** 仅包裹登录弹窗：不改全局 antd Input / Button。 */
export const LOGIN_MODAL_ANTD_THEME: ThemeConfig = {
  inherit: true,
  components: {
    Input: {
      controlHeight: 48,
      paddingInline: 16,
      fontSize: 14,
      borderRadius: 8,
      colorBorder: '#8e97a5',
      hoverBorderColor: '#ffffff',
      activeBorderColor: '#ffffff',
      activeShadow: 'none',
      colorBgContainer: 'rgba(18, 18, 18, 0.1)',
      hoverBg: 'rgba(18, 18, 18, 0.1)',
      activeBg: 'rgba(18, 18, 18, 0.1)',
      addonBg: 'transparent',
      colorText: '#ffffff',
      colorTextPlaceholder: '#8e97a5'
    },
    Button: {
      borderRadius: 8,
      controlHeight: 48,
      fontWeight: 500,
      contentFontSize: 18,
      defaultShadow: 'none',
      defaultBg: '#ffffff',
      defaultColor: '#121212',
      defaultBorderColor: '#ffffff',
      defaultHoverBg: '#ffffff',
      defaultHoverColor: '#121212',
      defaultHoverBorderColor: '#ffffff',
      defaultActiveBg: '#ffffff',
      defaultActiveColor: '#121212',
      defaultActiveBorderColor: '#ffffff'
    }
  }
}

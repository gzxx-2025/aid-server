import { theme, type ThemeConfig } from 'antd'

/**
 * Product-wide Ant Design theme.
 *
 * The legacy Nuxt application uses a dark shell on every route. Keeping the
 * palette in tokens makes regular components, portals and status variants use
 * the same dark algorithm instead of patching generated selectors one by one.
 */
export const AID_ANTD_THEME: ThemeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#00abd8',
    colorInfo: '#00abd8',
    colorLink: '#4ae7fd',
    colorBgBase: '#060a12',
    colorBgLayout: '#060a12',
    colorBgContainer: '#191a1d',
    colorBgElevated: '#191a1d',
    colorBgSpotlight: 'rgba(38, 50, 72, 0.96)',
    colorText: '#e6edf3',
    colorTextSecondary: '#8e97a5',
    colorTextTertiary: 'rgba(142, 151, 165, 0.72)',
    colorBorder: 'rgba(74, 231, 253, 0.28)',
    colorBorderSecondary: 'rgba(74, 231, 253, 0.16)',
    colorFillSecondary: 'rgba(74, 231, 253, 0.1)',
    colorFillTertiary: 'rgba(74, 231, 253, 0.06)',
    colorFillQuaternary: 'rgba(74, 231, 253, 0.04)',
    controlItemBgHover: 'rgba(14, 89, 250, 0.12)',
    controlItemBgActive: 'rgba(14, 89, 250, 0.22)',
    controlOutline: 'rgba(14, 89, 250, 0.2)',
    borderRadius: 8,
    borderRadiusLG: 12,
    wireframe: false
  },
  components: {
    Modal: {
      contentBg: '#191a1d',
      headerBg: '#191a1d',
      footerBg: '#191a1d',
      titleColor: '#e6edf3'
    },
    Message: {
      contentBg: '#191a1d'
    },
    Drawer: {
      colorBgElevated: '#191a1d',
      colorBgContainer: '#191a1d'
    },
    Select: {
      selectorBg: 'rgba(28, 38, 54, 0.92)',
      clearBg: '#191a1d',
      optionActiveBg: 'rgba(14, 89, 250, 0.12)',
      optionSelectedBg: 'rgba(14, 89, 250, 0.22)',
      optionSelectedColor: '#4ae7fd',
      multipleItemBg: 'rgba(14, 89, 250, 0.22)',
      hoverBorderColor: '#4ae7fd',
      activeBorderColor: '#00abd8',
      activeOutlineColor: 'rgba(14, 89, 250, 0.2)'
    },
    Input: {
      addonBg: 'rgba(28, 38, 54, 0.92)',
      hoverBg: 'rgba(28, 38, 54, 0.96)',
      activeBg: 'rgba(28, 38, 54, 0.96)',
      hoverBorderColor: '#4ae7fd',
      activeBorderColor: '#00abd8',
      activeShadow: '0 0 0 2px rgba(14, 89, 250, 0.2)'
    },
    InputNumber: {
      hoverBg: 'rgba(28, 38, 54, 0.96)',
      activeBg: 'rgba(28, 38, 54, 0.96)',
      hoverBorderColor: '#4ae7fd',
      activeBorderColor: '#00abd8',
      activeShadow: '0 0 0 2px rgba(14, 89, 250, 0.2)'
    },
    DatePicker: {
      hoverBg: 'rgba(28, 38, 54, 0.96)',
      activeBg: 'rgba(28, 38, 54, 0.96)',
      hoverBorderColor: '#4ae7fd',
      activeBorderColor: '#00abd8',
      activeShadow: '0 0 0 2px rgba(14, 89, 250, 0.2)',
      cellHoverBg: 'rgba(14, 89, 250, 0.12)',
      cellActiveWithRangeBg: 'rgba(14, 89, 250, 0.22)'
    },
    Dropdown: {
      colorBgElevated: '#191a1d'
    },
    Popover: {
      colorBgElevated: '#191a1d'
    },
    Tooltip: {
      colorBgSpotlight: 'rgba(38, 50, 72, 0.96)'
    }
  }
}

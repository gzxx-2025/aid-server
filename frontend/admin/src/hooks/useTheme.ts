import { theme as antdTheme } from 'antd';
import { useSettingsStore } from '@/store/useSettingsStore';

export function useAppTheme() {
  const primary = useSettingsStore((s) => s.theme);
  return {
    algorithm: antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: primary,
      colorInfo: primary,
      borderRadius: 8,
      colorBgLayout: '#f5f7fb',
      fontFamily:
        '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "PingFang SC", "Microsoft YaHei", sans-serif'
    },
    components: {
      Layout: {
        siderBg: '#ffffff',
        headerBg: '#ffffff',
        bodyBg: '#f5f7fb',
        headerHeight: 56,
        headerPadding: '0 20px'
      },
      Menu: {
        itemBg: 'transparent',
        subMenuItemBg: 'transparent',
        itemSelectedBg: 'rgba(37, 99, 235, 0.10)',
        itemSelectedColor: primary,
        itemHoverBg: 'rgba(37, 99, 235, 0.06)',
        itemHoverColor: primary,
        itemBorderRadius: 8
      },
      Tabs: {
        horizontalItemPadding: '6px 14px',
        cardBg: '#ffffff'
      },
      Card: {
        borderRadiusLG: 12
      },
      Button: {
        borderRadius: 8
      }
    }
  } as const;
}

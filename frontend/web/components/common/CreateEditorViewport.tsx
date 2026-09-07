'use client'

import { ConfigProvider, type ThemeConfig } from 'antd'
import { createContext, useContext, useMemo, useSyncExternalStore, type HTMLAttributes } from 'react'
import { createEditorScaleStyle, readCreateEditorScale } from '~/utils/createEditorViewport'

const CreateEditorViewportContext = createContext<number | null>(null)
const serverScale = () => 1

function subscribeViewport(callback: () => void) {
  window.addEventListener('resize', callback)
  window.visualViewport?.addEventListener('resize', callback)
  return () => {
    window.removeEventListener('resize', callback)
    window.visualViewport?.removeEventListener('resize', callback)
  }
}

/** Body portals inherit React context but need explicit CSS sizing tokens. */
export function useCreateEditorPortalStyle() {
  const scale = useContext(CreateEditorViewportContext)
  return scale == null ? undefined : createEditorScaleStyle(scale)
}

/**
 * Only the editor content is adaptive. Auxiliary dialogs remain siblings outside this
 * provider, so their own width contracts, mounting targets and theme stay unchanged.
 */
export function CreateEditorViewport(props: HTMLAttributes<HTMLDivElement>) {
  const scale = useSyncExternalStore(subscribeViewport, readCreateEditorScale, serverScale)
  const style = useMemo(() => createEditorScaleStyle(scale), [scale])
  const theme = useMemo<ThemeConfig>(() => ({
    token: {
      fontSize: 14 * scale,
      fontSizeSM: 12 * scale,
      fontSizeLG: 16 * scale,
      fontSizeXL: 20 * scale,
      controlHeight: 32 * scale,
      controlHeightSM: 24 * scale,
      controlHeightLG: 40 * scale,
      controlHeightXS: 16 * scale,
      borderRadius: 8 * scale,
      borderRadiusSM: 4 * scale,
      borderRadiusLG: 12 * scale,
      sizeUnit: 4 * scale,
      sizeStep: 4
    }
  }), [scale])

  return (
    <CreateEditorViewportContext.Provider value={scale}>
      <ConfigProvider
        theme={theme}
        select={{ styles: { popup: { root: style } } }}
        dropdown={{ styles: { root: style } }}
        tooltip={{ styles: { root: style } }}
        popover={{ styles: { root: style } }}
        popconfirm={{ styles: { root: style } }}
      >
        <div {...props} style={{ ...style, ...props.style }} />
      </ConfigProvider>
    </CreateEditorViewportContext.Provider>
  )
}

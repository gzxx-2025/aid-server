'use client'

import deleteIconRaw from '~/assets/img/icon/Delelte.svg'
import informationIconRaw from '~/assets/img/icon/information.svg'
import { assetUrl } from '~/utils/assetUrl'
import './AppConfirmIcon.css'

export type AppConfirmIconVariant = 'info' | 'warning' | 'danger'

const informationIcon = assetUrl(informationIconRaw)
const deleteIcon = assetUrl(deleteIconRaw)

/** 项目统一确认弹窗图标（原 components/atoms/AppConfirmIcon.vue） */
export function AppConfirmIcon({ variant = 'info' }: { variant?: AppConfirmIconVariant }) {
  const iconSrc = variant === 'danger' ? deleteIcon : informationIcon
  return (
    <span className={`app-confirm-icon app-confirm-icon--${variant}`} aria-hidden="true">
      <img className="app-confirm-icon__img" src={iconSrc} alt="" />
    </span>
  )
}

export default AppConfirmIcon

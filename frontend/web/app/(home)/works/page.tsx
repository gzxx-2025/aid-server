'use client'

import { Suspense } from 'react'
import WorksLibraryPanel from '~/components/home/WorksLibraryPanel'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useUserStore } from '~/stores/user'
import { requireLogin } from '~/utils/authLoginNavigation'

/** 原 pages/works.vue：layout=home-new，由 app/(home)/layout.tsx 承担壳层。 */
export default function WorksPage() {
  return (
    <Suspense fallback={null}>
      <WorksPageInner />
    </Suspense>
  )
}

/** WorksLibraryPanel 内部读 useSearchParams，构建期预渲染需 Suspense 边界（CSR bailout） */
function WorksPageInner() {
  const token = useUserStore((s) => s.token)

  const isLoggedIn = !!token
  const homeCreateModal = useHomeShellCreateModal()

  function onOpenCreate(tab: 'film' | 'series') {
    if (!isLoggedIn) {
      requireLogin()
      return
    }
    homeCreateModal.openCreateModal({ worksTab: tab })
  }

  return <WorksLibraryPanel onOpenCreate={onOpenCreate} />
}

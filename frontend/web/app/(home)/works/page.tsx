'use client'

import { useRouter } from 'next/navigation'
import { Suspense } from 'react'
import WorksLibraryPanel from '~/components/home/WorksLibraryPanel'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useUserStore } from '~/stores/user'

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
  const router = useRouter()
  const token = useUserStore((s) => s.token)

  const isLoggedIn = !!token
  const homeCreateModal = useHomeShellCreateModal()

  function goLogin() {
    router.push('/login')
  }

  function onOpenCreate(tab: 'film' | 'series') {
    if (!isLoggedIn) {
      goLogin()
      return
    }
    homeCreateModal.openCreateModal({ worksTab: tab })
  }

  return <WorksLibraryPanel onOpenCreate={onOpenCreate} />
}

'use client'

import WorksLibraryPanel from '~/components/home/WorksLibraryPanel'
import { useHomeShellCreateModal } from '~/composables/useHomeShellCreateModal'
import { useUserStore } from '~/stores/user'
import { requireLogin } from '~/utils/authLoginNavigation'

/** 原 pages/works.vue：layout=home-new，由 app/(home)/layout.tsx 承担壳层。 */
export default function WorksPage() {
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

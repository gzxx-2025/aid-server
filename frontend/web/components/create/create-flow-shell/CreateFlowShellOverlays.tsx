'use client'

import { lazy, Suspense, type ComponentProps } from 'react'
import UserMenuDropdown from '~/components/common/UserMenuDropdown'

const ExtractAgentModal = lazy(() =>
  import('~/components/steps/ExtractAgentModal').then((module) => ({
    default: module.ExtractAgentModal
  }))
)
const CreateFirstStepModal = lazy(() =>
  import('~/components/steps/CreateFirstStepModal').then((module) => ({
    default: module.CreateFirstStepModal
  }))
)
const ProjectGenConfigModal = lazy(() =>
  import('~/components/steps/ProjectGenConfigModal').then((module) => ({
    default: module.ProjectGenConfigModal
  }))
)
const RechargeModal = lazy(() =>
  import('~/components/common/RechargeModal').then((module) => ({ default: module.RechargeModal }))
)

interface CreateFlowShellOverlaysProps {
  extractModal: ComponentProps<typeof ExtractAgentModal>
  rechargeModal: ComponentProps<typeof RechargeModal>
  globalSettingModal: ComponentProps<typeof CreateFirstStepModal>
  projectConfigModal: ComponentProps<typeof ProjectGenConfigModal>
  userMenu: ComponentProps<typeof UserMenuDropdown>
}

export function CreateFlowShellOverlays(props: CreateFlowShellOverlaysProps) {
  return (
    <>
      <Suspense fallback={null}>
        <ExtractAgentModal {...props.extractModal} />
      </Suspense>
      <Suspense fallback={null}>
        <RechargeModal {...props.rechargeModal} />
      </Suspense>
      <Suspense fallback={null}>
        <CreateFirstStepModal {...props.globalSettingModal} />
      </Suspense>
      <Suspense fallback={null}>
        <ProjectGenConfigModal {...props.projectConfigModal} />
      </Suspense>
      <UserMenuDropdown {...props.userMenu} />
    </>
  )
}

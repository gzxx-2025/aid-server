import { lazy } from 'react'

export const PublishCasePlazaModal = lazy(() =>
  import('~/components/common/PublishCasePlazaModal')
)
export const ExtractAgentModal = lazy(() =>
  import('~/components/steps/ExtractAgentModal').then((module) => ({
    default: module.ExtractAgentModal
  }))
)
export const CreateFirstStepModal = lazy(() =>
  import('~/components/steps/CreateFirstStepModal').then((module) => ({
    default: module.CreateFirstStepModal
  }))
)
export const ProjectGenConfigModal = lazy(() =>
  import('~/components/steps/ProjectGenConfigModal').then((module) => ({
    default: module.ProjectGenConfigModal
  }))
)
export const RechargeModal = lazy(() =>
  import('~/components/common/RechargeModal').then((module) => ({
    default: module.RechargeModal
  }))
)

/** 新建项目弹窗左侧默认项（与字典第一项语义一致） */
export const CREATE_FIRST_STEP_DEFAULTS = {
  projectType: 'series' as const,
  scriptType: 'plot' as const,
  modelStrategy: 'economy' as const,
  creationMode: 'pro' as const
}

export type CreateFirstStepProjectType = (typeof CREATE_FIRST_STEP_DEFAULTS)['projectType']
export type CreateFirstStepScriptType = (typeof CREATE_FIRST_STEP_DEFAULTS)['scriptType']
export type CreateFirstStepModelStrategy = (typeof CREATE_FIRST_STEP_DEFAULTS)['modelStrategy']
export type CreateFirstStepCreationMode = (typeof CREATE_FIRST_STEP_DEFAULTS)['creationMode']

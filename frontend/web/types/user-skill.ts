/** Runtime catalog only exposes public identity for callable entrypoints. */
export interface UserSkillDefinition {
  id: number
  skillCode: string
  name?: string | null
  description?: string | null
  iconUrl?: string | null
  capability?: string | null
  outputKind?: string | null
}

export type UserSkillInputValue = string | number

export interface UserSkillInputOption {
  label: string
  value: UserSkillInputValue
}

export type UserSkillInputQuestionType =
  | 'single_select'
  | 'select_with_custom'
  | 'number'
  | 'text'
  | 'textarea'
  | 'multi_select'

export interface UserSkillInputQuestion {
  id: string
  field: string
  required: boolean
  reason?: string | null
  question: string
  inputType: UserSkillInputQuestionType
  options: UserSkillInputOption[]
  recommendedValue?: UserSkillInputValue | null
  defaultValue?: UserSkillInputValue | null
  allowCustom: boolean
  allowAiDecide: boolean
  min?: number | null
  max?: number | null
  unit?: string | null
}

export interface UserSkillConfirmedFact {
  field: string
  value: unknown
}

export interface UserSkillInputRequest {
  runId: number
  requestId: number
  round: number
  title?: string | null
  readiness?: string | null
  confirmedFacts: UserSkillConfirmedFact[]
  assumptions: string[]
  contextVersion: string
  schemaDigest: string
  questions: UserSkillInputQuestion[]
}

export interface UserSkillInputAnswer {
  questionId: string
  field: string
  value: UserSkillInputValue | UserSkillInputValue[]
}

export interface UserSkillInputResponseRequest {
  runId: number
  requestId: number
  responseKey: string
  contextVersion: string
  schemaDigest: string
  answers: UserSkillInputAnswer[]
  naturalLanguageAnswer?: string
}

export type UserSkillRuntimeOperation =
  | 'AUTO'
  | 'CREATE'
  | 'REWRITE'
  | 'CONTINUE'
  | 'NORMALIZE'
  | 'REPAIR'

export type UserSkillRuntimeQualityMode = 'AUTO' | 'NORMAL' | 'HIGH' | 'REVIEW_ONLY'
export type UserSkillRuntimeResponseMode = 'SCREENPLAY' | 'DIAGNOSTIC' | 'CHAT'

export interface UserSkillRuntimeReference {
  referenceType: 'TEXT' | 'PROJECT_ASSET'
  resourceId?: number
  text?: string
}

export interface UserSkillRuntimeInvokeRequest {
  skillCode: string
  idempotencyKey: string
  force?: boolean
  projectId: number
  episodeId: number
  parentRunId?: number
  operation: UserSkillRuntimeOperation
  qualityMode: UserSkillRuntimeQualityMode
  prompt?: string
  style?: string
  genre?: string
  language?: string
  targetDurationSeconds?: number
  references?: UserSkillRuntimeReference[]
}

export interface UserSkillRuntimeTaskHandle {
  stepId: number
  stepKey: string
  stepExecutionId: string
  workflowAttempt: number
  mediaTaskId?: number | null
  mediaStatus?: string | null
  billingStatus?: string | null
}

export interface UserSkillRuntimeRunHandle {
  runId: number
  rootRunId?: number | null
  parentRunId?: number | null
  skillCode: string
  skillVersionId: number
  generation: number
  status: 'CREATED' | 'NEEDS_INPUT' | 'RUNNING' | 'CANCELING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED' | string
  stage: string
  operation: UserSkillRuntimeOperation | string
  qualityMode: UserSkillRuntimeQualityMode | string
  prompt?: string | null
  responseMode?: UserSkillRuntimeResponseMode | string | null
  assistantMessage?: string | null
  requiredInput?: UserSkillInputRequest | null
  tasks: UserSkillRuntimeTaskHandle[]
  outputText?: string | null
  reviewReport?: string | null
  errorMessage?: string | null
}

export interface UserSkillRuntimeHistoryRequest {
  projectId: number
  episodeId: number
  skillCode: string
  beforeRunId?: number
  pageSize?: number
}

export interface UserSkillRuntimeHistoryPage {
  data: UserSkillRuntimeRunHandle[]
  hasMore: boolean
}

/** Persisted milestone. Its seq is the only reconnect cursor. */
export interface UserSkillRuntimeEventView {
  seq: number
  eventType: 'input_required' | 'stage' | 'progress' | 'task_linked' | 'artifact' | 'terminal' | string
  stage?: string | null
  stepId?: number | null
  mediaTaskId?: number | null
  payloadJson?: string | null
  createTime?: string | null
}

/** Non-persistent live delta. Persisted milestones remain the only reconnect cursor. */
export interface UserSkillRuntimeOutputDelta {
  content: string
  artifactType?: 'SCREENPLAY_TEXT' | 'REVIEW_REPORT' | string | null
  stepExecutionId?: string | null
  reset?: boolean
}

export interface UserSkillRuntimeEventsPage {
  data: UserSkillRuntimeEventView[]
  run: UserSkillRuntimeRunHandle
}

import type {
  UserSkillInputOption,
  UserSkillInputQuestion,
  UserSkillInputQuestionType,
  UserSkillInputRequest,
  UserSkillInputValue
} from '~/types/user-skill'

const INPUT_REQUEST_TYPES = new Set<UserSkillInputQuestionType>([
  'single_select',
  'select_with_custom',
  'number',
  'text',
  'textarea',
  'multi_select'
])

type UnknownRecord = Record<string, unknown>

function record(value: unknown): UnknownRecord | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as UnknownRecord
    : null
}

function parseJsonRecord(value: unknown): UnknownRecord | null {
  if (typeof value !== 'string' || !value.trim()) return null
  try {
    return record(JSON.parse(value))
  } catch {
    return null
  }
}

function inputValue(value: unknown): UserSkillInputValue | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim()) return value.trim()
  return null
}

function options(valueToParse: unknown): UserSkillInputOption[] {
  if (!Array.isArray(valueToParse)) return []
  return valueToParse.flatMap((candidate) => {
    const item = record(candidate)
    const optionValue = inputValue(item?.value)
    const label = String(item?.label || '').trim()
    return item && optionValue != null && label ? [{ label, value: optionValue }] : []
  })
}

function inputQuestion(valueToParse: unknown): UserSkillInputQuestion | null {
  const item = record(valueToParse)
  if (!item) return null
  const id = String(item.id ?? item.questionId ?? '').trim()
  const field = String(item.field || '').trim()
  const question = String(item.question || '').trim()
  const inputType = String(item.inputType || '').trim() as UserSkillInputQuestionType
  if (!id || !field || !question || !INPUT_REQUEST_TYPES.has(inputType)) return null
  const parsedOptions = options(item.options)
  if ((inputType === 'single_select' || inputType === 'select_with_custom') && !parsedOptions.length) {
    return null
  }
  const min = item.min == null || String(item.min).trim() === '' ? Number.NaN : Number(item.min)
  const max = item.max == null || String(item.max).trim() === '' ? Number.NaN : Number(item.max)
  return {
    id,
    field,
    required: item.required !== false,
    reason: String(item.reason || '').trim() || undefined,
    question,
    inputType,
    options: parsedOptions,
    recommendedValue: inputValue(item.recommendedValue),
    defaultValue: inputValue(item.defaultValue),
    allowCustom: item.allowCustom === true || inputType === 'number' || inputType === 'text',
    allowAiDecide: item.allowAiDecide === true,
    min: Number.isFinite(min) ? min : undefined,
    max: Number.isFinite(max) ? max : undefined,
    unit: String(item.unit || '').trim() || undefined
  }
}

export function normalizeUserSkillInputRequest(
  valueToParse: unknown,
  fallbackRunId?: number | null
): UserSkillInputRequest | null {
  const source = record(valueToParse) ?? parseJsonRecord(valueToParse)
  const item = record(source?.inputRequest) ?? source
  if (!item || !Array.isArray(item.questions)) return null
  const runId = Number(item.runId ?? fallbackRunId)
  const requestId = Number(item.requestId)
  const contextVersion = String(item.contextVersion || '').trim()
  const schemaDigest = String(item.schemaDigest || '').trim()
  const questions = item.questions.map(inputQuestion).filter((entry): entry is UserSkillInputQuestion => Boolean(entry))
  if (!Number.isFinite(runId) || runId <= 0 || !Number.isFinite(requestId) || requestId <= 0
    || !contextVersion || !schemaDigest) return null
  if (!questions.length || questions.length !== item.questions.length || questions.length > 4) return null
  const confirmedFacts = Array.isArray(item.confirmedFacts)
    ? item.confirmedFacts.flatMap((candidate) => {
        const fact = record(candidate)
        const field = String(fact?.field || '').trim()
        return field ? [{ field, value: fact?.value }] : []
      })
    : []
  const assumptions = Array.isArray(item.assumptions)
    ? item.assumptions.map((entry) => String(entry || '').trim()).filter(Boolean)
    : []
  const round = Number(item.round)
  return {
    runId,
    requestId,
    round: Number.isFinite(round) && round > 0 ? Math.floor(round) : 1,
    title: String(item.title || '').trim() || undefined,
    readiness: String(item.readiness || '').trim() || undefined,
    confirmedFacts,
    assumptions,
    contextVersion,
    schemaDigest,
    questions
  }
}

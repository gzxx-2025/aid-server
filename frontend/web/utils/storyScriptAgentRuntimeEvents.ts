import type {
  UserSkillRuntimeEventView,
  UserSkillRuntimeRunHandle
} from '~/types/user-skill'
import { userSkillRuntimeRunEvents } from '~/utils/business/skill'

type RuntimeEventPayload = Record<string, unknown>

function parseRuntimeEventPayload(event: UserSkillRuntimeEventView): RuntimeEventPayload {
  if (!event.payloadJson) return {}
  try {
    const parsed = JSON.parse(event.payloadJson) as unknown
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? parsed as RuntimeEventPayload
      : {}
  } catch {
    return {}
  }
}

export function runtimeMilestonePresentation(event: UserSkillRuntimeEventView): {
  stage: string
  message: string
} {
  const payload = parseRuntimeEventPayload(event)
  return {
    stage: String(payload.stage || event.stage || ''),
    message: String(payload.message || '')
  }
}

export async function recoverUserSkillRuntimeMilestones(input: {
  runId: number
  afterSeq: number
  onMilestone: (event: UserSkillRuntimeEventView) => void
}): Promise<{ afterSeq: number; run: UserSkillRuntimeRunHandle }> {
  const page = await userSkillRuntimeRunEvents(input.runId, input.afterSeq)
  let afterSeq = input.afterSeq
  const events = [...page.data].sort((left, right) => left.seq - right.seq)
  for (const event of events) {
    if (event.seq <= afterSeq) continue
    input.onMilestone(event)
    afterSeq = event.seq
  }
  return { afterSeq, run: page.run }
}

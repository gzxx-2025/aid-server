'use client'

import { useMemo, useState } from 'react'
import type {
  UserSkillInputValue,
  UserSkillInputAnswer,
  UserSkillInputQuestion,
  UserSkillInputRequest,
  UserSkillInputResponseRequest
} from '~/types/user-skill'

export const USER_SKILL_AI_DECIDE_VALUE = '__AI_DECIDE__'

interface Props {
  inputRequest: UserSkillInputRequest
  savedResponse?: UserSkillInputResponseRequest | null
  disabled?: boolean
  onSubmit: (
    inputRequest: UserSkillInputRequest,
    answers: UserSkillInputAnswer[]
  ) => boolean | Promise<boolean>
}

type AnswerValue = UserSkillInputValue | UserSkillInputValue[]

function isAnswered(value: AnswerValue | undefined): boolean {
  return Array.isArray(value) ? value.length > 0 : value !== undefined && String(value).trim() !== ''
}

function initialValue(question: UserSkillInputQuestion): AnswerValue | undefined {
  if (question.defaultValue != null) return question.defaultValue
  return undefined
}

export default function StoryScriptAgentInputRequestCard({
  inputRequest,
  savedResponse,
  disabled = false,
  onSubmit
}: Props) {
  const lockedAnswers = savedResponse?.answers ?? []
  const [values, setValues] = useState<Record<string, AnswerValue | undefined>>(() => {
    const savedByQuestion = new Map(lockedAnswers.map((answer) => [answer.questionId, answer.value]))
    return Object.fromEntries(inputRequest.questions.map((question) => [
      question.id,
      savedByQuestion.has(question.id) ? savedByQuestion.get(question.id) : initialValue(question)
    ]))
  })
  const [validationMessage, setValidationMessage] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const answersLocked = Boolean(savedResponse)

  const missingRequired = useMemo(
    () => inputRequest.questions.filter((question) => question.required && !isAnswered(values[question.id])),
    [inputRequest.questions, values]
  )

  function setValue(question: UserSkillInputQuestion, next: AnswerValue) {
    if (disabled || answersLocked || submitted || submitting) return
    setValidationMessage('')
    setValues((current) => ({ ...current, [question.id]: next }))
  }

  function toggleMulti(question: UserSkillInputQuestion, optionValue: UserSkillInputValue) {
    const current = Array.isArray(values[question.id]) ? values[question.id] as UserSkillInputValue[] : []
    const exists = current.some((item) => String(item) === String(optionValue))
    setValue(
      question,
      exists
        ? current.filter((item) => String(item) !== String(optionValue))
        : [...current, optionValue]
    )
  }

  function useAiDefaults() {
    if (disabled || answersLocked || submitted || submitting) return
    const next = { ...values }
    for (const question of inputRequest.questions) {
      if (isAnswered(next[question.id])) continue
      if (question.recommendedValue != null) {
        next[question.id] = question.recommendedValue
      } else if (question.allowAiDecide) {
        next[question.id] = USER_SKILL_AI_DECIDE_VALUE
      }
    }
    setValues(next)
    setValidationMessage('')
  }

  async function submit() {
    if (disabled || submitted || submitting) return
    if (missingRequired.length) {
      setValidationMessage(`请先完成：${missingRequired.map((question) => question.question).join('、')}`)
      return
    }
    const answers = inputRequest.questions.flatMap<UserSkillInputAnswer>((question) => {
      const answerValue = values[question.id]
      if (!isAnswered(answerValue)) return []
      return [{ questionId: question.id, field: question.field, value: answerValue as AnswerValue }]
    })
    setSubmitting(true)
    try {
      if (!await onSubmit(inputRequest, answers)) {
        setValidationMessage('提交失败，请检查后重试')
        return
      }
      setSubmitted(true)
    } catch {
      setValidationMessage('提交失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <article className={`story-agent-clarification story-agent-input-request${submitted ? ' is-answered' : ''}`}>
      <header>
        <span>{submitted ? '信息已提交' : inputRequest.title || '确认创作方向'}</span>
        <small>第 {inputRequest.round} 轮</small>
      </header>
      {inputRequest.assumptions.length ? (
        <p className="story-agent-input-request__assumption">
          其余信息将按以下理解继续：{inputRequest.assumptions.join('；')}
        </p>
      ) : null}
      <div className="story-agent-input-request__questions">
        {inputRequest.questions.map((question, index) => {
          const answerValue = values[question.id]
          const aiSelected = answerValue === USER_SKILL_AI_DECIDE_VALUE
          return (
            <section key={question.id} className="story-agent-input-request__question">
              <div className="story-agent-input-request__question-title">
                <strong>{index + 1}. {question.question}</strong>
                <span>{question.required ? '必填' : '可选'}</span>
              </div>
              {question.reason ? <p>{question.reason}</p> : null}
              {question.options.length ? (
                <div className="story-agent-clarification__options">
                  {question.options.map((option) => {
                    const selected = Array.isArray(answerValue)
                      ? answerValue.some((item) => String(item) === String(option.value))
                      : String(answerValue) === String(option.value)
                    return (
                      <button
                        key={`${question.id}:${option.value}`}
                        type="button"
                        className={selected ? 'is-active' : ''}
                        disabled={disabled || answersLocked || submitted || submitting}
                        onClick={() => question.inputType === 'multi_select'
                          ? toggleMulti(question, option.value)
                          : setValue(question, option.value)}
                      >
                        {option.label}
                        {question.recommendedValue != null
                          && String(question.recommendedValue) === String(option.value) ? ' · 推荐' : ''}
                      </button>
                    )
                  })}
                </div>
              ) : null}
              {question.allowCustom || question.inputType === 'number' || question.inputType === 'text' ? (
                <div className="story-agent-clarification__custom">
                  <label htmlFor={`skill-input-${inputRequest.requestId}-${question.id}`}>自定义</label>
                  <div className="story-agent-clarification__custom-input-wrap">
                    {question.inputType === 'textarea' ? (
                      <textarea
                        id={`skill-input-${inputRequest.requestId}-${question.id}`}
                        maxLength={20_000}
                        value={Array.isArray(answerValue) || aiSelected ? '' : answerValue ?? ''}
                        disabled={disabled || answersLocked || submitted || submitting}
                        onChange={(event) => setValue(question, event.target.value)}
                      />
                    ) : (
                    <input
                      id={`skill-input-${inputRequest.requestId}-${question.id}`}
                      type={question.inputType === 'number' ? 'number' : 'text'}
                      min={question.min ?? undefined}
                      max={question.max ?? undefined}
                      maxLength={question.inputType === 'number' ? undefined : 1000}
                      value={Array.isArray(answerValue) || aiSelected ? '' : answerValue ?? ''}
                      disabled={disabled || answersLocked || submitted || submitting}
                      onChange={(event) => setValue(
                        question,
                        question.inputType === 'number' && event.target.value !== ''
                          ? Number(event.target.value)
                          : event.target.value
                      )}
                    />
                    )}
                    {question.unit ? <span className="story-agent-clarification__custom-input-unit">{question.unit}</span> : null}
                  </div>
                </div>
              ) : null}
              {question.allowAiDecide ? (
                <button
                  type="button"
                  className={`story-agent-input-request__ai${aiSelected ? ' is-active' : ''}`}
                  disabled={disabled || answersLocked || submitted || submitting}
                  onClick={() => setValue(question, USER_SKILL_AI_DECIDE_VALUE)}
                >
                  交给 AI 决定
                </button>
              ) : null}
            </section>
          )
        })}
      </div>
      {!submitted ? (
        <footer className="story-agent-input-request__actions">
          {inputRequest.questions.some((question) => question.allowAiDecide) ? (
            <button type="button" disabled={disabled || answersLocked || submitting} onClick={useAiDefaults}>采用推荐 / AI 决定</button>
          ) : null}
          <button
            type="button"
            className="story-agent-clarification__confirm home-grad-btn"
            disabled={disabled || submitting}
            onClick={() => void submit()}
          >
            {submitting ? '提交中…' : answersLocked ? '重试上次提交' : '提交并继续'}
          </button>
        </footer>
      ) : null}
      {validationMessage ? <p className="story-agent-clarification__validation">{validationMessage}</p> : null}
    </article>
  )
}

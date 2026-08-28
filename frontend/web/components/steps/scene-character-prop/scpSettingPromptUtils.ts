import type {
  UserAssetRpsFormRow,
  UserAssetRpsRow,
  UserAssetRpsUpdateFormRequest
} from '~/types/business-api'
import { userAssetRpsUpdateForm } from '~/utils/businessApi'
import { htmlToPlainPreserveLineBreaks } from '~/utils/htmlPlain'

type RpsSettingPromptVariant = 'scene' | 'character' | 'prop'

export const RPS_SETTING_PROMPT_READONLY_TIP = '手添加的提示词禁止修改'

export type RpsSettingEditorState = {
  content: string
  isNew?: boolean
  formId?: number
  createSource?: string | null
}

function validFormId(value: unknown): number | null {
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

/** 提示词是纯文本协议，不能按 Markdown 解析，否则保存时会静默改变 `#`、`*` 等原文。 */
function promptTextToEditorHtml(text: string): string {
  const prompt = (text || '').trim()
  if (!prompt) return ''
  const escaped = prompt.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return `<p>${escaped.replace(/\r?\n/g, '<br/>')}</p>`
}

/** 资产设定对应列表返回的默认形态；设定入口与形态数组的首项保持同一语义。 */
export function primarySettingForm(raw: UserAssetRpsRow): UserAssetRpsFormRow | null {
  const forms = Array.isArray(raw.forms) ? raw.forms : []
  return forms.find((form) => validFormId(form?.id) != null) ?? forms[0] ?? null
}

export function rpsFormPrompt(
  form: UserAssetRpsFormRow | null,
  variant: RpsSettingPromptVariant
): string {
  if (!form) return ''
  const value = variant === 'character' ? form.descriptions : form.prompt
  return typeof value === 'string' ? value.trim() : ''
}

export function settingEditorStateFromRpsRow(
  raw: UserAssetRpsRow,
  variant: RpsSettingPromptVariant
): RpsSettingEditorState {
  return settingEditorStateFromRpsForm(primarySettingForm(raw), variant)
}

/** Build one editor state per form; callers must not collapse multiple forms to the first row. */
export function settingEditorStateFromRpsForm(
  form: UserAssetRpsFormRow | null,
  variant: RpsSettingPromptVariant
): RpsSettingEditorState {
  const prompt = rpsFormPrompt(form, variant)
  const formId = validFormId(form?.id)
  return {
    content: promptTextToEditorHtml(prompt),
    isNew: !prompt,
    ...(formId != null ? { formId } : {}),
    createSource: form?.createSource ?? null
  }
}

export function isRpsSettingPromptEditable(
  setting: RpsSettingEditorState | null | undefined
): boolean {
  return (
    validFormId(setting?.formId) != null &&
    String(setting?.createSource ?? '').trim().toLowerCase() === 'auto'
  )
}

export function buildRpsSettingPromptUpdateRequest(
  variant: RpsSettingPromptVariant,
  formId: number,
  editorContent: string
): UserAssetRpsUpdateFormRequest {
  const prompt = htmlToPlainPreserveLineBreaks(editorContent)
  return variant === 'character'
    ? { id: formId, descriptions: prompt }
    : { id: formId, prompt }
}

/**
 * 三类设定统一保存形态提示词。权限检查在请求层再次执行，避免仅依赖禁用按钮被绕过。
 */
export async function saveRpsSettingPrompt(
  variant: RpsSettingPromptVariant,
  setting: RpsSettingEditorState | null | undefined,
  editorContent: string
): Promise<RpsSettingEditorState> {
  if (!isRpsSettingPromptEditable(setting)) {
    throw new Error(RPS_SETTING_PROMPT_READONLY_TIP)
  }
  const formId = validFormId(setting?.formId)
  if (formId == null) {
    throw new Error('形态信息不存在，请刷新后重试')
  }

  const updated = await userAssetRpsUpdateForm(
    buildRpsSettingPromptUpdateRequest(variant, formId, editorContent)
  )
  const updatedPrompt = rpsFormPrompt(updated, variant)
  const nextFormId = validFormId(updated?.id) ?? formId
  const content = updatedPrompt ? promptTextToEditorHtml(updatedPrompt) : editorContent

  return {
    ...setting,
    content,
    isNew: false,
    formId: nextFormId,
    createSource: updated?.createSource ?? setting?.createSource ?? null
  }
}

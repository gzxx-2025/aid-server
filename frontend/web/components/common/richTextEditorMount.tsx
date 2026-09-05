'use client'

import { message } from 'antd'
import { registerCharacterSettingProtectedBlots } from '~/utils/quill/characterSettingProtectedBlots'
import {
shouldRememberEditorSelection,
type EditorChangeSource,
type EditorSelectionRange
} from '~/utils/quill/controlledEditorSync'
import {
createEditorTextSelection,
type EditorTextSelectionChange
} from '~/utils/quill/editorTextSelection'
import {
readPromptAssetRefFromNode,
registerPromptAssetRefBlot
} from '~/utils/quill/promptAssetRefBlot'
import { registerPromptParamRefBlot } from '~/utils/quill/promptParamRefBlot'
import {
createQuillSafely,
shouldProceedQuillMount,
type AsyncMountGate
} from '~/utils/quill/quillMountGuard'
import {
findParamGroup,
readPromptParamRefFromNode as readParamRefFromNode,
type PromptParamGroup,
type PromptParamOption,
type PromptParamType
} from '~/utils/storyboardPromptParamRef'
import {
deltaHasPositiveDelete,
normalizeClipboardDelta,
protectedEmbedMultiset,
protectedMultisetLost,
sanitizePastedHtml
} from './richTextEditorInternals'

/** RichTextEditor 的 Quill 异步挂载与事件接线（原 onMounted 主体，拆出以控制主文件体量） */

type QuillInstance = import('quill').default
type QuillCtorType = typeof import('quill').default

type MutableRef<T> = { current: T }

export interface RichTextEditorMountDeps {
  mountToken: number
  mountGate: AsyncMountGate
  getHost: () => HTMLElement | null
  /** 挂载/事件回调统一读取最新 props（组件侧 propsRef） */
  getProps: () => {
    value: string
    placeholder: string
    disabled: boolean
    maxLength?: number
    lockCharacterSettingKeys: boolean
    enablePromptAssetRefs: boolean
    enablePromptParamRefs: boolean
    promptParamGroups: PromptParamGroup[]
    onTextSelectionChange?: (selection: EditorTextSelectionChange | null) => void
  }
  quillRef: MutableRef<QuillInstance | null>
  quillCtorRef: MutableRef<QuillCtorType | null>
  isReadyRef: MutableRef<boolean>
  syncingFromPropRef: MutableRef<boolean>
  lastEmittedFromLockModeRef: MutableRef<string | null>
  cleanupEditorInputListenersRef: MutableRef<(() => void) | null>
  cleanupAssetRefClickRef: MutableRef<(() => void) | null>
  /** 失焦后仍保留的最近一次完整选区（selection-change 写入；插入 @ 时读取） */
  lastSelectionRef: MutableRef<EditorSelectionRange | null>
  /** 用户选区变化版本，用于阻止异步内容回填覆盖用户刚刚移动的新选区。 */
  selectionRevisionRef: MutableRef<number>
  setIsComposing: (v: boolean) => void
  setIsInputting: (v: boolean) => void
  isQuillTextEmpty: (q: QuillInstance) => boolean
  getPlainLen: (q: QuillInstance) => number
  getEmittedHtml: (q: QuillInstance) => string
  warnMaxLengthReached: () => void
  applyHtml: (html: string) => void
  emitModelValue: (html: string) => void
  /** 点击 @图片 引用块：打开资产选择器（index 为该 Embed 在 Quill 中的下标） */
  openAssetPicker: (payload: { index: number; assetId?: string; rect: DOMRect }) => void
  /** 点击 @参数 引用块：打开参数选择器 */
  openParamPicker: (payload: {
    index: number
    paramType: PromptParamType
    options: PromptParamOption[]
    key?: string
    rect: DOMRect
  }) => void
}

/** 对齐 Vue nextTick：flag 复位统一走宏任务 */
function nextTick(fn: () => void) {
  setTimeout(fn, 0)
}

export async function mountRichTextEditorQuill(deps: RichTextEditorMountDeps): Promise<void> {
  const { mountToken, mountGate } = deps

  try {
    const [{ default: Quill }] = await Promise.all([
      import('quill'),
      import('quill/dist/quill.snow.css')
    ])

    if (
      !shouldProceedQuillMount({
        token: mountToken,
        gate: mountGate,
        container: deps.getHost()
      })
    ) {
      return
    }

    const p0 = deps.getProps()

    /** 全局注册一次即可；仅 lockCharacterSettingKeys 时用剪贴板 matcher 转为原子 Embed */
    registerCharacterSettingProtectedBlots(Quill)
    if (p0.enablePromptAssetRefs) {
      registerPromptAssetRefBlot(Quill)
    }
    if (p0.enablePromptParamRefs) {
      registerPromptParamRefBlot(Quill)
    }
    deps.quillCtorRef.current = Quill

    const instance = createQuillSafely(
      (container) =>
        new Quill(container, {
          theme: 'snow',
          modules: { toolbar: false },
          placeholder: p0.placeholder,
          readOnly: p0.disabled
        }),
      deps.getHost()
    )
    if (!instance || !mountGate.isCurrent(mountToken)) {
      deps.quillCtorRef.current = null
      if (mountGate.isCurrent(mountToken) && !instance) {
        console.error('[RichTextEditor] Quill mount failed: invalid container')
      }
      return
    }
    deps.quillRef.current = instance
    const quill = instance

    // 粘贴 / dangerouslyPasteHTML：剥离外部站点的背景色、字色等内联样式，统一为深色底上的白字
    const Delta = Quill.import('delta') as typeof import('quill-delta').default
    const editorRoot = quill.root as HTMLElement
    editorRoot.addEventListener(
      'paste',
      (e) => {
        const html = e.clipboardData?.getData('text/html')
        if (!html?.trim()) return
        e.preventDefault()
        e.stopImmediatePropagation()
        const range = quill.getSelection(true)
        if (!range) return
        const sanitized = sanitizePastedHtml(html)
        const text = e.clipboardData?.getData('text/plain') ?? ''
        const pastedDelta = quill.clipboard.convert({ html: sanitized, text })
        const normalized = normalizeClipboardDelta(Delta, pastedDelta)
        quill.updateContents(
          new Delta().retain(range.index).delete(range.length).concat(normalized),
          'user'
        )
        quill.setSelection(range.index + normalized.length(), 0, 'silent')
        quill.scrollSelectionIntoView()
      },
      true
    )
    quill.clipboard.addMatcher(Node.ELEMENT_NODE, (node, delta) => {
      const p = deps.getProps()
      if (node instanceof HTMLElement && node.tagName === 'SPAN') {
        const DeltaCtor = Quill.import('delta') as typeof import('quill-delta').default
        if (p.enablePromptAssetRefs && node.classList.contains('scp-prompt-asset-ref')) {
          return new DeltaCtor().insert({ promptAssetRef: readPromptAssetRefFromNode(node) })
        }
        if (p.enablePromptParamRefs && node.classList.contains('scp-prompt-param-ref')) {
          return new DeltaCtor().insert({ promptParamRef: readParamRefFromNode(node) })
        }
        if (p.lockCharacterSettingKeys) {
          if (node.classList.contains('scp-char-setting-section')) {
            return new DeltaCtor().insert({ characterSettingSection: node.textContent ?? '' })
          }
          if (node.classList.contains('scp-char-setting-key')) {
            return new DeltaCtor().insert({ characterSettingKey: node.textContent ?? '' })
          }
        }
      }
      return normalizeClipboardDelta(Delta, delta)
    })

    const SILENT = Quill.sources.SILENT

    /**
     * 受保护 Embed 回滚时 `setContents` 会整体替换文档，选区可能被映射到 Embed 内部或非法下标，
     * 表现为后续无法输入。将光标移出 Embed 并重新聚焦。
     */
    function restoreCaretAfterProtectedRollback(q: QuillInstance) {
      const len = q.getLength()
      const maxIdx = Math.max(0, len - 1)
      const clamp = (i: number) => Math.max(0, Math.min(i, maxIdx))

      const range = q.getSelection()
      if (!range || range.index < 0 || range.index >= len) {
        q.setSelection(clamp(len - 2), 0, SILENT)
        q.focus()
        return
      }

      const { index } = range
      const leafPair = q.getLeaf(index) as
        | [InstanceType<typeof import('parchment').LeafBlot>, number]
        | undefined
      if (!leafPair) {
        q.focus()
        return
      }
      const [leaf, offset] = leafPair
      const blotName = (leaf.constructor as { blotName?: string }).blotName
      if (blotName === 'characterSettingKey' || blotName === 'characterSettingSection') {
        const leafLen = typeof leaf.length === 'function' ? leaf.length() : 1
        const afterEmb = index - offset + leafLen
        q.setSelection(clamp(afterEmb), 0, SILENT)
      }
      q.focus()
    }

    let composing = false

    const markInputting = () => {
      if (!deps.getProps().disabled) deps.setIsInputting(true)
    }
    const clearInputting = () => {
      if (!composing && deps.isQuillTextEmpty(quill)) deps.setIsInputting(false)
    }
    const onCompositionStart = () => {
      composing = true
      deps.setIsComposing(true)
      markInputting()
    }
    const onCompositionEnd = () => {
      composing = false
      deps.setIsComposing(false)
      if (!deps.isQuillTextEmpty(quill)) deps.setIsInputting(false)
      else clearInputting()
    }
    const onBlur = () => {
      if (composing) {
        composing = false
        deps.setIsComposing(false)
      }
      deps.setIsInputting(false)
    }

    editorRoot.addEventListener('beforeinput', markInputting)
    editorRoot.addEventListener('compositionstart', onCompositionStart)
    editorRoot.addEventListener('compositionend', onCompositionEnd)
    editorRoot.addEventListener('blur', onBlur)

    /**
     * 只记录用户产生的完整选区。内部 HTML 同步可能短暂把 Quill 选区重置为 0，
     * 这类 API 选区不能覆盖失焦前保存的位置。
     */
    let activeTextSelectionRange: EditorSelectionRange | null = null
    let selectionAnchorFrame = 0

    const emitTextSelection = () => {
      const onTextSelectionChange = deps.getProps().onTextSelectionChange
      if (!onTextSelectionChange || !activeTextSelectionRange?.length) return
      const documentText = quill.getText(0, Math.max(0, quill.getLength() - 1))
      const selection = createEditorTextSelection(
        documentText,
        activeTextSelectionRange.index,
        activeTextSelectionRange.length
      )
      if (!selection) {
        onTextSelectionChange(null)
        return
      }
      const endBounds = quill.getBounds(
        activeTextSelectionRange.index + activeTextSelectionRange.length,
        0
      )
      if (!endBounds) {
        onTextSelectionChange(null)
        return
      }
      const containerRect = quill.container.getBoundingClientRect()
      onTextSelectionChange({
        selection,
        anchor: {
          left: containerRect.left + endBounds.left,
          top: containerRect.top + endBounds.top,
          bottom: containerRect.top + endBounds.bottom
        }
      })
    }

    const refreshSelectionAnchor = () => {
      if (!activeTextSelectionRange?.length || selectionAnchorFrame) return
      selectionAnchorFrame = window.requestAnimationFrame(() => {
        selectionAnchorFrame = 0
        emitTextSelection()
      })
    }

    const onSelectionChange = (
      range: EditorSelectionRange | null,
      _oldRange: EditorSelectionRange | null,
      source: EditorChangeSource
    ) => {
      if (
        range?.length === 0 &&
        source !== 'user' &&
        !deps.syncingFromPropRef.current &&
        activeTextSelectionRange
      ) {
        activeTextSelectionRange = null
        deps.getProps().onTextSelectionChange?.(null)
      }
      if (source === 'user') deps.selectionRevisionRef.current += 1
      const event = { range, source, syncingInternally: deps.syncingFromPropRef.current }
      if (!shouldRememberEditorSelection(event)) return
      deps.lastSelectionRef.current = { index: event.range.index, length: event.range.length }
      const onTextSelectionChange = deps.getProps().onTextSelectionChange
      if (!onTextSelectionChange) return
      if (event.range.length === 0) {
        activeTextSelectionRange = null
        onTextSelectionChange(null)
        return
      }
      activeTextSelectionRange = { index: event.range.index, length: event.range.length }
      emitTextSelection()
    }
    quill.on('selection-change', onSelectionChange)
    editorRoot.addEventListener('scroll', refreshSelectionAnchor, { passive: true })
    window.addEventListener('scroll', refreshSelectionAnchor, true)
    window.addEventListener('resize', refreshSelectionAnchor)

    deps.cleanupEditorInputListenersRef.current = () => {
      if (selectionAnchorFrame) window.cancelAnimationFrame(selectionAnchorFrame)
      editorRoot.removeEventListener('beforeinput', markInputting)
      editorRoot.removeEventListener('compositionstart', onCompositionStart)
      editorRoot.removeEventListener('compositionend', onCompositionEnd)
      editorRoot.removeEventListener('blur', onBlur)
      editorRoot.removeEventListener('scroll', refreshSelectionAnchor)
      window.removeEventListener('scroll', refreshSelectionAnchor, true)
      window.removeEventListener('resize', refreshSelectionAnchor)
      quill.off('selection-change', onSelectionChange)
    }

    if (p0.enablePromptAssetRefs) {
      const onAssetRefClick = (ev: MouseEvent) => {
        const target = ev.target as HTMLElement | null
        const el = target?.closest?.('.scp-prompt-asset-ref') as HTMLElement | null
        if (!el || !deps.quillRef.current || !deps.quillCtorRef.current) return
        ev.preventDefault()
        ev.stopPropagation()
        const blot = deps.quillCtorRef.current.find(el) as { length?: () => number } | null
        if (!blot) return
        deps.openAssetPicker({
          index: deps.quillRef.current.getIndex(blot as never),
          assetId: el.dataset.assetId,
          rect: el.getBoundingClientRect()
        })
      }
      editorRoot.addEventListener('click', onAssetRefClick)
      deps.cleanupAssetRefClickRef.current = () =>
        editorRoot.removeEventListener('click', onAssetRefClick)
    }

    if (p0.enablePromptParamRefs) {
      const onParamRefClick = (ev: MouseEvent) => {
        const target = ev.target as HTMLElement | null
        const el = target?.closest?.('.scp-prompt-param-ref') as HTMLElement | null
        if (!el || !deps.quillRef.current || !deps.quillCtorRef.current) return
        ev.preventDefault()
        ev.stopPropagation()
        const blot = deps.quillCtorRef.current.find(el) as { length?: () => number } | null
        if (!blot) return
        const refValue = readParamRefFromNode(el)
        const group = findParamGroup(deps.getProps().promptParamGroups, refValue.paramType)
        if (!group?.options.length) return
        deps.openParamPicker({
          index: deps.quillRef.current.getIndex(blot as never),
          paramType: refValue.paramType,
          options: group.options.filter((o) => o.key !== 'none'),
          key: refValue.key,
          rect: el.getBoundingClientRect()
        })
      }
      editorRoot.addEventListener('click', onParamRefClick)
      const prevCleanup = deps.cleanupAssetRefClickRef.current
      deps.cleanupAssetRefClickRef.current = () => {
        prevCleanup?.()
        editorRoot.removeEventListener('click', onParamRefClick)
      }
    }

    quill.on('text-change', (change, oldDelta, source) => {
      const q = deps.quillRef.current
      if (!q) return
      if (source === 'silent') return
      if (deps.syncingFromPropRef.current) return
      if (source === 'user') {
        activeTextSelectionRange = null
        deps.getProps().onTextSelectionChange?.(null)
      }
      const p = deps.getProps()

      if (p.lockCharacterSettingKeys && source === 'user' && deltaHasPositiveDelete(change)) {
        const mb = protectedEmbedMultiset(oldDelta)
        const ma = protectedEmbedMultiset(q.getContents())
        if (mb.size > 0 && protectedMultisetLost(mb, ma)) {
          q.setContents(oldDelta, SILENT)
          const rolled = deps.getEmittedHtml(q)
          deps.lastEmittedFromLockModeRef.current = rolled
          deps.emitModelValue(rolled)
          nextTick(() => {
            if (deps.quillRef.current) restoreCaretAfterProtectedRollback(deps.quillRef.current)
            message.warning('小节标题与基本信息字段名不可删除或修改')
          })
          return
        }
      }

      if (p.maxLength != null && p.maxLength > 0) {
        const len = deps.getPlainLen(q)
        if (len > p.maxLength) {
          q.setContents(oldDelta, 'silent')
          deps.warnMaxLengthReached()
          return
        }
      }

      const html = deps.getEmittedHtml(q)
      if (!deps.isQuillTextEmpty(q)) {
        deps.setIsInputting(false)
      }
      if (p.lockCharacterSettingKeys) {
        deps.lastEmittedFromLockModeRef.current = html
      }
      deps.emitModelValue(html)
      if (source === 'user') {
        queueMicrotask(() => {
          const activeQuill = deps.quillRef.current
          if (!activeQuill || deps.syncingFromPropRef.current || !activeQuill.hasFocus()) return
          const range = activeQuill.getSelection(false)
          if (range != null && range.index >= 0) {
            deps.lastSelectionRef.current = { index: range.index, length: range.length }
          }
        })
      }
    })

    if (!mountGate.isCurrent(mountToken) || !deps.quillRef.current) {
      deps.quillRef.current = null
      deps.quillCtorRef.current = null
      return
    }

    deps.applyHtml(deps.getProps().value || '')

    if (!mountGate.isCurrent(mountToken)) {
      deps.quillRef.current = null
      deps.quillCtorRef.current = null
      return
    }
    deps.isReadyRef.current = true
  } catch (err) {
    deps.cleanupEditorInputListenersRef.current?.()
    deps.cleanupEditorInputListenersRef.current = null
    deps.cleanupAssetRefClickRef.current?.()
    deps.cleanupAssetRefClickRef.current = null
    deps.quillRef.current = null
    deps.quillCtorRef.current = null
    deps.isReadyRef.current = false
    /** 已卸载：吞掉竞态错误，禁止冒泡成整页 500 */
    if (!mountGate.isCurrent(mountToken)) return
    const msg = err instanceof Error ? err.message : String(err)
    if (/invalid quill container/i.test(msg)) {
      console.error('[RichTextEditor] Quill mount failed:', msg)
      return
    }
    throw err
  }
}

'use client'

import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode
} from 'react'
import { message } from 'antd'
import {
  htmlPlainTextLength,
  htmlPureTextCharCount,
  htmlToPlainText,
  plainPureTextCharCount
} from '~/utils/htmlPlain'
import { createAsyncMountGate } from '~/utils/quill/quillMountGuard'
import {
  clampEditorSelection,
  commitControlledEditorMutation,
  createControlledEditorValueCoordinator,
  mapSelectionThroughReplace,
  planPromptEmbedInsertion,
  resolveEditorSelection,
  shouldApplyControlledEditorValue,
  type EditorSelectionRange
} from '~/utils/quill/controlledEditorSync'
import {
  resolveQuillScrollRoot,
  restoreEditorScrollTop,
  shouldRestoreEditorFocusAfterExternalSync,
  shouldScrollEditorAfterSelectionRestore
} from '~/utils/quill/editorScroll'
import type {
  EditorTextReplacement,
  EditorTextSelection,
  EditorTextSelectionChange
} from '~/utils/quill/editorTextSelection'
import {
  dedupePromptAssets,
  formatAssetApiPlaceholder,
  formatAudioApiPlaceholder,
  isEmptyPromptAssetUrl,
  plainTextLengthForPrompt,
  promptAssetItemToRefValue,
  storyboardPromptHtmlToPlain,
  storyboardPromptPlainToHtml,
  type PromptAssetItem,
  type PromptAssetRefValue
} from '~/utils/storyboardPromptAssetRef'
import {
  paramRefsEqual,
  plainHasImageLabeledParamFields,
  plainHasVideoLabeledParamFields,
  selectionToParamRef,
  type PromptParamGroup,
  type PromptParamOption,
  type PromptParamRefValue,
  type PromptParamType
} from '~/utils/storyboardPromptParamRef'
import { protectedStructureFingerprint } from './richTextEditorInternals'
import {
  findAssetEmbedIndexByMatch,
  findParamEmbedIndex,
  findPlainTagQuillRange,
  getParamEmbedValue
} from './richTextEditorQuillScan'
import { mountRichTextEditorQuill } from './richTextEditorMount'
import { PromptAssetRefPicker } from './PromptAssetRefPicker'
import { PromptParamRefPicker } from './PromptParamRefPicker'
import './RichTextEditor.css'

type QuillInstance = import('quill').default
type QuillCtorType = typeof import('quill').default

export interface RichTextEditorProps {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  maxLength?: number
  minHeight?: string
  maxHeight?: string
  showCount?: boolean
  /** 字数统计左侧的弱提示（如自动保存状态） */
  footerHint?: ReactNode
  disabled?: boolean
  /**
   * 角色设定：锁定小节标题与基本信息各字段标签行（均为 `scp-char-setting-section` 原子 Embed）；
   * 旧数据中的 `scp-char-setting-key` 仍兼容。
   */
  lockCharacterSettingKeys?: boolean
  /** 分镜图描述：@图片 引用以整块展示，可点击切换 */
  enablePromptAssetRefs?: boolean
  promptAssets?: PromptAssetItem[]
  /** 分镜图描述：@构图 / @景别 等参数引用，可点击切换（无缩略图） */
  enablePromptParamRefs?: boolean
  promptParamGroups?: PromptParamGroup[]
  /** 字数统计与 maxLength 校验仅计纯文字（汉字/字母/数字，不含空格与标点） */
  countPureTextOnly?: boolean
  /** 达到 maxLength 且继续输入时的提示文案；不传则使用默认提示 */
  maxLengthWarning?: string
  onPromptParamChange?: (payload: {
    paramType: PromptParamType
    selection: { key: string; value: string } | null
  }) => void
  /** 用户主动选中的纯文本及其末端位置；失焦不会清空，重新落下光标时清空。 */
  onTextSelectionChange?: (selection: EditorTextSelectionChange | null) => void
  className?: string
  style?: CSSProperties
}

/** 原 defineExpose 能力（forwardRef + useImperativeHandle） */
export interface RichTextEditorHandle {
  focus: () => void
  getHtml: () => string
  getPlainPrompt: () => string
  insertPromptAssetRef: (item: PromptAssetItem) => void
  insertPromptAssetRefs: (items: PromptAssetItem[]) => void
  upsertPromptAssetRef: (item: PromptAssetItem) => void
  removePromptAssetRef: (assetId: string) => void
  removePromptAssetRefByMatch: (hint: {
    assetId?: string
    assetType?: PromptAssetItem['assetType']
    imageIndex?: number
    name?: string
  }) => void
  applyPromptAssetRefChanges: (changes: {
    remove?: Array<{
      assetId?: string
      assetType?: PromptAssetItem['assetType']
      imageIndex?: number
      name?: string
    }>
    upsert?: PromptAssetItem[]
  }) => void
  togglePromptAssetRef: (item: PromptAssetItem, selected: boolean) => void
  syncMissingPromptAssetRefs: (overrideAssets?: PromptAssetItem[]) => void
  syncPromptParamRef: (
    paramType: PromptParamType,
    selection: { key: string; value: string } | null | undefined
  ) => void
  hydratePromptRefEmbeds: () => void
  replaceTextSelection: (
    selection: EditorTextSelection,
    replacement: string
  ) => 'applied' | 'stale' | 'empty' | 'limit' | 'unavailable'
  replaceTextSelections: (
    replacements: readonly EditorTextReplacement[]
  ) => 'applied' | 'stale' | 'empty' | 'limit' | 'unavailable'
}

export const RichTextEditor = forwardRef<RichTextEditorHandle, RichTextEditorProps>(
  function RichTextEditor(rawProps, ref) {
    const props = {
      ...rawProps,
      placeholder: rawProps.placeholder ?? '',
      minHeight: rawProps.minHeight ?? '120px',
      showCount: rawProps.showCount ?? false,
      disabled: rawProps.disabled ?? false,
      lockCharacterSettingKeys: rawProps.lockCharacterSettingKeys ?? false,
      enablePromptAssetRefs: rawProps.enablePromptAssetRefs ?? false,
      promptAssets: rawProps.promptAssets ?? [],
      enablePromptParamRefs: rawProps.enablePromptParamRefs ?? false,
      promptParamGroups: rawProps.promptParamGroups ?? [],
      countPureTextOnly: rawProps.countPureTextOnly ?? false
    }
    /** 事件回调 / Quill 监听内一律读最新 props，避免闭包捕获旧值 */
    const propsRef = useRef(props)
    propsRef.current = props

    const hostRef = useRef<HTMLElement | null>(null)
    const quillRef = useRef<QuillInstance | null>(null)
    const isReadyRef = useRef(false)
    const [isInputting, setIsInputting] = useState(false)
    const [isComposing, setIsComposingState] = useState(false)
    const isComposingRef = useRef(false)
    const syncingFromPropRef = useRef(false)
    /** 用户点击/键入留下的最近完整选区；失焦后仍保留，供右侧引用操作原位插入或替换 */
    const lastSelectionRef = useRef<EditorSelectionRange | null>(null)
    /** 有序确认本地回声，并在 compositionend 后重放组合输入期间延迟的真实外部值。 */
    const controlledValueCoordinatorRef = useRef(createControlledEditorValueCoordinator())
    /** 连续外部回填时只允许最后一次 applyHtml 恢复选区。 */
    const htmlApplyGenerationRef = useRef(0)
    /** 用户或程序触发的每次 Quill 选区变化都会递增，用于保护更新后的新光标。 */
    const selectionRevisionRef = useRef(0)
    const cleanupEditorInputListenersRef = useRef<(() => void) | null>(null)
    const cleanupAssetRefClickRef = useRef<(() => void) | null>(null)
    const quillCtorRef = useRef<QuillCtorType | null>(null)
    /** Tab v-if 卸载时作废进行中的 import('quill')，防止 Invalid Quill container → 整页 500 */
    const mountGateRef = useRef<ReturnType<typeof createAsyncMountGate> | null>(null)
    if (!mountGateRef.current) mountGateRef.current = createAsyncMountGate()
    const mountGate = mountGateRef.current

    const [pickerOpen, setPickerOpen] = useState(false)
    const [pickerAnchorRect, setPickerAnchorRect] = useState<DOMRect | null>(null)
    const [pickerSelectedAssetId, setPickerSelectedAssetId] = useState<string | undefined>(
      undefined
    )
    const activeAssetBlotIndexRef = useRef<number | null>(null)

    const [paramPickerOpen, setParamPickerOpen] = useState(false)
    const [paramPickerAnchorRect, setParamPickerAnchorRect] = useState<DOMRect | null>(null)
    const [paramPickerOptions, setParamPickerOptions] = useState<PromptParamOption[]>([])
    const [paramPickerSelectedKey, setParamPickerSelectedKey] = useState<string | undefined>(
      undefined
    )
    const activeParamBlotIndexRef = useRef<number | null>(null)
    const activeParamTypeRef = useRef<PromptParamType | null>(null)

    const plainLength = useMemo(() => {
      if (props.enablePromptAssetRefs) {
        return plainTextLengthForPrompt(props.value)
      }
      if (props.countPureTextOnly) {
        return htmlPureTextCharCount(props.value)
      }
      return htmlPlainTextLength(props.value)
       
    }, [props.value, props.enablePromptAssetRefs, props.countPureTextOnly])

    /** 下拉选择器展示用：合并后的资产列表可能含重复项，按 id/name 去重 */
    /** 点选图片 chip 只列图片；点选音频 chip 只列音频（不播放） */
    const pickerPromptAssets = useMemo(() => {
      const all = dedupePromptAssets(props.promptAssets)
      const selected = all.find((a) => a.assetId === pickerSelectedAssetId)
      if (selected?.assetType === 'audio') {
        return all.filter((a) => a.assetType === 'audio')
      }
      return all.filter((a) => a.assetType !== 'audio')
       
    }, [props.promptAssets, pickerSelectedAssetId])

    /** 是否仅有空白（含 @图片 / @参数 引用块时视为非空） */
    function isQuillTextEmpty(q: QuillInstance): boolean {
      const p = propsRef.current
      if (p.enablePromptAssetRefs || p.enablePromptParamRefs) {
        for (const op of q.getContents().ops) {
          const ins = op.insert
          if (ins != null && typeof ins === 'object') {
            if ('promptAssetRef' in ins || 'promptParamRef' in ins) return false
          }
          if (typeof ins === 'string' && ins.replace(/\s/g, '').length > 0) {
            return false
          }
        }
        return true
      }
      const t = q.getText()
      const len = t.endsWith('\n') ? Math.max(0, t.length - 1) : t.length
      return len === 0
    }

    function getPlainLen(q: QuillInstance): number {
      const p = propsRef.current
      if (p.enablePromptAssetRefs) {
        return storyboardPromptHtmlToPlain(q.getSemanticHTML()).length
      }
      const t = q.getText()
      const text = t.endsWith('\n') ? t.slice(0, -1) : t
      if (p.countPureTextOnly) {
        return plainPureTextCharCount(text)
      }
      return text.length
    }

    const maxLengthWarnCooldownRef = useRef(false)
    function warnMaxLengthReached() {
      const p = propsRef.current
      if (maxLengthWarnCooldownRef.current || p.maxLength == null) return
      maxLengthWarnCooldownRef.current = true
      const limit = p.maxLength.toLocaleString('zh-CN')
      message.warning(p.maxLengthWarning ?? `剧本字数已达上限（${limit}字），无法继续输入`)
      window.setTimeout(() => {
        maxLengthWarnCooldownRef.current = false
      }, 1500)
    }

    function getEmittedHtml(q: QuillInstance): string {
      if (isQuillTextEmpty(q)) return ''
      return q.getSemanticHTML()
    }

    /** 将纯文本 @ 标签 / 结构化字段解析为带引用块的 HTML，便于 Quill 渲染为 embed */
    function rebuildPromptRefHtml(html: string): string {
      const p = propsRef.current
      if (!p.enablePromptAssetRefs && !p.enablePromptParamRefs) return html
      const plain = storyboardPromptHtmlToPlain(html)
      if (
        !plain.includes('@') &&
        !plainHasImageLabeledParamFields(plain) &&
        !plainHasVideoLabeledParamFields(plain)
      ) {
        return html
      }
      const rebuilt = storyboardPromptPlainToHtml(plain, p.promptAssets, p.promptParamGroups, {
        enableImageLabeledParams: p.enablePromptParamRefs,
        enableVideoLabeledParams: p.enablePromptParamRefs,
        enableAssetRefs: p.enablePromptAssetRefs
      })
      return rebuilt || html
    }

    /** 父组件 v-model 与编辑器刚 emit 的 HTML 相同时跳过 apply，避免 getSemanticHTML 往返破坏 Embed、误触发保护逻辑 */
    const lastEmittedFromLockModeRef = useRef<string | null>(null)

    function applyHtml(html: string) {
      const quill = quillRef.current
      if (!quill || isComposingRef.current) return
      const restoreFocus =
        typeof quill.hasFocus === 'function'
          ? quill.hasFocus()
          : document.activeElement === quill.root
      const selectionToRestore = resolveEditorSelection({
        liveRange: restoreFocus ? quill.getSelection(false) : null,
        savedRange: lastSelectionRef.current,
        hasFocus: restoreFocus,
        maxIndex: Math.max(0, quill.getLength() - 1)
      })
      const scrollRoot = resolveQuillScrollRoot(quill.root)
      const preservedScrollTop = scrollRoot.scrollTop
      const applyGeneration = ++htmlApplyGenerationRef.current
      syncingFromPropRef.current = true
      const content = rebuildPromptRefHtml(html || '')
      try {
        quill.clipboard.dangerouslyPasteHTML(content)
      } finally {
        syncingFromPropRef.current = false
      }
      propsRef.current.onTextSelectionChange?.(null)
      const clampedSelection = clampEditorSelection(
        selectionToRestore,
        Math.max(0, quill.getLength() - 1)
      )
      lastSelectionRef.current = clampedSelection
      restoreEditorScrollTop(scrollRoot, preservedScrollTop)
      const selectionRevisionAfterApply = selectionRevisionRef.current
      queueMicrotask(() => {
        if (applyGeneration !== htmlApplyGenerationRef.current) return
        if (selectionRevisionAfterApply !== selectionRevisionRef.current) return
        const activeQuill = quillRef.current
        restoreEditorSelection(clampedSelection, {
          focus: shouldRestoreEditorFocusAfterExternalSync({
            restoreFocusRequested: restoreFocus,
            editorRoot: activeQuill?.root,
            activeElement: typeof document !== 'undefined' ? document.activeElement : null
          }),
          scrollIntoView: shouldScrollEditorAfterSelectionRestore('external-html')
        })
        restoreEditorScrollTop(scrollRoot, preservedScrollTop)
        if (propsRef.current.lockCharacterSettingKeys && quillRef.current) {
          lastEmittedFromLockModeRef.current = getEmittedHtml(quillRef.current)
        }
      })
    }

    function restoreEditorSelection(
      range: EditorSelectionRange | null,
      options: { focus?: boolean; scrollIntoView?: boolean } = {}
    ) {
      const quill = quillRef.current
      if (!quill || range == null) return
      const selection = clampEditorSelection(range, Math.max(0, quill.getLength() - 1))
      lastSelectionRef.current = selection
      const hasFocus =
        typeof quill.hasFocus === 'function'
          ? quill.hasFocus()
          : document.activeElement === quill.root
      const focus = options.focus === true
      const scrollIntoView = options.scrollIntoView === true
      if (!focus && !hasFocus) return
      if (focus && !hasFocus) quill.focus({ preventScroll: true })
      quill.setSelection(selection.index, selection.length, 'silent')
      if (scrollIntoView) quill.scrollSelectionIntoView()
    }

    function emitModelValue(html: string) {
      controlledValueCoordinatorRef.current.recordLocal(html)
      propsRef.current.onChange(html)
    }

    // 原 onMounted：Quill 异步挂载与事件接线（主体拆至 richTextEditorMount）
    useEffect(() => {
      if (typeof window === 'undefined' || !hostRef.current) return
      const mountToken = mountGate.begin()

      void mountRichTextEditorQuill({
        mountToken,
        mountGate,
        getHost: () => hostRef.current,
        getProps: () => propsRef.current,
        quillRef,
        quillCtorRef,
        isReadyRef,
        syncingFromPropRef,
        lastEmittedFromLockModeRef,
        cleanupEditorInputListenersRef,
        cleanupAssetRefClickRef,
        lastSelectionRef,
        selectionRevisionRef,
        setIsComposing: (value) => {
          isComposingRef.current = value
          setIsComposingState(value)
        },
        setIsInputting,
        isQuillTextEmpty,
        getPlainLen,
        getEmittedHtml,
        warnMaxLengthReached,
        applyHtml,
        emitModelValue,
        openAssetPicker: ({ index, assetId, rect }) => {
          activeAssetBlotIndexRef.current = index
          setPickerSelectedAssetId(assetId)
          setPickerAnchorRect(rect)
          setPickerOpen(true)
        },
        openParamPicker: ({ index, paramType, options, key, rect }) => {
          activeParamBlotIndexRef.current = index
          activeParamTypeRef.current = paramType
          setParamPickerOptions(options)
          setParamPickerSelectedKey(key)
          setParamPickerAnchorRect(rect)
          setParamPickerOpen(true)
        }
      })

      // 原 onBeforeUnmount
      return () => {
        mountGate.dispose()
        isReadyRef.current = false
        cleanupEditorInputListenersRef.current?.()
        cleanupEditorInputListenersRef.current = null
        cleanupAssetRefClickRef.current?.()
        cleanupAssetRefClickRef.current = null
        quillRef.current = null
        quillCtorRef.current = null
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    // 原 watch(() => props.modelValue)：v-model 回写同步进编辑器
    useEffect(() => {
      const quill = quillRef.current
      if (!quill || !isReadyRef.current) return
      const p = propsRef.current
      const next = props.value || ''
      const controlledValue = controlledValueCoordinatorRef.current.receive(
        next,
        isComposingRef.current
      )
      if (controlledValue.decision === 'defer') return
      const valueToApply = controlledValue.value ?? next
      const cur = getEmittedHtml(quill)
      if (
        !shouldApplyControlledEditorValue({
          decision: controlledValue.decision,
          editorValue: cur,
          controlledValue: valueToApply
        })
      ) {
        controlledValueCoordinatorRef.current.clearLocalEchoes(controlledValue.localRevision)
        return
      }
      const replayingDivergedLocalEcho = controlledValue.decision === 'local-echo'
      if (
        !replayingDivergedLocalEcho &&
        p.lockCharacterSettingKeys &&
        valueToApply === lastEmittedFromLockModeRef.current
      ) {
        controlledValueCoordinatorRef.current.clearLocalEchoes(controlledValue.localRevision)
        return
      }
      if (
        !replayingDivergedLocalEcho &&
        p.lockCharacterSettingKeys &&
        protectedStructureFingerprint(valueToApply) === protectedStructureFingerprint(cur) &&
        htmlToPlainText(valueToApply) === htmlToPlainText(cur)
      ) {
        lastEmittedFromLockModeRef.current = valueToApply
        controlledValueCoordinatorRef.current.clearLocalEchoes(controlledValue.localRevision)
        return
      }
      if (!replayingDivergedLocalEcho && (p.enablePromptAssetRefs || p.enablePromptParamRefs)) {
        const curPlain = storyboardPromptHtmlToPlain(cur)
        const nextPlain = storyboardPromptHtmlToPlain(valueToApply)
        if (curPlain === nextPlain) {
          controlledValueCoordinatorRef.current.clearLocalEchoes(controlledValue.localRevision)
          return
        }
      }
      controlledValueCoordinatorRef.current.clearLocalEchoes(controlledValue.localRevision)
      applyHtml(valueToApply)
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.value, isComposing])

    // 原 watch(() => props.disabled)
    useEffect(() => {
      quillRef.current?.enable(!props.disabled)
    }, [props.disabled])

    function closeAssetPicker() {
      setPickerOpen(false)
      activeAssetBlotIndexRef.current = null
    }

    function closeParamPicker() {
      setParamPickerOpen(false)
      activeParamBlotIndexRef.current = null
      activeParamTypeRef.current = null
    }

    function emitHtmlFromEditor() {
      const quill = quillRef.current
      if (!quill) return
      const html = getEmittedHtml(quill)
      emitModelValue(html)
    }

    /**
     * 当前或最近一次完整选区。
     * 禁止 getSelection(true)：强聚焦会在失焦后把 Quill 的默认 0 位置误当作用户光标。
     */
    function getEditorSelection(): EditorSelectionRange {
      const quill = quillRef.current
      if (!quill) return { index: 0, length: 0 }
      const hasFocus =
        typeof quill.hasFocus === 'function'
          ? quill.hasFocus()
          : document.activeElement === quill.root
      return resolveEditorSelection({
        liveRange: hasFocus ? quill.getSelection(false) : null,
        savedRange: lastSelectionRef.current,
        hasFocus,
        maxIndex: Math.max(0, quill.getLength() - 1)
      })
    }

    /** 将光标移动到指定下标，并可选重新聚焦编辑器。 */
    function placeCaretAt(index: number, focus = true) {
      restoreEditorSelection(
        { index, length: 0 },
        {
          focus,
          scrollIntoView: shouldScrollEditorAfterSelectionRestore('user-caret') && focus
        }
      )
    }

    /**
     * 在光标处插入 @ 引用块（图片 / 构图等参数）
     * 非空文档且光标前非空白时在块前补空格，块后补空格并将光标置于块后
     */
    function insertPromptEmbedAtCaret(
      embedKey: 'promptAssetRef' | 'promptParamRef',
      value: PromptAssetRefValue | PromptParamRefValue,
      source: 'user' | 'silent' = 'user',
      focusAfterInsert = source === 'user'
    ): number {
      const quill = quillRef.current
      if (!quill) return 0
      const selection = getEditorSelection()
      const previousCharacter = selection.index > 0 ? quill.getText(selection.index - 1, 1) : ''
      const plan = planPromptEmbedInsertion({
        selection,
        documentHasContent: !isQuillTextEmpty(quill),
        previousCharacter
      })
      if (plan.replaceLength > 0) {
        quill.deleteText(plan.replaceIndex, plan.replaceLength, source)
      }
      if (plan.leadingSpace) {
        quill.insertText(plan.replaceIndex, ' ', source)
      }
      quill.insertEmbed(plan.embedIndex, embedKey, value, source)
      quill.insertText(plan.embedIndex + 1, ' ', source)
      placeCaretAt(plan.caretIndex, focusAfterInsert)
      return plan.embedIndex
    }

    /** 同步某一类参数标签（右侧下拉选中 / 取消时调用） */
    function syncPromptParamRef(
      paramType: PromptParamType,
      selection: { key: string; value: string } | null | undefined
    ) {
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptParamRefs) return
      const nextRef = selectionToParamRef(paramType, selection)
      const existing = getParamEmbedValue(quill, paramType)
      if (paramRefsEqual(existing, nextRef)) return

      const plainBefore = storyboardPromptHtmlToPlain(getEmittedHtml(quill))
      const SILENT = 'silent' as const
      const existingIdx = findParamEmbedIndex(quill, paramType)
      const explicitNone = selection?.key === 'none'
      const selectionBefore = getEditorSelection()
      const editorHadFocus =
        typeof quill.hasFocus === 'function'
          ? quill.hasFocus()
          : document.activeElement === quill.root
      let selectionAfter = selectionBefore
      let insertedAtCaret = false

      commitControlledEditorMutation(
        syncingFromPropRef,
        () => {
          if (!nextRef) {
            if (explicitNone && existingIdx != null) {
              quill.deleteText(existingIdx, 1, SILENT)
              selectionAfter = mapSelectionThroughReplace(selectionBefore, existingIdx, 1, 0)
            }
          } else if (existingIdx != null) {
            quill.deleteText(existingIdx, 1, SILENT)
            quill.insertEmbed(existingIdx, 'promptParamRef', nextRef, SILENT)
          } else {
            const plainRange = findPlainTagQuillRange(quill, nextRef.label)
            if (plainRange) {
              quill.deleteText(plainRange.index, plainRange.length, SILENT)
              quill.insertEmbed(plainRange.index, 'promptParamRef', nextRef, SILENT)
              selectionAfter = mapSelectionThroughReplace(
                selectionBefore,
                plainRange.index,
                plainRange.length,
                1
              )
            } else {
              insertPromptEmbedAtCaret('promptParamRef', nextRef, SILENT, true)
              insertedAtCaret = true
            }
          }

          if (!insertedAtCaret) {
            restoreEditorSelection(selectionAfter, {
              focus: editorHadFocus,
              scrollIntoView: false
            })
          }
          return storyboardPromptHtmlToPlain(getEmittedHtml(quill))
        },
        (plainAfter) => {
          if (plainBefore !== plainAfter) emitHtmlFromEditor()
        }
      )
    }

    function onPickerSelectAsset(item: PromptAssetItem) {
      const quill = quillRef.current
      if (!quill || activeAssetBlotIndexRef.current == null) {
        closeAssetPicker()
        return
      }
      const activeIndex = activeAssetBlotIndexRef.current
      const v = promptAssetItemToRefValue(item)
      syncingFromPropRef.current = true
      quill.deleteText(activeIndex, 1, 'user')
      quill.insertEmbed(activeIndex, 'promptAssetRef', v, 'user')
      syncingFromPropRef.current = false
      placeCaretAt(activeIndex + 2, true)
      emitHtmlFromEditor()
      closeAssetPicker()
    }

    function onParamPickerSelect(option: PromptParamOption) {
      const quill = quillRef.current
      const activeIndex = activeParamBlotIndexRef.current
      const activeType = activeParamTypeRef.current
      if (!quill || activeIndex == null || !activeType) {
        closeParamPicker()
        return
      }
      const nextRef = selectionToParamRef(activeType, option)
      if (!nextRef) {
        closeParamPicker()
        return
      }
      syncingFromPropRef.current = true
      quill.deleteText(activeIndex, 1, 'user')
      quill.insertEmbed(activeIndex, 'promptParamRef', nextRef, 'user')
      syncingFromPropRef.current = false
      placeCaretAt(activeIndex + 2, true)
      emitHtmlFromEditor()
      propsRef.current.onPromptParamChange?.({
        paramType: activeType,
        selection: { key: option.key, value: option.value }
      })
      closeParamPicker()
    }

    type PromptAssetMatchHint = {
      assetId?: string
      assetType?: PromptAssetItem['assetType']
      imageIndex?: number
      name?: string
    }

    function replacePromptAssetRefInEditor(item: PromptAssetItem): boolean {
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptAssetRefs) return false
      const idx = findAssetEmbedIndexByMatch(quill, {
        assetId: item.assetId,
        assetType: item.assetType,
        imageIndex: item.imageIndex,
        name: item.name
      })
      const refValue = promptAssetItemToRefValue(item)
      if (idx != null) {
        const selectionBefore = getEditorSelection()
        const editorHadFocus =
          typeof quill.hasFocus === 'function'
            ? quill.hasFocus()
            : document.activeElement === quill.root
        quill.deleteText(idx, 1, 'silent')
        quill.insertEmbed(idx, 'promptAssetRef', refValue, 'silent')
        restoreEditorSelection(selectionBefore, {
          focus: editorHadFocus,
          scrollIntoView: false
        })
        return true
      }
      insertPromptEmbedAtCaret('promptAssetRef', refValue, 'silent', false)
      return true
    }

    function removePromptAssetRefFromEditor(hint: PromptAssetMatchHint): boolean {
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptAssetRefs) return false
      const idx = findAssetEmbedIndexByMatch(quill, hint)
      if (idx == null) return false
      const selectionBefore = getEditorSelection()
      const editorHadFocus =
        typeof quill.hasFocus === 'function'
          ? quill.hasFocus()
          : document.activeElement === quill.root
      quill.deleteText(idx, 1, 'silent')
      restoreEditorSelection(
        mapSelectionThroughReplace(selectionBefore, idx, 1, 0),
        { focus: editorHadFocus, scrollIntoView: false }
      )
      return true
    }

    /** 参考图条的一批增删只产生一次受控回写，避免中间 HTML 覆盖后续输入。 */
    function applyPromptAssetRefChanges(changes: {
      remove?: PromptAssetMatchHint[]
      upsert?: PromptAssetItem[]
    }) {
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptAssetRefs) return
      const before = getEmittedHtml(quill)
      commitControlledEditorMutation(
        syncingFromPropRef,
        () => {
          for (const hint of changes.remove ?? []) removePromptAssetRefFromEditor(hint)
          for (const item of changes.upsert ?? []) replacePromptAssetRefInEditor(item)
          return getEmittedHtml(quill)
        },
        (after) => {
          if (after !== before) emitModelValue(after)
        }
      )
    }

    function upsertPromptAssetRef(item: PromptAssetItem) {
      applyPromptAssetRefChanges({ upsert: [item] })
    }

    function insertPromptAssetRefs(items: PromptAssetItem[]) {
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptAssetRefs || !items.length) return
      const before = getEmittedHtml(quill)
      commitControlledEditorMutation(
        syncingFromPropRef,
        () => {
          for (const item of items) {
            insertPromptEmbedAtCaret(
              'promptAssetRef',
              promptAssetItemToRefValue(item),
              'silent',
              false
            )
          }
          return getEmittedHtml(quill)
        },
        (after) => {
          if (after !== before) emitModelValue(after)
        }
      )
      restoreEditorSelection(lastSelectionRef.current, { focus: true, scrollIntoView: false })
    }

    function insertPromptAssetRef(item: PromptAssetItem) {
      insertPromptAssetRefs([item])
    }

    function removePromptAssetRef(assetId: string) {
      removePromptAssetRefByMatch({ assetId })
    }

    function removePromptAssetRefByMatch(hint: {
      assetId?: string
      assetType?: PromptAssetItem['assetType']
      imageIndex?: number
      name?: string
    }) {
      applyPromptAssetRefChanges({ remove: [hint] })
    }

    function togglePromptAssetRef(item: PromptAssetItem, selected: boolean) {
      if (selected) upsertPromptAssetRef(item)
      else removePromptAssetRefByMatch(item)
    }

    /** 为尚未写入描述框的已导入资产追加引用块；已有同名/同序号块则升级 url */
    function syncMissingPromptAssetRefs(overrideAssets?: PromptAssetItem[]) {
      const assets = overrideAssets ?? propsRef.current.promptAssets
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptAssetRefs || !assets.length) return
      const toUpsert: PromptAssetItem[] = []
      for (const item of assets) {
        const existingIndex = findAssetEmbedIndexByMatch(quill, {
          assetId: item.assetId,
          assetType: item.assetType,
          imageIndex: item.imageIndex,
          name: item.name
        })
        if (existingIndex == null || !isEmptyPromptAssetUrl(item.url)) toUpsert.push(item)
      }
      if (toUpsert.length) applyPromptAssetRefChanges({ upsert: toUpsert })
    }

    function getPlainPrompt(): string {
      const quill = quillRef.current
      if (!quill) return storyboardPromptHtmlToPlain(propsRef.current.value || '')
      return storyboardPromptHtmlToPlain(getEmittedHtml(quill))
    }

    function replaceTextSelections(
      replacements: readonly EditorTextReplacement[]
    ): 'applied' | 'stale' | 'empty' | 'limit' | 'unavailable' {
      const quill = quillRef.current
      if (!quill) return 'unavailable'
      if (replacements.length === 0) return 'empty'
      const documentLength = Math.max(0, quill.getLength() - 1)
      const normalized = replacements
        .map(({ selection, replacement }) => {
          const replacementBody = replacement.replace(/\r\n?/g, '\n').trim()
          const leadingLineBreaks = selection.text.match(/^\n+/)?.[0] ?? ''
          const trailingLineBreaks = selection.text.match(/\n+$/)?.[0] ?? ''
          return {
            selection,
            replacement: replacementBody
              ? `${leadingLineBreaks}${replacementBody}${trailingLineBreaks}`
              : ''
          }
        })
        .sort((left, right) => left.selection.index - right.selection.index)
      if (normalized.some((item) => !item.replacement)) return 'empty'
      for (let index = 0; index < normalized.length; index += 1) {
        const selection = normalized[index].selection
        const previous = normalized[index - 1]?.selection
        if (
          selection.index < 0 ||
          selection.length <= 0 ||
          selection.index + selection.length > documentLength ||
          quill.getText(selection.index, selection.length) !== selection.text ||
          (previous && previous.index + previous.length > selection.index)
        ) {
          return 'stale'
        }
      }
      let nextText = quill.getText(0, documentLength)
      for (const item of [...normalized].reverse()) {
        const { selection } = item
        nextText = `${nextText.slice(0, selection.index)}${item.replacement}${nextText.slice(
          selection.index + selection.length
        )}`
      }
      const maxLength = propsRef.current.maxLength
      if (maxLength != null) {
        const nextLength = propsRef.current.countPureTextOnly
          ? plainPureTextCharCount(nextText)
          : nextText.length
        if (nextLength > maxLength) return 'limit'
      }

      syncingFromPropRef.current = true
      try {
        for (const item of [...normalized].reverse()) {
          quill.deleteText(item.selection.index, item.selection.length, 'silent')
          quill.insertText(item.selection.index, item.replacement, 'silent')
        }
      } finally {
        syncingFromPropRef.current = false
      }
      const first = normalized[0]
      placeCaretAt(first.selection.index + first.replacement.length, true)
      propsRef.current.onTextSelectionChange?.(null)
      emitHtmlFromEditor()
      return 'applied'
    }

    function replaceTextSelection(
      selection: EditorTextSelection,
      replacement: string
    ): 'applied' | 'stale' | 'empty' | 'limit' | 'unavailable' {
      return replaceTextSelections([{ selection, replacement }])
    }

    function hydratePromptRefEmbeds() {
      const quill = quillRef.current
      if (!quill || !propsRef.current.enablePromptAssetRefs) return
      const before = getEmittedHtml(quill)
      commitControlledEditorMutation(
        syncingFromPropRef,
        () => {
          for (const item of propsRef.current.promptAssets) {
            const existingIndex = findAssetEmbedIndexByMatch(quill, {
              assetId: item.assetId,
              assetType: item.assetType,
              imageIndex: item.imageIndex,
              name: item.name
            })
            if (existingIndex != null) {
              replacePromptAssetRefInEditor(item)
              continue
            }

            const apiPlaceholder =
              item.assetType === 'audio'
                ? formatAudioApiPlaceholder(item.imageIndex, item.name)
                : formatAssetApiPlaceholder(item.imageIndex, item.name)
            const plainRange =
              findPlainTagQuillRange(quill, apiPlaceholder) ||
              findPlainTagQuillRange(quill, item.label || item.name)
            if (!plainRange) continue

            const selectionBefore = getEditorSelection()
            const editorHadFocus =
              typeof quill.hasFocus === 'function'
                ? quill.hasFocus()
                : document.activeElement === quill.root
            quill.deleteText(plainRange.index, plainRange.length, 'silent')
            quill.insertEmbed(
              plainRange.index,
              'promptAssetRef',
              promptAssetItemToRefValue(item),
              'silent'
            )
            restoreEditorSelection(
              mapSelectionThroughReplace(
                selectionBefore,
                plainRange.index,
                plainRange.length,
                1
              ),
              { focus: editorHadFocus, scrollIntoView: false }
            )
          }
          return getEmittedHtml(quill)
        },
        (after) => {
          if (after !== before) emitModelValue(after)
        }
      )
    }

    useImperativeHandle(ref, () => ({
      focus: () => quillRef.current?.focus(),
      getHtml: () =>
        quillRef.current ? getEmittedHtml(quillRef.current) : propsRef.current.value || '',
      getPlainPrompt,
      insertPromptAssetRef,
      insertPromptAssetRefs,
      upsertPromptAssetRef,
      removePromptAssetRef,
      removePromptAssetRefByMatch,
      togglePromptAssetRef,
      applyPromptAssetRefChanges,
      syncMissingPromptAssetRefs,
      syncPromptParamRef,
      hydratePromptRefEmbeds,
      replaceTextSelection,
      replaceTextSelections
    }))

    const rootClass = [
      'rich-text-editor',
      props.className,
      isInputting ? 'is-inputting' : '',
      props.lockCharacterSettingKeys ? 'rich-text-editor--lock-char-keys' : ''
    ]
      .filter(Boolean)
      .join(' ')

    const rootStyle = {
      ...props.style,
      '--rte-min-height': props.minHeight,
      '--rte-max-height': props.maxHeight ?? 'none'
    } as CSSProperties

    return (
      <div className={rootClass} style={rootStyle}>
        <div ref={(el) => void (hostRef.current = el)} className="rich-text-editor__host" />
        {props.showCount && props.maxLength != null ? (
          <div className="rich-text-editor__footer">
            {props.footerHint ? (
              <div className="rich-text-editor__footer-hint" aria-live="polite">
                {props.footerHint}
              </div>
            ) : null}
            <div
              className={`rich-text-editor__count${
                plainLength >= props.maxLength ? ' rich-text-editor__count--limit' : ''
              }`}
            >
              {plainLength}/{props.maxLength}
            </div>
          </div>
        ) : null}
        {props.enablePromptAssetRefs ? (
          <PromptAssetRefPicker
            open={pickerOpen}
            assets={pickerPromptAssets}
            selectedAssetId={pickerSelectedAssetId}
            anchorRect={pickerAnchorRect}
            onClose={closeAssetPicker}
            onSelect={onPickerSelectAsset}
          />
        ) : null}
        {props.enablePromptParamRefs ? (
          <PromptParamRefPicker
            open={paramPickerOpen}
            options={paramPickerOptions}
            selectedKey={paramPickerSelectedKey}
            anchorRect={paramPickerAnchorRect}
            onClose={closeParamPicker}
            onSelect={onParamPickerSelect}
          />
        ) : null}
      </div>
    )
  }
)

export default RichTextEditor

import { describe, expect, it } from 'vitest'
import {
  clampEditorSelection,
  commitControlledEditorMutation,
  createControlledEditorEchoTracker,
  createControlledEditorValueCoordinator,
  mapCaretThroughReplace,
  mapSelectionThroughReplace,
  planPromptEmbedInsertion,
  resolveEditorSelection,
  shouldApplyControlledEditorValue,
  shouldRememberEditorSelection
} from './controlledEditorSync'

describe('controlled editor sync', () => {
  it('consumes delayed local echoes without treating them as external content', () => {
    const tracker = createControlledEditorEchoTracker()
    tracker.record('<p>甲</p>')
    tracker.record('<p>甲乙</p>')

    expect(tracker.consume('<p>甲</p>')).toBe(true)
    expect(tracker.consume('<p>甲乙</p>')).toBe(true)
    expect(tracker.consume('<p>外部值</p>')).toBe(false)
  })

  it('accepts a coalesced latest echo and discards older pending values', () => {
    const tracker = createControlledEditorEchoTracker()
    tracker.record('a')
    tracker.record('ab')
    tracker.record('abc')

    expect(tracker.consume('abc')).toBe(true)
    expect(tracker.consume('a')).toBe(false)

    tracker.record('stale')
    tracker.clear()
    expect(tracker.consume('stale')).toBe(false)
  })

  it('maps a caret through insert, delete and equal-length embed replacement', () => {
    expect(mapCaretThroughReplace(8, 3, 0, 2)).toBe(10)
    expect(mapCaretThroughReplace(8, 3, 2, 0)).toBe(6)
    expect(mapCaretThroughReplace(8, 3, 1, 1)).toBe(8)
    expect(mapCaretThroughReplace(4, 3, 4, 1)).toBe(4)
  })

  it('keeps the complete selection after blur and never trusts an unfocused zero range', () => {
    expect(
      resolveEditorSelection({
        liveRange: { index: 0, length: 0 },
        savedRange: { index: 4, length: 3 },
        hasFocus: false,
        maxIndex: 12
      })
    ).toEqual({ index: 4, length: 3 })

    expect(
      resolveEditorSelection({
        liveRange: { index: 0, length: 0 },
        savedRange: null,
        hasFocus: false,
        maxIndex: 12
      })
    ).toEqual({ index: 12, length: 0 })
  })

  it('does not let internal api or silent selection changes overwrite the user range', () => {
    const range = { index: 0, length: 0 }
    expect(
      shouldRememberEditorSelection({ range, source: 'api', syncingInternally: false })
    ).toBe(false)
    expect(
      shouldRememberEditorSelection({ range, source: 'silent', syncingInternally: false })
    ).toBe(false)
    expect(
      shouldRememberEditorSelection({ range, source: 'user', syncingInternally: true })
    ).toBe(false)
    expect(
      shouldRememberEditorSelection({
        range: { index: 5, length: 2 },
        source: 'user',
        syncingInternally: false
      })
    ).toBe(true)
  })

  it('uses the live selection while focused and clamps it to editable content', () => {
    expect(
      resolveEditorSelection({
        liveRange: { index: 7, length: 9 },
        savedRange: { index: 2, length: 1 },
        hasFocus: true,
        maxIndex: 10
      })
    ).toEqual({ index: 7, length: 3 })
    expect(clampEditorSelection({ index: -5, length: 2 }, 10)).toEqual({ index: 0, length: 2 })
  })

  it('maps a selected range through changes before and across the selection', () => {
    expect(mapSelectionThroughReplace({ index: 8, length: 3 }, 3, 0, 2)).toEqual({
      index: 10,
      length: 3
    })
    expect(mapSelectionThroughReplace({ index: 2, length: 6 }, 4, 3, 1)).toEqual({
      index: 2,
      length: 4
    })
    expect(mapSelectionThroughReplace({ index: 4, length: 2 }, 3, 4, 1)).toEqual({
      index: 4,
      length: 0
    })
  })

  it('replays a deferred external value when composition ends', () => {
    const coordinator = createControlledEditorValueCoordinator()
    expect(coordinator.receive('<p>外部回填</p>', true)).toEqual({
      decision: 'defer',
      localRevision: 0
    })
    expect(coordinator.receive('<p>外部回填</p>', false)).toEqual({
      decision: 'apply',
      value: '<p>外部回填</p>',
      localRevision: 0
    })
  })

  it('lets the latest local composition result supersede an older deferred value', () => {
    const coordinator = createControlledEditorValueCoordinator()
    expect(coordinator.receive('<p>旧外部值</p>', true)).toEqual({
      decision: 'defer',
      localRevision: 0
    })
    expect(coordinator.recordLocal('<p>中文完成</p>')).toBe(1)
    expect(coordinator.receive('<p>中文完成</p>', true)).toEqual({
      decision: 'local-echo',
      value: '<p>中文完成</p>',
      localRevision: 1
    })
    expect(coordinator.receive('<p>中文完成</p>', false)).toEqual({
      decision: 'apply',
      value: '<p>中文完成</p>',
      localRevision: 1
    })
  })

  it('reapplies the final local composition echo if a deferred external value won the race', () => {
    const coordinator = createControlledEditorValueCoordinator()

    expect(coordinator.receive('<p>外部 X</p>', true).decision).toBe('defer')
    // Quill 已产生最终本地版本，但 React 父层回声尚未到达。
    expect(coordinator.recordLocal('<p>本地 Y</p>')).toBe(1)
    const externalApply = coordinator.receive('<p>外部 X</p>', false)
    expect(externalApply).toMatchObject({ decision: 'apply', value: '<p>外部 X</p>' })
    coordinator.clearLocalEchoes(externalApply.localRevision)

    const delayedLocalEcho = coordinator.receive('<p>本地 Y</p>', false)
    expect(delayedLocalEcho).toEqual({
      decision: 'local-echo',
      value: '<p>本地 Y</p>',
      localRevision: 1
    })
    expect(
      shouldApplyControlledEditorValue({
        decision: delayedLocalEcho.decision,
        editorValue: '<p>外部 X</p>',
        controlledValue: delayedLocalEcho.value!
      })
    ).toBe(true)
  })

  it('skips an ordinary local echo while the editor still contains that local version', () => {
    const coordinator = createControlledEditorValueCoordinator()
    coordinator.recordLocal('<p>本地 Y</p>')
    const localEcho = coordinator.receive('<p>本地 Y</p>', false)

    expect(
      shouldApplyControlledEditorValue({
        decision: localEcho.decision,
        editorValue: '<p>本地 Y</p>',
        controlledValue: localEcho.value!
      })
    ).toBe(false)
  })

  it('never lets the delayed first-reference echo erase later text near a second reference', () => {
    const coordinator = createControlledEditorValueCoordinator()
    const afterReferenceOne = '<p>@参考图1 第一处补充</p>'
    const afterReferenceTwo = '<p>@参考图1 第一处补充 @参考图2 第二处补充</p>'
    coordinator.recordLocal(afterReferenceOne)
    coordinator.recordLocal(afterReferenceTwo)

    const delayedFirstEcho = coordinator.receive(afterReferenceOne, false)
    expect(delayedFirstEcho.decision).toBe('stale-local-echo')
    expect(
      shouldApplyControlledEditorValue({
        decision: delayedFirstEcho.decision,
        editorValue: afterReferenceTwo,
        controlledValue: afterReferenceOne
      })
    ).toBe(false)

    expect(coordinator.receive(afterReferenceTwo, false)).toMatchObject({
      decision: 'local-echo',
      value: afterReferenceTwo
    })
  })

  it('skips an external controlled value when the editor already contains it', () => {
    expect(
      shouldApplyControlledEditorValue({
        decision: 'apply',
        editorValue: '<p>相同值</p>',
        controlledValue: '<p>相同值</p>'
      })
    ).toBe(false)
  })

  it('replaces the selected text and advances consecutive prompt-ref insertions', () => {
    const first = planPromptEmbedInsertion({
      selection: { index: 3, length: 4 },
      documentHasContent: true,
      previousCharacter: '甲'
    })
    expect(first).toEqual({
      replaceIndex: 3,
      replaceLength: 4,
      leadingSpace: true,
      embedIndex: 4,
      caretIndex: 6
    })

    const second = planPromptEmbedInsertion({
      selection: { index: first.caretIndex, length: 0 },
      documentHasContent: true,
      previousCharacter: ' '
    })
    expect(second).toEqual({
      replaceIndex: 6,
      replaceLength: 0,
      leadingSpace: false,
      embedIndex: 6,
      caretIndex: 8
    })
  })

  it('commits a multi-step prompt-ref edit with one final controlled change', () => {
    const guard = { current: false }
    let intermediateChanges = 0
    let finalChanges = 0

    commitControlledEditorMutation(
      guard,
      () => {
        for (const _operation of ['delete-selection', 'insert-embed', 'insert-space']) {
          if (!guard.current) intermediateChanges += 1
        }
        return '<p>最终值</p>'
      },
      (value) => {
        expect(value).toBe('<p>最终值</p>')
        finalChanges += 1
      }
    )

    expect(guard.current).toBe(false)
    expect(intermediateChanges).toBe(0)
    expect(finalChanges).toBe(1)
  })

  it('always releases the mutation guard and does not emit after an exception', () => {
    const guard = { current: false }
    let finalChanges = 0

    expect(() =>
      commitControlledEditorMutation(
        guard,
        () => {
          throw new Error('failed mutation')
        },
        () => {
          finalChanges += 1
        }
      )
    ).toThrow('failed mutation')
    expect(guard.current).toBe(false)
    expect(finalChanges).toBe(0)
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ScriptDetailByProjectRequest, ScriptDetailRow, ScriptSaveRequest } from '~/types/business-api'
import {
  StoryScriptAutoSaveCoordinator,
  setStoryScriptServerBaseline
} from '~/utils/storyScriptPersistence'

function row(
  ctx: ScriptDetailByProjectRequest,
  content: string,
  id = 1,
  version = 1
): ScriptDetailRow {
  return {
    id,
    ...ctx,
    originalText: content,
    contentHash: `hash-${id}-${content}`,
    comicVersion: version,
    status: 1
  }
}

function createHarness(ctx: ScriptDetailByProjectRequest, initial = '服务器正文') {
  setStoryScriptServerBaseline(ctx, initial ? row(ctx, initial) : null)
  const autoSave = vi.fn(async (body: ScriptSaveRequest) =>
    row(ctx, body.originalText, initial ? 1 : 2)
  )
  const loadLatest = vi.fn(async () => row(ctx, initial))
  const coordinator = new StoryScriptAutoSaveCoordinator(ctx, {
    autoSave,
    loadLatest,
    now: Date.now
  })
  return { coordinator, autoSave, loadLatest }
}

describe('StoryScriptAutoSaveCoordinator', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-24T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('首帧不保存，正文未变化也跳过', async () => {
    const ctx = { projectId: 1001, episodeId: 0 }
    const { coordinator, autoSave } = createHarness(ctx)

    coordinator.updateContent('本地首帧', false)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(autoSave).not.toHaveBeenCalled()

    coordinator.acceptServerBaseline(row(ctx, '本地首帧'))
    coordinator.updateContent('本地首帧', true)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(autoSave).not.toHaveBeenCalled()
  })

  it('停止输入5秒后只保存一次', async () => {
    const ctx = { projectId: 1002, episodeId: 0 }
    const { coordinator, autoSave } = createHarness(ctx)
    coordinator.updateContent('修改一', true)

    await vi.advanceTimersByTimeAsync(4_999)
    expect(autoSave).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)

    expect(autoSave).toHaveBeenCalledTimes(1)
    expect(autoSave.mock.calls[0][0].originalText).toBe('修改一')
  })

  it('持续输入时最迟20秒保存最新快照', async () => {
    const ctx = { projectId: 1003, episodeId: 0 }
    const { coordinator, autoSave } = createHarness(ctx)
    coordinator.updateContent('修改0', true)

    for (let index = 1; index <= 4; index += 1) {
      await vi.advanceTimersByTimeAsync(4_000)
      coordinator.updateContent(`修改${index}`, true)
    }
    await vi.advanceTimersByTimeAsync(3_999)
    expect(autoSave).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)

    expect(autoSave).toHaveBeenCalledTimes(1)
    expect(autoSave.mock.calls[0][0].originalText).toBe('修改4')
  })

  it('在途编辑合并为下一次最新保存', async () => {
    const ctx = { projectId: 1004, episodeId: 0 }
    setStoryScriptServerBaseline(ctx, row(ctx, '服务器正文'))
    let resolveFirst!: (value: ScriptDetailRow) => void
    const first = new Promise<ScriptDetailRow>((resolve) => { resolveFirst = resolve })
    const autoSave = vi
      .fn<(body: ScriptSaveRequest) => Promise<ScriptDetailRow>>()
      .mockReturnValueOnce(first)
      .mockImplementation(async (body) => row(ctx, body.originalText))
    const coordinator = new StoryScriptAutoSaveCoordinator(ctx, {
      autoSave,
      loadLatest: async () => row(ctx, '服务器正文'),
      now: Date.now
    })

    coordinator.updateContent('快照A', true)
    await vi.advanceTimersByTimeAsync(5_000)
    coordinator.updateContent('快照B', true)
    resolveFirst(row(ctx, '快照A'))
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(5_000)

    expect(autoSave).toHaveBeenCalledTimes(2)
    expect(autoSave.mock.calls[1][0].originalText).toBe('快照B')
  })

  it('允许空白自动保存且携带当前基线', async () => {
    const ctx = { projectId: 1005, episodeId: 0 }
    const { coordinator, autoSave } = createHarness(ctx)
    coordinator.updateContent('', true)
    await vi.advanceTimersByTimeAsync(5_000)

    expect(autoSave).toHaveBeenCalledWith(expect.objectContaining({
      originalText: '',
      baseScriptId: 1,
      baseContentHash: 'hash-1-服务器正文'
    }))
  })

  it('失败后按10秒开始重试并保留错误状态', async () => {
    const ctx = { projectId: 1006, episodeId: 0 }
    setStoryScriptServerBaseline(ctx, row(ctx, '服务器正文'))
    const autoSave = vi
      .fn<(body: ScriptSaveRequest) => Promise<ScriptDetailRow>>()
      .mockRejectedValueOnce(new Error('网络断开'))
      .mockImplementation(async (body) => row(ctx, body.originalText))
    const coordinator = new StoryScriptAutoSaveCoordinator(ctx, {
      autoSave,
      loadLatest: async () => row(ctx, '服务器正文'),
      now: Date.now
    })
    coordinator.updateContent('待重试', true)

    await vi.advanceTimersByTimeAsync(5_000)
    expect(coordinator.getState().status).toBe('error')
    await vi.advanceTimersByTimeAsync(9_999)
    expect(autoSave).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(autoSave).toHaveBeenCalledTimes(2)
  })

  it('409时暂停保存，并可基于最新服务器版本保留本地重试', async () => {
    const ctx = { projectId: 1007, episodeId: 0 }
    setStoryScriptServerBaseline(ctx, row(ctx, '旧服务器正文', 1))
    const latest = row(ctx, '其他页面正文', 2, 2)
    const autoSave = vi
      .fn<(body: ScriptSaveRequest) => Promise<ScriptDetailRow>>()
      .mockRejectedValueOnce({ code: 409, msg: '剧本内容冲突' })
      .mockImplementation(async (body) => row(ctx, body.originalText, 2, 2))
    const coordinator = new StoryScriptAutoSaveCoordinator(ctx, {
      autoSave,
      loadLatest: async () => latest,
      now: Date.now
    })
    coordinator.updateContent('本地正文', true)
    await vi.advanceTimersByTimeAsync(5_000)

    expect(coordinator.getState().status).toBe('conflict')
    const result = await coordinator.keepLocalAfterConflict()

    expect(result).toBe('saved')
    expect(autoSave.mock.calls[1][0]).toEqual(expect.objectContaining({
      originalText: '本地正文',
      baseScriptId: 2,
      baseContentHash: 'hash-2-其他页面正文'
    }))
  })

  it('不同作品和剧集使用独立基线', async () => {
    const first = createHarness({ projectId: 1008, episodeId: 1 }, '第一集')
    const second = createHarness({ projectId: 1008, episodeId: 2 }, '第二集')
    first.coordinator.updateContent('第一集修改', true)
    second.coordinator.updateContent('第二集修改', true)

    await vi.advanceTimersByTimeAsync(5_000)

    expect(first.autoSave.mock.calls[0][0]).toEqual(expect.objectContaining({ episodeId: 1 }))
    expect(second.autoSave.mock.calls[0][0]).toEqual(expect.objectContaining({ episodeId: 2 }))
  })
})

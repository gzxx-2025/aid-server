import { beforeEach, describe, expect, it, vi } from 'vitest'

const { post } = vi.hoisted(() => ({ post: vi.fn() }))

vi.mock('~/utils/api', () => ({
  request: { post }
}))

import { userScriptDelete, userScriptList } from './script'

describe('script history api', () => {
  beforeEach(() => {
    post.mockReset()
  })

  it('通过查询参数按20条分页加载全部状态', async () => {
    post.mockResolvedValue({
      code: 200,
      msg: '操作成功',
      total: 21,
      data: [{ id: 3, projectId: 10, episodeId: 0, status: 1 }]
    })

    const result = await userScriptList({
      projectId: 10,
      episodeId: 0,
      pageNum: 1,
      pageSize: 20
    })

    expect(post).toHaveBeenCalledWith(
      '/api/user/script/list',
      { projectId: 10, episodeId: 0 },
      { params: { pageNum: 1, pageSize: 20 } }
    )
    expect(result.rows).toHaveLength(1)
    expect(result.hasMore).toBe(true)
  })

  it('删除历史版本调用软删除接口', async () => {
    post.mockResolvedValue({ code: 200, msg: '操作成功' })

    await userScriptDelete(99)

    expect(post).toHaveBeenCalledWith('/api/user/script/delete', { id: 99 })
  })
})

import { describe, expect, it } from 'vitest'

import { auditStatusBadgeLabel, auditStatusBadgeTone } from './projectAudit'

describe('project audit card badge', () => {
  it('不再为审核通过项目展示重复状态', () => {
    expect(auditStatusBadgeLabel(4)).toBeNull()
    expect(auditStatusBadgeTone(4)).toBeNull()
  })

  it('保留审核中和审核失败提示', () => {
    expect(auditStatusBadgeLabel(3)).toBe('审核中')
    expect(auditStatusBadgeTone(3)).toBe('reviewing')
    expect(auditStatusBadgeLabel(5)).toBe('审核失败')
    expect(auditStatusBadgeTone(5)).toBe('failed')
  })
})

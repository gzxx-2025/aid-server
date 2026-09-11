import { describe, expect, it } from 'vitest'
import type { PublicProjectDetailRow, PublicProjectVideoRow } from '~/types/business-api'
import {
  normalizePublicCaseCard,
  resolveActivePublicEpisode,
  resolvePublicProjectPlayback,
  resolvePublicProjectType
} from './publicCasePlaza'

describe('public case plaza presentation', () => {
  it('normalizes legacy public project types and fallback labels', () => {
    expect(resolvePublicProjectType('tv')).toBe('series')
    expect(resolvePublicProjectType('film')).toBe('movie')

    const card = normalizePublicCaseCard({
      id: 12,
      projectName: '',
      projectType: 'series',
      authorNickname: ' ',
      episodeCount: 3
    } satisfies PublicProjectVideoRow)
    expect(card).toMatchObject({
      title: '公开项目 #12',
      authorName: '作者',
      category: 'series',
      categoryLabel: '电视剧集',
      episodeCount: 3
    })
  })

  it('uses the selected episode for public playback and falls back to the first episode', () => {
    const detail = {
      id: 7,
      projectName: '测试剧集',
      coverUrl: 'project-cover.jpg',
      finalVideoUrl: 'project.mp4',
      episodes: [
        { episodeId: 71, episodeNo: 1, coverUrl: 'ep1.jpg', videoUrl: 'ep1.mp4' },
        { episodeId: 72, episodeNo: 2, coverUrl: 'ep2.jpg', videoUrl: 'ep2.mp4' }
      ]
    } satisfies PublicProjectDetailRow

    expect(resolveActivePublicEpisode(detail, 72)?.episodeId).toBe(72)
    expect(resolvePublicProjectPlayback(detail, 72)).toEqual({
      videoUrl: 'ep2.mp4',
      coverUrl: 'ep2.jpg'
    })
    expect(resolvePublicProjectPlayback(detail, 999).videoUrl).toBe('ep1.mp4')
  })
})

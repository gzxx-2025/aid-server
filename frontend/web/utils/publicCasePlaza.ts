import type {
  PublicProjectDetailRow,
  PublicProjectEpisodeItem,
  PublicProjectVideoRow,
  UserProjectType
} from '~/types/business-api'

export interface PublicCaseCardItem {
  id: number
  title: string
  authorName: string
  description: string
  category: UserProjectType
  categoryLabel: string
  episodeCount: number
  coverUrl: string
}

const CATEGORY_LABEL: Record<UserProjectType, string> = {
  movie: '电影/短片',
  series: '电视剧集'
}

export function resolvePublicProjectType(value: unknown): UserProjectType {
  const normalized = String(value ?? '').trim().toLowerCase()
  return normalized === 'series' || normalized === 'tv' ? 'series' : 'movie'
}

export function normalizePublicCaseCard(row: PublicProjectVideoRow): PublicCaseCardItem {
  const category = resolvePublicProjectType(row.projectType)
  const episodeCount = Number(row.episodeCount)
  return {
    id: Number(row.id),
    title: String(row.projectName || '').trim() || `公开项目 #${row.id}`,
    authorName: String(row.authorNickname || '').trim() || '作者',
    description: String(row.projectDesc || '').trim(),
    category,
    categoryLabel: CATEGORY_LABEL[category],
    episodeCount: Number.isFinite(episodeCount) && episodeCount > 0 ? episodeCount : 0,
    coverUrl: String(row.coverUrl || '').trim()
  }
}

export function resolveActivePublicEpisode(
  detail: PublicProjectDetailRow,
  episodeId: number | null
): PublicProjectEpisodeItem | null {
  const episodes = Array.isArray(detail.episodes) ? detail.episodes : []
  if (!episodes.length) return null
  return episodes.find((item) => Number(item.episodeId) === episodeId) ?? episodes[0]
}

export function resolvePublicProjectPlayback(
  detail: PublicProjectDetailRow,
  episodeId: number | null
): { videoUrl: string; coverUrl: string } {
  const episode = resolveActivePublicEpisode(detail, episodeId)
  return {
    videoUrl: String(episode?.videoUrl || detail.previewVideoUrl || detail.finalVideoUrl || '').trim(),
    coverUrl: String(episode?.coverUrl || detail.coverUrl || '').trim()
  }
}

import { request } from '@/utils/request';

// 作品审核（后台）API —— 全部 POST，列表数据在 res.data，总数在 res.total
// 后端控制器：/aid/audit/*（与 C 端 /api/user/* 完全隔离）

export interface AuditQueryBase {
  pageNum?: number;
  pageSize?: number;
}

export interface ProjectAuditQuery extends AuditQueryBase {
  projectName?: string | null;
  projectType?: string | null;
  userId?: number | string | null;
  status?: number | null;
}

export interface EpisodeAuditQuery extends AuditQueryBase {
  projectId?: number | string | null;
  comicTitle?: string | null;
  userId?: number | string | null;
  status?: number | null;
}

export interface AuditRecordQuery extends AuditQueryBase {
  targetType?: string | null;
  targetId?: number | string | null;
  action?: number | null;
}

export interface AuditActionPayload {
  id: number;
  pass: boolean;
  reason?: string | null;
}

// 项目审核列表（仅剧集类项目，status 不传默认查审核中）
export function listAuditProject(data: ProjectAuditQuery) {
  return request({ url: '/aid/audit/project/list', method: 'post', data });
}

// 电影审核列表（仅电影类项目，status 不传默认查审核中）
export function listAuditMovie(data: ProjectAuditQuery) {
  return request({ url: '/aid/audit/movie/list', method: 'post', data });
}

// 剧集审核列表（status 不传默认查审核中）
export function listAuditEpisode(data: EpisodeAuditQuery) {
  return request({ url: '/aid/audit/episode/list', method: 'post', data });
}

// 项目审核详情（仅项目情况：封面+基本信息）
export function getProjectAuditDetail(id: number) {
  return request({ url: '/aid/audit/project/detail', method: 'post', data: { id } });
}

// 电影审核详情（封面 + 成品视频在线地址）
export function getMovieAuditDetail(id: number) {
  return request({ url: '/aid/audit/movie/detail', method: 'post', data: { id } });
}

// 剧集审核详情（含成品视频在线地址）
export function getEpisodeAuditDetail(id: number) {
  return request({ url: '/aid/audit/episode/detail', method: 'post', data: { id } });
}

// 审核项目（pass=true 通过 / false 驳回，驳回 reason 必填）
export function auditProject(data: AuditActionPayload) {
  return request({ url: '/aid/audit/project/audit', method: 'post', data });
}

// 审核剧集（pass=true 通过 / false 驳回，驳回 reason 必填）
export function auditEpisode(data: AuditActionPayload) {
  return request({ url: '/aid/audit/episode/audit', method: 'post', data });
}

// 审核流水记录列表
export function listAuditRecord(data: AuditRecordQuery) {
  return request({ url: '/aid/audit/record/list', method: 'post', data });
}

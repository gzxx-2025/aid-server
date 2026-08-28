import { request } from '@/utils/request';

// 作品发布管理（后台）API —— 全部 POST，列表数据在 res.data，总数在 res.total
// 后端控制器：/aid/publish/*

export interface PublishListQuery {
  pageNum?: number;
  pageSize?: number;
  /** approved=审核通过未发布 published=已发布，不传查两类合集 */
  publishState?: string | null;
  projectName?: string | null;
  projectType?: string | null;
  /** 作者关键字（昵称/邮箱/手机号） */
  keyword?: string | null;
}

export interface PublishItem {
  id: number;
  userId: number;
  nickName?: string;
  email?: string;
  phonenumber?: string;
  projectName: string;
  projectType: string;
  projectDesc?: string;
  coverUrl?: string;
  status: number;
  statusReason?: string;
  isPublic: string;
  publishTime?: string;
  updateTime?: string;
  createTime?: string;
}

export interface PublishActionPayload {
  id: number;
  reason?: string | null;
}

export interface WhitelistQuery {
  pageNum?: number;
  pageSize?: number;
  keyword?: string | null;
}

export interface WhitelistItem {
  id: number;
  userId: number;
  nickName?: string;
  email?: string;
  phonenumber?: string;
  avatar?: string;
  remark?: string;
  createBy?: string;
  createTime?: string;
}

export interface PublishUserItem {
  userId: number;
  /** 展示格式：昵称(ID) */
  nickName: string;
  email?: string;
  phonenumber?: string;
  avatar?: string;
  /** 用户级发布权限: 1允许 0禁止 */
  publishEnabled: number;
  inWhitelist: boolean;
}

// 发布管理列表（审核通过未发布 / 已发布）
export function listPublish(data: PublishListQuery) {
  return request({ url: '/aid/publish/list', method: 'post', data });
}

// 上架作品（原因可选）
export function publishOnline(data: PublishActionPayload) {
  return request({ url: '/aid/publish/online', method: 'post', data });
}

// 下架作品（原因必填）
export function publishOffline(data: PublishActionPayload) {
  return request({ url: '/aid/publish/offline', method: 'post', data });
}

// 回撤审核（撤销通过并下架，状态转审核失败，原因必填）
export function publishRevoke(data: PublishActionPayload) {
  return request({ url: '/aid/publish/revoke', method: 'post', data });
}

// 发布白名单列表
export function listWhitelist(data: WhitelistQuery) {
  return request({ url: '/aid/publish/whitelist/list', method: 'post', data });
}

// 添加发布白名单
export function addWhitelist(data: { userId: number; remark?: string | null }) {
  return request({ url: '/aid/publish/whitelist/add', method: 'post', data });
}

// 移除发布白名单（传白名单记录ID）
export function removeWhitelist(id: number) {
  return request({ url: '/aid/publish/whitelist/remove', method: 'post', data: { id } });
}

// 用户搜索（昵称/邮箱/手机号，最多50条）
export function searchPublishUsers(keyword: string) {
  return request<PublishUserItem[]>({ url: '/aid/publish/user/search', method: 'post', data: { keyword } });
}

// 设置用户发布权限（1允许 0禁止）
export function setUserPublishPermission(data: { userId: number; publishEnabled: number }) {
  return request({ url: '/aid/publish/user/permission', method: 'post', data });
}

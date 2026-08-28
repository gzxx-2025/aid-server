import { request } from '@/utils/request';

/** 用户聚合信息（aid_user_profile + sys_user 联表） */
export interface UserProfileVo {
  /** 扩展信息主键ID */
  id: number;
  /** 用户ID */
  userId: number;
  /** 账户余额 */
  balance?: number;
  /** 冻结余额 */
  frozenBalance?: number;
  /** 是否实名认证 */
  isReal?: string;
  /** 真实姓名 */
  realName?: string;
  /** 身份证 */
  idCard?: string;
  /** 会员等级编码 */
  memberLevel?: string;
  /** 会员到期时间 */
  memberExpireTime?: string;
  /** 累计充值金额 */
  totalRecharge?: number;
  /** 累计消费金额 */
  totalConsumption?: number;
  /** 备注 */
  remark?: string;
  /** 扩展信息创建时间 */
  createTime?: string;
  /** 用户账号 */
  userName?: string;
  /** 用户昵称 */
  nickName?: string;
  /** 头像地址 */
  avatar?: string;
  /** 手机号码 */
  phonenumber?: string;
  /** 用户邮箱 */
  email?: string;
  /** 用户性别（0男 1女 2未知） */
  sex?: string;
  /** 账号状态（0正常 1停用） */
  status?: string;
  /** 最后登录IP */
  loginIp?: string;
  /** 最后登录时间 */
  loginDate?: string;
  /** 注册时间 */
  registerTime?: string;
}

/** 列表查询参数 */
export interface UserProfileQuery {
  pageNum?: number;
  pageSize?: number;
  userId?: number | string;
  nickName?: string;
  userName?: string;
  phonenumber?: string;
  status?: string;
  memberLevel?: string;
  isReal?: string;
}

/** 余额调整请求 */
export interface BalanceAdjustParams {
  /** 目标用户ID */
  userId: number;
  /** 调整金额（正数，单位：元） */
  amount: number;
  /** 调整方向：add=增加 / deduct=扣减 */
  adjustType: 'add' | 'deduct';
  /** 调整原因 */
  reason?: string;
}

/** 后台新增用户请求（邮箱和手机号必须二选一） */
export interface AdminUserCreateParams {
  /** 用户邮箱 */
  email?: string;
  /** 用户手机号 */
  phonenumber?: string;
}

/** 新增成功后仅返回一次的登录凭据 */
export interface AdminUserCreateResult {
  /** 用户ID */
  userId: number;
  /** 可用于登录的邮箱或手机号 */
  account: string;
  /** 账号类型：phone / email */
  accountType: 'phone' | 'email';
  /** 系统生成的初始密码 */
  password: string;
}

// 查询用户扩展信息列表（联表 sys_user）
export function listExtenduserprofile(query: UserProfileQuery) {
  return request({
    url: '/aid/extenduserprofile/list',
    method: 'get',
    params: query
  });
}

// 查询用户扩展信息详细
export function getExtenduserprofile(id: number | string) {
  return request({
    url: '/aid/extenduserprofile/' + id,
    method: 'get'
  });
}

// 新增 C 端用户（自动初始化用户扩展信息和账户余额）
export function createExtenduserprofile(data: AdminUserCreateParams) {
  return request<AdminUserCreateResult>({
    url: '/aid/extenduserprofile',
    method: 'post',
    data
  });
}

// 修改用户扩展信息（后端仅允许改备注等非敏感字段）
export function updateExtenduserprofile(data: { id: number; remark?: string }) {
  return request({
    url: '/aid/extenduserprofile',
    method: 'put',
    data
  });
}

// 管理员调整用户余额（增加/扣减，自动写余额流水）
export function adjustBalance(data: BalanceAdjustParams) {
  return request({
    url: '/aid/extenduserprofile/adjustBalance',
    method: 'post',
    data
  });
}

// 封禁/解封用户（status: '0' 正常 / '1' 封禁；封禁会立即踢下线并阻止下次登录）
export function changeUserBanStatus(userId: number | string, status: '0' | '1') {
  return request({
    url: '/aid/extenduserprofile/changeStatus',
    method: 'put',
    data: { userId, status }
  });
}

// 删除用户（逻辑删除 + 立即踢下线，历史数据保留）
export function deleteUser(userId: number | string) {
  return request({
    url: '/aid/extenduserprofile/user/' + userId,
    method: 'delete'
  });
}

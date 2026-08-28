import type { User } from '~/types';
import type { LoginData,UserInfoFromApi } from '~/types/business-api';

/** 将接口 userInfo 映射为前端 User（登录与 /api/user/profile 共用） */
export function mapUserInfoFromApiToUser(
  u: UserInfoFromApi,
  options?: { fallbackEmail?: string; role?: User['role'] }
): User {
  return {
    id: String(u.userId),
    username: u.userName || u.nickName || '用户',
    email: u.email || options?.fallbackEmail || '',
    avatar: u.avatar,
    role: options?.role ?? 'user',
    nickName: u.nickName,
    phonenumber: u.phonenumber,
    balance: u.balance ?? null,
    frozenBalance: u.frozenBalance ?? null,
    memberLevel: u.memberLevel ?? null,
    memberLevelName: u.memberLevelName ?? null,
    memberExpireTime: u.memberExpireTime ?? null,
    totalRecharge: u.totalRecharge ?? null,
    totalConsumption: u.totalConsumption ?? null,
    isReal: u.isReal ?? null,
    realName: u.realName ?? null,
    idCard: u.idCard ?? null
  }
}

export function mapLoginDataToUser(data: LoginData, fallbackEmail?: string): User {
  return mapUserInfoFromApiToUser(data.userInfo, { fallbackEmail })
}

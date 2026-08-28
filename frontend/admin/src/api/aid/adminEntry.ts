import { request } from '@/utils/request';

/** 后台随机登录入口 - 状态（匿名，仅返回是否启用，不含访问码） */
export function getAdminEntryStatus() {
  return request({
    url: '/aid/adminEntry/status',
    method: 'get',
    // 匿名 + 不参与防重复提交
    headers: { isToken: false, repeatSubmit: false } as any
  });
}

/** 后台随机登录入口 - 校验访问码（匿名，仅返回 valid，不回显正确码） */
export function verifyAdminEntry(code: string) {
  return request({
    url: '/aid/adminEntry/verify',
    method: 'post',
    data: { code },
    headers: { isToken: false, repeatSubmit: false } as any
  });
}

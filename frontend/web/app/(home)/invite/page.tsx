'use client'

import InvitePanel from '@/components/home/InvitePanel'

/** 原 pages/invite.vue：layout=home-new + middleware=auth，登录守卫由 (home) 壳层统一处理 */
export default function InvitePage() {
  return <InvitePanel />
}

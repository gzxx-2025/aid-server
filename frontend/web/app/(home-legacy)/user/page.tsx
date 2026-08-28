'use client'

import { Suspense } from 'react'
import { LeftOutlined, RightOutlined, TeamOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import { useRouter, useSearchParams } from 'next/navigation'
import UserDetailPage from './[id]/page'

const users = [
  { id: '1', name: '张三', email: 'zhangsan@example.com', role: '管理员' },
  { id: '2', name: '李四', email: 'lisi@example.com', role: '普通用户' },
  { id: '3', name: '王五', email: 'wangwu@example.com', role: '普通用户' }
]

/** 原 pages/user/index.vue：layout=home，由 app/(home-legacy)/layout.tsx 承担壳（写死假数据的演示页） */
function UserIndexContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const detailId = searchParams.get('id')

  function goToUserDetail(id: string) {
    router.push(`/user?id=${id}`)
  }

  function goBack() {
    router.push('/')
  }

  if (detailId) return <UserDetailPage />

  return (
    <div className="user-page home-theme-page home-theme-static">
      <div className="page-container">
        <div className="page-header">
          <div className="header-content">
            <h1 className="page-title">
              <TeamOutlined className="title-icon" />
              用户中心
            </h1>
            <p className="page-subtitle">管理和查看平台用户信息</p>
          </div>
        </div>

        <div className="user-list-section">
          <div className="section-header">
            <h2 className="section-title">用户列表</h2>
            <div className="user-count">共 {users.length} 位用户</div>
          </div>
          <div className="user-cards">
            {users.map((user) => (
              <div
                key={user.id}
                className="user-card hover-card"
                onClick={() => goToUserDetail(user.id)}
              >
                <div className="user-avatar">
                  <span className="avatar-text">{user.name[0]}</span>
                </div>
                <div className="user-info">
                  <h3 className="user-name">{user.name}</h3>
                  <p className="user-email">{user.email}</p>
                  <div className={`user-role-badge ${user.role === '管理员' ? 'admin' : 'user'}`}>
                    {user.role}
                  </div>
                </div>
                <div className="card-arrow">
                  <RightOutlined />
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="page-actions">
          <Button size="large" onClick={goBack} className="back-button">
            <LeftOutlined />
            返回首页
          </Button>
        </div>
      </div>
    </div>
  )
}

export default function UserIndexPage() {
  return (
    <Suspense fallback={null}>
      <UserIndexContent />
    </Suspense>
  )
}

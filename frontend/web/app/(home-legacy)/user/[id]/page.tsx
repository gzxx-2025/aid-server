'use client'

import { Suspense } from 'react'
import { LeftOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
const users = {
  '1': { id: '1', name: '张三', email: 'zhangsan@example.com', role: '管理员' },
  '2': { id: '2', name: '李四', email: 'lisi@example.com', role: '普通用户' },
  '3': { id: '3', name: '王五', email: 'wangwu@example.com', role: '普通用户' }
}

/** 原 pages/user/[id].vue：layout=home，由 app/(home-legacy)/layout.tsx 承担壳（写死假数据的演示页） */
function UserDetailContent() {
  const params = useParams<{ id: string }>()
  const router = useRouter()
  const searchParams = useSearchParams()

  const raw = params?.id ?? searchParams.get('id')
  const userId = (Array.isArray(raw) ? raw[0] : raw) ?? ''

  const user = users[userId as keyof typeof users] || null

  function goToUserList() {
    router.push('/user')
  }

  return (
    <div className="user-detail-page home-theme-page home-theme-static">
      <div className="page-container">
        <div className="breadcrumb">
          <Button onClick={goToUserList}>
            <LeftOutlined />
            返回用户列表
          </Button>
        </div>
        <div className="page-header">
          <h1 className="page-title">用户详情</h1>
        </div>
        {user ? (
          <div className="user-detail-card">
            <div className="user-info">
              <div className="user-avatar-large">
                <span className="avatar-text">{user.name[0]}</span>
              </div>
              <div className="user-details">
                <h2 className="user-name">{user.name}</h2>
                <div className="detail-item">
                  <span className="detail-label">ID:</span>
                  <span className="detail-value">{user.id}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">邮箱:</span>
                  <span className="detail-value">{user.email}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">角色:</span>
                  <span className="detail-value">
                    <span className={`role-badge ${user.role === '管理员' ? 'admin' : 'user'}`}>
                      {user.role}
                    </span>
                  </span>
                </div>
              </div>
            </div>
            <div className="user-actions">
              <h3 className="actions-title">快速操作</h3>
              <div className="action-buttons">
                <Button type="primary" size="large">
                  编辑信息
                </Button>
                <Button size="large">查看历史</Button>
              </div>
            </div>
          </div>
        ) : (
          <div className="not-found">
            <div className="not-found-icon">❌</div>
            <h3>用户不存在</h3>
            <p>抱歉，找不到该用户信息</p>
            <Button type="primary" onClick={goToUserList}>
              返回用户列表
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

export default function UserDetailPage() {
  return (
    <Suspense fallback={null}>
      <UserDetailContent />
    </Suspense>
  )
}

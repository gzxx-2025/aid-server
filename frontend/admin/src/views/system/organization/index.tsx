import React, { useState } from 'react';
import { Tabs } from 'antd';
import { UserOutlined, TeamOutlined, ApartmentOutlined, IdcardOutlined } from '@ant-design/icons';

import PageHeader from '@/components/PageHeader';
import UserPage from '@/views/system/user';
import RolePage from '@/views/system/role';
import DeptPage from '@/views/system/dept';
import PostPage from '@/views/system/post';

/**
 * 组织与权限（需求3）：合并 用户 / 角色 / 部门 / 岗位 四个管理页为同一页面的 Tab。
 * 各 Tab 直接复用原页面组件，按需懒加载（切换到对应 Tab 才挂载）。
 */
export default function OrganizationPage() {
  const [active, setActive] = useState('user');

  return (
    <div className="crud-page">
      <PageHeader title="组织与权限" desc="用户、角色、部门、岗位的统一维护入口" />
      <Tabs
        activeKey={active}
        onChange={setActive}
        destroyInactiveTabPane={false}
        items={[
          { key: 'user', label: <span><UserOutlined /> 用户管理</span>, children: active === 'user' ? <UserPage embedded /> : null },
          { key: 'role', label: <span><TeamOutlined /> 角色管理</span>, children: active === 'role' ? <RolePage embedded /> : null },
          { key: 'dept', label: <span><ApartmentOutlined /> 部门管理</span>, children: active === 'dept' ? <DeptPage /> : null },
          { key: 'post', label: <span><IdcardOutlined /> 岗位管理</span>, children: active === 'post' ? <PostPage /> : null }
        ]}
      />
    </div>
  );
}

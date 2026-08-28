import React, { useEffect, useMemo, useState } from 'react';
import {
  Avatar, Button, Card, Col, Empty, Form, Input, Modal, Row, Space, Spin, Table, Tag, Tooltip, message
} from 'antd';
import {
  ChromeOutlined, DesktopOutlined, EnvironmentOutlined, LogoutOutlined, ReloadOutlined,
  SearchOutlined, TeamOutlined, UserOutlined
} from '@ant-design/icons';
import { list as listOnline, forceLogout, forceLogoutByUser } from '@/api/monitor/online';
import { useAuth } from '@/hooks/useAuth';
import PageHeader from '@/components/PageHeader';
import StatCard from '@/components/StatCard';
import { parseTime } from '@/utils/ruoyi';
import './style.less';

/**
 * 在线用户监控（按用户聚合）
 * - 一行 = 一个在线用户；展开后显示该用户名下所有在线会话（Token）
 * - 解决"同一用户多个未过期Token被当成多个在线用户"的问题
 * - 支持强退单个会话，或一键强退该用户全部会话
 */
export default function OnlinePage() {
  const { hasPermi } = useAuth();
  const canForceLogout = hasPermi('monitor:online:forceLogout');

  const [form] = Form.useForm();
  const [rows, setRows] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<{ userName?: string; ipaddr?: string }>({});

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await listOnline(filters);
      setRows(res.rows || res.data || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  const stats = useMemo(() => {
    const sessions = rows.reduce((sum, r) => sum + (r.onlineCount || (r.tokens ? r.tokens.length : 0)), 0);
    const locations = new Set(
      rows.flatMap((r) => (r.tokens || []).map((t: any) => t.loginLocation)).filter(Boolean)
    );
    return { sessions, users: rows.length, locations: locations.size };
  }, [rows]);

  // 强退单个会话
  const handleLogoutToken = (token: any) => {
    forceLogout(token.tokenId).then(() => {
      message.success('已强退该会话');
      load();
    });
  };

  // 强退该用户全部会话
  const handleLogoutUser = (user: any) => {
    forceLogoutByUser(user.userId).then(() => {
      message.success('已强退该用户全部会话');
      load();
    });
  };

  // 主表：在线用户
  const columns = [
    {
      title: '用户', dataIndex: 'userName', width: 220,
      render: (v: string, r: any) => (
        <Space>
          <Avatar size={32} icon={<UserOutlined />} className="user-avatar" />
          <div style={{ lineHeight: 1.2 }}>
            <div style={{ fontWeight: 500 }}>{v || '-'}</div>
            <div className="help-text">{r.deptName || '—'}</div>
          </div>
        </Space>
      )
    },
    {
      title: '用户ID', dataIndex: 'userId', width: 120,
      render: (v: any) => <Tag>{v ?? '-'}</Tag>
    },
    {
      title: '在线会话数', dataIndex: 'onlineCount', width: 130,
      render: (v: number, r: any) => <Tag color="green">{v ?? (r.tokens ? r.tokens.length : 0)} 个</Tag>
    },
    {
      title: '最近登录时间', dataIndex: 'lastLoginTime', width: 180,
      render: (v: any) => parseTime(v) || '-'
    },
    {
      title: '操作', key: 'ops', fixed: 'right' as const, width: 130,
      render: (_: any, r: any) => canForceLogout ? (
        <Button type="link" size="small" danger icon={<LogoutOutlined />}
          onClick={() => {
            Modal.confirm({
              title: '强制下线',
              content: `确定强退用户「${r.userName || r.userId}」的全部 ${r.onlineCount || (r.tokens ? r.tokens.length : 0)} 个会话吗？`,
              okType: 'danger',
              onOk: () => handleLogoutUser(r)
            });
          }}
        >全部强退</Button>
      ) : null
    }
  ];

  // 展开子表：该用户名下的会话（Token）
  const expandedRowRender = (record: any) => {
    const tokenColumns = [
      {
        title: '登录IP', dataIndex: 'ipaddr', width: 150,
        render: (v: string) => <Tag color="blue">{v || '-'}</Tag>
      },
      {
        title: '登录地点', dataIndex: 'loginLocation', width: 180,
        render: (v: string) => <Space size={4}><EnvironmentOutlined className="muted-icon" />{v || '未知'}</Space>
      },
      {
        title: '浏览器', dataIndex: 'browser', width: 160,
        render: (v: string) => v ? <Space size={4}><ChromeOutlined className="browser-icon" />{v}</Space> : '-'
      },
      {
        title: '操作系统', dataIndex: 'os', width: 160,
        render: (v: string) => v ? <Space size={4}><DesktopOutlined className="os-icon" />{v}</Space> : '-'
      },
      {
        title: '登录时间', dataIndex: 'loginTime', width: 170,
        render: (v: any) => parseTime(v) || '-'
      },
      {
        title: '会话编号', dataIndex: 'tokenId', width: 220, ellipsis: true,
        render: (v: string) => <Tooltip title={v}><span className="help-text">{v}</span></Tooltip>
      },
      {
        title: '操作', key: 'ops', fixed: 'right' as const, width: 100,
        render: (_: any, t: any) => canForceLogout ? (
          <Button type="link" size="small" danger icon={<LogoutOutlined />}
            onClick={() => {
              Modal.confirm({
                title: '强制下线',
                content: `确定强退该会话吗？（${t.ipaddr || t.tokenId}）`,
                okType: 'danger',
                onOk: () => handleLogoutToken(t)
              });
            }}
          >强退</Button>
        ) : null
      }
    ];
    return (
      <Table
        rowKey="tokenId"
        size="small"
        dataSource={record.tokens || []}
        columns={tokenColumns as any}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
    );
  };

  return (
    <div className="crud-page online-page">
      <PageHeader
        title="在线用户"
        desc="按用户聚合展示当前在线会话，支持强制下线单个会话或整个用户"
      />
      <Row gutter={16}>
        <Col xs={24} sm={8}>
          <StatCard label="在线用户数" value={stats.users} icon={<TeamOutlined />} color="#6366f1" />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard label="在线会话数(Token)" value={stats.sessions} icon={<UserOutlined />} color="#16a34a" />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard label="登录地点数" value={stats.locations} icon={<EnvironmentOutlined />} color="#0ea5e9" />
        </Col>
      </Row>

      <Card bordered={false} className="page-card">
        <Form form={form} layout="inline" onFinish={(v) => setFilters(v)}>
          <Form.Item name="userName" label="用户名"><Input allowClear placeholder="用户名" style={{ width: 160 }} /></Form.Item>
          <Form.Item name="ipaddr" label="登录IP"><Input allowClear placeholder="登录IP" style={{ width: 160 }} /></Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
              <Button icon={<ReloadOutlined />} onClick={() => { form.resetFields(); setFilters({}); }}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card bordered={false} className="page-card">
        <Spin spinning={loading}>
          {rows.length ? (
            <Table
              rowKey="userId"
              size="middle"
              dataSource={rows}
              columns={columns as any}
              expandable={{ expandedRowRender, defaultExpandAllRows: false }}
              scroll={{ x: 'max-content' }}
              pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 个在线用户` }}
            />
          ) : (
            <Empty description="当前无在线用户" />
          )}
        </Spin>
      </Card>
    </div>
  );
}

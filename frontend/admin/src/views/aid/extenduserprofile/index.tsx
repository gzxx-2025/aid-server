import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert, Avatar, Button, Card, Col, Descriptions, Drawer, Empty, Form, Input, InputNumber,
  Modal, Radio, Row, Select, Space, Table, Tabs, Tag, Tooltip, Typography, message
} from 'antd';
import {
  DollarOutlined, EditOutlined, EyeOutlined, KeyOutlined, LockOutlined,
  PlusOutlined, ReloadOutlined, SearchOutlined, ShoppingOutlined, UnlockOutlined,
  UserOutlined, DeleteOutlined, WalletOutlined
} from '@ant-design/icons';

import {
  listExtenduserprofile, updateExtenduserprofile, adjustBalance,
  changeUserBanStatus, createExtenduserprofile, deleteUser,
  type AdminUserCreateResult, type UserProfileVo, type UserProfileQuery
} from '@/api/aid/extenduserprofile';
import { listThridsocial } from '@/api/aid/thridsocial';
import { listBalancelog } from '@/api/aid/balancelog';
import { resetUserPwd } from '@/api/system/user';
import { useDict } from '@/hooks/useDict';
import { useAuth } from '@/hooks/useAuth';
import DictTag from '@/components/DictTag';
import StatCard from '@/components/StatCard';
import { parseTime } from '@/utils/ruoyi';
import './style.less';

const PERM_EDIT = 'aid:extenduserprofile:edit';
const PERM_REMOVE = 'aid:extenduserprofile:remove';
const PERM_ADD = 'aid:extenduserprofile:add';

/** 余额变动类型中文映射（与后端 changeType 对齐） */
const CHANGE_TYPE_MAP: Record<string, { label: string; color: string }> = {
  recharge: { label: '充值', color: 'green' },
  consume: { label: '消费', color: 'red' },
  freeze: { label: '冻结', color: 'orange' },
  unfreeze: { label: '解冻', color: 'blue' },
  settle_unfreeze: { label: '结算退回', color: 'blue' },
  settle_refund: { label: '差额退回', color: 'blue' },
  settle_extra: { label: '补扣', color: 'red' },
  admin_adjust: { label: '管理员调整', color: 'purple' }
};

/** 三方平台中文映射 */
const PLATFORM_MAP: Record<string, string> = {
  wechat: '微信',
  wx: '微信',
  weixin: '微信',
  wxmp: '微信公众号',
  wxapp: '微信小程序',
  alipay: '支付宝',
  qq: 'QQ',
  apple: 'Apple',
  phone: '手机号'
};

export default function UserManagePage() {
  const { hasPermi } = useAuth();
  const canEdit = hasPermi(PERM_EDIT);
  const canRemove = hasPermi(PERM_REMOVE);
  const canAdd = hasPermi(PERM_ADD);

  const dicts = useDict('sys_normal_disable', 'sys_user_sex');
  const statusDict = dicts['sys_normal_disable'] || [];
  const sexDict = dicts['sys_user_sex'] || [];

  const [searchForm] = Form.useForm();
  const [rows, setRows] = useState<UserProfileVo[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState<UserProfileQuery>({ pageNum: 1, pageSize: 10 });

  // 新增用户与一次性登录凭据
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [createSaving, setCreateSaving] = useState(false);
  const [createdCredential, setCreatedCredential] = useState<AdminUserCreateResult | null>(null);
  const [credentialOpen, setCredentialOpen] = useState(false);

  // 详情抽屉
  const [detailOpen, setDetailOpen] = useState(false);
  const [current, setCurrent] = useState<UserProfileVo | null>(null);
  const [socials, setSocials] = useState<any[]>([]);
  const [balanceLogs, setBalanceLogs] = useState<any[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);

  // 调整余额
  const [adjustOpen, setAdjustOpen] = useState(false);
  const [adjustForm] = Form.useForm();
  const [adjustSaving, setAdjustSaving] = useState(false);
  const [adjustTarget, setAdjustTarget] = useState<UserProfileVo | null>(null);

  // 重置密码
  const [pwdOpen, setPwdOpen] = useState(false);
  const [pwdForm] = Form.useForm();
  const [pwdSaving, setPwdSaving] = useState(false);
  const [pwdTarget, setPwdTarget] = useState<UserProfileVo | null>(null);

  // 编辑备注
  const [remarkOpen, setRemarkOpen] = useState(false);
  const [remarkForm] = Form.useForm();
  const [remarkSaving, setRemarkSaving] = useState(false);
  const [remarkTarget, setRemarkTarget] = useState<UserProfileVo | null>(null);

  const loadList = async () => {
    setLoading(true);
    try {
      const res: any = await listExtenduserprofile(query);
      setRows(res.rows || res.data || []);
      setTotal(res.total || 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadList();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  const handleSearch = () => {
    const v = searchForm.getFieldsValue();
    setQuery({ pageNum: 1, pageSize: query.pageSize, ...v });
  };

  const handleReset = () => {
    searchForm.resetFields();
    setQuery({ pageNum: 1, pageSize: query.pageSize });
  };

  const openCreate = () => {
    createForm.resetFields();
    createForm.setFieldsValue({ accountType: 'phone' });
    setCreateOpen(true);
  };

  const submitCreate = async () => {
    const values = await createForm.validateFields();
    const account = String(values.account || '').trim();
    const payload = values.accountType === 'email'
      ? { email: account }
      : { phonenumber: account };
    setCreateSaving(true);
    try {
      const res = await createExtenduserprofile(payload);
      setCreateOpen(false);
      setCreatedCredential(res.data);
      setCredentialOpen(true);
      message.success('用户添加成功');
      loadList();
    } finally {
      setCreateSaving(false);
    }
  };

  // 打开详情：加载三方账户 + 余额流水
  const openDetail = async (row: UserProfileVo) => {
    setCurrent(row);
    setDetailOpen(true);
    setDetailLoading(true);
    try {
      const [socialRes, logRes]: any = await Promise.all([
        listThridsocial({ userId: row.userId, pageNum: 1, pageSize: 50 }),
        listBalancelog({ userId: row.userId, pageNum: 1, pageSize: 50 })
      ]);
      setSocials(socialRes.rows || socialRes.data || []);
      setBalanceLogs(logRes.rows || logRes.data || []);
    } finally {
      setDetailLoading(false);
    }
  };

  // 调整余额
  const openAdjust = (row: UserProfileVo) => {
    setAdjustTarget(row);
    adjustForm.resetFields();
    adjustForm.setFieldsValue({ adjustType: 'add' });
    setAdjustOpen(true);
  };

  const submitAdjust = async () => {
    const v = await adjustForm.validateFields();
    if (!adjustTarget) return;
    setAdjustSaving(true);
    try {
      await adjustBalance({
        userId: adjustTarget.userId,
        amount: v.amount,
        adjustType: v.adjustType,
        reason: v.reason
      });
      message.success('调整成功');
      setAdjustOpen(false);
      loadList();
      // 详情抽屉打开的是同一用户时刷新
      if (detailOpen && current?.userId === adjustTarget.userId) {
        openDetail({ ...adjustTarget });
      }
    } finally {
      setAdjustSaving(false);
    }
  };

  // 封禁 / 解封（封禁会立即踢下线并阻止下次登录）
  const toggleBan = (row: UserProfileVo) => {
    const banned = row.status === '1';
    Modal.confirm({
      title: banned ? '确认解封' : '确认封禁',
      content: banned
        ? `确定要解封用户「${row.nickName || row.userName || row.userId}」吗？`
        : `确定要封禁用户「${row.nickName || row.userName || row.userId}」吗？封禁后将立即踢出当前在线会话，且无法再次登录。`,
      okButtonProps: { danger: !banned },
      onOk: async () => {
        await changeUserBanStatus(row.userId, banned ? '0' : '1');
        message.success(banned ? '已解封' : '已封禁并踢下线');
        loadList();
        if (detailOpen && current?.userId === row.userId) {
          setCurrent({ ...row, status: banned ? '0' : '1' });
        }
      }
    });
  };

  // 删除用户（逻辑删除 + 踢下线，历史数据保留）
  const handleDelete = (row: UserProfileVo) => {
    Modal.confirm({
      title: '确认删除用户',
      content: `确定要删除用户「${row.nickName || row.userName || row.userId}」吗？删除后该账号将无法登录，订单、流水等历史数据仍会保留以备审计。此操作不可在界面恢复。`,
      okButtonProps: { danger: true },
      okText: '删除',
      onOk: async () => {
        await deleteUser(row.userId);
        message.success('已删除');
        if (detailOpen && current?.userId === row.userId) {
          setDetailOpen(false);
        }
        loadList();
      }
    });
  };

  // 重置密码
  const openResetPwd = (row: UserProfileVo) => {
    setPwdTarget(row);
    pwdForm.resetFields();
    setPwdOpen(true);
  };

  const submitResetPwd = async () => {
    const v = await pwdForm.validateFields();
    if (!pwdTarget) return;
    setPwdSaving(true);
    try {
      await resetUserPwd(pwdTarget.userId, v.password);
      message.success('密码重置成功');
      setPwdOpen(false);
    } finally {
      setPwdSaving(false);
    }
  };

  // 编辑备注
  const openRemark = (row: UserProfileVo) => {
    setRemarkTarget(row);
    remarkForm.resetFields();
    remarkForm.setFieldsValue({ remark: row.remark });
    setRemarkOpen(true);
  };

  const submitRemark = async () => {
    const v = await remarkForm.validateFields();
    if (!remarkTarget) return;
    setRemarkSaving(true);
    try {
      await updateExtenduserprofile({ id: remarkTarget.id, remark: v.remark });
      message.success('保存成功');
      setRemarkOpen(false);
      loadList();
    } finally {
      setRemarkSaving(false);
    }
  };

  const fmtMoney = (v?: number) =>
    v === undefined || v === null ? '0.00' : Number(v).toFixed(2);

  const columns = useMemo(
    () => [
      {
        title: '用户',
        dataIndex: 'nickName',
        width: 220,
        fixed: 'left' as const,
        render: (_: any, r: UserProfileVo) => (
          <div className="user-cell">
            <Avatar size={40} src={r.avatar} icon={<UserOutlined />} />
            <div className="user-cell__info">
              <div className="user-cell__name">{r.nickName || '未设置昵称'}</div>
              <div className="user-cell__sub">ID: {r.userId}</div>
            </div>
          </div>
        )
      },
      {
        title: '手机号',
        dataIndex: 'phonenumber',
        width: 130,
        render: (v: string) => v || <span className="muted">-</span>
      },
      {
        title: '邮箱',
        dataIndex: 'email',
        width: 220,
        render: (v: string) => v || <span className="muted">-</span>
      },
      {
        title: '余额',
        dataIndex: 'balance',
        width: 110,
        render: (v: number) => <span className="money">¥{fmtMoney(v)}</span>
      },
      {
        title: '冻结',
        dataIndex: 'frozenBalance',
        width: 100,
        render: (v: number) => <span className="muted">¥{fmtMoney(v)}</span>
      },
      {
        title: '累计充值',
        dataIndex: 'totalRecharge',
        width: 110,
        render: (v: number) => `¥${fmtMoney(v)}`
      },
      {
        title: '会员等级',
        dataIndex: 'memberLevel',
        width: 110,
        render: (v: string) =>
          v ? <Tag color="gold">{v}</Tag> : <span className="muted">普通</span>
      },
      {
        title: '实名',
        dataIndex: 'isReal',
        width: 80,
        render: (v: string) =>
          v === 'Y' || v === '1' ? (
            <Tag color="green" bordered={false}>已实名</Tag>
          ) : (
            <Tag bordered={false}>未实名</Tag>
          )
      },
      {
        title: '状态',
        dataIndex: 'status',
        width: 90,
        render: (v: string) => <DictTag options={statusDict} value={v} />
      },
      {
        title: '注册时间',
        dataIndex: 'registerTime',
        width: 160,
        render: (v: string) => parseTime(v) || '-'
      },
      {
        title: '操作',
        key: 'ops',
        fixed: 'right' as const,
        width: 300,
        render: (_: any, r: UserProfileVo) => (
          <Space size={0} wrap>
            <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => openDetail(r)}>
              详情
            </Button>
            {canEdit && (
              <Button type="link" size="small" icon={<DollarOutlined />} onClick={() => openAdjust(r)}>
                调余额
              </Button>
            )}
            {canEdit && (
              <Button
                type="link"
                size="small"
                danger={r.status !== '1'}
                icon={r.status === '1' ? <UnlockOutlined /> : <LockOutlined />}
                onClick={() => toggleBan(r)}
              >
                {r.status === '1' ? '解封' : '封禁'}
              </Button>
            )}
            {canEdit && (
              <Button type="link" size="small" icon={<KeyOutlined />} onClick={() => openResetPwd(r)}>
                重置密码
              </Button>
            )}
            {canEdit && (
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openRemark(r)}>
                备注
              </Button>
            )}
            {canRemove && (
              <Button type="link" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(r)}>
                删除
              </Button>
            )}
          </Space>
        )
      }
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [statusDict, canEdit, canRemove]
  );

  const socialColumns = [
    {
      title: '平台',
      dataIndex: 'platformSource',
      width: 120,
      render: (v: string) => <Tag color="blue">{PLATFORM_MAP[v] || v || '-'}</Tag>
    },
    { title: 'OpenID', dataIndex: 'openid', ellipsis: true },
    { title: 'UnionID', dataIndex: 'unionid', ellipsis: true },
    {
      title: '绑定时间',
      dataIndex: 'createTime',
      width: 160,
      render: (v: string) => parseTime(v) || '-'
    }
  ];

  const logColumns = [
    {
      title: '类型',
      dataIndex: 'changeType',
      width: 110,
      render: (v: string) => {
        const hit = CHANGE_TYPE_MAP[v];
        return hit ? <Tag color={hit.color} bordered={false}>{hit.label}</Tag> : <Tag>{v}</Tag>;
      }
    },
    {
      title: '金额',
      dataIndex: 'amount',
      width: 110,
      render: (v: number) => (
        <span className={Number(v) >= 0 ? 'money--in' : 'money--out'}>
          {Number(v) >= 0 ? '+' : ''}
          {fmtMoney(v)}
        </span>
      )
    },
    {
      title: '变动后余额',
      dataIndex: 'afterBalance',
      width: 120,
      render: (v: number) => `¥${fmtMoney(v)}`
    },
    { title: '说明', dataIndex: 'bizName', ellipsis: true, render: (v: string) => v || '-' },
    {
      title: '时间',
      dataIndex: 'createTime',
      width: 160,
      render: (v: string) => parseTime(v) || '-'
    }
  ];

  const isBanned = current?.status === '1';
  const isReal = current?.isReal === 'Y' || current?.isReal === '1';

  return (
    <div className="user-manage">
      {/* 搜索区 */}
      <Card bordered={false} className="page-card user-manage__search">
        <Form form={searchForm} layout="inline" onFinish={handleSearch}>
          <Form.Item name="nickName" label="昵称">
            <Input allowClear placeholder="用户昵称" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="phonenumber" label="手机号">
            <Input allowClear placeholder="手机号" style={{ width: 160 }} />
          </Form.Item>
          <Form.Item name="userId" label="用户ID">
            <Input allowClear placeholder="用户ID" style={{ width: 140 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select allowClear placeholder="全部" style={{ width: 120 }} options={statusDict} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">
                搜索
              </Button>
              <Button icon={<ReloadOutlined />} onClick={handleReset}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      {/* 列表 */}
      <Card bordered={false} className="page-card user-manage__table">
        <div className="crud-page__toolbar">
          <Space>
            {canAdd && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                添加用户
              </Button>
            )}
          </Space>
          <div className="crud-page__stats">
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table<UserProfileVo>
          rowKey="id"
          size="middle"
          loading={loading}
          dataSource={rows}
          columns={columns as any}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: query.pageNum,
            pageSize: query.pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (pageNum, pageSize) => setQuery({ ...query, pageNum, pageSize })
          }}
        />
      </Card>

      {/* 详情抽屉 */}
      <Drawer
        title="用户详情"
        width={760}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        className="user-manage__drawer"
      >
        {current && (
          <>
            {/* 头部概览 */}
            <div className="detail-hero">
              <Avatar size={64} src={current.avatar} icon={<UserOutlined />} />
              <div className="detail-hero__main">
                <div className="detail-hero__name">
                  {current.nickName || '未设置昵称'}
                  {isBanned ? (
                    <Tag color="red" bordered={false} style={{ marginLeft: 8 }}>已封禁</Tag>
                  ) : (
                    <Tag color="green" bordered={false} style={{ marginLeft: 8 }}>正常</Tag>
                  )}
                  {isReal && <Tag color="blue" bordered={false}>已实名</Tag>}
                </div>
                <div className="detail-hero__sub">
                  ID: {current.userId} · 账号: {current.userName || '-'}
                </div>
              </div>
              {canEdit && (
                <Space>
                  <Button icon={<DollarOutlined />} onClick={() => openAdjust(current)}>
                    调整余额
                  </Button>
                  <Button
                    danger={!isBanned}
                    icon={isBanned ? <UnlockOutlined /> : <LockOutlined />}
                    onClick={() => toggleBan(current)}
                  >
                    {isBanned ? '解封' : '封禁'}
                  </Button>
                  {canRemove && (
                    <Button danger icon={<DeleteOutlined />} onClick={() => handleDelete(current)}>
                      删除
                    </Button>
                  )}
                </Space>
              )}
            </div>

            {/* 账户统计 */}
            <Row gutter={[14, 14]} className="detail-stats">
              <Col xs={12} sm={6}>
                <StatCard label="账户余额" value={`¥${fmtMoney(current.balance)}`} icon={<WalletOutlined />} color="#059669" />
              </Col>
              <Col xs={12} sm={6}>
                <StatCard label="冻结余额" value={`¥${fmtMoney(current.frozenBalance)}`} icon={<LockOutlined />} color="#d97706" />
              </Col>
              <Col xs={12} sm={6}>
                <StatCard label="累计充值" value={`¥${fmtMoney(current.totalRecharge)}`} icon={<DollarOutlined />} color="#2563eb" />
              </Col>
              <Col xs={12} sm={6}>
                <StatCard label="累计消费" value={`¥${fmtMoney(current.totalConsumption)}`} icon={<ShoppingOutlined />} color="#7c3aed" />
              </Col>
            </Row>

            {/* 基础信息 */}
            <Descriptions
              title="基础信息"
              bordered
              size="small"
              column={2}
              className="detail-desc"
            >
              <Descriptions.Item label="手机号">{current.phonenumber || '-'}</Descriptions.Item>
              <Descriptions.Item label="邮箱">{current.email || '-'}</Descriptions.Item>
              <Descriptions.Item label="性别">
                <DictTag options={sexDict} value={current.sex} />
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <DictTag options={statusDict} value={current.status} />
              </Descriptions.Item>
              <Descriptions.Item label="会员等级">
                {current.memberLevel ? <Tag color="gold">{current.memberLevel}</Tag> : '普通用户'}
              </Descriptions.Item>
              <Descriptions.Item label="会员到期">
                {current.memberExpireTime ? parseTime(current.memberExpireTime) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="最后登录IP">{current.loginIp || '-'}</Descriptions.Item>
              <Descriptions.Item label="最后登录">
                {current.loginDate ? parseTime(current.loginDate) : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="注册时间" span={2}>
                {current.registerTime ? parseTime(current.registerTime) : '-'}
              </Descriptions.Item>
              {isReal && (
                <>
                  <Descriptions.Item label="真实姓名">{current.realName || '-'}</Descriptions.Item>
                  <Descriptions.Item label="身份证号">{current.idCard || '-'}</Descriptions.Item>
                </>
              )}
              <Descriptions.Item label="备注" span={2}>
                <Space>
                  {current.remark || '-'}
                  {canEdit && (
                    <Tooltip title="编辑备注">
                      <Button
                        type="link"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={() => openRemark(current)}
                      />
                    </Tooltip>
                  )}
                </Space>
              </Descriptions.Item>
            </Descriptions>

            {/* 三方账户 + 余额流水 */}
            <Tabs
              className="detail-tabs"
              items={[
                {
                  key: 'social',
                  label: `绑定账户 (${socials.length})`,
                  children: socials.length ? (
                    <Table
                      rowKey="id"
                      size="small"
                      loading={detailLoading}
                      dataSource={socials}
                      columns={socialColumns}
                      pagination={false}
                    />
                  ) : (
                    <Empty description="暂无绑定的三方账户" />
                  )
                },
                {
                  key: 'balance',
                  label: `余额流水 (${balanceLogs.length})`,
                  children: balanceLogs.length ? (
                    <Table
                      rowKey="id"
                      size="small"
                      loading={detailLoading}
                      dataSource={balanceLogs}
                      columns={logColumns}
                      pagination={{ pageSize: 10, size: 'small' }}
                    />
                  ) : (
                    <Empty description="暂无余额变动记录" />
                  )
                }
              ]}
            />
          </>
        )}
      </Drawer>

      {/* 新增用户 */}
      <Modal
        title="添加用户"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={submitCreate}
        okText="确认生成"
        confirmLoading={createSaving}
        destroyOnClose
        maskClosable={false}
      >
        <Alert
          type="info"
          showIcon
          message="邮箱和手机号二选一，系统将自动生成初始密码。"
          style={{ marginBottom: 16 }}
        />
        <Form form={createForm} layout="vertical" preserve={false}>
          <Form.Item name="accountType" label="账号类型" rules={[{ required: true }]}>
            <Radio.Group
              optionType="button"
              buttonStyle="solid"
              onChange={() => createForm.setFieldValue('account', undefined)}
            >
              <Radio.Button value="phone">手机号</Radio.Button>
              <Radio.Button value="email">邮箱</Radio.Button>
            </Radio.Group>
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(previous, currentValue) => previous.accountType !== currentValue.accountType}>
            {({ getFieldValue }) => {
              const accountType = getFieldValue('accountType');
              const isEmail = accountType === 'email';
              return (
                <Form.Item
                  name="account"
                  label={isEmail ? '邮箱' : '手机号'}
                  rules={isEmail
                    ? [
                        { required: true, message: '请输入邮箱' },
                        { type: 'email', message: '邮箱格式错误' },
                        { max: 50, message: '邮箱长度不能超过50位' }
                      ]
                    : [
                        { required: true, message: '请输入手机号' },
                        { pattern: /^1[3-9]\d{9}$/, message: '手机号格式错误' }
                      ]}
                >
                  <Input
                    allowClear
                    maxLength={isEmail ? 50 : 11}
                    placeholder={isEmail ? '请输入用户邮箱' : '请输入用户手机号'}
                  />
                </Form.Item>
              );
            }}
          </Form.Item>
        </Form>
      </Modal>

      {/* 一次性登录凭据 */}
      <Modal
        title="用户添加成功"
        open={credentialOpen}
        onCancel={() => setCredentialOpen(false)}
        onOk={() => setCredentialOpen(false)}
        okText="我已保存"
        cancelButtonProps={{ style: { display: 'none' } }}
        afterClose={() => setCreatedCredential(null)}
        destroyOnClose
        maskClosable={false}
      >
        <Alert
          type="warning"
          showIcon
          message="初始密码仅显示本次，请复制后安全发送给用户。"
          style={{ marginBottom: 16 }}
        />
        {createdCredential && (
          <div className="credential-result">
            <div className="credential-result__label">登录账号</div>
            <Typography.Paragraph copyable={{ text: createdCredential.account }}>
              {createdCredential.account}
            </Typography.Paragraph>
            <div className="credential-result__label">初始密码</div>
            <Typography.Paragraph copyable={{ text: createdCredential.password }}>
              <Typography.Text code>{createdCredential.password}</Typography.Text>
            </Typography.Paragraph>
            <Typography.Paragraph
              className="credential-result__all"
              copyable={{
                text: `账号：${createdCredential.account}\n密码：${createdCredential.password}`,
                tooltips: ['复制账号和密码', '已复制']
              }}
            >
              复制完整登录信息
            </Typography.Paragraph>
          </div>
        )}
      </Modal>

      {/* 调整余额 */}
      <Modal
        title="调整用户余额"
        open={adjustOpen}
        onCancel={() => setAdjustOpen(false)}
        onOk={submitAdjust}
        confirmLoading={adjustSaving}
        destroyOnClose
        maskClosable={false}
      >
        {adjustTarget && (
          <div className="adjust-hint">
            用户「{adjustTarget.nickName || adjustTarget.userName || adjustTarget.userId}」 当前余额
            <span className="money"> ¥{fmtMoney(adjustTarget.balance)}</span>
          </div>
        )}
        <Form form={adjustForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="adjustType" label="调整方向" rules={[{ required: true }]}>
            <Radio.Group optionType="button" buttonStyle="solid">
              <Radio value="add">增加余额</Radio>
              <Radio value="deduct">扣减余额</Radio>
            </Radio.Group>
          </Form.Item>
          <Form.Item
            name="amount"
            label="金额（元）"
            rules={[{ required: true, message: '请输入金额' }]}
          >
            <InputNumber min={0.01} precision={2} style={{ width: '100%' }} placeholder="请输入金额" />
          </Form.Item>
          <Form.Item name="reason" label="调整原因">
            <Input.TextArea rows={3} maxLength={200} placeholder="选填，记录到余额流水便于审计" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 重置密码 */}
      <Modal
        title="重置密码"
        open={pwdOpen}
        onCancel={() => setPwdOpen(false)}
        onOk={submitResetPwd}
        confirmLoading={pwdSaving}
        destroyOnClose
        maskClosable={false}
      >
        {pwdTarget && (
          <div className="adjust-hint">
            为用户「{pwdTarget.nickName || pwdTarget.userName || pwdTarget.userId}」设置新密码
          </div>
        )}
        <Form form={pwdForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item
            name="password"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, max: 20, message: '密码长度 6-20 位' }
            ]}
          >
            <Input.Password placeholder="请输入新密码" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 编辑备注 */}
      <Modal
        title="编辑备注"
        open={remarkOpen}
        onCancel={() => setRemarkOpen(false)}
        onOk={submitRemark}
        confirmLoading={remarkSaving}
        destroyOnClose
        maskClosable={false}
      >
        <Form form={remarkForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={4} maxLength={500} placeholder="请输入备注" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

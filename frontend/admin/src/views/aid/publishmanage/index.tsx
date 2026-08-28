import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Card, Tabs, Table, Form, Input, Select, Button, Space, Tag, Modal, Image, Switch, Tooltip, Avatar, Empty, message
} from 'antd';
import { UserOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  listPublish, publishOnline, publishOffline, publishRevoke,
  listWhitelist, addWhitelist, removeWhitelist,
  searchPublishUsers, setUserPublishPermission,
  PublishItem, WhitelistItem, PublishUserItem
} from '@/api/aid/publish';
import { PROJECT_STATUS_OPTIONS, getLabelByValue, getAntdTagColor } from '@/utils/enums';
import { useAuth } from '@/hooks/useAuth';
import PageHeader from '@/components/PageHeader';
import './style.less';

/** 发布状态筛选项 */
const PUBLISH_STATE_OPTIONS = [
  { label: '审核通过未发布', value: 'approved' },
  { label: '已发布', value: 'published' }
];
/** 作品类型筛选项 */
const PROJECT_TYPE_OPTIONS = [
  { label: '电影', value: 'movie' },
  { label: '剧集', value: 'series' }
];

/** 列表数据通用 hook：按 tab 激活时懒加载，统一管理分页/搜索/刷新 */
function usePageList(fetchFn: (q: any) => Promise<any>, enabled: boolean) {
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [search, setSearch] = useState<Record<string, any>>({});

  const load = useCallback(() => {
    setLoading(true);
    fetchFn({ ...search, pageNum, pageSize })
      .then((res: any) => {
        setList(res.data || []);
        setTotal(res.total || 0);
      })
      .finally(() => setLoading(false));
  }, [fetchFn, search, pageNum, pageSize]);

  useEffect(() => {
    if (enabled) load();
  }, [enabled, load]);

  return {
    loading, list, total, pageNum, pageSize,
    setPage: (p: number, s: number) => { setPageNum(p); setPageSize(s); },
    applySearch: (v: Record<string, any>) => { setPageNum(1); setSearch(v); },
    resetSearch: () => { setPageNum(1); setSearch({}); },
    reload: load
  };
}

/** 电影/剧集 彩色标签 */
function TypeTag({ type }: { type?: string }) {
  if (!type) return <span>--</span>;
  return <Tag color={type === 'movie' ? 'purple' : 'blue'}>{type === 'movie' ? '电影' : '剧集'}</Tag>;
}

/** 作者信息（昵称 + 邮箱/手机号悬浮） */
function AuthorCell({ row }: { row: PublishItem | WhitelistItem }) {
  const contact = [row.email, row.phonenumber].filter(Boolean).join(' / ');
  return (
    <Tooltip title={contact || '无联系方式'}>
      <span>{row.nickName || '--'}（{row.userId}）</span>
    </Tooltip>
  );
}

export default function PublishManage() {
  const { hasPermi } = useAuth();
  const canEdit = hasPermi('aid:publish:edit');
  const canWhitelist = hasPermi('aid:publish:whitelist');
  const canQuery = hasPermi('aid:publish:query');

  const [activeTab, setActiveTab] = useState('publish');
  const publish = usePageList(listPublish, activeTab === 'publish');
  const whitelist = usePageList(listWhitelist, activeTab === 'whitelist');
  const [publishForm] = Form.useForm();
  const [whitelistForm] = Form.useForm();

  /** ===== 上架/下架/回撤 操作弹窗 ===== */
  const actionConfig = {
    online: { title: '上架作品', api: publishOnline, reasonRequired: false, danger: false, tip: '作品将对所有用户公开展示，原因选填。' },
    offline: { title: '下架作品', api: publishOffline, reasonRequired: true, danger: true, tip: '作品将从公开广场移除（审核状态保留），原因必填并通知用户。' },
    revoke: { title: '回撤审核', api: publishRevoke, reasonRequired: true, danger: true, tip: '撤销审核通过并同步下架，状态转为审核失败，用户修改后可重新提审，原因必填。' }
  } as const;

  const openAction = (kind: keyof typeof actionConfig, row: PublishItem) => {
    const cfg = actionConfig[kind];
    let reason = '';
    Modal.confirm({
      title: `${cfg.title}：${row.projectName}`,
      width: 480,
      content: (
        <div>
          <div className="help-text publish-action-tip">{cfg.tip}</div>
          <Input.TextArea
            rows={3}
            maxLength={500}
            showCount
            placeholder={kind === 'revoke'
              ? '请输入回撤理由（必填，将展示给用户）'
              : cfg.reasonRequired ? '请输入原因（必填，将展示给用户）' : '原因（选填）'}
            onChange={(e) => { reason = e.target.value; }}
          />
        </div>
      ),
      okText: `确认${cfg.title.slice(0, 2)}`,
      okButtonProps: { danger: cfg.danger },
      cancelText: '取消',
      onOk: () => {
        if (cfg.reasonRequired && !reason.trim()) {
          message.warning('请填写原因');
          return Promise.reject(new Error('reason required'));
        }
        return cfg.api({ id: row.id, reason: reason.trim() || null }).then(() => {
          message.success(`${cfg.title.slice(0, 2)}成功`);
          publish.reload();
        });
      }
    });
  };

  /** ===== 发布管理列定义 ===== */
  const publishColumns: ColumnsType<PublishItem> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '封面', dataIndex: 'coverUrl', width: 90,
      render: (v: string) => v
        ? <Image src={v} alt="封面" width={64} height={40} style={{ objectFit: 'cover', borderRadius: 4 }} />
        : <span className="help-text">无封面</span>
    },
    { title: '作品名称', dataIndex: 'projectName', ellipsis: true },
    { title: '类型', dataIndex: 'projectType', width: 80, render: (v: string) => <TypeTag type={v} /> },
    { title: '作者', key: 'author', width: 160, render: (_: any, row) => <AuthorCell row={row} /> },
    { title: '介绍', dataIndex: 'projectDesc', ellipsis: true },
    {
      title: '审核状态', dataIndex: 'status', width: 100,
      render: (v: number) => <Tag color={getAntdTagColor(PROJECT_STATUS_OPTIONS, v)}>{getLabelByValue(PROJECT_STATUS_OPTIONS, v)}</Tag>
    },
    {
      title: '发布状态', dataIndex: 'isPublic', width: 100,
      render: (v: string) => (v === '1' ? <Tag color="success">已发布</Tag> : <Tag>未发布</Tag>)
    },
    { title: '原因', dataIndex: 'statusReason', ellipsis: true },
    { title: '发布时间', dataIndex: 'publishTime', width: 165 },
    {
      title: '操作', key: 'op', width: 200, fixed: 'right',
      render: (_: any, row) => (
        <Space size={4}>
          {canEdit && row.isPublic !== '1' && row.status === 4 && (
            <Button type="link" size="small" onClick={() => openAction('online', row)}>上架</Button>
          )}
          {canEdit && row.isPublic === '1' && (
            <Button type="link" size="small" danger onClick={() => openAction('offline', row)}>下架</Button>
          )}
          {canEdit && row.status === 4 && (
            <Button type="link" size="small" danger onClick={() => openAction('revoke', row)}>回撤审核</Button>
          )}
        </Space>
      )
    }
  ];

  /** ===== 白名单：添加弹窗（用户搜索选择） ===== */
  const [addOpen, setAddOpen] = useState(false);
  const [addSubmitting, setAddSubmitting] = useState(false);
  const [userOptions, setUserOptions] = useState<PublishUserItem[]>([]);
  const [userSearching, setUserSearching] = useState(false);
  const [selectedUserId, setSelectedUserId] = useState<number | undefined>();
  const [addRemark, setAddRemark] = useState('');

  const searchTimerRef = useRef<ReturnType<typeof setTimeout>>();

  // 300ms 防抖，避免逐键触发搜索请求
  const doSearchUsers = (keyword: string) => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    if (!keyword.trim()) {
      setUserOptions([]);
      return;
    }
    searchTimerRef.current = setTimeout(() => {
      setUserSearching(true);
      searchPublishUsers(keyword.trim())
        .then((res: any) => setUserOptions(res.data || []))
        .finally(() => setUserSearching(false));
    }, 300);
  };

  useEffect(() => () => {
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
  }, []);

  const submitAdd = () => {
    if (!selectedUserId) {
      message.warning('请选择用户');
      return;
    }
    setAddSubmitting(true);
    addWhitelist({ userId: selectedUserId, remark: addRemark.trim() || null })
      .then(() => {
        message.success('添加成功');
        setAddOpen(false);
        whitelist.reload();
      })
      .finally(() => setAddSubmitting(false));
  };

  const confirmRemove = (row: WhitelistItem) => {
    Modal.confirm({
      title: '移除白名单',
      content: `确认将「${row.nickName || row.userId}」移出发布白名单吗？移出后该用户重新受发布总开关限制。`,
      okText: '确认移除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => removeWhitelist(row.id).then(() => { message.success('移除成功'); whitelist.reload(); })
    });
  };

  const whitelistColumns: ColumnsType<WhitelistItem> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '用户', key: 'user', width: 220,
      render: (_: any, row) => (
        <Space>
          <Avatar size="small" src={row.avatar} icon={<UserOutlined />} />
          <AuthorCell row={row} />
        </Space>
      )
    },
    { title: '邮箱', dataIndex: 'email', width: 200, ellipsis: true, render: (v: string) => v || '--' },
    { title: '手机号', dataIndex: 'phonenumber', width: 140, render: (v: string) => v || '--' },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (v: string) => v || '--' },
    { title: '添加人', dataIndex: 'createBy', width: 120 },
    { title: '添加时间', dataIndex: 'createTime', width: 165 },
    {
      title: '操作', key: 'op', width: 90, fixed: 'right',
      render: (_: any, row) => canWhitelist && (
        <Button type="link" size="small" danger onClick={() => confirmRemove(row)}>移除</Button>
      )
    }
  ];

  /** ===== 用户发布权限 tab ===== */
  const [permKeyword, setPermKeyword] = useState('');
  const [permUsers, setPermUsers] = useState<PublishUserItem[]>([]);
  const [permLoading, setPermLoading] = useState(false);
  const [permSearched, setPermSearched] = useState(false);

  const searchPermUsers = () => {
    if (!permKeyword.trim()) {
      message.warning('请输入关键字');
      return;
    }
    setPermLoading(true);
    searchPublishUsers(permKeyword.trim())
      .then((res: any) => { setPermUsers(res.data || []); setPermSearched(true); })
      .finally(() => setPermLoading(false));
  };

  const togglePermission = (row: PublishUserItem, enabled: boolean) => {
    setUserPublishPermission({ userId: row.userId, publishEnabled: enabled ? 1 : 0 }).then(() => {
      message.success(enabled ? '已允许发布' : '已禁止发布');
      setPermUsers((prev) => prev.map((u) => (u.userId === row.userId ? { ...u, publishEnabled: enabled ? 1 : 0 } : u)));
    });
  };

  const permColumns: ColumnsType<PublishUserItem> = [
    {
      title: '用户', key: 'user', width: 260,
      render: (_: any, row) => (
        <Space>
          <Avatar size="small" src={row.avatar} icon={<UserOutlined />} />
          <span>{row.nickName}</span>
        </Space>
      )
    },
    { title: '邮箱', dataIndex: 'email', width: 220, ellipsis: true, render: (v: string) => v || '--' },
    { title: '手机号', dataIndex: 'phonenumber', width: 150, render: (v: string) => v || '--' },
    {
      title: '白名单', dataIndex: 'inWhitelist', width: 100,
      render: (v: boolean) => (v ? <Tag color="gold">白名单</Tag> : <Tag>否</Tag>)
    },
    {
      title: '发布权限', dataIndex: 'publishEnabled', width: 140,
      render: (v: number, row) => (
        <Switch
          checked={v !== 0}
          checkedChildren="允许"
          unCheckedChildren="禁止"
          disabled={!canEdit}
          onChange={(checked) => togglePermission(row, checked)}
        />
      )
    }
  ];

  /** ===== 通用渲染 ===== */
  const renderTable = (state: any, columns: ColumnsType<any>) => (
    <Table
      rowKey="id"
      size="middle"
      loading={state.loading}
      columns={columns}
      dataSource={state.list}
      scroll={{ x: 'max-content' }}
      pagination={{
        current: state.pageNum,
        pageSize: state.pageSize,
        total: state.total,
        showSizeChanger: true,
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: (p: number, s: number) => state.setPage(p, s)
      }}
    />
  );

  const tabItems = [
    {
      key: 'publish',
      label: '发布管理',
      children: (
        <div className="publish-tab-body">
          <Card className="page-card" bordered={false}>
            <Form form={publishForm} layout="inline" onFinish={(v) => publish.applySearch(v)} style={{ rowGap: 8 }}>
              <Form.Item name="publishState">
                <Select placeholder="发布状态" allowClear style={{ width: 160 }} options={PUBLISH_STATE_OPTIONS} />
              </Form.Item>
              <Form.Item name="projectName">
                <Input placeholder="作品名称" allowClear style={{ width: 160 }} />
              </Form.Item>
              <Form.Item name="projectType">
                <Select placeholder="作品类型" allowClear style={{ width: 120 }} options={PROJECT_TYPE_OPTIONS} />
              </Form.Item>
              <Form.Item name="keyword">
                <Input placeholder="作者昵称/邮箱/手机号" allowClear style={{ width: 200 }} />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit">搜索</Button>
                  <Button onClick={() => { publishForm.resetFields(); publish.resetSearch(); }}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
          <Card className="page-card" bordered={false}>
            {renderTable(publish, publishColumns)}
          </Card>
        </div>
      )
    },
    {
      key: 'whitelist',
      label: '发布白名单',
      children: (
        <div className="publish-tab-body">
          <Card className="page-card" bordered={false}>
            <Form form={whitelistForm} layout="inline" onFinish={(v) => whitelist.applySearch(v)}>
              <Form.Item name="keyword">
                <Input placeholder="昵称/邮箱/手机号" allowClear style={{ width: 200 }} />
              </Form.Item>
              <Form.Item>
                <Space>
                  <Button type="primary" htmlType="submit">搜索</Button>
                  <Button onClick={() => { whitelistForm.resetFields(); whitelist.resetSearch(); }}>重置</Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
          <Card className="page-card" bordered={false}>
            <div className="crud-page__toolbar">
              <Space>
                {canWhitelist && (
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => {
                    setSelectedUserId(undefined);
                    setAddRemark('');
                    setUserOptions([]);
                    setAddOpen(true);
                  }}>
                    添加白名单
                  </Button>
                )}
              </Space>
              <div className="crud-page__stats">
                <span>共 {whitelist.total} 条</span>
              </div>
            </div>
            {renderTable(whitelist, whitelistColumns)}
          </Card>
        </div>
      )
    },
    ...(canQuery ? [{
      key: 'permission',
      label: '用户发布权限',
      children: (
        <div className="publish-tab-body">
          <Card className="page-card" bordered={false}>
            <Space>
              <Input
                placeholder="昵称/邮箱/手机号"
                allowClear
                style={{ width: 240 }}
                value={permKeyword}
                onChange={(e) => setPermKeyword(e.target.value)}
                onPressEnter={searchPermUsers}
              />
              <Button type="primary" icon={<SearchOutlined />} loading={permLoading} onClick={searchPermUsers}>搜索</Button>
            </Space>
          </Card>
          <Card className="page-card" bordered={false}>
            {permSearched && permUsers.length === 0 ? (
              <Empty description="未找到匹配用户" />
            ) : (
              <Table
                rowKey="userId"
                size="middle"
                loading={permLoading}
                columns={permColumns}
                dataSource={permUsers}
                pagination={false}
                scroll={{ x: 'max-content' }}
              />
            )}
          </Card>
        </div>
      )
    }] : [])
  ];

  return (
    <div className="crud-page">
      <PageHeader
        title="发布管理"
        desc="管理作品上下架与回撤、发布白名单及用户发布权限"
      />
      <Tabs activeKey={activeTab} onChange={setActiveTab} items={tabItems} />

      {/* 添加白名单弹窗 */}
      <Modal
        title="添加发布白名单"
        open={addOpen}
        onCancel={() => setAddOpen(false)}
        confirmLoading={addSubmitting}
        onOk={submitAdd}
        okText="确认添加"
        cancelText="取消"
        destroyOnClose
      >
        <Form layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item label="选择用户" required extra="支持昵称 / 邮箱 / 手机号搜索，已在白名单的用户不可重复添加">
            <Select
              showSearch
              placeholder="输入关键字搜索用户"
              style={{ width: '100%' }}
              value={selectedUserId}
              onChange={setSelectedUserId}
              onSearch={doSearchUsers}
              loading={userSearching}
              filterOption={false}
              notFoundContent={userSearching ? '搜索中...' : '暂无匹配用户'}
              options={userOptions.map((u) => ({
                value: u.userId,
                label: `${u.nickName}${u.email ? ` · ${u.email}` : ''}${u.phonenumber ? ` · ${u.phonenumber}` : ''}${u.inWhitelist ? '（已在白名单）' : ''}`,
                disabled: u.inWhitelist
              }))}
            />
          </Form.Item>
          <Form.Item label="备注（选填）">
            <Input.TextArea
              rows={2}
              maxLength={100}
              showCount
              value={addRemark}
              onChange={(e) => setAddRemark(e.target.value)}
              placeholder="加入原因等备注信息"
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

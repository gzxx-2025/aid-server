import React, { useEffect, useState } from 'react';
import { Button, Card, DatePicker, Form, Input, Modal, Select, Space, Table, Tag, message } from 'antd';
import { ClearOutlined, DeleteOutlined, DownloadOutlined, SearchOutlined, UnlockOutlined } from '@ant-design/icons';
import { list as listLogininfor, delLogininfor, cleanLogininfor, unlockLogininfor } from '@/api/monitor/logininfor';
import { download } from '@/utils/request';
import { useDict } from '@/hooks/useDict';
import DictTag from '@/components/DictTag';
import Auth from '@/components/Auth';
import PageHeader from '@/components/PageHeader';
import { parseTime } from '@/utils/ruoyi';

export default function LogininforPage() {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10, orderByColumn: 'loginTime', isAsc: 'desc' });
  const [dateRange, setDateRange] = useState<any>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);

  const dicts = useDict('sys_common_status');
  const statusDict = dicts['sys_common_status'] || [];

  const loadList = async () => {
    setLoading(true);
    try {
      const params: any = { ...query };
      if (dateRange?.length === 2) {
        params.params = { beginTime: dateRange[0]?.format('YYYY-MM-DD 00:00:00'), endTime: dateRange[1]?.format('YYYY-MM-DD 23:59:59') };
      }
      const res: any = await listLogininfor(params);
      setList(res.rows || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);

  const handleTable = (_p: any, _f: any, sorter: any) => {
    const next: any = { ...query };
    if (sorter.order) {
      next.orderByColumn = sorter.field;
      next.isAsc = sorter.order === 'ascend' ? 'asc' : 'desc';
    }
    setQuery(next);
  };

  const handleDelete = (row?: any) => {
    const ids = row?.infoId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({ title: '提示', content: `是否确认删除访问编号为 "${ids}" 的数据项？`, okType: 'danger',
      onOk: async () => { await delLogininfor(ids); message.success('删除成功'); setSelectedKeys([]); loadList(); } });
  };
  const handleClean = () => {
    Modal.confirm({ title: '清空所有登录日志？', okType: 'danger',
      onOk: async () => { await cleanLogininfor(); message.success('清空成功'); loadList(); } });
  };
  const handleUnlock = (row: any) => {
    Modal.confirm({ title: '提示', content: `是否确认解锁用户 "${row.userName}" 的登录状态？`,
      onOk: async () => { await unlockLogininfor(row.userName); message.success('解锁成功'); loadList(); } });
  };

  return (
    <div className="crud-page">
      <PageHeader title="登录日志" desc="记录用户登录行为，支持解锁账号、删除与清空日志" />
      <Card className="page-card" bordered={false}>
        <Form form={form} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="ipaddr" label="登录地址"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="userName" label="用户名称"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 140 }} options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item label="登录时间"><DatePicker.RangePicker showTime value={dateRange} onChange={setDateRange as any} /></Form.Item>
          <Form.Item><Space>
            <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
            <Button onClick={() => { form.resetFields(); setDateRange(null); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
          </Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false} style={{ marginTop: 16 }}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="monitor:logininfor:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
            <Auth permission="monitor:logininfor:remove"><Button danger icon={<ClearOutlined />} onClick={handleClean}>清空</Button></Auth>
            <Auth permission="monitor:logininfor:unlock">
              <Button disabled={selectedKeys.length !== 1} icon={<UnlockOutlined />} onClick={() => {
                const row = list.find((r: any) => r.infoId === selectedKeys[0]);
                if (row) handleUnlock(row);
              }}>解锁</Button>
            </Auth>
            <Auth permission="monitor:logininfor:export"><Button icon={<DownloadOutlined />} onClick={() => download('/monitor/logininfor/export', query, `logininfor_${Date.now()}.xlsx`)}>导出</Button></Auth>
          </Space>
          <div className="crud-page__stats">
            {selectedKeys.length > 0 && <Tag color="blue">已选 {selectedKeys.length}</Tag>}
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table rowKey="infoId" size="small" loading={loading} dataSource={list} scroll={{ x: 1300 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          onChange={handleTable}
          columns={[
            { title: '访问编号', dataIndex: 'infoId', width: 100 },
            { title: '用户名称', dataIndex: 'userName', width: 140 },
            { title: '登录地址', dataIndex: 'ipaddr', width: 140 },
            { title: '登录地点', dataIndex: 'loginLocation', width: 180, ellipsis: true },
            { title: '浏览器', dataIndex: 'browser', width: 140 },
            { title: '操作系统', dataIndex: 'os', width: 140 },
            { title: '登录状态', dataIndex: 'status', width: 110, render: (v: any) => <DictTag options={statusDict} value={v} /> },
            { title: '操作信息', dataIndex: 'msg', ellipsis: true },
            { title: '登录日期', dataIndex: 'loginTime', width: 180, sorter: true, defaultSortOrder: 'descend' as const, render: (v: string) => parseTime(v) }
          ]}
        />
      </Card>
    </div>
  );
}

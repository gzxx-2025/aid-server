import React, { useEffect, useState } from 'react';
import { Button, Card, DatePicker, Descriptions, Form, Input, Modal, Select, Space, Table, message } from 'antd';
import { ClearOutlined, DeleteOutlined, DownloadOutlined, EyeOutlined, SearchOutlined } from '@ant-design/icons';
import { list as listOperlog, delOperlog, cleanOperlog } from '@/api/monitor/operlog';
import { download } from '@/utils/request';
import { useDict } from '@/hooks/useDict';
import DictTag from '@/components/DictTag';
import Auth from '@/components/Auth';
import { parseTime } from '@/utils/ruoyi';

export default function OperlogPage() {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10, orderByColumn: 'operTime', isAsc: 'desc' });
  const [dateRange, setDateRange] = useState<any>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [detail, setDetail] = useState<any | null>(null);

  const dicts = useDict('sys_oper_type', 'sys_common_status');
  const typeDict = dicts['sys_oper_type'] || [];
  const statusDict = dicts['sys_common_status'] || [];

  const loadList = async () => {
    setLoading(true);
    try {
      const params: any = { ...query };
      if (dateRange?.length === 2) {
        params.params = {
          beginTime: dateRange[0]?.format('YYYY-MM-DD 00:00:00'),
          endTime: dateRange[1]?.format('YYYY-MM-DD 23:59:59')
        };
      }
      const res: any = await listOperlog(params);
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
    const ids = row?.operId || selectedKeys.join(',');
    if (!ids) return;
    Modal.confirm({
      title: '提示', content: `是否确认删除日志编号为 "${ids}" 的数据项？`, okType: 'danger',
      onOk: async () => { await delOperlog(ids); message.success('删除成功'); setSelectedKeys([]); loadList(); }
    });
  };
  const handleClean = () => {
    Modal.confirm({
      title: '清空所有操作日志？', okType: 'danger',
      onOk: async () => { await cleanOperlog(); message.success('清空成功'); loadList(); }
    });
  };

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Form form={form} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="operIp" label="操作地址"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="title" label="系统模块"><Input allowClear style={{ width: 200 }} /></Form.Item>
          <Form.Item name="operName" label="操作人员"><Input allowClear style={{ width: 180 }} /></Form.Item>
          <Form.Item name="businessType" label="类型"><Select allowClear style={{ width: 140 }} options={typeDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 140 }} options={statusDict.map((d: any) => ({ label: d.label, value: d.value }))} /></Form.Item>
          <Form.Item label="操作时间"><DatePicker.RangePicker showTime value={dateRange} onChange={setDateRange as any} /></Form.Item>
          <Form.Item><Space>
            <Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button>
            <Button onClick={() => { form.resetFields(); setDateRange(null); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button>
          </Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false} style={{ marginTop: 16 }}>
        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <Auth permission="monitor:operlog:remove"><Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />} onClick={() => handleDelete()}>批量删除</Button></Auth>
          <Auth permission="monitor:operlog:remove"><Button danger icon={<ClearOutlined />} onClick={handleClean}>清空</Button></Auth>
          <Auth permission="monitor:operlog:export"><Button icon={<DownloadOutlined />} onClick={() => download('/monitor/operlog/export', query, `operlog_${Date.now()}.xlsx`)}>导出</Button></Auth>
        </div>
        <Table rowKey="operId" size="small" loading={loading} dataSource={list} scroll={{ x: 1300 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
          onChange={handleTable}
          columns={[
            { title: '日志编号', dataIndex: 'operId', width: 100 },
            { title: '系统模块', dataIndex: 'title', width: 140, ellipsis: true },
            { title: '操作类型', dataIndex: 'businessType', width: 110, render: (v: any) => <DictTag options={typeDict} value={v} /> },
            { title: '操作人员', dataIndex: 'operName', width: 120, sorter: true },
            { title: '操作地址', dataIndex: 'operIp', width: 140 },
            { title: '操作地点', dataIndex: 'operLocation', width: 180, ellipsis: true },
            { title: '操作状态', dataIndex: 'status', width: 100, render: (v: any) => <DictTag options={statusDict} value={v} /> },
            { title: '操作时间', dataIndex: 'operTime', width: 180, sorter: true, defaultSortOrder: 'descend' as const, render: (v: string) => parseTime(v) },
            { title: '消耗时间', dataIndex: 'costTime', width: 110, sorter: true, render: (v: number) => v != null ? `${v} 毫秒` : '-' },
            { title: '操作', key: 'ops', width: 100, fixed: 'right' as const, render: (_: any, r: any) => (
              <Auth permission="monitor:operlog:query">
                <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetail(r)}>详细</Button>
              </Auth>
            ) }
          ]}
        />
      </Card>

      <Modal open={!!detail} title="操作日志详细" onCancel={() => setDetail(null)} footer={<Button onClick={() => setDetail(null)}>关闭</Button>} width={820}>
        {detail && (
          <Descriptions column={2} bordered size="small">
            <Descriptions.Item label="操作模块">{detail.title} / <DictTag options={typeDict} value={detail.businessType} /></Descriptions.Item>
            <Descriptions.Item label="登录信息">{detail.operName} / {detail.operIp} / {detail.operLocation}</Descriptions.Item>
            <Descriptions.Item label="请求地址" span={2}>{detail.operUrl}</Descriptions.Item>
            <Descriptions.Item label="请求方式">{detail.requestMethod}</Descriptions.Item>
            <Descriptions.Item label="操作方法">{detail.method}</Descriptions.Item>
            <Descriptions.Item label="请求参数" span={2}>
              <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', background: '#f5f7fa', padding: 8, borderRadius: 4, maxHeight: 160, overflow: 'auto', margin: 0 }}>{detail.operParam || '-'}</pre>
            </Descriptions.Item>
            <Descriptions.Item label="返回参数" span={2}>
              <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', background: '#f5f7fa', padding: 8, borderRadius: 4, maxHeight: 160, overflow: 'auto', margin: 0 }}>{detail.jsonResult || '-'}</pre>
            </Descriptions.Item>
            <Descriptions.Item label="操作状态"><DictTag options={statusDict} value={detail.status} /></Descriptions.Item>
            <Descriptions.Item label="消耗时间">{detail.costTime} 毫秒</Descriptions.Item>
            <Descriptions.Item label="操作时间" span={2}>{parseTime(detail.operTime)}</Descriptions.Item>
            {String(detail.status) === '1' && <Descriptions.Item label="异常信息" span={2}>{detail.errorMsg}</Descriptions.Item>}
          </Descriptions>
        )}
      </Modal>
    </div>
  );
}

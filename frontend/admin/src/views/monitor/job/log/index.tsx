import React, { useEffect, useState } from 'react';
import {
  Button,
  Card,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  message
} from 'antd';
import {
  ArrowLeftOutlined,
  ClearOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  RedoOutlined,
  SearchOutlined
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useParams } from 'react-router-dom';
import type { Dayjs } from 'dayjs';

import Auth from '@/components/Auth';
import DictTag from '@/components/DictTag';
import { useDict } from '@/hooks/useDict';
import { parseTime } from '@/utils/ruoyi';
import { download } from '@/utils/request';
import { listJobLog, delJobLog, cleanJobLog } from '@/api/monitor/jobLog';
import { getJob } from '@/api/monitor/job';

interface JobLogRow {
  jobLogId: number;
  jobName: string;
  jobGroup: string;
  invokeTarget: string;
  jobMessage: string;
  status: string;
  exceptionInfo?: string;
  createTime: string;
}

export default function JobLogPage() {
  const { jobId } = useParams();
  const navigate = useNavigate();
  const [queryForm] = Form.useForm();
  const dicts = useDict('sys_job_group', 'sys_common_status');
  const [rows, setRows] = useState<JobLogRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [detail, setDetail] = useState<JobLogRow | null>(null);

  const fetchData = async () => {
    const vals = queryForm.getFieldsValue();
    const params: Record<string, any> = {
      pageNum,
      pageSize,
      jobName: vals.jobName,
      jobGroup: vals.jobGroup,
      status: vals.status
    };
    if (dateRange && dateRange.length === 2) {
      params.params = {
        beginTime: dateRange[0].format('YYYY-MM-DD 00:00:00'),
        endTime: dateRange[1].format('YYYY-MM-DD 23:59:59')
      };
    }
    setLoading(true);
    try {
      const res: any = await listJobLog(params);
      setRows(res.rows || []);
      setTotal(res.total || 0);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    (async () => {
      if (jobId && jobId !== '0') {
        try {
          const res: any = await getJob(Number(jobId));
          const data = res.data || {};
          queryForm.setFieldsValue({
            jobName: data.jobName,
            jobGroup: data.jobGroup
          });
        } catch {
          /* ignore */
        }
      }
      fetchData();
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pageNum, pageSize]);

  const handleSearch = () => {
    setPageNum(1);
    fetchData();
  };

  const handleReset = () => {
    queryForm.resetFields();
    setDateRange(null);
    setPageNum(1);
    setTimeout(fetchData, 0);
  };

  const handleBatchDelete = async () => {
    if (!selectedKeys.length) return;
    Modal.confirm({
      title: '确认删除',
      content: `确认删除选中的 ${selectedKeys.length} 条日志？`,
      okType: 'danger',
      onOk: async () => {
        await delJobLog(selectedKeys.join(','));
        message.success('删除成功');
        setSelectedKeys([]);
        fetchData();
      }
    });
  };

  const handleClean = async () => {
    Modal.confirm({
      title: '确认清空',
      content: '是否确认清空所有调度日志？',
      okType: 'danger',
      onOk: async () => {
        await cleanJobLog();
        message.success('清空成功');
        fetchData();
      }
    });
  };

  const handleExport = () => {
    const params: Record<string, any> = queryForm.getFieldsValue();
    if (dateRange && dateRange.length === 2) {
      params.params = {
        beginTime: dateRange[0].format('YYYY-MM-DD 00:00:00'),
        endTime: dateRange[1].format('YYYY-MM-DD 23:59:59')
      };
    }
    download('/monitor/jobLog/export', params, `log_${Date.now()}.xlsx`);
  };

  const columns: ColumnsType<JobLogRow> = [
    { title: '日志编号', dataIndex: 'jobLogId', width: 100 },
    { title: '任务名称', dataIndex: 'jobName', width: 160, ellipsis: true },
    {
      title: '任务组',
      dataIndex: 'jobGroup',
      width: 110,
      render: (v) => <DictTag options={dicts.sys_job_group || []} value={v} />
    },
    { title: '调用目标', dataIndex: 'invokeTarget', width: 240, ellipsis: true },
    { title: '日志信息', dataIndex: 'jobMessage', ellipsis: true },
    {
      title: '执行状态',
      dataIndex: 'status',
      width: 100,
      render: (v) => <DictTag options={dicts.sys_common_status || []} value={v} />
    },
    {
      title: '执行时间',
      dataIndex: 'createTime',
      width: 170,
      render: (v) => parseTime(v)
    },
    {
      title: '操作',
      key: '__ops__',
      width: 100,
      fixed: 'right',
      render: (_, row) => (
        <Auth permission="monitor:job:query">
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setDetail(row)}>
            详细
          </Button>
        </Auth>
      )
    }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Form form={queryForm} layout="inline" onFinish={handleSearch}>
          <Form.Item name="jobName" label="任务名称">
            <Input placeholder="请输入任务名称" allowClear style={{ width: 180 }} />
          </Form.Item>
          <Form.Item name="jobGroup" label="任务组名">
            <Select
              allowClear
              placeholder="请选择任务组名"
              style={{ width: 180 }}
              options={(dicts.sys_job_group || []).map((d) => ({ label: d.label, value: d.value }))}
            />
          </Form.Item>
          <Form.Item name="status" label="执行状态">
            <Select
              allowClear
              placeholder="请选择执行状态"
              style={{ width: 180 }}
              options={(dicts.sys_common_status || []).map((d) => ({
                label: d.label,
                value: d.value
              }))}
            />
          </Form.Item>
          <Form.Item label="执行时间">
            <DatePicker.RangePicker
              value={dateRange as any}
              onChange={(v) => setDateRange(v as any)}
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} htmlType="submit">
                搜索
              </Button>
              <Button icon={<RedoOutlined />} onClick={handleReset}>
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/monitor/job')}>
              返回
            </Button>
            <Auth permission="monitor:job:remove">
              <Button
                danger
                icon={<DeleteOutlined />}
                disabled={!selectedKeys.length}
                onClick={handleBatchDelete}
              >
                删除
              </Button>
            </Auth>
            <Auth permission="monitor:job:remove">
              <Button danger icon={<ClearOutlined />} onClick={handleClean}>
                清空
              </Button>
            </Auth>
            <Auth permission="monitor:job:export">
              <Button icon={<DownloadOutlined />} onClick={handleExport}>
                导出
              </Button>
            </Auth>
          </Space>
          <div className="crud-page__stats">
            {selectedKeys.length > 0 && <Tag color="blue">已选 {selectedKeys.length}</Tag>}
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table<JobLogRow>
          rowKey="jobLogId"
          size="middle"
          columns={columns}
          dataSource={rows}
          loading={loading}
          scroll={{ x: 'max-content' }}
          rowSelection={{
            selectedRowKeys: selectedKeys,
            onChange: (keys) => setSelectedKeys(keys)
          }}
          pagination={{
            current: pageNum,
            pageSize,
            total,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPageNum(p);
              setPageSize(s);
            }
          }}
        />
      </Card>

      <Modal
        title="调度日志详情"
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={
          <Button onClick={() => setDetail(null)}>关闭</Button>
        }
        width={720}
      >
        {detail && (
          <Descriptions bordered column={2} size="small" labelStyle={{ width: 120 }}>
            <Descriptions.Item label="日志序号">{detail.jobLogId}</Descriptions.Item>
            <Descriptions.Item label="任务分组">
              <DictTag options={dicts.sys_job_group || []} value={detail.jobGroup} />
            </Descriptions.Item>
            <Descriptions.Item label="任务名称" span={2}>{detail.jobName}</Descriptions.Item>
            <Descriptions.Item label="调用方法" span={2}>{detail.invokeTarget}</Descriptions.Item>
            <Descriptions.Item label="日志信息" span={2}>{detail.jobMessage}</Descriptions.Item>
            <Descriptions.Item label="执行时间" span={2}>{parseTime(detail.createTime)}</Descriptions.Item>
            <Descriptions.Item label="执行状态" span={2}>
              <DictTag options={dicts.sys_common_status || []} value={detail.status} />
            </Descriptions.Item>
            {detail.status === '1' && (
              <Descriptions.Item label="异常信息" span={2}>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{detail.exceptionInfo}</pre>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Modal>
    </div>
  );
}

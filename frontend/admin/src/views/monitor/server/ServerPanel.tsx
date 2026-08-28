import React, { useEffect, useState } from 'react';
import { Card, Col, Descriptions, Progress, Row, Spin, Table, Tag, Button, Space } from 'antd';
import {
  CloudServerOutlined,
  DatabaseOutlined,
  HddOutlined,
  RocketOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import { getServer } from '@/api/monitor/server';

const cardStyle: React.CSSProperties = {
  height: '100%',
  borderRadius: 12,
  border: '1px solid #f0f2f5'
};

const headerIconStyle: React.CSSProperties = {
  width: 32,
  height: 32,
  borderRadius: 8,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: '#fff',
  fontSize: 16,
  marginRight: 10
};

/** 服务监控面板：CPU / 内存 / 服务器 / JVM / 磁盘 */
export default function ServerPanel() {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await getServer();
      setData(res.data ?? res);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  if (loading || !data) {
    return (
      <div style={{ padding: 80, textAlign: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }

  const cpu = data.cpu || {};
  const mem = data.mem || {};
  const jvm = data.jvm || {};
  const sys = data.sys || {};
  const sysFiles = data.sysFiles || [];

  const memUsage = Number(mem.usage) || 0;
  const cpuUsed = Number(cpu.used) || 0;
  const jvmUsage = jvm.total ? Math.round((Number(jvm.used) / Number(jvm.total)) * 100) : 0;

  const fileColumns = [
    { title: '盘符路径', dataIndex: 'dirName', width: 180 },
    { title: '文件系统', dataIndex: 'sysTypeName', width: 120 },
    { title: '盘符类型', dataIndex: 'typeName', width: 100 },
    { title: '总大小', dataIndex: 'total', width: 100 },
    { title: '可用大小', dataIndex: 'free', width: 100 },
    { title: '已用大小', dataIndex: 'used', width: 100 },
    {
      title: '已用百分比',
      dataIndex: 'usage',
      render: (v: number) => {
        const pct = Number(v) || 0;
        const status = pct > 85 ? 'exception' : pct > 70 ? 'active' : 'normal';
        return <Progress percent={pct} size="small" status={status as any} />;
      }
    }
  ];

  return (
    <div>
      <div style={{ textAlign: 'right', marginBottom: 12 }}>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      </div>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={12}>
          <Card
            bordered={false}
            style={cardStyle}
            bodyStyle={{ padding: 20 }}
            title={
              <span>
                <span style={{ ...headerIconStyle, background: 'linear-gradient(135deg, #2563eb 0%, #6366f1 100%)' }}>
                  <DatabaseOutlined />
                </span>
                CPU
              </span>
            }
          >
            <Descriptions column={2} size="small" colon>
              <Descriptions.Item label="核心数">{cpu.cpuNum}</Descriptions.Item>
              <Descriptions.Item label="用户使用率">{cpu.used}%</Descriptions.Item>
              <Descriptions.Item label="系统使用率">{cpu.sys}%</Descriptions.Item>
              <Descriptions.Item label="当前空闲率">{cpu.free}%</Descriptions.Item>
            </Descriptions>
            <Progress percent={cpuUsed} status={cpuUsed > 85 ? 'exception' : 'active'} style={{ marginTop: 14 }} />
          </Card>
        </Col>

        <Col xs={24} md={12}>
          <Card
            bordered={false}
            style={cardStyle}
            bodyStyle={{ padding: 20 }}
            title={
              <span>
                <span style={{ ...headerIconStyle, background: 'linear-gradient(135deg, #06b6d4 0%, #3b82f6 100%)' }}>
                  <HddOutlined />
                </span>
                内存
              </span>
            }
          >
            <Descriptions column={2} size="small" colon>
              <Descriptions.Item label="总内存">{mem.total} GB</Descriptions.Item>
              <Descriptions.Item label="已用内存">{mem.used} GB</Descriptions.Item>
              <Descriptions.Item label="剩余内存">{mem.free} GB</Descriptions.Item>
              <Descriptions.Item label="使用率">
                <Tag color={memUsage > 85 ? 'error' : memUsage > 70 ? 'warning' : 'success'}>{memUsage}%</Tag>
              </Descriptions.Item>
            </Descriptions>
            <Progress percent={memUsage} status={memUsage > 85 ? 'exception' : 'active'} style={{ marginTop: 14 }} />
          </Card>
        </Col>

        <Col xs={24} md={12}>
          <Card
            bordered={false}
            style={cardStyle}
            bodyStyle={{ padding: 20 }}
            title={
              <span>
                <span style={{ ...headerIconStyle, background: 'linear-gradient(135deg, #f59e0b 0%, #f97316 100%)' }}>
                  <CloudServerOutlined />
                </span>
                服务器信息
              </span>
            }
          >
            <Descriptions column={1} size="small" colon>
              <Descriptions.Item label="服务器名称">{sys.computerName}</Descriptions.Item>
              <Descriptions.Item label="服务器IP">{sys.computerIp}</Descriptions.Item>
              <Descriptions.Item label="项目路径">{sys.userDir}</Descriptions.Item>
              <Descriptions.Item label="操作系统">{sys.osName}</Descriptions.Item>
              <Descriptions.Item label="系统架构">{sys.osArch}</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>

        <Col xs={24} md={12}>
          <Card
            bordered={false}
            style={cardStyle}
            bodyStyle={{ padding: 20 }}
            title={
              <span>
                <span style={{ ...headerIconStyle, background: 'linear-gradient(135deg, #64748b 0%, #475569 100%)' }}>
                  <RocketOutlined />
                </span>
                Java 虚拟机信息
              </span>
            }
          >
            <Descriptions column={2} size="small" colon>
              <Descriptions.Item label="JVM名称" span={2}>{jvm.name}</Descriptions.Item>
              <Descriptions.Item label="JVM版本">{jvm.version}</Descriptions.Item>
              <Descriptions.Item label="启动时间">{jvm.startTime}</Descriptions.Item>
              <Descriptions.Item label="运行时长" span={2}>{jvm.runTime}</Descriptions.Item>
              <Descriptions.Item label="总内存">{jvm.total} MB</Descriptions.Item>
              <Descriptions.Item label="已用内存">{jvm.used} MB</Descriptions.Item>
              <Descriptions.Item label="安装路径" span={2}>{jvm.home}</Descriptions.Item>
            </Descriptions>
            <Progress percent={jvmUsage} status={jvmUsage > 85 ? 'exception' : 'active'} style={{ marginTop: 14 }} />
          </Card>
        </Col>

        <Col span={24}>
          <Card bordered={false} style={{ ...cardStyle, height: 'auto' }} bodyStyle={{ padding: 20 }} title="磁盘状态">
            <Table
              rowKey={(r: any, i: any) => `${r.dirName}-${i}`}
              size="small"
              columns={fileColumns}
              dataSource={sysFiles}
              pagination={false}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}

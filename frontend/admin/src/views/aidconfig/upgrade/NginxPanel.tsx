import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Form,
  Input,
  Modal,
  Row,
  Space,
  Tag,
  Typography,
  message
} from 'antd';
import {
  CheckCircleOutlined,
  CloudServerOutlined,
  HistoryOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons';
import { DeploymentConfig, UpdaterLastTask } from '@/api/aidconfig/upgrade';
import { NginxAction, NginxConfigParams, submitNginxTask } from '@/api/aidconfig/nginx';
import { useUserStore } from '@/store/useUserStore';

interface Props {
  config: DeploymentConfig | null;
  ready: boolean;
  busy: boolean;
  loading: boolean;
  lastTask?: UpdaterLastTask;
  onSubmitted: (action: string) => void;
  onRefresh: () => Promise<void>;
}

export default function NginxPanel({ config, ready, busy, loading, lastTask, onSubmitted, onRefresh }: Props) {
  const [form] = Form.useForm<NginxConfigParams>();
  const permissions = useUserStore((state) => state.permissions);
  const canManage = permissions.includes('*:*:*') || permissions.includes('aidconfig:upgrade:nginx');
  const [dirty, setDirty] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [taskId, setTaskId] = useState<string>();
  const lock = useRef(false);
  const revision = useRef('');
  const [modal, modalContext] = Modal.useModal();
  const values = config?.values || {};
  const available = ready && values.NGINX_MANAGEMENT_AVAILABLE === 'true';
  const stale = dirty && revision.current !== values.NGINX_REVISION;
  const disabled = !available || !canManage || busy || submitting || stale;

  useEffect(() => {
    if (!config || dirty) return;
    revision.current = config.values.NGINX_REVISION || '';
    form.setFieldsValue({
      backendOrigin: config.values.NGINX_BACKEND_ORIGIN,
      maxBodyMb: config.values.NGINX_MAX_BODY_MB,
      readTimeoutSeconds: config.values.NGINX_READ_TIMEOUT_SECONDS,
      connectTimeoutSeconds: config.values.NGINX_CONNECT_TIMEOUT_SECONDS,
      extraDirectives: config.values.NGINX_EXTRA_DIRECTIVES || ''
    });
  }, [config, dirty, form]);

  const submit = async (action: NginxAction) => {
    if (lock.current || disabled) return;
    lock.current = true;
    setSubmitting(true);
    try {
      const fields = action === 'NGINX_ROLLBACK' ? {} : await form.validateFields();
      if (action !== 'NGINX_VALIDATE') {
        const confirmed = await modal.confirm({
          title: action === 'NGINX_APPLY' ? '校验并应用 Nginx 配置？' : '恢复上一次 Nginx 配置？',
          content: '仅操作当前安装的受管站点。升级器先校验，再平滑重载；失败时尝试恢复原配置。不重启数据库或业务服务。',
          okText: '确认执行',
          cancelText: '取消',
          okButtonProps: { danger: action === 'NGINX_ROLLBACK' }
        });
        if (!confirmed) return;
      }
      const result = await submitNginxTask(action, { ...fields, expectedRevision: revision.current });
      setTaskId(result.data);
      onSubmitted(action);
      message.success('任务已受理，请等待执行结果');
    } catch (error) {
      if (!(error && typeof error === 'object' && 'errorFields' in error))
        message.error('任务未提交，请检查提示后重试');
    } finally {
      lock.current = false;
      setSubmitting(false);
    }
  };

  useEffect(() => {
    if (
      taskId &&
      lastTask &&
      lastTask.taskId === taskId &&
      lastTask.state === 'SUCCESS' &&
      lastTask.action !== 'NGINX_VALIDATE'
    )
      setDirty(false);
  }, [lastTask, taskId]);

  const refresh = async () => {
    if (lock.current || busy || loading) return;
    lock.current = true;
    try {
      if (
        dirty &&
        !(await modal.confirm({ title: '放弃尚未应用的编辑？', okText: '重新读取', cancelText: '继续编辑' }))
      )
        return;
      setDirty(false);
      await onRefresh();
    } finally {
      lock.current = false;
    }
  };
  const numberRules = (max: number) => [
    { required: true, message: '请输入数值' },
    {
      validator: (_: unknown, value: string) =>
        /^[1-9]\d*$/.test(value || '') && Number(value) <= max
          ? Promise.resolve()
          : Promise.reject(new Error(`请输入 1–${max} 的整数`))
    }
  ];
  const task = lastTask?.action?.startsWith('NGINX_') ? lastTask : undefined;

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      {modalContext}
      <Row justify="space-between" align="middle" gutter={[12, 12]}>
        <Col>
          <Space>
            <CloudServerOutlined />
            <Typography.Title level={4} style={{ margin: 0 }}>
              站点网关
            </Typography.Title>
            <Tag color={available ? 'green' : 'default'}>{available ? '已接入受管配置' : '尚未接入'}</Tag>
            {dirty && <Tag color="orange">有未应用编辑</Tag>}
          </Space>
        </Col>
        <Col>
          <Button icon={<ReloadOutlined />} onClick={refresh} loading={loading} disabled={busy}>
            重新读取
          </Button>
        </Col>
      </Row>
      {!available && (
        <Alert
          type="warning"
          showIcon
          message="需要配套的安装脚本与升级器"
          description="旧部署或第三方 Nginx 不会被自动接管。请先升级部署组件并按部署文档接入受管站点；未接入时这里只展示信息，不执行系统操作。"
        />
      )}
      {!canManage && <Alert type="info" showIcon message="当前账号仅可查看；应用配置需单独授予 Nginx 配置管理权限。" />}
      {stale && (
        <Alert type="warning" showIcon message="服务器配置已更新，请重新读取后再编辑，避免覆盖其他管理员的修改。" />
      )}
      <Row gutter={[20, 20]}>
        <Col xs={24} xl={15}>
          <Card title="连接与代理参数" extra={<Tag>{config?.mode === 'docker' ? 'Docker' : '原生部署'}</Tag>}>
            <Form
              form={form}
              layout="vertical"
              disabled={!available || !canManage || busy || submitting}
              onValuesChange={() => setDirty(true)}
            >
              <Form.Item
                name="backendOrigin"
                label="后端服务地址"
                extra="填写 Nginx 所在环境能够访问的地址，不包含 /aid 等路径。支持分机部署；HTTPS 会验证上游证书。"
                rules={[
                  { required: true, message: '请输入后端服务地址' },
                  {
                    pattern: /^https?:\/\/([a-zA-Z0-9][a-zA-Z0-9.-]*|\[[0-9a-fA-F:]+\])(:\d+)?$/,
                    message: '仅支持 HTTP(S) 主机和可选端口'
                  }
                ]}
              >
                <Input placeholder="https://api.example.com" maxLength={255} autoComplete="off" />
              </Form.Item>
              <Row gutter={16}>
                <Col xs={24} md={8}>
                  <Form.Item name="maxBodyMb" label="上传限制（MB）" rules={numberRules(10240)}>
                    <Input inputMode="numeric" />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item name="readTimeoutSeconds" label="读取超时（秒）" rules={numberRules(3600)}>
                    <Input inputMode="numeric" />
                  </Form.Item>
                </Col>
                <Col xs={24} md={8}>
                  <Form.Item name="connectTimeoutSeconds" label="连接超时（秒）" rules={numberRules(120)}>
                    <Input inputMode="numeric" />
                  </Form.Item>
                </Col>
              </Row>
              <Collapse
                items={[
                  {
                    key: 'advanced',
                    label: '高级指令',
                    children: (
                      <>
                        <Typography.Paragraph type="secondary">
                          仅开放
                          gzip、gzip_min_length、keepalive_timeout、client_body_timeout、send_timeout。每条以分号结束并写在同一行；不允许修改文件路径、执行命令或覆盖系统路由。
                        </Typography.Paragraph>
                        <Form.Item name="extraDirectives">
                          <Input.TextArea
                            rows={3}
                            maxLength={2048}
                            placeholder="gzip on; gzip_min_length 1024; keepalive_timeout 65s;"
                            style={{ fontFamily: 'monospace' }}
                          />
                        </Form.Item>
                      </>
                    )
                  }
                ]}
              />
              <Space wrap style={{ marginTop: 24 }}>
                <Button
                  icon={<CheckCircleOutlined />}
                  disabled={disabled}
                  loading={submitting}
                  onClick={() => submit('NGINX_VALIDATE')}
                >
                  仅校验
                </Button>
                <Button
                  type="primary"
                  icon={<SafetyCertificateOutlined />}
                  disabled={disabled}
                  loading={submitting}
                  onClick={() => submit('NGINX_APPLY')}
                >
                  校验并应用
                </Button>
                <Button icon={<HistoryOutlined />} danger disabled={disabled} onClick={() => submit('NGINX_ROLLBACK')}>
                  恢复上次配置
                </Button>
              </Space>
            </Form>
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card title="自动维护的公开入口">
              <Descriptions column={1} size="small">
                <Descriptions.Item label="爬虫规则">
                  <Typography.Text code copyable>
                    /robots.txt
                  </Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="网站地图">
                  <Typography.Text code copyable>
                    /sitemap.xml
                  </Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="后端来源">与左侧后端服务地址保持一致</Descriptions.Item>
                <Descriptions.Item label="持久化位置">
                  <Typography.Text copyable>
                    {config?.allowedConfigRoot ? `${config.allowedConfigRoot}/nginx-managed` : '未检测到'}
                  </Typography.Text>
                </Descriptions.Item>
              </Descriptions>
              <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
                SEO 内容仍在 SEO 管理中维护。这里只负责访问入口、代理和配置生效；升级保留参数，不覆盖其他站点。
              </Typography.Paragraph>
            </Card>
            {task && (
              <Alert
                showIcon
                type={task.state === 'SUCCESS' ? 'success' : task.state === 'FAILED' ? 'error' : 'info'}
                message={`最近任务 · ${task.state || '等待执行'}`}
                description={task.message || '执行进度统一显示在升级任务区域'}
              />
            )}
          </Space>
        </Col>
      </Row>
      <Collapse
        items={[
          {
            key: 'preview',
            label: '当前已保存的受管配置（只读，可复制）',
            children: (
              <Row gutter={[16, 16]}>
                {(['PUBLIC', 'ADMIN'] as const).map((kind) => (
                  <Col xs={24} xl={12} key={kind}>
                    <Card size="small" title={kind === 'PUBLIC' ? 'Web 入口' : '管理入口'}>
                      <Typography.Paragraph copyable={{ text: values[`NGINX_${kind}_PREVIEW`] || '' }}>
                        <pre style={{ maxHeight: 360, overflow: 'auto', fontSize: 12 }}>
                          {values[`NGINX_${kind}_PREVIEW`] || '暂无受管配置'}
                        </pre>
                      </Typography.Paragraph>
                    </Card>
                  </Col>
                ))}
              </Row>
            )
          }
        ]}
      />
    </Space>
  );
}

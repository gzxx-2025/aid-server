import { CheckCircleFilled, ExportOutlined } from '@ant-design/icons';
import { Button, Modal, Space, Typography, theme } from 'antd';

export default function TokenDanceOldKeyNotice({ open, oldHint, onClose }: {
  open: boolean; oldHint?: string; onClose: () => void;
}) {
  const { token } = theme.useToken();

  return <Modal title={<Space><CheckCircleFilled aria-hidden style={{ color: token.colorSuccess }} />新 Key 已保存</Space>}
    open={open} onCancel={onClose} width={520} centered
    footer={<Button type="primary" onClick={onClose}>我知道了</Button>}>
    <Space direction="vertical" size={20} style={{ width: '100%', paddingTop: 8 }}>
      <Typography.Paragraph style={{ margin: 0 }}>授权已完成，无需手动填写。旧 Key 不会自动删除，可稍后到官网清理。</Typography.Paragraph>
      <div style={{ width: '100%', padding: 16, borderRadius: token.borderRadiusLG, background: token.colorFillAlter,
        border: `1px solid ${token.colorBorderSecondary}` }}>
        <Typography.Text strong>删除旧 Key 前，请确认</Typography.Text>
        {oldHint && <div style={{ marginTop: 8, overflowWrap: 'anywhere' }}><Typography.Text type="secondary">旧 Key：{oldHint}</Typography.Text></div>}
        <ul style={{ margin: '12px 0', paddingLeft: 20, lineHeight: 1.8 }}>
          <li>旧 Key 提交的任务和充值查询已结束。</li>
          <li>没有其他程序继续使用旧 Key。</li>
        </ul>
        <Typography.Text strong>请勿删除刚刚授权的新 Key。</Typography.Text>
      </div>
      <div>
        <Typography.Link href="https://tokendance.space/keys" target="_blank" rel="noopener noreferrer"
          style={{ display: 'inline-flex', alignItems: 'center', gap: 8, minHeight: 32 }}>
          前往 TokenDance 管理 API Key<ExportOutlined aria-hidden />
        </Typography.Link>
        <Typography.Paragraph type="secondary" style={{ margin: '4px 0 0' }}>
          在新窗口打开官网，登录后可查看并删除不再使用的旧 Key。
        </Typography.Paragraph>
      </div>
    </Space>
  </Modal>;
}

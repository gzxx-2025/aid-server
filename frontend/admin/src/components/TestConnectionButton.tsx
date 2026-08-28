import React, { useState } from 'react';
import { Button, message } from 'antd';
import { ExperimentOutlined } from '@ant-design/icons';
import { runConfigTest, type ConfigTestKey, type ConfigTestResult } from '@/api/system/configTest';
import TestResultModal from './TestResultModal';

interface Props {
  /** 测试类型 */
  testKey: ConfigTestKey;
  /** 取当前（未保存）表单值作为 payload */
  getPayload: () => Record<string, any>;
  /** 按钮文案 */
  label?: string;
  /** 预留：是否走 multipart（图片审查另有专用入口，这里仅占位） */
  multipart?: boolean;
  /** 按钮尺寸 */
  size?: 'small' | 'middle' | 'large';
  /** 按钮类型 */
  type?: 'primary' | 'default' | 'dashed' | 'link' | 'text';
  /** 是否幽灵按钮 */
  ghost?: boolean;
  /** 是否危险样式（用于表格 link 按钮场景无需） */
  danger?: boolean;
}

/**
 * 通用「测试连接」按钮：
 * 点击 → loading（期间禁用防重复）→ 调 /system/config/test → 用 TestResultModal 展示结果。
 */
export default function TestConnectionButton({
  testKey,
  getPayload,
  label = '测试连接',
  size = 'small',
  type = 'primary',
  ghost = true,
  danger = false
}: Props) {
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [result, setResult] = useState<ConfigTestResult | null>(null);

  const handleClick = async () => {
    if (loading) return;
    setLoading(true);
    try {
      const payload = getPayload() || {};
      const res = await runConfigTest(testKey, payload);
      setResult(res.data);
      setOpen(true);
    } catch (e: any) {
      // 拦截器已弹错误提示，这里兜底
      message.error(e?.message || '测试请求失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <Button
        size={size}
        type={type}
        ghost={ghost}
        danger={danger}
        icon={<ExperimentOutlined />}
        loading={loading}
        onClick={handleClick}
      >
        {label}
      </Button>
      <TestResultModal open={open} result={result} onClose={() => setOpen(false)} />
    </>
  );
}

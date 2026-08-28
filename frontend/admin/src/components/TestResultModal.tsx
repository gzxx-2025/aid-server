import React from 'react';
import { Collapse, Modal, Tag } from 'antd';
import { CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons';
import type { ConfigTestResult } from '@/api/system/configTest';

interface Props {
  open: boolean;
  /** 测试结果；为 null 时不渲染内容 */
  result: ConfigTestResult | null;
  onClose: () => void;
}

/**
 * 配置连通性测试结果弹窗。
 * 成功/失败图标 + message + 折叠区(details + elapsedMs + provider)。
 * details 为空则不显示折叠区。
 */
export default function TestResultModal({ open, result, onClose }: Props) {
  const success = !!result?.success;
  const hasDetails = !!(result?.details && String(result.details).trim());

  return (
    <Modal
      open={open}
      title="连通性测试结果"
      onCancel={onClose}
      onOk={onClose}
      footer={null}
      destroyOnClose
      width={560}
    >
      {result && (
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0' }}>
            {success ? (
              <CheckCircleFilled style={{ fontSize: 32, color: '#52c41a' }} />
            ) : (
              <CloseCircleFilled style={{ fontSize: 32, color: '#ff4d4f' }} />
            )}
            <div>
              <div style={{ fontSize: 16, fontWeight: 600 }}>
                {success ? '连接成功' : '连接失败'}
              </div>
              <div style={{ color: '#64748b', marginTop: 2 }}>{result.message}</div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', margin: '4px 0 8px' }}>
            {result.provider && (
              <span style={{ color: '#64748b', fontSize: 12 }}>
                命中厂商：<Tag bordered={false}>{result.provider}</Tag>
              </span>
            )}
            <span style={{ color: '#64748b', fontSize: 12 }}>
              耗时：<Tag bordered={false} color="blue">{result.elapsedMs} ms</Tag>
            </span>
          </div>

          {hasDetails && (
            <Collapse
              ghost
              items={[
                {
                  key: 'details',
                  label: <span style={{ fontWeight: 500 }}>详细信息</span>,
                  children: (
                    <pre
                      style={{
                        background: '#0f172a',
                        color: '#94e3b1',
                        padding: 12,
                        borderRadius: 6,
                        fontSize: 12,
                        lineHeight: 1.5,
                        margin: 0,
                        maxHeight: 320,
                        overflow: 'auto',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-all',
                        fontFamily: 'Consolas, Menlo, monospace'
                      }}
                    >
                      {result.details}
                    </pre>
                  )
                }
              ]}
            />
          )}
        </div>
      )}
    </Modal>
  );
}

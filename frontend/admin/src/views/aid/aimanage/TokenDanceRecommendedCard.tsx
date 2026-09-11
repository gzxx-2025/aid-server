import React from 'react';
import { Alert, Badge, Button } from 'antd';
import { ArrowRightOutlined, DatabaseOutlined, SafetyCertificateOutlined, WalletOutlined } from '@ant-design/icons';
import tokenDanceLogo from '@/assets/logo/tokendance.png';
import type { Provider } from './types';

interface Props {
  provider: Provider | null;
  loading: boolean;
  modelCount: number;
  enabledModelCount: number;
  onOpen: (action: 'account' | 'catalog') => void;
  onSelect: () => void;
}

/** 推荐供应商的固定接入入口，不主动查询余额或发起授权。 */
export default function TokenDanceRecommendedCard({ provider, loading, modelCount, enabledModelCount, onOpen, onSelect }: Props) {
  return (
    <section className="tokendance-recommended" aria-labelledby="tokendance-title" aria-busy={loading}>
      <div className="tokendance-recommended__main">
        <div className="tokendance-recommended__identity">
          <img className="tokendance-recommended__mark" src={tokenDanceLogo} alt="TokenDance Logo" width={56} height={56} />
          <div>
            <div className="tokendance-recommended__title">
              <h2 id="tokendance-title">TokenDance</h2>
              <span className="tokendance-recommended__badge">推荐供应商</span>
            </div>
            <p>一个账户，连接多品牌 AI 模型</p>
            <span className="tokendance-recommended__description">文本、图像、视频、音频，在这里统一接入与管理。</span>
          </div>
        </div>
        <div className="tokendance-recommended__overview">
          <div className="tokendance-recommended__status">
            <Badge status={loading ? 'default' : provider?.status === '0' ? 'success' : 'default'} text={loading ? '正在加载供应商' : provider ? (provider.status === '0' ? '供应商已启用' : '供应商已停用') : '等待配置供应商'} />
          </div>
          <div className="tokendance-recommended__stats">
            <div><strong>{loading ? '—' : modelCount}</strong><span>已导入模型</span></div>
            <div><strong>{loading ? '—' : enabledModelCount}</strong><span>已启用模型</span></div>
          </div>
        </div>
      </div>
      <div className="tokendance-recommended__features">
        <div><DatabaseOutlined aria-hidden="true" /><span><strong>模型与成本目录</strong><small>浏览可用模型，核对能力与费用</small></span></div>
        <div><SafetyCertificateOutlined aria-hidden="true" /><span><strong>账户安全授权</strong><small>连接供应商账户，管理授权状态</small></span></div>
        <div><WalletOutlined aria-hidden="true" /><span><strong>余额与充值</strong><small>查看账户余额，按需前往充值</small></span></div>
      </div>
      <div className="tokendance-recommended__footer">
        <div className="tokendance-recommended__actions">
          <Button type="primary" icon={<DatabaseOutlined />} disabled={!provider || loading} onClick={() => onOpen('catalog')}>浏览模型目录 <ArrowRightOutlined /></Button>
          <Button icon={<SafetyCertificateOutlined />} disabled={!provider || loading} onClick={() => onOpen('account')}>授权与账户</Button>
          <span className="tokendance-recommended__hint">授权账户 → 导入模型 → 核对并启用</span>
        </div>
        <Button type="link" disabled={!provider || loading} onClick={onSelect}>管理已导入模型 <ArrowRightOutlined /></Button>
      </div>
      {!loading && !provider && <Alert type="warning" showIcon message="未找到默认供应商配置" description="请确认已执行 060-tokendance-default-provider.sql，并检查该供应商是否被删除。无需手动新增或填写密钥。" />}
    </section>
  );
}

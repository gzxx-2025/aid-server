import React, { useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Descriptions, Divider, Input, InputNumber, Modal, QRCode, Radio,
  Space, Spin, Tabs, Tag, Typography, message
} from 'antd';
import { CopyOutlined, DollarOutlined, KeyOutlined, ReloadOutlined } from '@ant-design/icons';
import type { Provider } from './types';
import { rememberAuthorization, tokenDanceCallbackUrl } from './tokenDanceOAuth';
import TokenDanceOldKeyNotice from './TokenDanceOldKeyNotice';
import {
  completeTokenDanceAuthorization,
  createTokenDancePayment,
  getTokenDanceAuthorization,
  getTokenDanceBalance,
  getTokenDanceCredential,
  getTokenDancePaymentStatus,
  revokeTokenDanceCredential,
  startTokenDanceAuthorization,
  type TokenDanceAuthorizationView,
  type TokenDanceBalanceView,
  type TokenDanceCredentialView,
  type TokenDancePaymentView
} from '@/api/aid/aimanage';

interface Props {
  open: boolean;
  provider: Provider | null;
  onClose: () => void;
}

const AUTH_TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'EXPIRED']);
const PAYMENT_TERMINAL = new Set(['PAID', 'FAILED', 'CREATE_FAILED', 'CLOSED', 'REFUNDED', 'EXPIRED']);

const statusColor = (status?: string) => {
  if (status === 'SUCCEEDED' || status === 'PAID') return 'success';
  if (status === 'FAILED' || status === 'EXPIRED' || status === 'CREATE_FAILED') return 'error';
  if (status === 'EXCHANGING' || status === 'PENDING' || status === 'CREATING' || status === 'CREATE_UNKNOWN') return 'processing';
  return 'default';
};

const formatMoney = (value?: number) => Number(value || 0).toLocaleString('zh-CN', {
  minimumFractionDigits: 2, maximumFractionDigits: 6
});

export default function TokenDanceAccountModal({ open, provider, onClose }: Props) {
  const [tab, setTab] = useState('credential');
  const [credential, setCredential] = useState<TokenDanceCredentialView | null>(null);
  const [credentialLoading, setCredentialLoading] = useState(false);
  const [authMode, setAuthMode] = useState<'CALLBACK' | 'HEADLESS'>('CALLBACK');
  const [authorization, setAuthorization] = useState<TokenDanceAuthorizationView | null>(null);
  const [authorizationCode, setAuthorizationCode] = useState('');
  const [authorizationLoading, setAuthorizationLoading] = useState(false);
  const [showOldKey, setShowOldKey] = useState(false);
  const [replacedHint, setReplacedHint] = useState<string>();
  const [balance, setBalance] = useState<TokenDanceBalanceView | null>(null);
  const [balanceLoading, setBalanceLoading] = useState(false);
  const [amount, setAmount] = useState<number | null>(null);
  const [amountConfirmed, setAmountConfirmed] = useState(false);
  const [payment, setPayment] = useState<TokenDancePaymentView | null>(null);
  const [paymentLoading, setPaymentLoading] = useState(false);
  const authTimerRef = useRef<number | null>(null);
  const paymentTimerRef = useRef<number | null>(null);
  const authPollingRef = useRef<Set<number>>(new Set());
  const paymentPollingRef = useRef<Set<number>>(new Set());
  const credentialRequestsRef = useRef<Map<number, Promise<any>>>(new Map());
  const authorizationRequestsRef = useRef<Set<string>>(new Set());
  const balanceRequestsRef = useRef<Map<number, Promise<any>>>(new Map());
  const paymentCreateRequestsRef = useRef<Set<number>>(new Set());
  const paymentRequestIdRef = useRef('');
  const authFlowRef = useRef(0);
  const paymentFlowRef = useRef(0);
  const scopeRef = useRef(0);
  const providerId = provider?.id;

  const clearTimers = () => {
    if (authTimerRef.current != null) window.clearTimeout(authTimerRef.current);
    if (paymentTimerRef.current != null) window.clearTimeout(paymentTimerRef.current);
    authTimerRef.current = null;
    paymentTimerRef.current = null;
    authPollingRef.current.clear();
    paymentPollingRef.current.clear();
  };

  const loadCredential = async (scope = scopeRef.current) => {
    if (!providerId) return;
    const requestedProviderId = providerId;
    let pending = credentialRequestsRef.current.get(requestedProviderId);
    if (!pending) {
      pending = getTokenDanceCredential(requestedProviderId);
      credentialRequestsRef.current.set(requestedProviderId, pending);
    }
    setCredentialLoading(true);
    try {
      const response: any = await pending;
      if (scope === scopeRef.current) setCredential(response.data || null);
    } catch (error: any) {
      if (scope === scopeRef.current) message.error(error?.message || '凭证状态查询失败');
    } finally {
      if (credentialRequestsRef.current.get(requestedProviderId) === pending) {
        credentialRequestsRef.current.delete(requestedProviderId);
      }
      if (scope === scopeRef.current) setCredentialLoading(false);
    }
  };

  useEffect(() => {
    scopeRef.current += 1;
    const scope = scopeRef.current;
    clearTimers();
    authFlowRef.current += 1;
    paymentFlowRef.current += 1;
    setTab('credential');
    setCredential(null);
    setAuthorization(null);
    setAuthorizationCode('');
    setAuthMode('CALLBACK');
    setShowOldKey(false);
    setReplacedHint(undefined);
    setBalance(null);
    setPayment(null);
    setAmount(null);
    setAmountConfirmed(false);
    paymentRequestIdRef.current = '';
    setCredentialLoading(false);
    setAuthorizationLoading(false);
    setBalanceLoading(false);
    setPaymentLoading(false);
    if (open && providerId) void loadCredential(scope);
    return clearTimers;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, providerId]);

  const pollAuthorization = (view: TokenDanceAuthorizationView, scope = scopeRef.current, flow = authFlowRef.current) => {
    if (flow !== authFlowRef.current) return;
    if (!providerId || AUTH_TERMINAL.has(view.status)) {
      if (view.status === 'SUCCEEDED') {
        setShowOldKey((view.credentialVersion || 0) > 1);
        void loadCredential(scope);
      }
      return;
    }
    authTimerRef.current = window.setTimeout(async () => {
      if (authPollingRef.current.has(flow) || scope !== scopeRef.current || flow !== authFlowRef.current) return;
      authPollingRef.current.add(flow);
      try {
        const response: any = await getTokenDanceAuthorization(providerId, view.authorizationRef);
        if (scope !== scopeRef.current || flow !== authFlowRef.current) return;
        const next = response.data as TokenDanceAuthorizationView;
        setAuthorization(next);
        pollAuthorization(next, scope, flow);
      } catch {
        if (scope === scopeRef.current && flow === authFlowRef.current) pollAuthorization(view, scope, flow);
      } finally {
        authPollingRef.current.delete(flow);
      }
    }, 2000);
  };

  const startAuthorization = async () => {
    if (!providerId) return;
    const requestKey = `start:${providerId}`;
    if (authorizationRequestsRef.current.has(requestKey)) return;
    authorizationRequestsRef.current.add(requestKey);
    if (authTimerRef.current != null) window.clearTimeout(authTimerRef.current);
    const flow = ++authFlowRef.current;
    const scope = scopeRef.current;
    setAuthorizationLoading(true);
    setReplacedHint(credential?.credentialHint);
    try {
      const response: any = await startTokenDanceAuthorization(providerId, {
        mode: authMode, keyName: '视觉AID',
        callbackUrl: authMode === 'CALLBACK' ? tokenDanceCallbackUrl() : undefined
      });
      const view = response.data as TokenDanceAuthorizationView;
      if (scope !== scopeRef.current || flow !== authFlowRef.current) return;
      if (!view.authorizationUrl) throw new Error('授权地址缺失，请重新发起');
      setAuthorization(view);
      if (authMode === 'CALLBACK' && view.authorizationUrl) {
        rememberAuthorization({
          providerId, authorizationRef: view.authorizationRef, returnPath: window.location.pathname,
          replacing: !!credential?.bound, oldHint: credential?.credentialHint, createdAt: Date.now()
        });
        window.location.assign(view.authorizationUrl);
        return;
      }
      if (view.authorizationUrl) window.open(view.authorizationUrl, '_blank', 'noopener,noreferrer');
      message.success('请在授权后粘贴授权码');
    } catch (error: any) {
      if (scope === scopeRef.current && flow === authFlowRef.current) message.error(error?.message || '授权启动失败');
    } finally {
      authorizationRequestsRef.current.delete(requestKey);
      if (scope === scopeRef.current && flow === authFlowRef.current) setAuthorizationLoading(false);
    }
  };

  const completeHeadless = async () => {
    if (!providerId || !authorization?.authorizationRef || !authorizationCode.trim()) return;
    const requestKey = `complete:${providerId}:${authorization.authorizationRef}`;
    if (authorizationRequestsRef.current.has(requestKey)) return;
    authorizationRequestsRef.current.add(requestKey);
    const flow = authFlowRef.current;
    const scope = scopeRef.current;
    setAuthorizationLoading(true);
    try {
      const response: any = await completeTokenDanceAuthorization(providerId, {
        authorizationRef: authorization.authorizationRef, code: authorizationCode.trim()
      });
      const view = response.data as TokenDanceAuthorizationView;
      if (scope !== scopeRef.current || flow !== authFlowRef.current) return;
      setAuthorization(view);
      setAuthorizationCode('');
      if (view.status === 'SUCCEEDED') {
        message.success('授权成功');
        setShowOldKey(!!credential?.bound || (view.credentialVersion || 0) > 1);
        await loadCredential();
      } else if (!AUTH_TERMINAL.has(view.status)) {
        pollAuthorization(view, scope, flow);
      }
    } catch (error: any) {
      if (scope === scopeRef.current && flow === authFlowRef.current) message.error(error?.message || '授权交换失败');
    } finally {
      authorizationRequestsRef.current.delete(requestKey);
      if (scope === scopeRef.current && flow === authFlowRef.current) setAuthorizationLoading(false);
    }
  };

  const revokeCredential = () => {
    if (!providerId) return;
    Modal.confirm({
      title: '撤销本地凭证？',
      content: '撤销后，新任务和依赖该凭证版本的历史任务将无法继续访问 TokenDance。',
      okText: '确认撤销', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        await revokeTokenDanceCredential(providerId);
        message.success('凭证已撤销');
        await loadCredential();
        setBalance(null);
        setPayment(null);
      }
    });
  };

  const changeAuthorizationMode = (next: 'CALLBACK' | 'HEADLESS') => {
    if (authTimerRef.current != null) window.clearTimeout(authTimerRef.current);
    authTimerRef.current = null;
    authFlowRef.current += 1;
    setAuthorization(null);
    setAuthorizationCode('');
    setAuthMode(next);
  };

  const loadBalance = async (force = false) => {
    if (!providerId) return;
    const requestedProviderId = providerId;
    let pending = balanceRequestsRef.current.get(requestedProviderId);
    if (!pending) {
      pending = getTokenDanceBalance(requestedProviderId, force);
      balanceRequestsRef.current.set(requestedProviderId, pending);
    }
    const scope = scopeRef.current;
    setBalanceLoading(true);
    try {
      const response: any = await pending;
      if (scope === scopeRef.current) setBalance(response.data || null);
    } catch (error: any) {
      if (scope === scopeRef.current) message.error(error?.message || '余额查询失败');
    } finally {
      if (balanceRequestsRef.current.get(requestedProviderId) === pending) {
        balanceRequestsRef.current.delete(requestedProviderId);
      }
      if (scope === scopeRef.current) setBalanceLoading(false);
    }
  };

  const pollPayment = (view: TokenDancePaymentView, scope = scopeRef.current, flow = paymentFlowRef.current) => {
    if (flow !== paymentFlowRef.current) return;
    if (!providerId || PAYMENT_TERMINAL.has(view.status)) {
      if (view.status === 'PAID') {
        message.success('官方充值已到账，正在刷新余额');
        void loadBalance(true);
      }
      return;
    }
    paymentTimerRef.current = window.setTimeout(async () => {
      if (paymentPollingRef.current.has(flow) || scope !== scopeRef.current || flow !== paymentFlowRef.current) return;
      paymentPollingRef.current.add(flow);
      try {
        const response: any = await getTokenDancePaymentStatus(providerId, view.id);
        if (scope !== scopeRef.current || flow !== paymentFlowRef.current) return;
        const next = response.data as TokenDancePaymentView;
        setPayment(next);
        pollPayment(next, scope, flow);
      } catch {
        if (scope === scopeRef.current && flow === paymentFlowRef.current) pollPayment(view, scope, flow);
      } finally {
        paymentPollingRef.current.delete(flow);
      }
    }, 3000);
  };

  const createPayment = async () => {
    if (!providerId || !amount || amount < 1 || amount > 100000 || !amountConfirmed) return;
    const requestedProviderId = providerId;
    if (paymentCreateRequestsRef.current.has(requestedProviderId)) return;
    paymentCreateRequestsRef.current.add(requestedProviderId);
    const flow = ++paymentFlowRef.current;
    const scope = scopeRef.current;
    setPaymentLoading(true);
    try {
      if (!paymentRequestIdRef.current) {
        paymentRequestIdRef.current = typeof crypto.randomUUID === 'function'
          ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      }
      const response: any = await createTokenDancePayment(providerId, {
        amount: Math.trunc(amount), userConfirmed: true, requestId: paymentRequestIdRef.current
      });
      const view = response.data as TokenDancePaymentView;
      if (scope !== scopeRef.current || flow !== paymentFlowRef.current) return;
      setPayment(view);
      pollPayment(view, scope, flow);
    } catch (error: any) {
      if (scope === scopeRef.current && flow === paymentFlowRef.current) message.error(error?.message || '充值会话创建失败');
    } finally {
      paymentCreateRequestsRef.current.delete(requestedProviderId);
      if (scope === scopeRef.current && flow === paymentFlowRef.current) setPaymentLoading(false);
    }
  };

  const credentialTab = (
    <Spin spinning={credentialLoading}>
      <Descriptions bordered size="small" column={2} items={[
        { key: 'bound', label: '绑定状态', children: credential?.bound ? <Tag color="success">已授权</Tag> : <Tag>未授权</Tag> },
        { key: 'version', label: '凭证版本', children: credential?.credentialVersion ?? '-' },
        { key: 'hint', label: '密钥尾号', children: credential?.credentialHint || '-' },
        { key: 'time', label: '授权时间', children: credential?.authorizedAt || '-' },
        { key: 'by', label: '授权管理员', children: credential?.authorizedBy || '-', span: 2 }
      ]} />
      <Divider orientation="left">创建或更换 API Key</Divider>
      <Space direction="vertical" style={{ width: '100%' }} size={10}>
        <Typography.Text type="secondary">点击后前往 TokenDance 登录确认，返回时自动保存 Key。应用：视觉AID。</Typography.Text>
        <details>
          <summary style={{ cursor: 'pointer' }}>无法自动返回？使用备用授权方式</summary>
          <Radio.Group value={authMode} onChange={(event) => changeAuthorizationMode(event.target.value)} style={{ marginTop: 8 }}>
            <Radio.Button value="CALLBACK">自动返回</Radio.Button>
            <Radio.Button value="HEADLESS">粘贴一次性授权码</Radio.Button>
          </Radio.Group>
        </details>
        <Space wrap>
          <Button type="primary" icon={<KeyOutlined />} loading={authorizationLoading}
            disabled={!providerId} onClick={startAuthorization}>{credential?.bound ? '重新生成 Key' : '授权连接 TokenDance'}</Button>
          {authorization?.authorizationUrl && (
            <Typography.Link href={authorization.authorizationUrl} target="_blank" rel="noreferrer">重新打开授权页</Typography.Link>
          )}
          {credential?.bound && <Button danger onClick={revokeCredential}>撤销本地凭证</Button>}
        </Space>
        {authorization && (
          <Alert type={AUTH_TERMINAL.has(authorization.status) && authorization.status !== 'SUCCEEDED' ? 'error' : 'info'} showIcon
            message={<Space>授权状态<Tag color={statusColor(authorization.status)}>{authorization.status}</Tag></Space>}
            description={authorization.failureReason || `授权流程有效期至 ${authorization.expiresAt || '-'}`} />
        )}
        {authMode === 'HEADLESS' && authorization && !AUTH_TERMINAL.has(authorization.status) && (
          <Space.Compact style={{ maxWidth: 620, width: '100%' }}>
            <Input.Password value={authorizationCode} onChange={(event) => setAuthorizationCode(event.target.value)}
              placeholder="粘贴 TokenDance 页面显示的一次性授权码" />
            <Button type="primary" loading={authorizationLoading} disabled={!authorizationCode.trim()} onClick={completeHeadless}>完成交换</Button>
          </Space.Compact>
        )}
      </Space>
    </Spin>
  );

  const balanceTab = (
    <div>
      {!credential?.bound && <Alert type="warning" showIcon message="请先完成 TokenDance 授权" style={{ marginBottom: 12 }} />}
      <Button type="primary" icon={<ReloadOutlined />} loading={balanceLoading} disabled={!credential?.bound}
        onClick={() => loadBalance(true)} style={{ marginBottom: 12 }}>查询官方余额</Button>
      {balance && <Descriptions bordered size="small" column={2} items={[
        { key: 'balance', label: '可用余额', children: `${formatMoney(balance.balance)} ${balance.unit || 'CNY'}` },
        { key: 'total', label: '累计额度', children: `${formatMoney(balance.totalCredits)} ${balance.unit || 'CNY'}` },
        { key: 'used', label: '累计消耗', children: `${formatMoney(balance.creditsUsed)} ${balance.unit || 'CNY'}` },
        { key: 'version', label: '凭证版本', children: balance.credentialVersion },
        { key: 'source', label: '官方原始单位', children: balance.sourceUnit || '-' },
        { key: 'time', label: '查询时间', children: balance.queriedAt || '-' }
      ]} />}
    </div>
  );

  const paymentTab = (
    <div>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="充值进入站长的 TokenDance 账户，不会增加本站用户积分，也不会形成本站充值账单。" />
      {!credential?.bound && <Alert type="warning" showIcon message="请先完成 TokenDance 授权" style={{ marginBottom: 12 }} />}
      <Space wrap align="center">
        <InputNumber min={1} max={100000} precision={0} addonBefore="金额" addonAfter="元"
          value={amount} onChange={(value) => {
            setAmount(value);
            setAmountConfirmed(false);
            paymentRequestIdRef.current = '';
          }} disabled={!credential?.bound || !!payment} />
        <Button onClick={() => setAmountConfirmed((value) => !value)}
          type={amountConfirmed ? 'primary' : 'default'} disabled={!credential?.bound || !!payment}>
          {amountConfirmed ? '已确认金额' : '确认付款金额'}
        </Button>
        <Button type="primary" icon={<DollarOutlined />} loading={paymentLoading}
          disabled={!credential?.bound || !amountConfirmed || !amount || !!payment} onClick={createPayment}>生成付款码</Button>
        {payment && <Button disabled={!PAYMENT_TERMINAL.has(payment.status)} onClick={() => {
          if (paymentTimerRef.current != null) window.clearTimeout(paymentTimerRef.current);
          paymentTimerRef.current = null;
          paymentFlowRef.current += 1;
          setPayment(null);
          setAmountConfirmed(false);
          paymentRequestIdRef.current = '';
        }}>新建充值</Button>}
      </Space>
      {payment && (
        <div style={{ marginTop: 18 }}>
          <Space align="start" size={20} wrap>
            {(payment.paymentUrl || payment.alipayUrl) && <QRCode value={payment.paymentUrl || payment.alipayUrl || ''} size={180}
              status={payment.status === 'PAID' ? 'scanned' : PAYMENT_TERMINAL.has(payment.status) ? 'expired' : 'active'} />}
            <Descriptions bordered size="small" column={1} style={{ minWidth: 420 }} items={[
              { key: 'id', label: '本地充值会话号', children: payment.id },
              { key: 'status', label: '支付状态', children: <Tag color={statusColor(payment.status)}>{payment.status}</Tag> },
              { key: 'amount', label: '充值金额', children: `${payment.amount} 元` },
              { key: 'expire', label: '过期时间', children: payment.expiredAt || '-' },
              { key: 'paid', label: '到账时间', children: payment.paidAt || '-' },
              { key: 'query', label: '最后查询时间', children: payment.lastQueryTime || '-' },
              { key: 'refresh', label: '余额刷新', children: payment.balanceRefreshStatus || '-' },
              { key: 'reason', label: '状态说明', children: payment.failureReason || '-' }
            ]} />
          </Space>
          <Space style={{ marginTop: 12 }} wrap>
            {payment.paymentUrl && <Button icon={<CopyOutlined />} onClick={async () => {
              try {
                await navigator.clipboard.writeText(payment.paymentUrl || '');
                message.success('付款链接已复制');
              } catch {
                message.error('复制失败，请手动打开付款链接');
              }
            }}>复制付款链接</Button>}
            {payment.alipayUrl && <Typography.Link href={payment.alipayUrl} target="_blank" rel="noreferrer">在支付宝中打开</Typography.Link>}
          </Space>
        </div>
      )}
    </div>
  );

  return (
    <Modal open={open} title={`${provider?.providerName || 'TokenDance'} · 账户管理`} footer={null}
      onCancel={onClose} width={900} destroyOnClose maskClosable={false}>
      <Tabs activeKey={tab} onChange={setTab} items={[
        { key: 'credential', label: 'API Key 授权', children: credentialTab },
        { key: 'balance', label: '官方余额', children: balanceTab },
        { key: 'payment', label: '站长充值', children: paymentTab }
      ]} />
      <TokenDanceOldKeyNotice open={showOldKey} oldHint={replacedHint} onClose={() => setShowOldKey(false)} />
    </Modal>
  );
}

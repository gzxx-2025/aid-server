import { useEffect, useState } from 'react';
import { Button, Result, Spin } from 'antd';
import { exchangeAuthorization, forgetAuthorization, readAuthorization, type AuthorizationContext } from './tokenDanceOAuth';
import TokenDanceOldKeyNotice from './TokenDanceOldKeyNotice';

let callbackCode: string | null | undefined;
function readCallback() {
  if (callbackCode === undefined) {
    const parameters = new URLSearchParams(window.location.search);
    callbackCode = parameters.get('code');
    // 仅进入本回调页才清理参数；共享 chunk 提前加载不得影响其他页面的 code。
    if (callbackCode) {
      window.history.replaceState(window.history.state, '', window.location.pathname);
    }
  }
  return callbackCode;
}

export default function TokenDanceOAuthCallback() {
  const [code] = useState(readCallback);
  const [context, setContext] = useState<AuthorizationContext>();
  const [status, setStatus] = useState<'loading' | 'success' | 'error'>('loading');
  const [detail, setDetail] = useState('正在完成授权，请不要关闭页面');
  const [showOldKey, setShowOldKey] = useState(false);
  useEffect(() => {
    let active = true;
    const run = async () => {
      try {
        if (!code) throw new Error('没有待处理的授权码，请返回供应商配置');
        const current = readAuthorization();
        if (active) setContext(current);
        const result = await exchangeAuthorization(code, current);
        if (result.status !== 'SUCCEEDED') throw new Error(result.failureReason || '授权尚未完成，请返回配置查看状态');
        forgetAuthorization();
        if (active) {
          setStatus('success');
          setDetail('API Key 已自动保存到当前供应商，无需复制。');
          setShowOldKey(current.replacing || (result.credentialVersion || 0) > 1);
        }
      } catch (error) {
        if (active) { setStatus('error'); setDetail(error instanceof Error ? error.message : '授权失败，请重新发起'); }
      }
    };
    void run();
    return () => { active = false; };
  }, []);
  const returnPath = context?.returnPath?.startsWith('/') && !context.returnPath.startsWith('//')
    ? context.returnPath : import.meta.env.BASE_URL || '/';
  return <div style={{ maxWidth: 900, margin: '64px auto', padding: 24 }}>
    {status === 'loading' ? <Spin tip={detail}><div style={{ height: 160 }} /></Spin>
      : <Result status={status} title={status === 'success' ? 'TokenDance 已连接' : '授权未完成'} subTitle={detail}
        extra={<Button type="primary" href={returnPath}>返回供应商配置</Button>} />}
    <TokenDanceOldKeyNotice open={showOldKey} oldHint={context?.oldHint} onClose={() => setShowOldKey(false)} />
  </div>;
}

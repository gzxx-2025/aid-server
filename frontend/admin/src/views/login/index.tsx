import React, { useEffect, useState } from 'react';
import { Form, Input, Button, Checkbox, message } from 'antd';
import {
  UserOutlined,
  LockOutlined,
  SafetyOutlined,
  ThunderboltOutlined,
  CloudServerOutlined,
  SafetyCertificateOutlined,
  RocketOutlined
} from '@ant-design/icons';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import Cookies from 'js-cookie';

import { useUserStore } from '@/store/useUserStore';
import { usePermissionStore } from '@/store/usePermissionStore';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import { getCodeImg } from '@/api/login';
import './index.less';

interface LoginForm {
  username: string;
  password: string;
  code?: string;
  rememberMe?: boolean;
}

const FEATURES = [
  {
    icon: <RocketOutlined />,
    title: '高效协作',
    desc: '一站式管理 AI 服务商、模型、素材与任务'
  },
  {
    icon: <ThunderboltOutlined />,
    title: '极速体验',
    desc: '流畅动效与响应式布局，提升工作效率'
  },
  {
    icon: <SafetyCertificateOutlined />,
    title: '安全可信',
    desc: '权限隔离与密钥加密，守护数据安全'
  },
  {
    icon: <CloudServerOutlined />,
    title: '稳定可靠',
    desc: '多环境部署，监控告警与日志审计齐备'
  }
];

export default function LoginPage() {
  const [form] = Form.useForm<LoginForm>();
  const [loading, setLoading] = useState(false);
  const [captchaEnabled, setCaptchaEnabled] = useState(true);
  const [codeUrl, setCodeUrl] = useState('');
  const [uuid, setUuid] = useState('');
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { login } = useUserStore();
  const generateRoutes = usePermissionStore((s) => s.generateRoutes);
  const fetchInfo = useUserStore((s) => s.fetchInfo);
  const loginLogo = useAdminBrandStore((s) => s.resolvedLoginLogo);
  const siteName = useAdminBrandStore((s) => s.resolvedSiteName);

  const fetchCaptcha = async () => {
    try {
      const res: any = await getCodeImg();
      const enabled = res.captchaEnabled === undefined ? true : res.captchaEnabled;
      setCaptchaEnabled(enabled);
      if (enabled) {
        setCodeUrl('data:image/gif;base64,' + res.img);
        setUuid(res.uuid);
      }
    } catch {
      setCaptchaEnabled(false);
    }
  };

  useEffect(() => {
    fetchCaptcha();
    const username = Cookies.get('username');
    const rememberMe = Cookies.get('rememberMe');
    form.setFieldsValue({
      username: username || '',
      password: '',
      rememberMe: rememberMe === 'true'
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSubmit = async (values: LoginForm) => {
    setLoading(true);
    try {
      if (values.rememberMe) {
        Cookies.set('username', values.username, { expires: 30, secure: true, sameSite: 'lax' });
        Cookies.set('rememberMe', 'true', { expires: 30, secure: true, sameSite: 'lax' });
      } else {
        Cookies.remove('username');
        Cookies.remove('rememberMe');
      }
      await login({
        username: values.username,
        password: values.password,
        code: values.code,
        uuid,
        // 后台随机登录入口：登录页渲染在 /<访问码> 地址，取首段作为访问码随登录请求发送
        entryCode: (() => {
          const seg = location.pathname.replace(/^\/+/, '').split('/')[0];
          return seg && seg !== 'login' ? seg : undefined;
        })()
      });
      await fetchInfo();
      await generateRoutes();
      message.success('登录成功');
      const redirect = params.get('redirect') || '/';
      navigate(redirect, { replace: true });
    } catch {
      // 失败提示已由 axios 响应拦截器统一弹出，这里不再重复 message.error，
      // 否则同一个错误（如「登录入口校验失败」「密码错误」）会报错两次。
      if (captchaEnabled) fetchCaptcha();
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      {/* 左侧展示区 */}
      <motion.div
        className="login-page__showcase"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.6 }}
      >
        <div className="login-page__showcase-bg" aria-hidden>
          <span className="login-page__orb login-page__orb--1" />
          <span className="login-page__orb login-page__orb--2" />
          <span className="login-page__orb login-page__orb--3" />
          <div className="login-page__grid" />
        </div>

        <div className="login-page__brand">
          <img src={loginLogo} alt="logo" />
          <div className="login-page__brand-text">
            <span className="login-page__brand-title">{siteName}</span>
            <span className="login-page__brand-sub">AI 内容创作中台</span>
          </div>
        </div>

        <motion.div
          className="login-page__hero"
          initial={{ opacity: 0, y: 18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5, delay: 0.1 }}
        >
          <h1>
            让 AI 创作<br />
            <span>更高效、更可控</span>
          </h1>
          <p>集成服务商、模型、素材、任务与用户的一体化管理平台</p>
        </motion.div>

        <div className="login-page__features">
          {FEATURES.map((f, i) => (
            <motion.div
              key={f.title}
              className="login-page__feature"
              initial={{ opacity: 0, y: 18 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.4, delay: 0.2 + i * 0.08 }}
            >
              <span className="login-page__feature-icon">{f.icon}</span>
              <div>
                <div className="login-page__feature-title">{f.title}</div>
                <div className="login-page__feature-desc">{f.desc}</div>
              </div>
            </motion.div>
          ))}
        </div>

        <div className="login-page__showcase-footer">
          Copyright © 2018 - 2026 {siteName}. All Rights Reserved.
        </div>
      </motion.div>

      {/* 右侧登录区 */}
      <div className="login-page__form-wrap">
        <motion.div
          className="login-page__panel"
          initial={{ opacity: 0, y: 24, scale: 0.97 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 0.45, ease: [0.22, 0.61, 0.36, 1] }}
        >
          <div className="login-page__panel-header">
            <h2>欢迎回来</h2>
            <p>登录 {siteName} 后台管理系统</p>
          </div>

          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            size="large"
            requiredMark={false}
            className="login-page__form"
          >
            <Form.Item
              name="username"
              label="账号"
              rules={[{ required: true, message: '请输入您的账号' }]}
            >
              <Input
                prefix={<UserOutlined />}
                placeholder="请输入账号"
                autoComplete="username"
              />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入您的密码' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>
            {captchaEnabled && (
              <Form.Item
                name="code"
                label="验证码"
                rules={[{ required: true, message: '请输入验证码' }]}
              >
                <div className="login-page__captcha">
                  <Input
                    prefix={<SafetyOutlined />}
                    placeholder="请输入验证码"
                    maxLength={5}
                  />
                  <img
                    src={codeUrl}
                    alt="验证码"
                    className="login-page__captcha-img"
                    onClick={fetchCaptcha}
                  />
                </div>
              </Form.Item>
            )}

            <div className="login-page__row">
              <Form.Item name="rememberMe" valuePropName="checked" noStyle>
                <Checkbox>记住账号</Checkbox>
              </Form.Item>
              <a className="login-page__forgot" onClick={(e) => e.preventDefault()}>
                忘记密码？
              </a>
            </div>

            <Form.Item style={{ marginBottom: 0, marginTop: 18 }}>
              <Button
                type="primary"
                htmlType="submit"
                block
                loading={loading}
                className="login-page__submit"
              >
                {loading ? '登 录 中 ...' : '登 录'}
              </Button>
            </Form.Item>
          </Form>

          <div className="login-page__tip">
            登录即表示您同意我们的 <a onClick={(e) => e.preventDefault()}>服务条款</a> 与{' '}
            <a onClick={(e) => e.preventDefault()}>隐私政策</a>
          </div>
        </motion.div>
      </div>
    </div>
  );
}

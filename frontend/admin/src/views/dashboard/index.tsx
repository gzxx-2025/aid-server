import React, { useEffect, useMemo, useState } from 'react';
import { Card, Col, Row, Space, Spin, Tooltip } from 'antd';
import {
  UserOutlined,
  TeamOutlined,
  ProjectOutlined,
  PlaySquareOutlined,
  PictureOutlined,
  DollarCircleOutlined,
  CalendarOutlined,
  FundOutlined,
  ReloadOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { motion } from 'framer-motion';

import { useUserStore } from '@/store/useUserStore';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import { getDashboardOverview } from '@/api/aid/dashboard';
import ModelMonitor from '@/views/aid/modelmonitor';
import './index.less';

/** allSettled 取值，失败回退默认值 */
function settled<T>(r: PromiseSettledResult<T>, fallback: T): T {
  return r.status === 'fulfilled' ? r.value : fallback;
}

/** 数字千分位 */
function fmt(n: number): string {
  return (n ?? 0).toLocaleString('zh-CN');
}

interface Counts {
  userTotal: number;
  userEnabled: number;
  online: number;
  projectTotal: number;
  projectMaking: number;
  episodeTotal: number;
  storyboardTotal: number;
  genTotal: number;
  genSuccess: number;
  genProcessing: number;
  genFailed: number;
  orderPaid: number;
  orderPending: number;
}

const EMPTY_COUNTS: Counts = {
  userTotal: 0, userEnabled: 0, online: 0,
  projectTotal: 0, projectMaking: 0, episodeTotal: 0, storyboardTotal: 0,
  genTotal: 0, genSuccess: 0, genProcessing: 0, genFailed: 0,
  orderPaid: 0, orderPending: 0
};

export default function Dashboard() {
  const { nickName } = useUserStore();
  const siteName = useAdminBrandStore((s) => s.resolvedSiteName);
  const [loading, setLoading] = useState(true);
  const [counts, setCounts] = useState<Counts>(EMPTY_COUNTS);

  const greet = useMemo(() => {
    const h = dayjs().hour();
    if (h < 6) return '凌晨好';
    if (h < 12) return '早上好';
    if (h < 14) return '中午好';
    if (h < 18) return '下午好';
    return '晚上好';
  }, []);

  const loadAll = async () => {
    setLoading(true);
    // 业务概览聚合（系统资源/操作日志已从首页移除，不再请求）
    const tasks = await Promise.allSettled([getDashboardOverview()]);
    const ovRes: any = settled(tasks[0] as any, null);
    const ov = (ovRes && (ovRes.data ?? ovRes)) || {};
    setCounts({
      userTotal: ov.userTotal ?? 0,
      userEnabled: ov.userEnabled ?? 0,
      online: ov.online ?? 0,
      projectTotal: ov.projectTotal ?? 0,
      projectMaking: ov.projectMaking ?? 0,
      episodeTotal: ov.episodeTotal ?? 0,
      storyboardTotal: ov.storyboardTotal ?? 0,
      genTotal: ov.genTotal ?? 0,
      genSuccess: ov.genSuccess ?? 0,
      genProcessing: ov.genProcessing ?? 0,
      genFailed: ov.genFailed ?? 0,
      orderPaid: ov.orderPaid ?? 0,
      orderPending: ov.orderPending ?? 0
    });
    setLoading(false);
  };

  useEffect(() => {
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stats = [
    {
      title: '平台用户', value: counts.userTotal,
      sub: `启用 ${fmt(counts.userEnabled)}`,
      icon: <UserOutlined />, color: 'linear-gradient(135deg, #2563eb, #6366f1)', accent: '#2563eb'
    },
    {
      title: '在线用户', value: counts.online,
      sub: '实时会话',
      icon: <TeamOutlined />, color: 'linear-gradient(135deg, #06b6d4, #3b82f6)', accent: '#06b6d4'
    },
    {
      title: '漫剧项目', value: counts.projectTotal,
      sub: `制作中 ${fmt(counts.projectMaking)}`,
      icon: <ProjectOutlined />, color: 'linear-gradient(135deg, #10b981, #14b8a6)', accent: '#10b981'
    },
    {
      title: '剧集总数', value: counts.episodeTotal,
      sub: `分镜 ${fmt(counts.storyboardTotal)}`,
      icon: <PlaySquareOutlined />, color: 'linear-gradient(135deg, #f59e0b, #f97316)', accent: '#f59e0b'
    },
    {
      title: '生成记录', value: counts.genTotal,
      sub: `成功 ${fmt(counts.genSuccess)}`,
      icon: <PictureOutlined />, color: 'linear-gradient(135deg, #8b5cf6, #6366f1)', accent: '#8b5cf6'
    },
    {
      title: '已支付订单', value: counts.orderPaid,
      sub: `待支付 ${fmt(counts.orderPending)}`,
      icon: <DollarCircleOutlined />, color: 'linear-gradient(135deg, #ec4899, #f43f5e)', accent: '#ec4899'
    }
  ];

  const genDone = counts.genSuccess + counts.genFailed;
  const successRate = genDone > 0 ? Math.round((counts.genSuccess / genDone) * 100) : 0;

  // 内容创作漏斗
  const funnel = [
    { label: '漫剧项目', value: counts.projectTotal, color: '#2563eb' },
    { label: '剧集', value: counts.episodeTotal, color: '#7c3aed' },
    { label: '分镜', value: counts.storyboardTotal, color: '#db2777' },
    { label: '生成产物', value: counts.genTotal, color: '#f59e0b' }
  ];
  const funnelMax = Math.max(...funnel.map((f) => f.value), 1);

  return (
    <div className="dashboard">
      <motion.div
        className="dashboard__hero"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
      >
        <div className="dashboard__hero-main">
          <h2>{greet}，{nickName || '管理员'} 👋</h2>
          <p>欢迎使用 {siteName} 漫剧创作管理平台，以下是平台实时业务概览。</p>
          <div className="dashboard__hero-time">
            <CalendarOutlined />
            {dayjs().format('YYYY 年 MM 月 DD 日 dddd')}
          </div>
        </div>
        <div className="dashboard__hero-kpis">
          <div className="dashboard__hero-kpi">
            <span className="dashboard__hero-kpi-num">{fmt(counts.projectTotal)}</span>
            <span className="dashboard__hero-kpi-label">漫剧项目</span>
          </div>
          <i className="dashboard__hero-divider" />
          <div className="dashboard__hero-kpi">
            <span className="dashboard__hero-kpi-num">{fmt(counts.genTotal)}</span>
            <span className="dashboard__hero-kpi-label">生成产物</span>
          </div>
          <i className="dashboard__hero-divider" />
          <div className="dashboard__hero-kpi">
            <span className="dashboard__hero-kpi-num">{successRate}%</span>
            <span className="dashboard__hero-kpi-label">生成成功率</span>
          </div>
          <Tooltip title="刷新数据">
            <button className="dashboard__hero-refresh" onClick={loadAll} disabled={loading}>
              <ReloadOutlined spin={loading} />
            </button>
          </Tooltip>
        </div>
      </motion.div>

      <Spin spinning={loading}>
        <Row gutter={[18, 18]}>
          {stats.map((s, i) => (
            <Col xs={24} sm={12} lg={8} xxl={4} key={s.title}>
              <motion.div
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.3, delay: 0.04 * i }}
              >
                <Card className="dashboard__stat" bordered={false} style={{ ['--stat-accent' as any]: s.accent }}>
                  <div className="dashboard__stat-row">
                    <div className="dashboard__stat-info">
                      <div className="dashboard__stat-title">{s.title}</div>
                      <div className="dashboard__stat-value">{fmt(s.value)}</div>
                      <div className="dashboard__stat-sub">{s.sub}</div>
                    </div>
                    <div className="dashboard__stat-icon" style={{ background: s.color }}>
                      {s.icon}
                    </div>
                  </div>
                </Card>
              </motion.div>
            </Col>
          ))}
        </Row>

        {/* 内容创作漏斗：紧跟 KPI 的业务转化总览 */}
        <Row gutter={[18, 18]}>
          <Col span={24}>
            <Card
              className="dashboard__card"
              bordered={false}
              title={<Space size={8}><FundOutlined style={{ color: '#2563eb' }} /><span>内容创作漏斗</span></Space>}
            >
              <Row gutter={[24, 18]}>
                {funnel.map((f, idx) => {
                  const prev = idx > 0 ? funnel[idx - 1].value : 0;
                  const rate = idx > 0 && prev > 0 ? Math.round((f.value / prev) * 100) : null;
                  return (
                    <Col xs={24} md={12} xl={6} key={f.label}>
                      <div className="dashboard__funnel-item">
                        <div className="dashboard__funnel-head">
                          <span className="dashboard__funnel-label">
                            <i style={{ background: f.color }} />
                            {f.label}
                          </span>
                          <span className="dashboard__funnel-val">
                            {fmt(f.value)}
                            {rate !== null && (
                              <em className="dashboard__funnel-rate">转化 {rate}%</em>
                            )}
                          </span>
                        </div>
                        <div className="dashboard__funnel-bar">
                          <motion.div
                            className="dashboard__funnel-bar-fill"
                            style={{ background: f.color }}
                            initial={{ width: 0 }}
                            animate={{ width: `${Math.max((f.value / funnelMax) * 100, 3)}%` }}
                            transition={{ duration: 0.6, delay: 0.05 * idx }}
                          />
                        </div>
                      </div>
                    </Col>
                  );
                })}
              </Row>
            </Card>
          </Col>
        </Row>
      </Spin>

      {/* 算力调度看板：实时（自带轮询/刷新），放在首页底部作为重点观测区 */}
      <div className="dashboard__board" style={{ marginTop: 6 }}>
        <ModelMonitor />
      </div>
    </div>
  );
}

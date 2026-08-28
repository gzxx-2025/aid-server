import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Empty,
  Input,
  Popover,
  Progress,
  Radio,
  Select,
  Space,
  Spin,
  Switch,
  Tabs,
  Tag,
  Tooltip
} from 'antd';
import { ReloadOutlined, ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';

import PageCard from '@/components/PageCard';
import {
  getModelQueueSnapshot,
  ModelHealthTimeline,
  ModelQueueSnapshot,
  ModelQueueStat
} from '@/api/aid/modelmonitor';

/** 自动刷新间隔候选（秒） */
const REFRESH_OPTIONS = [3, 5, 10, 30];

/** 健康状态 → 颜色 */
const HEALTH_COLORS: Record<string, string> = {
  operational: '#22c55e',
  degraded: '#f59e0b',
  error: '#ef4444',
  none: '#e2e8f0'
};

/** 健康状态 → 文案 */
const HEALTH_LABELS: Record<string, string> = {
  operational: '正常',
  degraded: '降级',
  error: '异常',
  none: '无调用'
};

/** 模型状态码 → 文案 */
function statusLabel(status?: string) {
  if (status === '0') return { text: '正常', color: 'success' as const };
  if (status === '1') return { text: '停用', color: 'default' as const };
  return { text: status || '-', color: 'default' as const };
}

/** 并发上限展示：null/不限 → ∞ */
function limitText(limit?: number | null, limited?: boolean) {
  if (!limited || limit == null) return '∞';
  return String(limit);
}

/** 使用率 Progress 状态 */
function progressStatus(percent?: number | null) {
  if (percent == null) return 'normal';
  if (percent >= 95) return 'exception';
  if (percent >= 75) return 'active';
  return 'normal';
}

/** 排队数颜色：越长越紧 */
function waitingColor(waiting: number) {
  if (waiting <= 0) return 'default';
  if (waiting < 5) return 'blue';
  if (waiting < 20) return 'gold';
  return 'red';
}

/** 耗时人性化：<10s 用毫秒，否则用秒 */
function latencyText(ms?: number | null) {
  if (ms == null) return '-';
  if (ms < 10000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

/** 可用率百分比 → 分级颜色（≥99 绿 / ≥95 黄绿 / ≥90 橙 / <90 红） */
function pctColor(pct?: number | null) {
  if (pct == null) return '#94a3b8';
  if (pct >= 99) return '#22c55e';
  if (pct >= 95) return '#84cc16';
  if (pct >= 90) return '#f59e0b';
  return '#ef4444';
}

/**
 * 可用率短柱条：按百分比填充；0% 直接红色占满（必然有失败）；
 * 无调用（pct=null）灰色占满。
 */
function AvailabilityBar({ pct, label }: { pct?: number | null; label: string }) {
  const noData = pct == null;
  const zero = !noData && pct <= 0;
  const fillPct = noData || zero ? 100 : Math.max(pct!, 4);
  const fillColor = noData ? '#e2e8f0' : zero ? '#ef4444' : pctColor(pct);
  return (
    <Tooltip title={`${label}：${noData ? '无调用' : `${pct}%`}`}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' }}>
        <span style={{ color: '#64748b' }}>{label}</span>
        <span
          style={{
            display: 'inline-block',
            width: 56,
            height: 8,
            borderRadius: 4,
            background: '#f1f5f9',
            overflow: 'hidden',
            verticalAlign: 'middle'
          }}
        >
          <span
            style={{
              display: 'block',
              width: `${fillPct}%`,
              height: '100%',
              borderRadius: 4,
              background: fillColor,
              transition: 'width .3s'
            }}
          />
        </span>
        <strong style={{ color: noData ? '#94a3b8' : pctColor(pct) }}>
          {noData ? '-' : `${pct}%`}
        </strong>
      </span>
    </Tooltip>
  );
}

/** 48格健康时间轴（方块条） */
function HealthStrip({ timeline }: { timeline: ModelHealthTimeline }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ fontSize: 11, color: '#94a3b8', flexShrink: 0 }}>24小时前</span>
      <div style={{ display: 'flex', gap: 2, flex: 1, minWidth: 0 }}>
        {(timeline.items || []).map((item, i) => (
          <Tooltip
            key={i}
            title={
              <div style={{ fontSize: 12 }}>
                <div>{item.bucketTime}（30分钟）</div>
                <div>
                  状态：{HEALTH_LABELS[item.status] || item.status}
                  {item.status !== 'none' && ` · 成功 ${item.successCount} / 失败 ${item.failCount}`}
                </div>
                {item.avgLatencyMs != null && <div>平均耗时：{latencyText(item.avgLatencyMs)}</div>}
                {item.errorMessage && <div style={{ color: '#fca5a5' }}>最近错误：{item.errorMessage}</div>}
              </div>
            }
          >
            <div
              style={{
                flex: 1,
                minWidth: 4,
                maxWidth: 14,
                height: 24,
                borderRadius: 2,
                background: HEALTH_COLORS[item.status] || HEALTH_COLORS.none,
                cursor: 'pointer'
              }}
            />
          </Tooltip>
        ))}
      </div>
      <span style={{ fontSize: 11, color: '#94a3b8', flexShrink: 0 }}>现在</span>
    </div>
  );
}

/** 单个统计项（文字型，替代原卡片） */
function StatText({
  label,
  value,
  color,
  tip
}: {
  label: string;
  value: React.ReactNode;
  color?: string;
  tip?: string;
}) {
  const body = (
    <span style={{ whiteSpace: 'nowrap' }}>
      <span style={{ color: '#64748b' }}>{label} </span>
      <strong style={{ color: color || '#0f172a' }}>{value}</strong>
    </span>
  );
  return tip ? <Tooltip title={tip}>{body}</Tooltip> : body;
}

/**
 * AI 模型监控页：上游请求并发/排队 + 运行健康度。
 * <p>轮询 /aid/modelmonitor/snapshot（在途请求实时 + 健康总览30秒缓存），
 * 并发口径与真正执行限流的媒体层一致：running=在途上游请求数，waiting=等待名额的请求数。</p>
 * <p>
 * 顶部异常概览可悬浮查看并点击定位到具体模型；服务商以 Tab 分组，
 * 每个模型按 48 格（30分钟/格）方块时间轴展示最近24小时运行状况。</p>
 */
export default function ModelQueueMonitorPage() {
  const [snapshot, setSnapshot] = useState<ModelQueueSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [intervalSec, setIntervalSec] = useState<number>(5);
  const [modelKeyword, setModelKeyword] = useState('');
  const [modelTypeFilter, setModelTypeFilter] = useState<string | undefined>(undefined);
  /** 启停筛选：enabled=仅启用（默认） / disabled=仅停用 / all=全部；同时作用于服务商 Tab 与模型 */
  const [enabledFilter, setEnabledFilter] = useState<'enabled' | 'disabled' | 'all'>('enabled');
  const [errMsg, setErrMsg] = useState<string | null>(null);
  const [activeProviderKey, setActiveProviderKey] = useState<string | undefined>(undefined);
  /** 点击异常模型定位后短暂高亮的模型编码 */
  const [highlightModel, setHighlightModel] = useState<string | null>(null);

  // 防止页面卸载后还 setState
  const aliveRef = useRef(true);
  // 单飞：上一个请求未回时不发新的，避免堆叠
  const inFlightRef = useRef(false);
  const highlightTimerRef = useRef<number | null>(null);

  const load = useCallback(async (silent = false) => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    if (!silent) setLoading(true);
    try {
      const res = await getModelQueueSnapshot();
      if (!aliveRef.current) return;
      const data = (res as any).data ?? res;
      setSnapshot(data);
      setErrMsg(null);
    } catch (e: any) {
      if (!aliveRef.current) return;
      setErrMsg(e?.message || '获取监控数据失败');
    } finally {
      inFlightRef.current = false;
      if (aliveRef.current && !silent) setLoading(false);
    }
  }, []);

  // 首次加载
  useEffect(() => {
    aliveRef.current = true;
    load(false);
    return () => {
      aliveRef.current = false;
      if (highlightTimerRef.current) window.clearTimeout(highlightTimerRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 定时轮询
  useEffect(() => {
    if (!autoRefresh) return;
    const handle = window.setInterval(() => {
      // 页面隐藏时不轮询，避免后台标签页空跑
      if (document.visibilityState === 'hidden') return;
      load(true);
    }, Math.max(1, intervalSec) * 1000);
    return () => window.clearInterval(handle);
  }, [autoRefresh, intervalSec, load]);

  // tab 切回时立即刷新一次
  useEffect(() => {
    const onVis = () => {
      if (document.visibilityState === 'visible' && autoRefresh) {
        load(true);
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, [autoRefresh, load]);

  const health = snapshot?.health || null;

  // 队列侧：modelCode → 排队/并发行
  const queueModelByCode = useMemo(() => {
    const m = new Map<string, ModelQueueStat>();
    (snapshot?.models || []).forEach((r) => m.set(r.modelCode, r));
    return m;
  }, [snapshot]);

  /** 启停筛选命中判断：服务商用 status（'0'=启用），模型用 enabled 布尔 */
  const matchProviderEnabled = useCallback(
    (status?: string) => {
      if (enabledFilter === 'all') return true;
      return enabledFilter === 'enabled' ? status === '0' : status !== '0';
    },
    [enabledFilter]
  );
  const matchModelEnabled = useCallback(
    (enabled?: boolean) => {
      if (enabledFilter === 'all') return true;
      return enabledFilter === 'enabled' ? enabled !== false : enabled === false;
    },
    [enabledFilter]
  );

  // 健康侧：providerId → 该服务商模型时间线（已按启停筛选）
  const healthByProviderId = useMemo(() => {
    const m = new Map<number, ModelHealthTimeline[]>();
    (health?.providerTimelines || []).forEach((t) => {
      if (t.providerId == null || !matchModelEnabled(t.enabled)) return;
      const list = m.get(t.providerId) || [];
      list.push(t);
      m.set(t.providerId, list);
    });
    return m;
  }, [health, matchModelEnabled]);

  // 服务商 Tab 列表：以队列快照为主（含并发信息），按启停筛选后按名称稳定排序（避免负载变化导致 Tab 跳动）
  const providerTabsData = useMemo(() => {
    const providers = (snapshot?.providers || []).filter((p) => matchProviderEnabled(p.status));
    providers.sort((a, b) => (a.providerName || '').localeCompare(b.providerName || '', 'zh-CN'));
    return providers.map((p) => {
      const timelines = p.providerId != null ? healthByProviderId.get(p.providerId) || [] : [];
      const errorModels = timelines.filter((t) => t.latestStatus === 'error');
      const degradedModels = timelines.filter((t) => t.latestStatus === 'degraded');
      return { provider: p, timelines, errorModels, degradedModels };
    });
  }, [snapshot, healthByProviderId, matchProviderEnabled]);

  // 当前筛选口径下可见的全部模型时间线（横幅计数与异常清单口径与页面展示一致）
  const visibleTimelines = useMemo(
    () => providerTabsData.flatMap((d) => d.timelines),
    [providerTabsData]
  );

  // 异常/降级模型（可见范围内汇总，顶部概览用）
  const abnormalModels = useMemo(() => {
    const list = visibleTimelines.filter(
      (t) => t.latestStatus === 'error' || t.latestStatus === 'degraded'
    );
    // 异常在前、降级在后
    list.sort((a, b) => (a.latestStatus === 'error' ? -1 : 1) - (b.latestStatus === 'error' ? -1 : 1));
    return list;
  }, [visibleTimelines]);

  // 模型分类可选值（筛选下拉）
  const modelTypeOptions = useMemo(() => {
    const set = new Set<string>();
    (health?.providerTimelines || []).forEach((t) => t.modelType && set.add(t.modelType));
    (snapshot?.models || []).forEach((m) => m.modelType && set.add(m.modelType));
    return Array.from(set).map((t) => ({ label: t, value: t }));
  }, [health, snapshot]);

  // 默认激活 Tab：优先有异常模型的服务商，否则第一个；筛选后当前 Tab 不可见时自动重置
  useEffect(() => {
    if (providerTabsData.length === 0) return;
    const exists = providerTabsData.some((d) => String(d.provider.providerId) === activeProviderKey);
    if (activeProviderKey && exists) return;
    const withError = providerTabsData.find((d) => d.errorModels.length > 0);
    setActiveProviderKey(String((withError || providerTabsData[0]).provider.providerId));
  }, [providerTabsData, activeProviderKey]);

  /** 点击异常模型 → 切换到对应服务商 Tab 并滚动定位 + 短暂高亮 */
  const locateModel = useCallback((timeline: ModelHealthTimeline) => {
    if (timeline.providerId != null) {
      setActiveProviderKey(String(timeline.providerId));
    }
    setHighlightModel(timeline.modelCode);
    if (highlightTimerRef.current) window.clearTimeout(highlightTimerRef.current);
    highlightTimerRef.current = window.setTimeout(() => setHighlightModel(null), 3000);
    // 等 Tab 内容渲染后再滚动
    window.setTimeout(() => {
      document
        .getElementById(`model-health-row-${timeline.modelCode}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 150);
  }, []);

  /** 异常模型清单（悬浮弹层内容，可点击定位） */
  const abnormalPopContent = (
    <div style={{ maxWidth: 420, maxHeight: 320, overflowY: 'auto' }}>
      {abnormalModels.map((t) => (
        <div
          key={t.modelCode}
          onClick={() => locateModel(t)}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '6px 8px',
            borderRadius: 6,
            cursor: 'pointer'
          }}
          onMouseEnter={(e) => (e.currentTarget.style.background = '#f1f5f9')}
          onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
        >
          <Badge color={HEALTH_COLORS[t.latestStatus]} />
          <span style={{ fontWeight: 600 }}>{t.modelName || t.modelCode}</span>
          <span style={{ fontSize: 12, color: '#94a3b8' }}>{t.providerName}</span>
          <Tag color={t.latestStatus === 'error' ? 'red' : 'orange'} style={{ marginLeft: 'auto' }}>
            {HEALTH_LABELS[t.latestStatus]}
          </Tag>
          {t.availabilityPct != null && (
            <span style={{ fontSize: 12, color: '#64748b' }}>可用率 {t.availabilityPct}%</span>
          )}
        </div>
      ))}
      <div style={{ fontSize: 12, color: '#94a3b8', padding: '4px 8px' }}>点击可定位到对应模型</div>
    </div>
  );

  /** 顶部健康总览横幅 */
  const renderHealthBanner = () => {
    if (!health) {
      return (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="模型健康数据暂不可用（不影响排队/并发监控）"
        />
      );
    }
    // 计数与整体状态按当前启停筛选口径实时计算，与下方 Tab 内容保持一致
    const operationalCount = visibleTimelines.filter((t) => t.latestStatus === 'operational').length;
    const degradedCount = visibleTimelines.filter((t) => t.latestStatus === 'degraded').length;
    const errorCount = visibleTimelines.filter((t) => t.latestStatus === 'error').length;
    const noDataCount = visibleTimelines.filter((t) => t.latestStatus === 'none').length;
    const overall =
      errorCount > 0
        ? 'error'
        : degradedCount > 0
        ? 'degraded'
        : operationalCount > 0
        ? 'operational'
        : 'none';
    const overallText =
      overall === 'error'
        ? '部分服务异常'
        : overall === 'degraded'
        ? '部分服务降级'
        : overall === 'none'
        ? '暂无调用数据'
        : '所有服务运行正常';
    const bannerColor =
      overall === 'error'
        ? '#fef2f2'
        : overall === 'degraded'
        ? '#fffbeb'
        : overall === 'none'
        ? '#f8fafc'
        : '#f0fdf4';
    const borderColor =
      overall === 'error'
        ? '#fecaca'
        : overall === 'degraded'
        ? '#fde68a'
        : overall === 'none'
        ? '#e2e8f0'
        : '#bbf7d0';
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 12,
          padding: '10px 14px',
          marginBottom: 12,
          borderRadius: 8,
          background: bannerColor,
          border: `1px solid ${borderColor}`
        }}
      >
        <Badge color={HEALTH_COLORS[overall]} />
        <strong style={{ fontSize: 15 }}>{overallText}</strong>
        <span style={{ color: '#64748b', fontSize: 12 }}>
          正常 {operationalCount} · 降级 {degradedCount} · 异常 {errorCount} · 无调用 {noDataCount}（近{' '}
          {health.trendPeriod || '24h'}，更新于 {health.lastUpdated || '-'}）
        </span>
        {abnormalModels.length > 0 && (
          <Popover content={abnormalPopContent} title="异常 / 降级模型" placement="bottom">
            <Tag
              icon={<WarningOutlined />}
              color={abnormalModels.some((t) => t.latestStatus === 'error') ? 'red' : 'orange'}
              style={{ cursor: 'pointer', marginLeft: 'auto' }}
            >
              {abnormalModels.length} 个模型待关注（悬浮查看 / 点击定位）
            </Tag>
          </Popover>
        )}
      </div>
    );
  };

  /** AI 算力概览：文字型紧凑一行（替代原四张卡片） */
  const renderCapacityLine = () => {
    const globalPercent = snapshot?.globalUsagePercent ?? 0;
    const totalWaiting = snapshot?.totalWaiting ?? 0;
    const saturatedCount = (snapshot?.models || []).filter(
      (m) => m.saturated && m.status === '0'
    ).length;
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 16,
          padding: '8px 14px',
          borderRadius: 8,
          background: '#f8fafc',
          border: '1px solid #e2e8f0',
          fontSize: 13
        }}
      >
        <ThunderboltOutlined style={{ color: '#3b82f6' }} />
        <StatText
          label="全局在途请求"
          value={`${snapshot?.globalRunning ?? '-'} / ${snapshot?.globalLimit ?? '-'}（${globalPercent}%）`}
          color={globalPercent >= 95 ? '#ef4444' : globalPercent >= 75 ? '#f59e0b' : '#22c55e'}
        />
        <StatText
          label="排队总长"
          value={totalWaiting}
          color={totalWaiting > 0 ? '#3b82f6' : undefined}
          tip="等待上游名额的请求总数，按 model_name 精确统计，无抽样"
        />
        <StatText
          label="已饱和模型"
          value={saturatedCount}
          color={saturatedCount > 0 ? '#ef4444' : '#22c55e'}
          tip="当前在途请求数已达模型并发上限的启用模型数"
        />
        <StatText
          label="单用户并发上限"
          value={snapshot?.userDefaultLimit ?? '-'}
          tip="可在系统参数 media_concurrent_limit_user 调整"
        />
        {(snapshot?.unassignedProviderWaiting ?? 0) > 0 && (
          <Tooltip title="model_name 不在模型表的排队请求（如合成任务），已计入总排队但不归属任一模型/服务商，各服务商排队之和可能小于总排队，属正常现象">
            <span style={{ color: '#94a3b8', fontSize: 12 }}>
              未归属模型排队 {snapshot?.unassignedProviderWaiting}
            </span>
          </Tooltip>
        )}
      </div>
    );
  };

  /** 单个服务商 Tab 内容：服务商信息条 + 模型健康方块列表 */
  const renderProviderPane = (data: (typeof providerTabsData)[number]) => {
    const { provider: p, timelines } = data;
    const s = statusLabel(p.status);
    const kw = modelKeyword.trim().toLowerCase();

    // 模型列表以健康时间线为准（含停用模型），按最新状态排序：异常 > 降级 > 正常 > 无调用
    const order: Record<string, number> = { error: 0, degraded: 1, operational: 2, none: 3 };
    const filtered = timelines
      .filter((t) => {
        if (modelTypeFilter && t.modelType !== modelTypeFilter) return false;
        if (!kw) return true;
        return (
          (t.modelCode || '').toLowerCase().includes(kw) ||
          (t.modelName || '').toLowerCase().includes(kw)
        );
      })
      .sort((a, b) => (order[a.latestStatus] ?? 9) - (order[b.latestStatus] ?? 9));

    return (
      <div>
        {/* 服务商信息条：该有的信息全量展示 */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 16,
            padding: '8px 14px',
            marginBottom: 12,
            borderRadius: 8,
            background: '#f8fafc',
            border: '1px solid #e2e8f0',
            fontSize: 13
          }}
        >
          <strong style={{ fontSize: 14 }}>{p.providerName || `provider-${p.providerId}`}</strong>
          <span style={{ color: '#94a3b8', fontSize: 12 }}>ID: {p.providerId}</span>
          <Tag color={s.color}>{s.text}</Tag>
          <StatText label="模型数" value={p.modelCount} />
          <StatText label="并发" value={`${p.running} / ${limitText(p.concurrencyLimit, p.limited)}`} />
          <StatText
            label="排队中"
            value={<Tag color={waitingColor(p.waiting)}>{p.waiting}</Tag>}
          />
          {p.limited ? (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 160 }}>
              <span style={{ color: '#64748b' }}>使用率</span>
              <Progress
                percent={p.usagePercent ?? 0}
                size="small"
                status={progressStatus(p.usagePercent) as any}
                style={{ width: 110, margin: 0 }}
              />
            </span>
          ) : (
            <StatText label="使用率" value="不限并发" color="#94a3b8" />
          )}
          <StatText
            label="健康"
            value={`正常 ${timelines.filter((t) => t.latestStatus === 'operational').length} · 降级 ${
              data.degradedModels.length
            } · 异常 ${data.errorModels.length} · 无调用 ${
              timelines.filter((t) => t.latestStatus === 'none').length
            }`}
            color={
              data.errorModels.length > 0
                ? '#ef4444'
                : data.degradedModels.length > 0
                ? '#f59e0b'
                : timelines.some((t) => t.latestStatus === 'operational')
                ? '#22c55e'
                : '#94a3b8'
            }
          />
          {p.saturated && <Tag color="red">并发已饱和</Tag>}
        </div>

        {/* 模型维度：每个模型一行方块时间轴 */}
        {filtered.length === 0 ? (
          <Empty description={timelines.length === 0 ? '该服务商暂无健康数据' : '无匹配模型'} />
        ) : (
          filtered.map((t) => {
            const q = queueModelByCode.get(t.modelCode);
            const highlighted = highlightModel === t.modelCode;
            return (
              <div
                key={t.modelCode}
                id={`model-health-row-${t.modelCode}`}
                style={{
                  padding: '10px 14px',
                  marginBottom: 10,
                  borderRadius: 8,
                  border: highlighted ? '2px solid #3b82f6' : '1px solid #e2e8f0',
                  boxShadow: highlighted ? '0 0 0 4px rgba(59,130,246,0.15)' : undefined,
                  transition: 'box-shadow .3s, border-color .3s'
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    flexWrap: 'wrap',
                    gap: 10,
                    marginBottom: 8
                  }}
                >
                  <Badge color={HEALTH_COLORS[t.latestStatus]} />
                  <strong>{t.modelName || t.modelCode}</strong>
                  <span style={{ fontSize: 12, color: '#94a3b8' }}>{t.modelCode}</span>
                  {t.modelType && <Tag>{t.modelType}</Tag>}
                  {t.enabled === false && <Tag color="default">停用</Tag>}
                  <Tag
                    color={
                      t.latestStatus === 'error'
                        ? 'red'
                        : t.latestStatus === 'degraded'
                        ? 'orange'
                        : t.latestStatus === 'none'
                        ? 'default'
                        : 'green'
                    }
                  >
                    {HEALTH_LABELS[t.latestStatus]}
                  </Tag>
                  <span
                    style={{
                      marginLeft: 'auto',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 14,
                      flexWrap: 'wrap',
                      fontSize: 12,
                      color: '#64748b'
                    }}
                  >
                    <AvailabilityBar pct={t.availabilityPct} label="24h" />
                    <AvailabilityBar pct={t.availability7dPct} label="7天" />
                    <AvailabilityBar pct={t.availability15dPct} label="15天" />
                    <AvailabilityBar pct={t.availability30dPct} label="30天" />
                    <span>
                      24h 成功/失败{' '}
                      <strong style={{ color: '#22c55e' }}>{t.successCount ?? 0}</strong>
                      <span style={{ color: '#cbd5e1' }}> / </span>
                      <strong style={{ color: (t.failCount ?? 0) > 0 ? '#ef4444' : '#94a3b8' }}>
                        {t.failCount ?? 0}
                      </strong>
                    </span>
                    <span>
                      最新延迟 <strong style={{ color: '#0f172a' }}>{latencyText(t.latestLatencyMs)}</strong>
                    </span>
                    <span>
                      7天平均延迟{' '}
                      <strong style={{ color: '#0f172a' }}>{latencyText(t.avgLatency7dMs)}</strong>
                    </span>
                    <span>
                      24h平均 <strong style={{ color: '#0f172a' }}>{latencyText(t.avgLatencyMs)}</strong>
                    </span>
                    {q && (
                      <>
                        <span>
                          并发{' '}
                          <strong
                            style={{ color: q.saturated ? '#ef4444' : '#0f172a' }}
                          >{`${q.running} / ${limitText(q.concurrencyLimit, q.limited)}`}</strong>
                        </span>
                        <span>
                          排队 <strong style={{ color: q.waiting > 0 ? '#3b82f6' : '#0f172a' }}>{q.waiting}</strong>
                        </span>
                        <span>
                          近{snapshot?.usageWindowHours ?? 24}h调用{' '}
                          <strong style={{ color: '#0f172a' }}>{q.recentUsage ?? '-'}</strong>
                        </span>
                      </>
                    )}
                  </span>
                </div>
                <HealthStrip timeline={t} />
              </div>
            );
          })
        )}
      </div>
    );
  };

  const generatedAtText = snapshot
    ? new Date(snapshot.generatedAt).toLocaleTimeString('zh-CN', { hour12: false })
    : '-';

  const tabItems = providerTabsData.map((d) => {
    const p = d.provider;
    const hasError = d.errorModels.length > 0;
    const hasDegraded = d.degradedModels.length > 0;
    const hasOperational = d.timelines.some((t) => t.latestStatus === 'operational');
    const dotColor = hasError
      ? HEALTH_COLORS.error
      : hasDegraded
      ? HEALTH_COLORS.degraded
      : hasOperational
      ? HEALTH_COLORS.operational
      : HEALTH_COLORS.none;
    return {
      key: String(p.providerId),
      label: (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span
            style={{ width: 8, height: 8, borderRadius: '50%', background: dotColor, display: 'inline-block' }}
          />
          <span>
            {p.providerName || `provider-${p.providerId}`}（并发 {p.running}/
            {limitText(p.concurrencyLimit, p.limited)}）
          </span>
          {p.status !== '0' && <Tag style={{ marginInlineEnd: 0 }}>停用</Tag>}
          {hasError && (
            <Tag color="red" style={{ marginInlineEnd: 0 }}>
              {d.errorModels.length} 异常
            </Tag>
          )}
        </span>
      ),
      children: renderProviderPane(d)
    };
  });

  return (
    <div className="crud-page">
      {errMsg && <Alert type="error" showIcon style={{ marginBottom: 12 }} message={errMsg} closable />}

      <PageCard
        title={
          <Space>
            <ThunderboltOutlined />
            <span>模型监控</span>
          </Space>
        }
        extra={
          <Space wrap>
            <span style={{ color: '#64748b' }}>
              数据更新于 <strong>{generatedAtText}</strong>
            </span>
            <span style={{ color: '#cbd5e1' }}>|</span>
            <span>自动刷新</span>
            <Switch checked={autoRefresh} onChange={setAutoRefresh} size="small" />
            <Radio.Group
              size="small"
              value={intervalSec}
              onChange={(e) => setIntervalSec(e.target.value)}
              optionType="button"
              buttonStyle="solid"
              options={REFRESH_OPTIONS.map((s) => ({ label: `${s}s`, value: s }))}
            />
            <Button icon={<ReloadOutlined />} size="small" onClick={() => load(false)}>
              立即刷新
            </Button>
          </Space>
        }
      >
        {/* 1. 健康总览横幅：整体状态 + 异常模型悬浮清单（点击定位） */}
        {renderHealthBanner()}
        {/* 2. AI 算力概览：文字型紧凑一行 */}
        {renderCapacityLine()}
      </PageCard>

      <PageCard
        title="服务商 / 模型运行状况"
        className="page-card"
        bodyStyle={{ paddingTop: 4 }}
        extra={
          <Space wrap>
            <Radio.Group
              size="small"
              value={enabledFilter}
              onChange={(e) => setEnabledFilter(e.target.value)}
              optionType="button"
              buttonStyle="solid"
              options={[
                { label: '启用', value: 'enabled' },
                { label: '停用', value: 'disabled' },
                { label: '全部', value: 'all' }
              ]}
            />
            <Input.Search
              size="small"
              allowClear
              placeholder="搜索模型名 / Code"
              style={{ width: 200 }}
              value={modelKeyword}
              onChange={(e) => setModelKeyword(e.target.value)}
            />
            <Select
              size="small"
              allowClear
              placeholder="模型分类"
              style={{ width: 130 }}
              value={modelTypeFilter}
              onChange={setModelTypeFilter}
              options={modelTypeOptions}
            />
          </Space>
        }
      >
        <Spin spinning={loading && !snapshot}>
          {tabItems.length === 0 ? (
            <Empty description="暂无服务商数据" />
          ) : (
            <Tabs
              items={tabItems}
              activeKey={activeProviderKey}
              onChange={setActiveProviderKey}
              size="small"
            />
          )}
        </Spin>
      </PageCard>
    </div>
  );
}

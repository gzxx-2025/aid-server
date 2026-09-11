import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Card, Drawer, Tabs, Table, Form, Input, Select, Button, Space, Tag, Modal, Descriptions, Empty, Spin, Image, message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  listAuditProject, listAuditMovie, listAuditEpisode, listAuditRecord,
  getProjectAuditDetail, getMovieAuditDetail, getEpisodeAuditDetail,
  auditProject, auditEpisode
} from '@/api/aid/audit';
import {
  PROJECT_STATUS_OPTIONS, EPISODE_STATUS_OPTIONS, getLabelByValue, getAntdTagColor
} from '@/utils/enums';
import { useAuth } from '@/hooks/useAuth';
import PageHeader from '@/components/PageHeader';
import './style.less';

/** 审核动作枚举（与后端 AuditActionEnum 一致） */
const AUDIT_ACTION_OPTIONS = [
  { label: '提交审核', value: 1 },
  { label: '审核通过', value: 2 },
  { label: '审核驳回', value: 3 },
  { label: '作品发布', value: 4 },
  { label: '后台下架', value: 5 },
  { label: '审核回撤', value: 6 }
];
/** 审核对象类型枚举（与后端 AuditTargetTypeEnum 一致） */
const AUDIT_TARGET_OPTIONS = [
  { label: '项目', value: 'project' },
  { label: '剧集', value: 'episode' }
];
/** 审核中状态值（项目/剧集通用） */
const STATUS_AUDITING = 3;
/** 成片导出状态文案 */
const EXPORT_STATUS_TEXT: Record<number, string> = { 0: '未导出', 1: '合成中', 2: '导出成功', 3: '导出失败' };
/** 生成模式文案 */
const GEN_MODE_TEXT: Record<string, string> = { economy: '经济模式', performance: '性能模式' };
/** 创作模式文案 */
const CREATION_MODE_TEXT: Record<string, string> = { i2v: '图生视频', multi: '多参生视频' };

type AuditKind = 'project' | 'movie' | 'episode';

/** 列表数据通用 hook：按 tab 激活时懒加载，统一管理分页/搜索/刷新 */
function useAuditList(fetchFn: (q: any) => Promise<any>, enabled: boolean) {
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [search, setSearch] = useState<Record<string, any>>({});

  const load = useCallback(() => {
    setLoading(true);
    fetchFn({ ...search, pageNum, pageSize })
      .then((res: any) => {
        setList(res.data || []);
        setTotal(res.total || 0);
      })
      .finally(() => setLoading(false));
  }, [fetchFn, search, pageNum, pageSize]);

  useEffect(() => {
    if (enabled) load();
  }, [enabled, load]);

  return {
    loading, list, total, pageNum, pageSize,
    setPage: (p: number, s: number) => { setPageNum(p); setPageSize(s); },
    applySearch: (v: Record<string, any>) => { setPageNum(1); setSearch(v); },
    resetSearch: () => { setPageNum(1); setSearch({}); },
    reload: load
  };
}

/** 状态标签 */
function StatusTag({ options, value }: { options: any[]; value: number }) {
  return <Tag color={getAntdTagColor(options, value)}>{getLabelByValue(options, value)}</Tag>;
}

/** 电影/剧集 彩色标签 */
function BizTag({ text }: { text?: string }) {
  if (!text) return <span>--</span>;
  return <Tag color={text === '电影' ? 'geekblue' : 'cyan'}>{text}</Tag>;
}

interface SearchField {
  name: string;
  placeholder: string;
  type?: 'input' | 'select';
  options?: any[];
  width?: number;
}

export default function ComicAudit() {
  const { hasPermi } = useAuth();
  const canAudit = hasPermi('aid:audit:audit');
  const canQuery = hasPermi('aid:audit:query');

  const [activeTab, setActiveTab] = useState<string>('project');

  // 四个列表（按激活 tab 懒加载）
  const project = useAuditList(listAuditProject, activeTab === 'project');
  const movie = useAuditList(listAuditMovie, activeTab === 'movie');
  const episode = useAuditList(listAuditEpisode, activeTab === 'episode');
  const record = useAuditList(listAuditRecord, activeTab === 'record');

  // 各 tab 的搜索表单实例
  const [projectForm] = Form.useForm();
  const [movieForm] = Form.useForm();
  const [episodeForm] = Form.useForm();
  const [recordForm] = Form.useForm();

  // 审核详情弹窗
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailKind, setDetailKind] = useState<AuditKind>('project');
  const [detail, setDetail] = useState<any>({});
  const [detailLoading, setDetailLoading] = useState(false);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  const isAuditing = (status: number) => status === STATUS_AUDITING;

  // 按 kind 取对应列表 hook，用于审核后刷新
  const listOf = (kind: AuditKind) => (kind === 'project' ? project : kind === 'movie' ? movie : episode);

  /** ===== 审核详情 ===== */
  const openDetail = (kind: AuditKind, row: any) => {
    setDetailKind(kind);
    setReason('');
    setDetail({});
    setDetailOpen(true);
    setDetailLoading(true);
    const api = kind === 'project' ? getProjectAuditDetail : kind === 'movie' ? getMovieAuditDetail : getEpisodeAuditDetail;
    api(row.id)
      .then((res: any) => setDetail(res.data || {}))
      .finally(() => setDetailLoading(false));
  };
  const closeDetail = () => {
    if (videoRef.current) videoRef.current.pause();
    setDetailOpen(false);
  };

  /** ===== 审核动作（项目/电影走 auditProject，剧集走 auditEpisode） ===== */
  const callAudit = (kind: AuditKind, id: number, pass: boolean, reasonText: string | null) =>
    (kind === 'episode' ? auditEpisode : auditProject)({ id, pass, reason: reasonText });

  const submitFromDetail = (pass: boolean) => {
    if (!pass && !reason.trim()) {
      message.warning('请填写驳回原因');
      return;
    }
    setSubmitting(true);
    callAudit(detailKind, detail.id, pass, reason.trim() || null)
      .then(() => {
        message.success(pass
          ? (detailKind === 'episode' ? '已通过' : detail.isPublic === '1' ? '已通过并更新' : '已通过并自动发布')
          : '已驳回');
        closeDetail();
        listOf(detailKind).reload();
      })
      .finally(() => setSubmitting(false));
  };

  const quickApprove = (kind: AuditKind, row: any) => {
    const autoPublish = kind !== 'episode' && row.isPublic !== '1';
    const updatePublished = kind !== 'episode' && row.isPublic === '1';
    Modal.confirm({
      title: autoPublish ? '审核通过并发布' : updatePublished ? '审核通过并更新' : '审核通过',
      content: autoPublish
        ? '确认通过并自动发布吗？建议先查看「审核详情」确认内容。'
        : updatePublished
          ? '确认通过并更新线上版本吗？建议先核对待审版本与当前线上版本。'
        : '确认通过吗？所属作品已公开时，本集通过后将立即可播。',
      okText: autoPublish ? '确认通过并发布' : updatePublished ? '确认通过并更新' : '确认通过',
      cancelText: '取消',
      onOk: () => callAudit(kind, row.id, true, null).then(() => {
        message.success(autoPublish ? '已通过并自动发布' : updatePublished ? '已通过并更新' : '已通过');
        listOf(kind).reload();
      })
    });
  };

  const quickReject = (kind: AuditKind, row: any) => {
    let rejectReason = '';
    Modal.confirm({
      title: '驳回',
      content: (
        <Input.TextArea
          rows={3}
          maxLength={500}
          showCount
          placeholder="请输入驳回原因（将展示给用户）"
          onChange={(e) => { rejectReason = e.target.value; }}
        />
      ),
      okText: '确认驳回',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        if (!rejectReason.trim()) {
          message.warning('请填写驳回原因');
          return Promise.reject(new Error('reason required'));
        }
        return callAudit(kind, row.id, false, rejectReason.trim()).then(() => { message.success('已驳回'); listOf(kind).reload(); });
      }
    });
  };

  /** ===== 通用渲染：搜索栏 ===== */
  const renderSearch = (form: any, state: any, fields: SearchField[]) => (
    <Card className="page-card" bordered={false}>
      <Form form={form} layout="inline" onFinish={(v) => state.applySearch(v)} style={{ rowGap: 8 }}>
        {fields.map((f) => (
          <Form.Item name={f.name} key={f.name}>
            {f.type === 'select' ? (
              <Select placeholder={f.placeholder} allowClear style={{ width: f.width || 150 }} options={f.options} />
            ) : (
              <Input placeholder={f.placeholder} allowClear style={{ width: f.width || 150 }} />
            )}
          </Form.Item>
        ))}
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">搜索</Button>
            <Button onClick={() => { form.resetFields(); state.resetSearch(); }}>重置</Button>
          </Space>
        </Form.Item>
      </Form>
    </Card>
  );

  /** ===== 通用渲染：操作列 ===== */
  const opColumn = (kind: AuditKind): any => ({
    title: '操作',
    key: 'op',
    width: 220,
    fixed: 'right',
    render: (_: any, row: any) => (
      <Space size={4}>
        {canQuery && <Button type="link" size="small" onClick={() => openDetail(kind, row)}>审核详情</Button>}
        {canAudit && <Button type="link" size="small" disabled={!isAuditing(row.status)} onClick={() => quickApprove(kind, row)}>通过</Button>}
        {canAudit && <Button type="link" size="small" danger disabled={!isAuditing(row.status)} onClick={() => quickReject(kind, row)}>驳回</Button>}
      </Space>
    )
  });

  /** ===== 通用渲染：表格（含分页） ===== */
  const renderTable = (state: any, columns: ColumnsType<any>) => (
    <Card className="page-card" bordered={false}>
      <Table
        rowKey="id"
        size="middle"
        loading={state.loading}
        columns={columns}
        dataSource={state.list}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: state.pageNum,
          pageSize: state.pageSize,
          total: state.total,
          showSizeChanger: true,
          showTotal: (t: number) => `共 ${t} 条`,
          onChange: (p: number, s: number) => state.setPage(p, s)
        }}
      />
    </Card>
  );

  /** ===== 各 tab 列定义 ===== */
  const projectColumns: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目名称', dataIndex: 'projectName', ellipsis: true },
    { title: '用户ID', dataIndex: 'userId', width: 90 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: number) => <StatusTag options={PROJECT_STATUS_OPTIONS} value={v} /> },
    { title: '驳回原因', dataIndex: 'statusReason', ellipsis: true },
    { title: '提交时间', dataIndex: 'updateTime', width: 165 },
    opColumn('project')
  ];

  const movieColumns: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '电影名称', dataIndex: 'projectName', ellipsis: true },
    { title: '用户ID', dataIndex: 'userId', width: 90 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: number) => <StatusTag options={PROJECT_STATUS_OPTIONS} value={v} /> },
    { title: '驳回原因', dataIndex: 'statusReason', ellipsis: true },
    { title: '提交时间', dataIndex: 'updateTime', width: 165 },
    opColumn('movie')
  ];

  const episodeColumns: ColumnsType<any> = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '项目ID', dataIndex: 'projectId', width: 90 },
    { title: '类型', dataIndex: 'projectTypeDesc', width: 80, render: (v: string) => <BizTag text={v} /> },
    { title: '集号', dataIndex: 'episodeNo', width: 70 },
    { title: '单集标题', dataIndex: 'comicTitle', ellipsis: true },
    { title: '用户ID', dataIndex: 'userId', width: 90 },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: number) => <StatusTag options={EPISODE_STATUS_OPTIONS} value={v} /> },
    { title: '驳回原因', dataIndex: 'statusReason', ellipsis: true },
    { title: '提交时间', dataIndex: 'updateTime', width: 165 },
    opColumn('episode')
  ];

  const recordColumns: ColumnsType<any> = [
    { title: '时间', dataIndex: 'createTime', width: 165 },
    { title: '对象类型', dataIndex: 'targetTypeDesc', width: 90 },
    { title: '作品类型', dataIndex: 'bizTypeDesc', width: 90, render: (v: string) => <BizTag text={v} /> },
    { title: '对象ID', dataIndex: 'targetId', width: 90 },
    { title: '作品用户', dataIndex: 'ownerUserId', width: 90 },
    {
      title: '审核动作', dataIndex: 'action', width: 100,
      render: (v: number, r: any) => <Tag color={v === 2 ? 'success' : v === 3 ? 'error' : 'default'}>{r.actionDesc}</Tag>
    },
    { title: '状态变更', key: 'change', width: 110, render: (_: any, r: any) => `${r.beforeStatus} → ${r.afterStatus}` },
    { title: '审核意见/原因', dataIndex: 'auditReason', ellipsis: true },
    { title: '操作人', dataIndex: 'operator', width: 120 }
  ];

  const tabItems = [
    {
      key: 'project', label: '项目审核',
      children: (
        <div className="audit-tab-body">
          {renderSearch(projectForm, project, [
            { name: 'projectName', placeholder: '项目名称' },
            { name: 'userId', placeholder: '用户ID', width: 120 },
            { name: 'status', placeholder: '状态(默认审核中)', type: 'select', options: PROJECT_STATUS_OPTIONS }
          ])}
          {renderTable(project, projectColumns)}
        </div>
      )
    },
    {
      key: 'movie', label: '电影审核',
      children: (
        <div className="audit-tab-body">
          {renderSearch(movieForm, movie, [
            { name: 'projectName', placeholder: '电影名称' },
            { name: 'userId', placeholder: '用户ID', width: 120 },
            { name: 'status', placeholder: '状态(默认审核中)', type: 'select', options: PROJECT_STATUS_OPTIONS }
          ])}
          {renderTable(movie, movieColumns)}
        </div>
      )
    },
    {
      key: 'episode', label: '剧集审核',
      children: (
        <div className="audit-tab-body">
          {renderSearch(episodeForm, episode, [
            { name: 'projectId', placeholder: '项目ID', width: 120 },
            { name: 'comicTitle', placeholder: '单集标题' },
            { name: 'userId', placeholder: '用户ID', width: 120 },
            { name: 'status', placeholder: '状态(默认审核中)', type: 'select', options: EPISODE_STATUS_OPTIONS }
          ])}
          {renderTable(episode, episodeColumns)}
        </div>
      )
    },
    {
      key: 'record', label: '审核记录',
      children: (
        <div className="audit-tab-body">
          {renderSearch(recordForm, record, [
            { name: 'targetType', placeholder: '对象类型', type: 'select', options: AUDIT_TARGET_OPTIONS, width: 120 },
            { name: 'targetId', placeholder: '对象ID', width: 120 },
            { name: 'action', placeholder: '审核动作', type: 'select', options: AUDIT_ACTION_OPTIONS, width: 130 }
          ])}
          {renderTable(record, recordColumns)}
        </div>
      )
    }
  ];

  // 详情弹窗：状态枚举与标题
  const detailStatusOptions = detailKind === 'episode' ? EPISODE_STATUS_OPTIONS : PROJECT_STATUS_OPTIONS;
  const detailTitle = detailKind === 'project' ? '项目审核详情' : detailKind === 'movie' ? '电影审核详情' : '剧集审核详情';
  const showVideo = detailKind === 'movie' || detailKind === 'episode';
  const showCover = detailKind === 'project' || detailKind === 'movie';
  const isMetadataReview = detail.metadataChanged === true;
  const approveText = detailKind === 'episode'
    ? '审核通过'
    : detail.isPublic === '1' ? '审核通过并更新' : '审核通过并发布';

  return (
    <div className="crud-page">
      <PageHeader
        title="内容审核"
        desc="项目、电影、剧集的提审处理与审核记录查询"
      />
      <Tabs activeKey={activeTab} onChange={setActiveTab} items={tabItems} />

      <Drawer
        title={detailTitle}
        open={detailOpen}
        width={720}
        onClose={closeDetail}
        afterOpenChange={(open) => { if (!open) { setDetail({}); setReason(''); } }}
        footer={
          <Space>
            {isAuditing(detail.status) && canAudit && (
              <>
                <Button type="primary" loading={submitting} onClick={() => submitFromDetail(true)}>
                  {approveText}
                </Button>
                <Button danger loading={submitting} onClick={() => submitFromDetail(false)}>驳回</Button>
              </>
            )}
            <Button onClick={closeDetail}>关闭</Button>
          </Space>
        }
      >
        <Spin spinning={detailLoading}>
          {/* 封面（项目/电影审核） */}
          {showCover && (
            <div className="audit-media-block">
              <div className="help-text audit-media-block__label">
                {isMetadataReview ? '本次待审封面' : '封面'}
                {isMetadataReview && <Tag color="processing" style={{ marginLeft: 4 }}>本次审核对象</Tag>}
              </div>
              {detail.coverUrl ? (
                <Image src={detail.coverUrl} alt="封面" className="audit-media-block__cover" />
              ) : (
                <div className="empty-box audit-media-block__empty">
                  <Empty description="暂无封面" imageStyle={{ height: 60 }} />
                </div>
              )}
              {isMetadataReview && detail.publishedCoverUrl && (
                <div style={{ marginTop: 12 }}>
                  <div className="help-text audit-media-block__label">当前线上封面</div>
                  <Image src={detail.publishedCoverUrl} alt="当前线上封面" className="audit-media-block__cover audit-media-block__cover--online" />
                </div>
              )}
            </div>
          )}
          {/* 待审新片（重新导出提审时优先展示，本次审核对象） */}
          {showVideo && detail.pendingVideoUrl && (
            <div className="audit-media-block">
              <div className="help-text audit-media-block__label">
                待审新片 <Tag color="processing" style={{ marginLeft: 4 }}>本次审核对象</Tag>
              </div>
              <video
                ref={videoRef}
                src={detail.pendingVideoUrl}
                poster={detail.finalCoverUrl || detail.coverUrl || detail.comicCoverUrl}
                controls
                controlsList="nodownload"
                className="audit-media-block__video"
              />
            </div>
          )}
          {/* 成品视频（电影/剧集审核；存在待审新片时为线上旧片） */}
          {showVideo && (
            <div className="audit-media-block">
              <div className="help-text audit-media-block__label">
                成品视频
                {detail.pendingVideoUrl && <Tag style={{ marginLeft: 4 }}>线上旧片</Tag>}
              </div>
              {detail.finalVideoUrl ? (
                <video
                  ref={detail.pendingVideoUrl ? undefined : videoRef}
                  src={detail.finalVideoUrl}
                  poster={detail.finalCoverUrl || detail.coverUrl || detail.comicCoverUrl}
                  controls
                  controlsList="nodownload"
                  className="audit-media-block__video"
                />
              ) : (
                <div className="empty-box audit-media-block__empty audit-media-block__empty--tall">
                  <Empty description="暂无成品视频" imageStyle={{ height: 60 }} />
                </div>
              )}
              {detail.finalVideoUrl && detail.exportStatus !== 2 && (
                <div style={{ marginTop: 8 }}>
                  <Tag color="warning">成片导出状态：{EXPORT_STATUS_TEXT[detail.exportStatus] ?? '未知'}（画面可能非最终版）</Tag>
                </div>
              )}
            </div>
          )}
          {/* 剧集单集封面（剧集审核） */}
          {detailKind === 'episode' && detail.comicCoverUrl && (
            <div className="audit-media-block">
              <div className="help-text audit-media-block__label">
                {isMetadataReview ? '本次待审单集封面' : '单集封面'}
                {isMetadataReview && <Tag color="processing" style={{ marginLeft: 4 }}>本次审核对象</Tag>}
              </div>
              <Image src={detail.comicCoverUrl} alt="单集封面" className="audit-media-block__cover audit-media-block__cover--online" />
              {isMetadataReview && detail.publishedComicCoverUrl && (
                <div style={{ marginTop: 12 }}>
                  <div className="help-text audit-media-block__label">当前线上单集封面</div>
                  <Image src={detail.publishedComicCoverUrl} alt="当前线上单集封面" className="audit-media-block__cover audit-media-block__cover--online" />
                </div>
              )}
            </div>
          )}

          <Descriptions
            column={1}
            size="small"
            bordered
            labelStyle={{ width: 96, whiteSpace: 'nowrap', color: '#666' }}
            contentStyle={{ wordBreak: 'break-all' }}
          >
            {detailKind !== 'episode' && <Descriptions.Item label={detailKind === 'movie' ? '电影名称' : '项目名称'}>{detail.projectName}</Descriptions.Item>}
            {detailKind !== 'episode' && isMetadataReview && <Descriptions.Item label="线上名称">{detail.publishedProjectName || '--'}</Descriptions.Item>}
            {detailKind === 'episode' && <Descriptions.Item label="所属项目">{detail.projectName}（{detail.projectId}）</Descriptions.Item>}
            {detailKind === 'episode' && <Descriptions.Item label="作品类型"><BizTag text={detail.projectTypeDesc} /></Descriptions.Item>}
            {detailKind === 'episode' && <Descriptions.Item label="单集标题">第{detail.episodeNo}集 · {detail.comicTitle}</Descriptions.Item>}
            {detailKind === 'episode' && isMetadataReview && <Descriptions.Item label="线上标题">{detail.publishedComicTitle || '--'}</Descriptions.Item>}
            {detailKind !== 'episode' && <Descriptions.Item label="作品类型"><BizTag text={detail.projectTypeDesc} /></Descriptions.Item>}
            <Descriptions.Item label="作者">{detail.authorNickname ? `${detail.authorNickname}（${detail.userId}）` : detail.userId}</Descriptions.Item>
            <Descriptions.Item label="画面比例">{detail.aspectRatio || '--'}</Descriptions.Item>
            <Descriptions.Item label="剧本类型">{detail.scriptType || '--'}</Descriptions.Item>
            <Descriptions.Item label="视频风格">{detail.videoStyleType || '--'}</Descriptions.Item>
            <Descriptions.Item label="生成模式">{GEN_MODE_TEXT[detail.defaultGenMode || detail.genMode] || detail.defaultGenMode || detail.genMode || '--'}</Descriptions.Item>
            <Descriptions.Item label="创作模式">{CREATION_MODE_TEXT[detail.defaultCreationMode || detail.creationMode] || detail.defaultCreationMode || detail.creationMode || '--'}</Descriptions.Item>
            <Descriptions.Item label="当前状态"><StatusTag options={detailStatusOptions} value={detail.status} /></Descriptions.Item>
            <Descriptions.Item label="状态原因">{detail.statusReason || '--'}</Descriptions.Item>
            {detailKind === 'episode' && <Descriptions.Item label="项目描述">{detail.projectDesc || '--'}</Descriptions.Item>}
            <Descriptions.Item label={isMetadataReview ? (detailKind === 'episode' ? '待审单集描述' : '待审描述') : (detailKind === 'episode' ? '单集描述' : '描述')}>
              {(detailKind === 'episode' ? detail.comicDesc : detail.projectDesc) || '--'}
            </Descriptions.Item>
            {isMetadataReview && (
              <Descriptions.Item label={detailKind === 'episode' ? '线上单集描述' : '线上描述'}>
                {(detailKind === 'episode' ? detail.publishedComicDesc : detail.publishedProjectDesc) || '--'}
              </Descriptions.Item>
            )}
            {detailKind !== 'episode' && <Descriptions.Item label="是否公开">{detail.isPublic === '1' ? <Tag color="success">已公开</Tag> : <Tag>未公开</Tag>}</Descriptions.Item>}
            {detailKind !== 'episode' && <Descriptions.Item label="发布时间">{detail.publishTime || '--'}</Descriptions.Item>}
            <Descriptions.Item label="创建时间">{detail.createTime || '--'}</Descriptions.Item>
            <Descriptions.Item label="提交时间">{detail.updateTime || '--'}</Descriptions.Item>
          </Descriptions>
          {isAuditing(detail.status) && canAudit && (
            <Input.TextArea
              style={{ marginTop: 16 }}
              rows={3}
              maxLength={500}
              showCount
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="审核意见（通过可选填；驳回必填，将作为驳回原因展示给用户）"
            />
          )}
        </Spin>
      </Drawer>
    </div>
  );
}

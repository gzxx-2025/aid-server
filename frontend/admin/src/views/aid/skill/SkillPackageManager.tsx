import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Avatar, Button, Card, Col, Descriptions, Divider, Drawer, Form, Input, InputNumber,
  List, Modal, Popconfirm, Row, Select, Space, Spin, Steps, Table, Tabs, Tag,
  Typography, message
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CheckCircleOutlined, EyeOutlined, ReloadOutlined, RocketOutlined, SaveOutlined } from '@ant-design/icons';

import {
  activateSkillVersion, discardSkillDraft, getSkillDependencyLabels, getSkillDraft, getSkillVersion,
  listSkillTextModels, listSkillVersions, publishSkillDraft, saveSkillDraft, validateSkillDraft,
  type AdminSkillSummary, type SkillDraftDetail, type SkillPackagePayload,
  type SkillTextModelOption, type SkillValidationIssue, type SkillValidationResult,
  type SkillVersionDetail, type SkillVersionSummary
} from '@/api/aid/skill';
import SchemaFieldEditor, { validateSkillSchema } from './SchemaFieldEditor';
import SkillRelationEditor from './SkillRelationEditor';
import SkillResourceEditor from './SkillResourceEditor';
import { validateResourceContentBytes } from './skillPackageUtils';

interface SkillPackageManagerProps {
  open: boolean;
  skill?: AdminSkillSummary;
  canEdit: boolean;
  onClose: () => void;
  onChanged?: () => void | Promise<void>;
}

interface PublishFormValues {
  versionCode: string;
}

const SEMVER_PATTERN = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

function parseJson(value: string | undefined, label: string, objectOnly = false) {
  if (!value?.trim()) return objectOnly ? `${label}不能为空` : undefined;
  try {
    const parsed = JSON.parse(value) as unknown;
    if (objectOnly && (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))) {
      return `${label}根节点必须是对象`;
    }
    return undefined;
  } catch {
    return `${label}不是有效 JSON`;
  }
}

function validateResources(resources: SkillPackagePayload['resources'] | undefined) {
  if ((resources || []).length > 64) return '参考资源不能超过 64 项';
  const keys = new Set<string>();
  for (const [index, resource] of (resources || []).entries()) {
    const field = `资源 ${index + 1}`;
    if (!/^[a-z0-9][a-z0-9._-]{0,99}$/.test(resource.resourceKey || '')) return `${field}标识错误`;
    if (keys.has(resource.resourceKey)) return `资源标识 ${resource.resourceKey} 重复`;
    keys.add(resource.resourceKey);
    if (!resource.content?.trim()) return `${field}内容不能为空`;
    if ((resource.routeJson || '').length > 8000) return `${field}路由不能超过 8000 个字符`;
    const routeError = parseJson(resource.routeJson || '{}', `${field}路由`, true);
    if (routeError) return routeError;
    const route = JSON.parse(resource.routeJson || '{}') as Record<string, unknown>;
    if (route.always !== undefined && typeof route.always !== 'boolean') return `${field}路由 always 必须为布尔值`;
    for (const key of ['operations', 'keywords'] as const) {
      const items = route[key];
      if (items !== undefined && (!Array.isArray(items) || items.some((item) =>
        typeof item !== 'string' || !item.trim() || item.length > 100))) {
        return `${field}路由 ${key} 必须是非空且单项不超过 100 字符的字符串数组`;
      }
    }
  }
  return validateResourceContentBytes(resources || []);
}

function issueList(title: string, issues: SkillValidationIssue[], color: string) {
  if (!issues.length) return null;
  return <Card size="small" title={<Typography.Text style={{ color }}>{title}（{issues.length}）</Typography.Text>}>
    <List size="small" dataSource={issues} renderItem={(issue) => <List.Item>
      <Space align="start"><Typography.Text code>{issue.field || 'package'}</Typography.Text>
        <Typography.Text>{issue.message}</Typography.Text></Space>
    </List.Item>} />
  </Card>;
}

function readonlyRelations(relations: SkillVersionDetail['relations']) {
  if (!relations.length) return <Typography.Text type="secondary">无固定子 Skill</Typography.Text>;
  return <List size="small" dataSource={relations} renderItem={(relation) => <List.Item>
    <Space wrap>
      <Tag>{relation.relationKey}</Tag>
      <Typography.Text>{relation.childSkillCode || relation.childSkillId}</Typography.Text>
      <Typography.Text type="secondary">@ {relation.childVersionCode || relation.childVersionId}</Typography.Text>
      {relation.requiredFlag && <Tag color="blue">必需</Tag>}
    </Space>
  </List.Item>} />;
}

function normalizedDraft(draft: SkillDraftDetail) {
  return {
    ...draft,
    defaultModelCode: draft.defaultModelCode || draft.modelCode,
    selectableModelCodes: draft.selectableModelCodes?.length
      ? draft.selectableModelCodes : draft.modelCode ? [draft.modelCode] : [],
    resources: draft.resources || [],
    relations: draft.relations || []
  };
}

function modelCapabilityText(model: SkillTextModelOption) {
  const capability = model.capability || {};
  const modalities = Array.isArray(capability.inputModalities)
    ? capability.inputModalities.join('、') : '文本';
  const reasoning = capability.supportsReasoning ? `思考：${Array.isArray(capability.allowedReasoningLevels)
    ? capability.allowedReasoningLevels.join('/') : '支持'}` : '思考：不支持';
  return `输入：${modalities || '文本'}；${reasoning}`;
}

function modelPriceText(model: SkillTextModelOption) {
  const billing = model.billing as { billingDesc?: string; isFree?: boolean } | undefined;
  if (billing?.isFree) return '免费';
  return billing?.billingDesc || '未配置价格';
}

/** 管理不可变 Skill 版本包：编辑草稿、校验、发布，再显式切换当前版本。 */
export default function SkillPackageManager({
  open, skill, canEdit, onClose, onChanged
}: SkillPackageManagerProps) {
  const [form] = Form.useForm<SkillPackagePayload>();
  const [publishForm] = Form.useForm<PublishFormValues>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const [validating, setValidating] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [activatingId, setActivatingId] = useState<number>();
  const [seedingVersionId, setSeedingVersionId] = useState<number>();
  const [error, setError] = useState('');
  const [draft, setDraft] = useState<SkillDraftDetail>();
  const [versions, setVersions] = useState<SkillVersionSummary[]>([]);
  const [versionTotal, setVersionTotal] = useState(0);
  const [versionPageNum, setVersionPageNum] = useState(1);
  const [versionPageSize, setVersionPageSize] = useState(20);
  const [versionLoading, setVersionLoading] = useState(false);
  const [currentVersionId, setCurrentVersionId] = useState<number | null>(null);
  const [models, setModels] = useState<SkillTextModelOption[]>([]);
  const [relationSkillLabels, setRelationSkillLabels] = useState<Record<number, string>>({});
  const [relationVersionLabels, setRelationVersionLabels] = useState<Record<number, string>>({});
  const [dirty, setDirty] = useState(false);
  const [validation, setValidation] = useState<SkillValidationResult>();
  const [publishOpen, setPublishOpen] = useState(false);
  const [pendingActivationId, setPendingActivationId] = useState<number>();
  const [versionDetail, setVersionDetail] = useState<SkillVersionDetail>();
  const [versionDetailLoading, setVersionDetailLoading] = useState(false);
  const loadSequence = useRef(0);
  const versionListSequence = useRef(0);
  const relationLabelSequence = useRef(0);
  const versionPageRef = useRef({ pageNum: 1, pageSize: 20 });
  const requestedVersionId = useRef<number>();
  const packageActionRef = useRef<string>();
  const [packageAction, setPackageAction] = useState<string>();

  const beginPackageAction = (action: string) => {
    if (packageActionRef.current) return false;
    packageActionRef.current = action;
    setPackageAction(action);
    return true;
  };
  const endPackageAction = (action: string) => {
    if (packageActionRef.current !== action) return;
    packageActionRef.current = undefined;
    setPackageAction(undefined);
  };
  const packageBusy = !!packageAction;

  const rememberSkillLabel = useCallback((id: number, label: string) => {
    setRelationSkillLabels((current) => current[id] === label ? current : { ...current, [id]: label });
  }, []);
  const rememberVersionLabel = useCallback((id: number, label: string) => {
    setRelationVersionLabels((current) => current[id] === label ? current : { ...current, [id]: label });
  }, []);

  const hydrateRelationLabels = useCallback(async (nextDraft: SkillDraftDetail) => {
    const sequence = ++relationLabelSequence.current;
    const skillLabels: Record<number, string> = {};
    const versionLabels: Record<number, string> = {};
    for (const relation of nextDraft.relations || []) {
      skillLabels[relation.childSkillId] = relation.relationKey || `Skill #${relation.childSkillId}`;
      versionLabels[relation.childVersionId] = `版本 #${relation.childVersionId}`;
    }
    setRelationSkillLabels({ ...skillLabels });
    setRelationVersionLabels({ ...versionLabels });
    const versionIds = [...new Set((nextDraft.relations || []).map((relation) => relation.childVersionId))];
    if (!versionIds.length) return;
    try {
      const response = await getSkillDependencyLabels(nextDraft.skillId, versionIds);
      if (sequence !== relationLabelSequence.current) return;
      for (const label of response.data || []) {
        skillLabels[label.childSkillId] = `${label.childSkillName}（${label.childSkillCode}）`;
        versionLabels[label.childVersionId] = `${label.childVersionCode}${label.current ? '（当前）' : ''}`;
      }
    } catch {
      // 标签接口不可用时保留稳定 ID，不清空已选关系。
    }
    if (sequence !== relationLabelSequence.current) return;
    setRelationSkillLabels({ ...skillLabels });
    setRelationVersionLabels({ ...versionLabels });
  }, []);

  const loadVersionPage = useCallback(async (pageNum: number, pageSize: number, force = false) => {
    if (!open || !skill) return;
    const sequence = ++versionListSequence.current;
    setVersionLoading(true);
    try {
      const response = await listSkillVersions({ skillId: skill.id, pageNum, pageSize }, { force });
      if (sequence !== versionListSequence.current) return;
      const rows = response.data || [];
      setVersions(rows);
      setVersionTotal(Number(response.total || 0));
      setCurrentVersionId(response.currentVersionId ?? null);
    } catch (cause: any) {
      if (sequence === versionListSequence.current) message.error(cause?.message || '版本列表加载失败');
    } finally {
      if (sequence === versionListSequence.current) setVersionLoading(false);
    }
  }, [open, skill]);

  const load = useCallback(async (force = false) => {
    if (!open || !skill) return;
    const requestedSkillId = skill.id;
    const sequence = ++loadSequence.current;
    const versionSequence = ++versionListSequence.current;
    const { pageNum, pageSize } = versionPageRef.current;
    setLoading(true);
    setError('');
    try {
      const [versionResponse, draftResponse, modelResponse] = await Promise.all([
        listSkillVersions({ skillId: requestedSkillId, pageNum, pageSize }, { force }),
        getSkillDraft(requestedSkillId, undefined, { force }),
        listSkillTextModels({ force })
      ]);
      if (sequence !== loadSequence.current) return;
      const nextDraft = draftResponse.data;
      if (versionSequence === versionListSequence.current) {
        const rows = versionResponse.data || [];
        setVersions(rows);
        setVersionTotal(Number(versionResponse.total || 0));
        setCurrentVersionId(versionResponse.currentVersionId ?? null);
      }
      setModels((modelResponse.data || []).filter((item) => item.modelCode));
      setDraft(nextDraft);
      form.setFieldsValue(normalizedDraft(nextDraft));
      await hydrateRelationLabels(nextDraft);
      setDirty(false);
      setValidation(undefined);
    } catch (cause: any) {
      if (sequence === loadSequence.current) setError(cause?.message || '版本包加载失败');
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [form, hydrateRelationLabels, open, skill]);

  useEffect(() => {
    if (open) {
      setCurrentVersionId(skill?.currentVersionId ?? null);
      load();
    }
    else {
      loadSequence.current += 1;
      versionListSequence.current += 1;
      relationLabelSequence.current += 1;
      versionPageRef.current = { pageNum: 1, pageSize: 20 };
      form.resetFields();
      publishForm.resetFields();
      setDraft(undefined);
      setVersions([]);
      setVersionTotal(0);
      setVersionPageNum(1);
      setVersionPageSize(20);
      setVersionLoading(false);
      setCurrentVersionId(null);
      packageActionRef.current = undefined;
      setPackageAction(undefined);
      setRelationSkillLabels({});
      setRelationVersionLabels({});
      setValidation(undefined);
      setPendingActivationId(undefined);
      setVersionDetail(undefined);
      setDirty(false);
      setError('');
    }
  }, [form, load, open, publishForm, skill?.currentVersionId]);

  const defaultModelCode = Form.useWatch('defaultModelCode', form);
  const selectableModelCodes = Form.useWatch('selectableModelCodes', form) || [];
  const selectedModels = useMemo(() => selectableModelCodes
    .map((code: string) => models.find((item) => item.modelCode === code))
    .filter((item): item is SkillTextModelOption => !!item), [models, selectableModelCodes]);

  const packagePayload = async () => {
    if (!skill || !draft) throw new Error('草稿尚未加载，请稍后再试');
    const values = await form.validateFields();
    const schemaError = parseJson(values.inputSchemaJson, '输入 Schema', true)
      || parseJson(values.outputSchemaJson, '输出 Schema', true)
      || (values.definitionJson?.trim() ? parseJson(values.definitionJson, '定义 JSON', true) : undefined);
    if (schemaError) throw new Error(schemaError);
    const resourceError = validateResources(values.resources);
    if (resourceError) throw new Error(resourceError);
    if (values.maxOutputTokens + values.safetyMarginTokens >= values.contextWindowTokens) {
      throw new Error('输出上限与安全余量必须小于上下文窗口');
    }
    const modelCodes = [...new Set(values.selectableModelCodes || [])];
    if (!values.defaultModelCode || !modelCodes.includes(values.defaultModelCode)) {
      throw new Error('默认模型必须属于可选模型');
    }
    if (modelCodes.length > 20 || modelCodes.some((code) => !models.find((model) =>
      model.modelCode === code && model.available))) {
      throw new Error('可选模型包含已停用模型，请先替换');
    }
    return {
      ...values, skillId: skill.id, baseVersionId: draft.baseVersionId,
      modelCode: values.defaultModelCode, selectableModelCodes: modelCodes,
      resources: values.resources || [], relations: values.relations || []
    };
  };

  const saveDraft = async () => {
    if (!skill || !beginPackageAction('save')) return;
    setSaving(true);
    try {
      const payload = await packagePayload();
      const response = await saveSkillDraft({
        ...payload, draftId: draft?.draftId, draftDigest: draft?.draftDigest
      });
      const nextDraft = response.data;
      setDraft(nextDraft);
      form.setFieldsValue(normalizedDraft(nextDraft));
      setDirty(false);
      setValidation(undefined);
      message.success('草稿已保存，当前执行版本未变化');
    } catch (cause: any) {
      message.error(cause?.message || '草稿保存失败');
    } finally {
      setSaving(false);
      endPackageAction('save');
    }
  };

  const validateDraft = async () => {
    if (!beginPackageAction('validate')) return;
    setValidating(true);
    try {
      const payload = await packagePayload();
      const response = await validateSkillDraft(payload, { force: true });
      setValidation(response.data);
      if (response.data.valid) message.success('草稿校验通过');
      else message.error('草稿校验未通过');
    } catch (cause: any) {
      message.error(cause?.message || '草稿校验失败');
    } finally {
      setValidating(false);
      endPackageAction('validate');
    }
  };

  const discardDraft = async (baseVersionId?: number, baseVersionCode?: string) => {
    if (!skill || !draft?.draftId || !draft.draftDigest || !beginPackageAction('discard')) return;
    setDiscarding(true);
    if (baseVersionId) setSeedingVersionId(baseVersionId);
    try {
      await discardSkillDraft(skill.id, draft.draftId, draft.draftDigest);
      const response = await getSkillDraft(skill.id, baseVersionId, { force: true });
      const nextDraft = response.data;
      setDraft(nextDraft);
      form.setFieldsValue(normalizedDraft(nextDraft));
      await hydrateRelationLabels(nextDraft);
      setDirty(false);
      setValidation(undefined);
      if (nextDraft.draftId) {
        message.warning('检测到新的活动草稿，未切换编辑基础版本');
      } else if (baseVersionId) {
        message.success(`已放弃原草稿，并基于 ${baseVersionCode || nextDraft.baseVersionCode || baseVersionId} 编辑`);
      } else {
        message.success('已放弃草稿，并恢复到当前版本编辑种子');
      }
    } catch (cause: any) {
      message.error(cause?.message || '草稿放弃失败');
    } finally {
      setDiscarding(false);
      if (baseVersionId) setSeedingVersionId(undefined);
      endPackageAction('discard');
    }
  };

  const canPublish = !!draft?.draftId && !!draft.draftDigest && !dirty
    && validation?.valid === true && validation.draftDigest === draft.draftDigest;

  const publishDraft = async () => {
    if (!draft?.draftId || !draft.draftDigest || !beginPackageAction('publish')) return;
    setPublishing(true);
    try {
      const { versionCode } = await publishForm.validateFields();
      const response = await publishSkillDraft(draft.draftId, draft.draftDigest, versionCode.trim());
      setPendingActivationId(response.data.id);
      message.success('新版本已发布，需显式切换后才会执行');
      setPublishOpen(false);
      publishForm.resetFields();
      versionPageRef.current = { pageNum: 1, pageSize: versionPageRef.current.pageSize };
      setVersionPageNum(1);
      await load(true);
      await onChanged?.();
    } catch (cause: any) {
      message.error(cause?.message || '版本发布失败');
    } finally {
      setPublishing(false);
      endPackageAction('publish');
    }
  };

  const activateVersion = async (version: SkillVersionSummary) => {
    if (!skill || version.id === currentVersionId || !beginPackageAction('activate')) return;
    const expectedCurrentVersionId = currentVersionId;
    setActivatingId(version.id);
    try {
      await activateSkillVersion(skill.id, version.id, expectedCurrentVersionId);
      setCurrentVersionId(version.id);
      setVersions((current) => current.map((item) => ({ ...item, current: item.id === version.id })));
      if (pendingActivationId === version.id) setPendingActivationId(undefined);
      message.success(`已切换到 ${version.versionCode}`);
      const page = versionPageRef.current;
      await loadVersionPage(page.pageNum, page.pageSize, true);
      await onChanged?.();
    } catch (cause: any) {
      message.error(cause?.message || '版本切换失败');
      const page = versionPageRef.current;
      await loadVersionPage(page.pageNum, page.pageSize, true);
    } finally {
      setActivatingId(undefined);
      endPackageAction('activate');
    }
  };

  const openVersion = async (version: SkillVersionSummary) => {
    requestedVersionId.current = version.id;
    setVersionDetailLoading(true);
    try {
      const response = await getSkillVersion(version.id);
      if (requestedVersionId.current !== version.id) return;
      setVersionDetail(response.data);
    } catch (cause: any) {
      if (requestedVersionId.current === version.id) message.error(cause?.message || '版本详情加载失败');
    } finally {
      if (requestedVersionId.current === version.id) setVersionDetailLoading(false);
    }
  };

  const loadVersionSeed = async (version: SkillVersionSummary) => {
    if (!skill) return;
    const sequence = ++loadSequence.current;
    setLoading(true);
    setSeedingVersionId(version.id);
    try {
      const response = await getSkillDraft(skill.id, version.id, { force: true });
      if (sequence !== loadSequence.current) return;
      const nextDraft = response.data;
      setDraft(nextDraft);
      form.setFieldsValue(normalizedDraft(nextDraft));
      await hydrateRelationLabels(nextDraft);
      setDirty(false);
      setValidation(undefined);
      if (nextDraft.draftId) {
        message.warning(nextDraft.baseVersionId === version.id
          ? '服务端返回了当前活动草稿，未创建新的编辑种子'
          : '仍有活动草稿，未切换到所选版本；请先放弃当前草稿');
      } else {
        message.success(`已从 ${version.versionCode} 创建未保存编辑种子`);
      }
    } catch (cause: any) {
      if (sequence === loadSequence.current) message.error(cause?.message || '版本编辑种子加载失败');
    } finally {
      if (sequence === loadSequence.current) {
        setLoading(false);
        setSeedingVersionId(undefined);
      }
    }
  };

  const startFromVersion = (version: SkillVersionSummary) => {
    if (draft?.draftId) {
      if (!draft.draftDigest) {
        message.error('活动草稿缺少摘要，请刷新后再操作');
        return;
      }
      Modal.confirm({
        title: '已有活动草稿，需先放弃',
        content: `放弃当前已保存草稿后，才会基于 ${version.versionCode} 生成新的编辑种子。未保存修改也会丢失。`,
        okText: '放弃并继续', okButtonProps: { danger: true }, cancelText: '保留草稿',
        onOk: () => discardDraft(version.id, version.versionCode)
      });
      return;
    }
    if (!dirty) {
      loadVersionSeed(version);
      return;
    }
    Modal.confirm({
      title: `放弃未保存修改并基于 ${version.versionCode} 编辑？`,
      content: '如果服务端已有活动草稿，会继续返回该草稿，不会创建第二份。',
      okText: '继续', cancelText: '取消', onOk: () => loadVersionSeed(version)
    });
  };

  const close = () => {
    if (packageActionRef.current) {
      message.warning('包管理操作进行中，请稍候');
      return;
    }
    if (!dirty) {
      onClose();
      return;
    }
    Modal.confirm({
      title: '放弃未保存修改？', content: '草稿表单中有尚未保存的内容。',
      okText: '放弃', okButtonProps: { danger: true }, cancelText: '继续编辑', onOk: onClose
    });
  };

  const versionColumns: ColumnsType<SkillVersionSummary> = [
    { title: '版本', dataIndex: 'versionCode', render: (value, row) => <Space>
      <Typography.Text strong={row.id === currentVersionId}>{value}</Typography.Text>
      {row.id === currentVersionId && <Tag color="success">当前</Tag>}
    </Space> },
    { title: '发布状态', dataIndex: 'publishStatus', width: 110, render: (value) => <Tag>{value}</Tag> },
    { title: '包摘要', dataIndex: 'packageDigest', ellipsis: true, render: (value) => <Typography.Text
      type="secondary" copyable={{ text: value }}>{value?.slice(0, 12) || '--'}</Typography.Text> },
    { title: '发布时间', dataIndex: 'createTime', width: 170 },
    { title: '发布人', dataIndex: 'createBy', width: 110, render: (value) => value || '--' },
    { title: '操作', key: 'action', width: 310, fixed: 'right', render: (_, row) => <Space>
      <Button type="link" icon={<EyeOutlined />} loading={versionDetailLoading}
        onClick={() => openVersion(row)}>查看</Button>
      {canEdit && <Button type="link" disabled={packageBusy} loading={seedingVersionId === row.id}
        onClick={() => startFromVersion(row)}>基于此版本编辑</Button>}
      {canEdit && row.id !== currentVersionId && <Popconfirm title={`确认将 ${row.versionCode} 切换为当前执行版本？`}
        description="只影响切换后的新运行；历史运行仍绑定原版本。" onConfirm={() => activateVersion(row)}>
        <Button type="link" disabled={packageBusy}
          loading={activatingId === row.id}>切换</Button>
      </Popconfirm>}
    </Space> }
  ];

  return <>
    <Drawer width={1180} open={open} onClose={close} destroyOnClose={false}
      title={skill ? `${skill.name} · 版本包` : 'Skill 版本包'}
      extra={<Button icon={<ReloadOutlined />} disabled={packageBusy} loading={loading}
        onClick={() => load(true)}>刷新</Button>}>
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />}
      <Spin spinning={loading}>
        <Alert type="info" showIcon style={{ marginBottom: 16 }}
          message="草稿、发布和启用相互独立"
          description="保存草稿不会影响运行；校验通过后发布为不可变版本，发布完成后仍需在版本历史中显式切换。" />
        <Steps size="small" style={{ marginBottom: 20 }} current={pendingActivationId
          ? 2 : validation?.valid && validation.draftDigest === draft?.draftDigest && !dirty ? 1 : 0} items={[
          { title: '保存草稿', description: '仅保存编辑内容' },
          { title: '校验并发布', description: '生成不可变版本' },
          { title: '显式切换', description: '新运行开始使用' }
        ]} />
        {pendingActivationId && <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="新版本已发布，但尚未启用"
          description="请到“当前与历史”中核对版本内容，再执行切换。" />}
        <Tabs items={[
          {
            key: 'draft', label: <Space><SaveOutlined />编辑草稿</Space>, children: <>
              {draft && <Descriptions size="small" bordered column={4} style={{ marginBottom: 16 }} items={[
                { key: 'code', label: '稳定编码', children: draft.skillCode },
                { key: 'base', label: '基于版本', children: draft.baseVersionCode || '未发布' },
                { key: 'scope', label: '调用范围', children: draft.invocationScope },
                { key: 'executor', label: '执行器', children: draft.executorType },
                { key: 'digest', label: '草稿摘要', span: 3, children: <Typography.Text copyable>
                  {draft.draftDigest || '尚未保存'}</Typography.Text> },
                { key: 'time', label: '更新时间', children: draft.updateTime || '--' }
              ]} />}
              <Form form={form} layout="vertical" disabled={!canEdit || !draft || packageBusy}
                onValuesChange={() => { setDirty(true); setValidation(undefined); }}>
                <Row gutter={16}>
                  <Col span={12}><Form.Item name="selectableModelCodes" label="可选模型（最多 20 个）"
                    rules={[{ required: true, message: '请选择至少一个模型' }]}>
                    <Select mode="multiple" showSearch optionFilterProp="label" maxCount={20}
                      onChange={(codes: string[]) => {
                        if (!codes.includes(form.getFieldValue('defaultModelCode'))) {
                          form.setFieldValue('defaultModelCode', codes.find((code) =>
                            models.find((model) => model.modelCode === code)?.available));
                        }
                      }} options={models.map((model) => ({
                        value: model.modelCode,
                        disabled: !model.available && !selectableModelCodes.includes(model.modelCode),
                        label: `${model.modelName || model.modelCode}（${model.modelCode}）${model.available
                          ? '' : ` · ${model.unavailableReason || '已停用、需替换'}`}`
                      }))} />
                  </Form.Item></Col>
                  <Col span={12}><Form.Item name="defaultModelCode" label="默认模型"
                    rules={[{ required: true, message: '请选择默认模型' }]}>
                    <Select showSearch optionFilterProp="label" options={selectedModels.map((model) => ({
                      value: model.modelCode, disabled: !model.available,
                      label: `${model.modelName || model.modelCode}${model.available ? '' : '（已停用、需替换）'}`
                    }))} />
                  </Form.Item></Col>
                </Row>
                {selectedModels.some((model) => !model.available) && <Alert type="warning" showIcon
                  style={{ marginBottom: 16 }} message="已引用模型中存在停用项"
                  description="停用模型仅用于保留历史版本审计，必须移除或替换后才能校验和发布。" />}
                <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
                  {selectedModels.map((model) => <Col span={12} key={model.modelCode}><Card size="small"
                    title={<Space><Avatar size="small" src={model.modelLogo || model.providerLogo} />
                      <Typography.Text>{model.modelName || model.modelCode}</Typography.Text>
                      {model.modelCode === defaultModelCode && <Tag color="blue">默认</Tag>}
                      {!model.available && <Tag color="error">已停用</Tag>}</Space>}>
                    <Typography.Paragraph type="secondary" style={{ marginBottom: 4 }}>
                      {model.providerName || '未知供应商'} · {modelCapabilityText(model)}
                    </Typography.Paragraph>
                    <Typography.Text>{modelPriceText(model)}</Typography.Text>
                  </Card></Col>)}
                </Row>
                <Form.Item name="systemPrompt" label="系统 Prompt" rules={[{ required: true, message: '请输入系统 Prompt' }]}>
                  <Input.TextArea rows={12} maxLength={100000} showCount />
                </Form.Item>
                <Tabs items={[
                  { key: 'input', label: '输入 Schema', children: <Form.Item name="inputSchemaJson"
                    rules={[{ validator: (_, value) => validateSkillSchema(value) }]}><SchemaFieldEditor /></Form.Item> },
                  { key: 'output', label: '输出 Schema', children: <Form.Item name="outputSchemaJson"
                    rules={[{ validator: (_, value) => validateSkillSchema(value) }]}><SchemaFieldEditor /></Form.Item> },
                  { key: 'definition', label: '定义 JSON', children: <Form.Item name="definitionJson"
                    rules={[{ validator: (_, value) => {
                      const errorMessage = value?.trim() ? parseJson(value, '定义 JSON', true) : undefined;
                      return errorMessage ? Promise.reject(new Error(errorMessage)) : Promise.resolve();
                    } }]}><Input.TextArea rows={16} /></Form.Item> }
                ]} />
                <Row gutter={16}>
                  <Col span={8}><Form.Item name="maxOutputTokens" label="输出 Token 上限"
                    rules={[{ required: true }]}><InputNumber min={1} max={1600000} precision={0}
                      style={{ width: '100%' }} /></Form.Item></Col>
                  <Col span={8}><Form.Item name="contextWindowTokens" label="上下文窗口"
                    rules={[{ required: true }]}><InputNumber min={1024} max={1600000} precision={0}
                      style={{ width: '100%' }} /></Form.Item></Col>
                  <Col span={8}><Form.Item name="safetyMarginTokens" label="安全余量"
                    rules={[{ required: true }]}><InputNumber min={0} max={1600000} precision={0}
                      style={{ width: '100%' }} /></Form.Item></Col>
                </Row>
                <Divider orientation="left">参考资源与路由</Divider>
                <Form.Item name="resources" rules={[{ validator: (_, value) => {
                  const resourceError = validateResources(value);
                  return resourceError ? Promise.reject(new Error(resourceError)) : Promise.resolve();
                } }]}><SkillResourceEditor /></Form.Item>
                <Divider orientation="left">固定子 Skill</Divider>
                <Form.List name="relations">{(fields, { add, remove }) => <Space direction="vertical"
                  size={8} style={{ width: '100%' }}>
                  {fields.map(({ key, name }) => <SkillRelationEditor key={key} name={name}
                      parentSkillId={skill!.id} form={form} disabled={!canEdit || packageBusy}
                      skillLabels={relationSkillLabels} versionLabels={relationVersionLabels}
                      onSkillLabel={rememberSkillLabel} onVersionLabel={rememberVersionLabel}
                      onRemove={() => remove(name)} />)}
                  {canEdit && <Button type="dashed" block disabled={packageBusy || fields.length >= 16}
                    onClick={() => add({ requiredFlag: true })}>添加子 Skill（{fields.length}/16）</Button>}
                  <Typography.Text type="secondary">版本发布后关系固定且不可变；服务端仍会校验版本归属、调用范围和可用状态。</Typography.Text>
                </Space>}</Form.List>
              </Form>
              {validation && <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 16 }}>
                <Alert type={validation.valid ? 'success' : 'error'} showIcon
                  message={validation.valid ? '草稿校验通过' : '草稿校验未通过'}
                  description={<Typography.Text>草稿摘要：{validation.draftDigest || '--'}</Typography.Text>} />
                {issueList('错误', validation.errors || [], '#cf1322')}
                {issueList('警告', validation.warnings || [], '#d48806')}
              </Space>}
              {canEdit && <Space wrap style={{ marginTop: 20 }}>
                <Button type="primary" icon={<SaveOutlined />} disabled={packageBusy}
                  loading={saving} onClick={saveDraft}>保存草稿</Button>
                {draft?.draftId && draft.draftDigest && <Popconfirm
                  title="确认放弃当前已保存草稿？"
                  description="该操作无法撤销；已保存草稿和当前尚未保存的编辑都会丢失，成功后将重新读取当前执行版本作为编辑种子。"
                  okText="放弃草稿" okButtonProps={{ danger: true }} cancelText="取消"
                  onConfirm={() => discardDraft()}>
                  <Button danger disabled={packageBusy} loading={discarding}>放弃草稿</Button>
                </Popconfirm>}
                <Button icon={<CheckCircleOutlined />} disabled={packageBusy}
                  loading={validating} onClick={validateDraft}>校验当前内容</Button>
                <Button icon={<RocketOutlined />} disabled={packageBusy || !canPublish}
                  onClick={() => setPublishOpen(true)}>发布新版本</Button>
                {!canPublish && <Typography.Text type="secondary">先保存草稿并对已保存摘要完成校验</Typography.Text>}
              </Space>}
            </>
          },
          {
            key: 'versions', label: `当前与历史（${versionTotal}）`, children: <Table
              rowKey="id" loading={versionLoading} dataSource={versions} columns={versionColumns}
              pagination={{ current: versionPageNum, pageSize: versionPageSize, total: versionTotal,
                showSizeChanger: true, showTotal: (total) => `共 ${total} 个版本`,
                onChange: (pageNum, pageSize) => {
                  versionPageRef.current = { pageNum, pageSize };
                  setVersionPageNum(pageNum);
                  setVersionPageSize(pageSize);
                  loadVersionPage(pageNum, pageSize);
                } }}
              scroll={{ x: 900 }} locale={{ emptyText: '暂无已发布版本' }} />
          }
        ]} />
      </Spin>
    </Drawer>

    <Modal title="发布不可变版本" open={publishOpen} onCancel={() => setPublishOpen(false)}
      onOk={publishDraft} confirmLoading={publishing} okButtonProps={{ disabled: packageBusy }}
      cancelButtonProps={{ disabled: packageBusy }} okText="发布（暂不切换）" destroyOnClose>
      <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="发布后内容不可修改"
        description="发布只创建版本，不会自动改变当前执行版本。" />
      <Form form={publishForm} layout="vertical">
        <Form.Item name="versionCode" label="版本号" rules={[
          { required: true, message: '请输入版本号' },
          { pattern: SEMVER_PATTERN, message: '请输入严格的 SemVer 2.0.0 版本号' }
        ]}><Input placeholder="例如 1.2.0" maxLength={64} /></Form.Item>
      </Form>
    </Modal>

    <Drawer width={980} open={!!versionDetail} onClose={() => setVersionDetail(undefined)}
      title={versionDetail ? `${versionDetail.skillCode} @ ${versionDetail.versionCode}` : '版本详情'}>
      {versionDetail && <>
        <Descriptions bordered size="small" column={3} items={[
          { key: 'current', label: '当前版本', children: versionDetail.id === currentVersionId
            ? <Tag color="success">是</Tag> : '否' },
          { key: 'status', label: '状态', children: versionDetail.status === '0' ? '启用' : '停用' },
          { key: 'publish', label: '发布范围', children: versionDetail.publishStatus },
          { key: 'model', label: '默认模型', children: versionDetail.defaultModelCode || versionDetail.modelCode },
          { key: 'models', label: '可选模型', span: 2, children: <Space wrap>
            {(versionDetail.selectableModelCodes?.length ? versionDetail.selectableModelCodes
              : [versionDetail.modelCode]).map((code) => <Tag key={code}
                color={code === versionDetail.defaultModelCode ? 'blue' : undefined}>{code}</Tag>)}
          </Space> },
          { key: 'scope', label: '调用范围', children: versionDetail.invocationScope },
          { key: 'executor', label: '执行器', children: versionDetail.executorType },
          { key: 'digest', label: '包摘要', span: 3, children: <Typography.Text copyable>
            {versionDetail.packageDigest}</Typography.Text> }
        ]} />
        <Divider orientation="left">系统 Prompt</Divider>
        <Typography.Paragraph copyable style={{ whiteSpace: 'pre-wrap', maxHeight: 360, overflow: 'auto' }}>
          {versionDetail.systemPrompt}
        </Typography.Paragraph>
        <Divider orientation="left">Schema 与定义</Divider>
        <Tabs items={[
          { key: 'input', label: '输入 Schema', children: <Input.TextArea readOnly rows={14}
            value={versionDetail.inputSchemaJson} /> },
          { key: 'output', label: '输出 Schema', children: <Input.TextArea readOnly rows={14}
            value={versionDetail.outputSchemaJson} /> },
          { key: 'definition', label: '定义 JSON', children: <Input.TextArea readOnly rows={14}
            value={versionDetail.definitionJson || ''} /> },
          { key: 'manifest', label: 'Manifest', children: <Input.TextArea readOnly rows={14}
            value={versionDetail.manifestJson} /> }
        ]} />
        <Divider orientation="left">不可变资源</Divider>
        <SkillResourceEditor disabled value={(versionDetail.resources || []).map((resource) => ({
          ...resource, content: resource.content || '', routeJson: resource.routeJson || '{}'
        }))} />
        <Divider orientation="left">固定子 Skill</Divider>
        {readonlyRelations(versionDetail.relations || [])}
      </>}
    </Drawer>
  </>;
}

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Card, Tabs, Table, Button, Space, Tag, Modal, Form, Select, message, Popconfirm, Typography
} from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getAgentMatrix, getPoolOptions, saveMatrixCell, deleteMatrixCell } from '@/api/aid/genagentpool';
import { listModelByFunc } from '@/api/aid/aimanage';
import { useAuth } from '@/hooks/useAuth';
import PageHeader from '@/components/PageHeader';
import SectionTitle from '@/components/SectionTitle';

const { Text } = Typography;

/** 通配维度值（非分镜场景不随创作模式/剧本类型变） */
const WILDCARD = '*';

/** 步骤元数据：storyboard=随创作模式×剧本类型变；fixed=固定业务场景、通配维度 */
interface SceneOpt { value: string; label: string }
interface ModelOption {
  value: string;
  label: string;
  sizeOptions?: string[];
  aspectRatioOptions?: string[];
  defaultSize?: string;
  defaultAspectRatio?: string;
  supportsSizePreset?: boolean;
  supportsAspectRatio?: boolean;
}
interface StepMeta {
  value: string;
  label: string;
  kind: 'storyboard' | 'fixed';
  image?: boolean;          // 是否图片场景（需清晰度/比例）
  scenes?: SceneOpt[];      // fixed 步骤下的业务场景列表
}
const STEPS: StepMeta[] = [
  { value: 'script', label: '分镜脚本', kind: 'storyboard' },
  { value: 'stylist', label: '分镜图提示词', kind: 'storyboard' },
  { value: 'video_prompt', label: '分镜视频提示词', kind: 'storyboard' },
  {
    value: 'extract', label: '资产提取', kind: 'fixed', image: false, scenes: [
      { value: 'main_character_extract', label: '角色提取' },
      { value: 'main_scene_extract', label: '场景提取' },
      { value: 'main_prop_extract', label: '道具提取' }
    ]
  },
  {
    value: 'form', label: '资产形态', kind: 'fixed', image: false, scenes: [
      { value: 'main_character_form', label: '角色形态' },
      { value: 'main_scene_form', label: '场景形态' },
      { value: 'main_prop_form', label: '道具形态' }
    ]
  },
  {
    value: 'asset_image', label: '资产生图', kind: 'fixed', image: true, scenes: [
      { value: 'main_character_image', label: '角色图' },
      { value: 'main_scene_image', label: '场景图' },
      { value: 'main_prop_image', label: '道具图' },
      { value: 'main_character_card_image', label: '角色设定卡' }
    ]
  },
  {
    value: 'storyboard_image', label: '分镜生图', kind: 'fixed', image: true, scenes: [
      { value: 'main_storyboard_image', label: '分镜生图' }
    ]
  }
];

/** 创作模式 */
const CREATION_MODE_OPTIONS = [
  { label: '图生(i2v)', value: 'i2v' },
  { label: '多参(multi)', value: 'multi' },
  { label: '专业(pro)', value: 'pro' },
  { label: '宫格(auto_grid)', value: 'auto_grid' }
];
/** 剧本类型 */
const SCRIPT_TYPE_OPTIONS = [
  { label: '剧情演绎', value: 'plot' },
  { label: '真人解说', value: 'monologue' }
];
/** 仅剧情演绎支持的创作模式（专业/宫格不支持真人解说） */
const PLOT_ONLY_MODES = ['pro', 'auto_grid'];

/** 项目生成场景对应 capability.sceneRules 键；这里只映射场景，不维护任何规格枚举。 */
function capabilitySceneOf(biz: string): 'textToImage' | 'imageToImage' {
  return biz === 'main_character_card_image' || biz === 'main_storyboard_image'
    ? 'imageToImage'
    : 'textToImage';
}

/** 兼容生成池旧接口：缺少能力字段时，从现有模型池接口的 capability 动态补齐。 */
function mergeModelCapability(option: any, detail: any, biz: string): ModelOption {
  if (Object.prototype.hasOwnProperty.call(option, 'sizeOptions')
    || Object.prototype.hasOwnProperty.call(option, 'aspectRatioOptions')) {
    return option as ModelOption;
  }
  const capability = detail?.capability || {};
  const sceneRule = capability.sceneRules?.[capabilitySceneOf(biz)] || {};
  const sceneSizes = Array.isArray(sceneRule.sizeOptions) ? sceneRule.sizeOptions : [];
  const sceneAspects = Array.isArray(sceneRule.aspectRatioOptions) ? sceneRule.aspectRatioOptions : [];
  const topSizes = Array.isArray(capability.sizeOptions) ? capability.sizeOptions : [];
  const topAspects = Array.isArray(capability.aspectRatioOptions) ? capability.aspectRatioOptions : [];
  return {
    ...option,
    sizeOptions: sceneSizes.length ? sceneSizes : topSizes,
    aspectRatioOptions: sceneAspects.length ? sceneAspects : topAspects,
    defaultSize: sceneRule.defaultSize || capability.defaultSize || detail?.defaultSizeCode,
    defaultAspectRatio: sceneRule.defaultAspectRatio || capability.defaultAspectRatio || detail?.defaultAspectRatio,
    supportsSizePreset: typeof sceneRule.supportsSizePreset === 'boolean'
      ? sceneRule.supportsSizePreset
      : detail?.supportsSizePreset,
    supportsAspectRatio: typeof sceneRule.supportsAspectRatio === 'boolean'
      ? sceneRule.supportsAspectRatio
      : detail?.supportsAspectRatio
  };
}

const labelOf = (opts: { label: string; value: string }[], v: string) =>
  opts.find((o) => o.value === v)?.label || v || '--';

/** storyboard 步骤：由步骤+创作模式推导业务场景 biz */
function deriveStoryboardBiz(step: string, creationMode: string): string {
  if (step === 'script') return 'main_storyboard_script';
  if (step === 'stylist') return 'main_storyboard_stylist';
  if (creationMode === 'i2v') return 'main_storyboard_video_prompt_image';
  if (creationMode === 'auto_grid') return 'main_storyboard_video_prompt_grid';
  return 'main_storyboard_video_prompt'; // multi / pro
}

export default function GenAgentMatrix() {
  const { hasPermi } = useAuth();
  const canEdit = hasPermi('aid:genagentpool:edit');
  const canRemove = hasPermi('aid:genagentpool:remove');

  const [activeStep, setActiveStep] = useState('script');
  const [loading, setLoading] = useState(false);
  const [cells, setCells] = useState<any[]>([]);

  // 编辑弹窗
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [optionsLoading, setOptionsLoading] = useState(false);
  const [agentOpts, setAgentOpts] = useState<any[]>([]);
  const [modelOpts, setModelOpts] = useState<ModelOption[]>([]);
  const [form] = Form.useForm();
  const watchMode = Form.useWatch('creationMode', form);
  const watchBiz = Form.useWatch('bizCategoryCode', form);
  const watchEconomyModel = Form.useWatch('economyModel', form);
  const watchPerformanceModel = Form.useWatch('performanceModel', form);

  const stepMeta = useMemo(() => STEPS.find((s) => s.value === activeStep) as StepMeta, [activeStep]);
  const isFixed = stepMeta.kind === 'fixed';
  const isImage = !!stepMeta.image;

  const loadMatrix = useCallback(() => {
    setLoading(true);
    getAgentMatrix()
      .then((res: any) => setCells(res.data || []))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { loadMatrix(); }, [loadMatrix]);

  // 当前步骤下的格子
  const stepCells = useMemo(
    () => cells.filter((c) => c.step === activeStep),
    [cells, activeStep]
  );

  // 当前编辑场景的 biz：fixed 直接取所选 biz；storyboard 随步骤+创作模式联动
  const currentBiz = useMemo(
    () => (isFixed ? watchBiz : deriveStoryboardBiz(activeStep, watchMode)),
    [isFixed, watchBiz, activeStep, watchMode]
  );

  // 弹窗内 biz 变化时加载可选智能体/模型（带竞态守卫：biz 快速切换时丢弃过期响应）
  useEffect(() => {
    if (!modalOpen || !currentBiz) return;
    let stale = false;
    setAgentOpts([]);
    setModelOpts([]);
    setOptionsLoading(true);
    Promise.all([
      getPoolOptions(currentBiz),
      listModelByFunc(currentBiz).catch(() => ({ data: [] }))
    ])
      .then(([res, modelRes]: any[]) => {
        if (stale) return;
        setAgentOpts((res.data?.agents || []).map((o: any) => ({ label: o.label, value: o.value })));
        const details = Array.isArray(modelRes?.data) ? modelRes.data : [];
        const detailByCode = new Map(details.map((model: any) => [model.modelCode, model]));
        setModelOpts((res.data?.models || []).map((o: any) =>
          mergeModelCapability({ ...o, label: o.label, value: o.value }, detailByCode.get(o.value), currentBiz)
        ));
      })
      .finally(() => { if (!stale) setOptionsLoading(false); });
    return () => { stale = true; };
  }, [modalOpen, currentBiz]);

  const economyModelOption = useMemo(
    () => modelOpts.find((model) => model.value === watchEconomyModel),
    [modelOpts, watchEconomyModel]
  );
  const performanceModelOption = useMemo(
    () => modelOpts.find((model) => model.value === watchPerformanceModel),
    [modelOpts, watchPerformanceModel]
  );

  const toSelectOptions = (values?: string[]) =>
    (values || []).map((value) => ({ label: value, value }));

  /** 模型切换后仅保留新模型支持的值，否则回退模型默认项或能力列表首项。 */
  const applyModelCapabilities = useCallback((strategy: 'economy' | 'performance', modelCode?: string) => {
    if (!modelCode) {
      form.setFieldsValue({
        [`${strategy}Resolution`]: undefined,
        [`${strategy}AspectRatio`]: undefined
      });
      return;
    }
    const model = modelOpts.find((item) => item.value === modelCode);
    if (!model) return;

    const resolutionField = `${strategy}Resolution`;
    const aspectRatioField = `${strategy}AspectRatio`;
    const sizeOptions = model.supportsSizePreset === false ? [] : (model.sizeOptions || []);
    const aspectOptions = model.supportsAspectRatio === false ? [] : (model.aspectRatioOptions || []);
    const currentResolution = form.getFieldValue(resolutionField);
    const currentAspectRatio = form.getFieldValue(aspectRatioField);
    const nextResolution = sizeOptions.includes(currentResolution)
      ? currentResolution
      : (sizeOptions.includes(model.defaultSize || '') ? model.defaultSize : sizeOptions[0]);
    const nextAspectRatio = aspectOptions.includes(currentAspectRatio)
      ? currentAspectRatio
      : (aspectOptions.includes(model.defaultAspectRatio || '') ? model.defaultAspectRatio : aspectOptions[0]);
    form.setFieldsValue({
      [resolutionField]: nextResolution,
      [aspectRatioField]: nextAspectRatio
    });
  }, [form, modelOpts]);

  // 编辑旧配置或切换业务场景后，按接口返回的模型能力自动修复已失效选项。
  useEffect(() => {
    if (!modalOpen || !isImage || optionsLoading || modelOpts.length === 0) return;
    applyModelCapabilities('economy', watchEconomyModel);
    applyModelCapabilities('performance', watchPerformanceModel);
  }, [modalOpen, isImage, optionsLoading, modelOpts, watchEconomyModel,
    watchPerformanceModel, applyModelCapabilities]);

  // 智能体下拉选项：在"该 biz 启用智能体"基础上，补上当前格子已保存的智能体，
  // 确保即使某智能体停用/biz 调整，编辑时仍能看到并重选自己原本配置的值（修复"选不到自己的"）。
  const watchAll = Form.useWatch([], form) || {};
  const agentSelectOpts = useMemo(() => {
    const seen = new Set(agentOpts.map((o: any) => o.value));
    const extra: any[] = [];
    const saved = [
      watchAll.economyAgent,
      watchAll.performanceAgent,
      ...(Array.isArray(watchAll.extraPoolAgents) ? watchAll.extraPoolAgents : [])
    ];
    saved.forEach((code: string) => {
      if (code && !seen.has(code)) {
        seen.add(code);
        extra.push({ label: code, value: code });
      }
    });
    return extra.length ? [...agentOpts, ...extra] : agentOpts;
  }, [agentOpts, watchAll.economyAgent, watchAll.performanceAgent, watchAll.extraPoolAgents]);

  const openEdit = (cell: any | null) => {
    if (cell) {
      const extra = (cell.poolAgents || []).filter(
        (a: string) => a !== cell.economyAgent && a !== cell.performanceAgent
      );
      form.setFieldsValue({
        bizCategoryCode: cell.bizCategoryCode,
        creationMode: cell.creationMode,
        scriptType: cell.scriptType,
        economyAgent: cell.economyAgent,
        economyModel: cell.economyModel,
        economyResolution: cell.economyResolution,
        economyAspectRatio: cell.economyAspectRatio,
        performanceAgent: cell.performanceAgent,
        performanceModel: cell.performanceModel,
        performanceResolution: cell.performanceResolution,
        performanceAspectRatio: cell.performanceAspectRatio,
        extraPoolAgents: extra
      });
    } else {
      form.resetFields();
      if (isFixed) {
        form.setFieldsValue({
          bizCategoryCode: stepMeta.scenes?.[0]?.value,
          creationMode: WILDCARD,
          scriptType: WILDCARD
        });
      } else {
        form.setFieldsValue({ creationMode: 'i2v', scriptType: 'plot' });
      }
    }
    setModalOpen(true);
  };

  const handleSave = () => {
    form.validateFields().then((vals) => {
      const biz = isFixed ? vals.bizCategoryCode : deriveStoryboardBiz(activeStep, vals.creationMode);
      const payload: any = {
        step: activeStep,
        bizCategoryCode: biz,
        creationMode: isFixed ? WILDCARD : vals.creationMode,
        scriptType: isFixed ? WILDCARD : vals.scriptType,
        economyAgent: vals.economyAgent || null,
        economyModel: vals.economyModel || null,
        performanceAgent: vals.performanceAgent || null,
        performanceModel: vals.performanceModel || null,
        extraPoolAgents: vals.extraPoolAgents || []
      };
      if (isImage) {
        payload.economyResolution = vals.economyResolution || null;
        payload.economyAspectRatio = vals.economyAspectRatio || null;
        payload.performanceResolution = vals.performanceResolution || null;
        payload.performanceAspectRatio = vals.performanceAspectRatio || null;
      }
      setSaving(true);
      saveMatrixCell(payload)
        .then(() => {
          message.success('保存成功');
          setModalOpen(false);
          loadMatrix();
        })
        .finally(() => setSaving(false));
    });
  };

  const handleDelete = (cell: any) => {
    deleteMatrixCell({
      step: cell.step,
      bizCategoryCode: cell.bizCategoryCode,
      creationMode: cell.creationMode,
      scriptType: cell.scriptType
    }).then(() => {
      message.success('删除成功');
      loadMatrix();
    });
  };

  const sceneLabelOf = (biz: string) =>
    stepMeta.scenes?.find((s) => s.value === biz)?.label || biz;

  const renderDefault = (agent?: string, model?: string, resolution?: string, aspect?: string) => {
    if (!agent) return <Text type="secondary">未配置</Text>;
    return (
      <div>
        <div>{agent}</div>
        <Text type="secondary" style={{ fontSize: 12 }}>
          模型：{model || '默认'}{isImage ? ` · ${resolution || '--'} · ${aspect || '--'}` : ''}
        </Text>
      </div>
    );
  };

  const columns: ColumnsType<any> = useMemo(() => {
    const cols: ColumnsType<any> = [];
    if (isFixed) {
      cols.push({
        title: '业务场景', dataIndex: 'bizCategoryCode', width: 200,
        render: (v: string) => <Tag color="geekblue">{sceneLabelOf(v)}</Tag>
      });
    } else {
      cols.push(
        {
          title: '创作模式', dataIndex: 'creationMode', width: 120,
          render: (v: string) => <Tag color="geekblue">{labelOf(CREATION_MODE_OPTIONS, v)}</Tag>
        },
        {
          title: '剧本类型', dataIndex: 'scriptType', width: 110,
          render: (v: string) => <Tag color="cyan">{labelOf(SCRIPT_TYPE_OPTIONS, v)}</Tag>
        }
      );
    }
    cols.push(
      {
        title: '业务编码', dataIndex: 'bizCategoryCode', width: 240, ellipsis: true,
        render: (v: string) => <Text type="secondary" style={{ fontSize: 12 }}>{v}</Text>
      },
      {
        title: '经济默认', key: 'eco', width: 260,
        render: (_: any, r: any) => renderDefault(r.economyAgent, r.economyModel, r.economyResolution, r.economyAspectRatio)
      },
      {
        title: '性能默认', key: 'perf', width: 260,
        render: (_: any, r: any) => renderDefault(r.performanceAgent, r.performanceModel, r.performanceResolution, r.performanceAspectRatio)
      },
      {
        title: '可选池', dataIndex: 'poolAgents', key: 'pool',
        render: (pool: string[]) => (
          <Space size={[4, 4]} wrap>
            {(pool || []).map((a) => <Tag key={a}>{a}</Tag>)}
          </Space>
        )
      },
      {
        title: '操作', key: 'op', width: 130, fixed: 'right',
        render: (_: any, r: any) => (
          <Space size={4}>
            {canEdit && <Button type="link" size="small" onClick={() => openEdit(r)}>编辑</Button>}
            {canRemove && (
              <Popconfirm title="确认删除该组合配置？" onConfirm={() => handleDelete(r)}>
                <Button type="link" size="small" danger>删除</Button>
              </Popconfirm>
            )}
          </Space>
        )
      }
    );
    return cols;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isFixed, isImage, stepMeta, canEdit, canRemove]);

  // 专业/宫格仅支持剧情演绎：动态过滤剧本类型选项
  const scriptTypeOptionsForMode = useMemo(() => {
    if (PLOT_ONLY_MODES.includes(watchMode)) {
      return SCRIPT_TYPE_OPTIONS.filter((o) => o.value === 'plot');
    }
    return SCRIPT_TYPE_OPTIONS;
  }, [watchMode]);

  return (
    <div className="crud-page">
      <PageHeader
        title="生成智能体池"
        desc={isFixed
          ? '资产/分镜生图场景不随创作模式变；每个业务场景配经济/性能默认智能体与模型' + (isImage ? '（含清晰度/比例）' : '') + '，可加额外候选进可选池。'
          : '每个「创作模式 × 剧本类型」是一个组合；经济/性能各选默认智能体与模型，可再加额外候选进可选池。'}
      />
      <Tabs
        activeKey={activeStep}
        onChange={setActiveStep}
        items={STEPS.map((s) => ({ key: s.value, label: s.label }))}
      />
      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            {canEdit && (
              <Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit(null)}>新增组合</Button>
            )}
          </Space>
          <div className="crud-page__stats">
            <span>共 {stepCells.length} 条</span>
          </div>
        </div>
        <Table
          rowKey={(r) => `${r.bizCategoryCode}|${r.creationMode}|${r.scriptType}`}
          size="middle"
          loading={loading}
          columns={columns}
          dataSource={stepCells}
          pagination={false}
          scroll={{ x: 'max-content' }}
        />
      </Card>

      <Modal
        title={`配置组合 · ${stepMeta.label}`}
        open={modalOpen}
        width={700}
        onCancel={() => setModalOpen(false)}
        onOk={handleSave}
        confirmLoading={saving}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 8 }}>
          {isFixed ? (
            <Form.Item label="业务场景" name="bizCategoryCode" rules={[{ required: true, message: '请选择业务场景' }]}>
              <Select
                options={stepMeta.scenes}
                onChange={() => form.setFieldsValue({
                  economyAgent: undefined, economyModel: undefined,
                  performanceAgent: undefined, performanceModel: undefined, extraPoolAgents: []
                })}
              />
            </Form.Item>
          ) : (
            <>
              <Form.Item label="创作模式" name="creationMode" rules={[{ required: true, message: '请选择创作模式' }]}>
                <Select
                  options={CREATION_MODE_OPTIONS}
                  onChange={() => {
                    form.setFieldsValue({ economyAgent: undefined, economyModel: undefined, performanceAgent: undefined, performanceModel: undefined, extraPoolAgents: [] });
                    if (PLOT_ONLY_MODES.includes(form.getFieldValue('creationMode'))) {
                      form.setFieldsValue({ scriptType: 'plot' });
                    }
                  }}
                />
              </Form.Item>
              <Form.Item label="剧本类型" name="scriptType" rules={[{ required: true, message: '请选择剧本类型' }]}>
                <Select options={scriptTypeOptionsForMode} />
              </Form.Item>
              <Form.Item label="业务场景">
                <Text code>{currentBiz}</Text>
                <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>（按步骤+创作模式自动确定）</Text>
              </Form.Item>
            </>
          )}

          <SectionTitle title="经济模式默认" />
          <Form.Item label="经济·智能体" name="economyAgent">
            <Select allowClear showSearch optionFilterProp="label" loading={optionsLoading} options={agentSelectOpts} placeholder="该场景下可选智能体" />
          </Form.Item>
          <Form.Item label="经济·模型" name="economyModel">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              loading={optionsLoading}
              options={modelOpts}
              placeholder="留空走智能体默认模型"
              onChange={(value) => applyModelCapabilities('economy', value)}
            />
          </Form.Item>
          {isImage && (
            <Form.Item label="经济·清晰度/比例">
              <Space>
                <Form.Item name="economyResolution" noStyle>
                  <Select
                    style={{ width: 140 }}
                    options={toSelectOptions(economyModelOption?.sizeOptions)}
                    disabled={!economyModelOption || economyModelOption.supportsSizePreset === false}
                    placeholder={economyModelOption ? '选择清晰度' : '请先选择模型'}
                  />
                </Form.Item>
                <Form.Item name="economyAspectRatio" noStyle>
                  <Select
                    style={{ width: 140 }}
                    options={toSelectOptions(economyModelOption?.aspectRatioOptions)}
                    disabled={!economyModelOption || economyModelOption.supportsAspectRatio === false}
                    placeholder={economyModelOption?.supportsAspectRatio === false ? '模型不支持比例' : '选择比例'}
                  />
                </Form.Item>
              </Space>
            </Form.Item>
          )}
          <SectionTitle title="性能模式默认" />
          <Form.Item label="性能·智能体" name="performanceAgent">
            <Select allowClear showSearch optionFilterProp="label" loading={optionsLoading} options={agentSelectOpts} placeholder="该场景下可选智能体" />
          </Form.Item>
          <Form.Item label="性能·模型" name="performanceModel">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              loading={optionsLoading}
              options={modelOpts}
              placeholder="留空走智能体默认模型"
              onChange={(value) => applyModelCapabilities('performance', value)}
            />
          </Form.Item>
          {isImage && (
            <Form.Item label="性能·清晰度/比例">
              <Space>
                <Form.Item name="performanceResolution" noStyle>
                  <Select
                    style={{ width: 140 }}
                    options={toSelectOptions(performanceModelOption?.sizeOptions)}
                    disabled={!performanceModelOption || performanceModelOption.supportsSizePreset === false}
                    placeholder={performanceModelOption ? '选择清晰度' : '请先选择模型'}
                  />
                </Form.Item>
                <Form.Item name="performanceAspectRatio" noStyle>
                  <Select
                    style={{ width: 140 }}
                    options={toSelectOptions(performanceModelOption?.aspectRatioOptions)}
                    disabled={!performanceModelOption || performanceModelOption.supportsAspectRatio === false}
                    placeholder={performanceModelOption?.supportsAspectRatio === false ? '模型不支持比例' : '选择比例'}
                  />
                </Form.Item>
              </Space>
            </Form.Item>
          )}
          <Form.Item label="额外候选池" name="extraPoolAgents" tooltip="除经济/性能默认外，额外允许用户选择的智能体">
            <Select mode="multiple" allowClear showSearch optionFilterProp="label" loading={optionsLoading} options={agentSelectOpts} placeholder="可选，多选" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

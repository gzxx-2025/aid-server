import React, { useEffect, useRef, useState } from 'react';
import { Avatar, Button, Card, Form, Input, Popconfirm, Select, Space, Switch, Table, Tag, Tooltip, message } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined, SoundOutlined, UserOutlined } from '@ant-design/icons';
import {
  listVoiceLibrary, getVoiceLibrary, addVoiceLibrary, updateVoiceLibrary,
  updateVoiceLibraryStatus, delVoiceLibrary,
  listProviderOptions, listAudioModels, listVoiceTag
} from '@/api/aid/voicelibrary';
import { LANGUAGE_OPTIONS, GENDER_OPTIONS, AGE_RANGE_OPTIONS, resolveEnumLabel, resolveEmotionLabel, isNeverOffline, isAlreadyOffline, isOfflineSoon } from './constants';
import VoiceFormDialog from './VoiceFormDialog';
import Auth from '@/components/Auth';
import { parseTime } from '@/utils/ruoyi';

export default function VoiceLibraryPage() {
  const [loading, setLoading] = useState(false);
  const [list, setList] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<any>({ pageNum: 1, pageSize: 10 });
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [providerOpts, setProviderOpts] = useState<any[]>([]);
  const [modelOpts, setModelOpts] = useState<any[]>([]);
  const [tagDict, setTagDict] = useState<any>({ characterTypes: [], voiceStyles: [], toneTags: [] });
  const [dlg, setDlg] = useState<{ open: boolean; title: string; data?: any }>({ open: false, title: '' });
  const [playingId, setPlayingId] = useState<any>(null);
  const audioRef = useRef<HTMLAudioElement>(null);
  const [searchForm] = Form.useForm();

  useEffect(() => {
    listProviderOptions({ pageSize: 200 }).then((r: any) => setProviderOpts(r.rows || r.data || []));
    listAudioModels({}).then((r: any) => setModelOpts(r.rows || r.data || []));
    ['character_type', 'voice_style', 'tone'].forEach((tagType) => {
      listVoiceTag({ pageNum: 1, pageSize: 500, tagType, status: '0' }).then((r: any) => {
        const key = tagType === 'character_type' ? 'characterTypes' : tagType === 'voice_style' ? 'voiceStyles' : 'toneTags';
        setTagDict((prev: any) => ({ ...prev, [key]: r.rows || r.data || [] }));
      });
    });
    // 擅长情感候选不再走全局配置：以供应商声明为标准，由 VoiceFormDialog 随所选模型 capabilityJson 解析
  }, []);

  const loadList = async () => {
    setLoading(true);
    try {
      const res: any = await listVoiceLibrary(query);
      setList(res.rows || res.data || []);
      setTotal(res.total || 0);
    } finally { setLoading(false); }
  };
  useEffect(() => { loadList(); }, [query]);

  const togglePlay = (row: any) => {
    if (!row?.sampleUrl || !audioRef.current) return;
    if (playingId === row.id) { audioRef.current.pause(); audioRef.current.currentTime = 0; setPlayingId(null); return; }
    audioRef.current.src = row.sampleUrl;
    setPlayingId(row.id);
    audioRef.current.play().catch(() => { setPlayingId(null); message.warning('试听失败'); });
  };

  const resolveTagName = (type: string, code: string) => {
    const src = type === 'character_type' ? tagDict.characterTypes : type === 'voice_style' ? tagDict.voiceStyles : tagDict.toneTags;
    return (src || []).find((t: any) => t.tagCode === code)?.tagName || code;
  };

  /** 兼容后端返回 JSON 字符串 / 非数组：统一成字符串数组 */
  const normTags = (v: any): string[] => {
    if (Array.isArray(v)) return v.filter((x) => x !== null && x !== undefined && typeof x !== 'boolean').map((x) => String(x));
    if (v === null || v === undefined || v === '') return [];
    if (typeof v === 'string') {
      const s = v.trim();
      if (s.startsWith('[') && s.endsWith(']')) {
        try {
          const parsed = JSON.parse(s);
          return Array.isArray(parsed) ? parsed.filter((x) => typeof x !== 'boolean').map((x) => String(x)) : [];
        } catch { return []; }
      }
      return [s];
    }
    return [];
  };

  const CapDot = ({ on, label }: { on?: boolean; label: string }) => (
    <Tooltip title={label}>
      <span style={{ width: 22, height: 22, borderRadius: '50%', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, background: on ? '#2563eb' : '#f1f5f9', color: on ? '#fff' : '#c0c4cc', border: `1px solid ${on ? '#2563eb' : '#e5e7eb'}` }}>{label.charAt(0)}</span>
    </Tooltip>
  );

  const columns: any[] = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '音色', key: 'voice', width: 260, render: (_: any, r: any) => (
      <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
        <Avatar src={r.avatarUrl} icon={<UserOutlined />} size={40} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 600, fontSize: 13 }}>
            {r.voiceName}
            <Tag style={{ marginLeft: 4 }}>{resolveEnumLabel('language', r.language)}</Tag>
            <Tag color={r.gender === 'female' ? 'magenta' : r.gender === 'male' ? 'blue' : 'default'}>{resolveEnumLabel('gender', r.gender)}</Tag>
            <Tag>{resolveEnumLabel('ageRange', r.ageRange)}</Tag>
          </div>
          <code style={{ fontSize: 11, color: '#94a3b8' }}>{r.voiceCode}</code>
        </div>
      </div>
    ) },
    { title: '归属', key: 'owner', width: 180, render: (_: any, r: any) => (<div style={{ fontSize: 12 }}><div>{r.providerName || '-'}</div><div style={{ color: '#94a3b8' }}>{r.modelName || '-'}</div></div>) },
    { title: '标签', key: 'tags', width: 280, render: (_: any, r: any) => {
      const tags: React.ReactNode[] = [];
      normTags(r.characterTypes).forEach((t: string) => tags.push(<Tag key={'ct-' + t}>{resolveTagName('character_type', t)}</Tag>));
      normTags(r.voiceStyles).forEach((t: string) => tags.push(<Tag key={'vs-' + t} color="orange">{resolveTagName('voice_style', t)}</Tag>));
      normTags(r.toneTags).forEach((t: string) => tags.push(<Tag key={'tn-' + t} color="green">{resolveTagName('tone', t)}</Tag>));
      normTags(r.emotionTags).forEach((t: string) => tags.push(<Tag key={'em-' + t} color="red">{resolveEmotionLabel(t)}</Tag>));
      return tags.length ? <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>{tags}</div> : <span style={{ color: '#c0c4cc' }}>未设标签</span>;
    } },
    { title: '能力', key: 'cap', width: 120, align: 'center' as const, render: (_: any, r: any) => (<Space size={4}><CapDot on={r.supportsEmotion} label="情感" /><CapDot on={r.supportsSpeed} label="语速" /><CapDot on={r.supportsPitch} label="音调" /></Space>) },
    { title: '试听', key: 'play', width: 90, align: 'center' as const, render: (_: any, r: any) => r.sampleUrl ? <Button size="small" type={playingId === r.id ? 'primary' : 'default'} icon={<SoundOutlined />} onClick={() => togglePlay(r)}>{playingId === r.id ? '播放中' : '试听'}</Button> : '-' },
    { title: '状态', dataIndex: 'status', width: 80, render: (v: string, r: any) => <Switch checked={v === '0'} onChange={async (c) => { await updateVoiceLibraryStatus({ id: r.id, status: c ? '0' : '1' }); message.success(c ? '已启用' : '已停用'); loadList(); }} /> },
    { title: '下架时间', dataIndex: 'offlineTime', width: 140, render: (v: string) => isNeverOffline(v) ? <span style={{ color: '#c0c4cc' }}>永不下架</span> : isAlreadyOffline(v) ? <Tag color="red">已下架</Tag> : isOfflineSoon(v) ? <span style={{ color: '#f59e0b' }}>{parseTime(v, 'YYYY-MM-DD HH:mm')}</span> : (parseTime(v, 'YYYY-MM-DD HH:mm') || '-') },
    { title: '排序', dataIndex: 'sortOrder', width: 60 },
    { title: '操作', key: 'ops', width: 140, fixed: 'right' as const, render: (_: any, r: any) => (
      <Space size={0}>
        <Auth permission="aid:voice-library:edit"><Button type="link" size="small" icon={<EditOutlined />} onClick={async () => { const res: any = await getVoiceLibrary(r.id); setDlg({ open: true, title: '修改音色', data: res.data || res }); }}>修改</Button></Auth>
        <Auth permission="aid:voice-library:remove"><Popconfirm title="确认删除？" onConfirm={async () => { await delVoiceLibrary(r.id); message.success('删除成功'); loadList(); }}><Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm></Auth>
      </Space>
    ) }
  ];

  return (
    <div className="crud-page">
      <Card className="page-card" bordered={false}>
        <Form form={searchForm} layout="inline" onFinish={(v) => setQuery({ ...query, ...v, pageNum: 1 })} style={{ rowGap: 8 }}>
          <Form.Item name="providerId" label="服务商"><Select allowClear style={{ width: 160 }} placeholder="全部" options={providerOpts.map((p: any) => ({ label: p.providerName, value: p.id }))} /></Form.Item>
          <Form.Item name="modelId" label="模型"><Select allowClear showSearch optionFilterProp="label" style={{ width: 180 }} placeholder="全部" options={modelOpts.map((m: any) => ({ label: m.modelName, value: m.id }))} /></Form.Item>
          <Form.Item name="language" label="语言"><Select allowClear style={{ width: 100 }} options={LANGUAGE_OPTIONS.map((o) => ({ label: o.name, value: o.code }))} /></Form.Item>
          <Form.Item name="gender" label="性别"><Select allowClear style={{ width: 90 }} options={GENDER_OPTIONS.map((o) => ({ label: o.name, value: o.code }))} /></Form.Item>
          <Form.Item name="ageRange" label="年龄"><Select allowClear style={{ width: 100 }} options={AGE_RANGE_OPTIONS.map((o) => ({ label: o.name, value: o.code }))} /></Form.Item>
          <Form.Item name="status" label="状态"><Select allowClear style={{ width: 90 }} options={[{ label: '启用', value: '0' }, { label: '停用', value: '1' }]} /></Form.Item>
          <Form.Item name="voiceName" label="关键字"><Input allowClear style={{ width: 160 }} placeholder="名称/编码" /></Form.Item>
          <Form.Item><Space><Button type="primary" icon={<SearchOutlined />} htmlType="submit">搜索</Button><Button onClick={() => { searchForm.resetFields(); setQuery({ pageNum: 1, pageSize: 10 }); }}>重置</Button></Space></Form.Item>
        </Form>
      </Card>
      <Card className="page-card" bordered={false}>
        <div className="crud-page__toolbar">
          <Space>
            <Auth permission="aid:voice-library:add"><Button type="primary" icon={<PlusOutlined />} onClick={() => setDlg({ open: true, title: '新增音色' })}>新增音色</Button></Auth>
            <Auth permission="aid:voice-library:remove">
              <Popconfirm
                title={`确认删除选中的 ${selectedKeys.length} 条音色吗？`}
                onConfirm={() => { delVoiceLibrary(selectedKeys.join(',')).then(() => { message.success('删除成功'); loadList(); }); }}
              >
                <Button danger disabled={!selectedKeys.length} icon={<DeleteOutlined />}>批量删除</Button>
              </Popconfirm>
            </Auth>
          </Space>
          <div className="crud-page__stats">
            {selectedKeys.length > 0 && <Tag color="blue">已选 {selectedKeys.length}</Tag>}
            <span>共 {total} 条</span>
          </div>
        </div>
        <Table rowKey="id" size="middle" loading={loading} dataSource={list} columns={columns} scroll={{ x: 1500 }}
          rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
          pagination={{ current: query.pageNum, pageSize: query.pageSize, total, showSizeChanger: true, showTotal: (t) => `共 ${t} 条`, onChange: (p, s) => setQuery({ ...query, pageNum: p, pageSize: s }) }}
        />
      </Card>
      <audio ref={audioRef} style={{ display: 'none' }} onEnded={() => setPlayingId(null)} onError={() => setPlayingId(null)} />
      <VoiceFormDialog
        open={dlg.open} title={dlg.title} data={dlg.data}
        providerOpts={providerOpts} modelOpts={modelOpts} tagDict={tagDict}
        onCancel={() => setDlg({ open: false, title: '' })}
        onOk={async (values: any) => {
          if (values.id) { await updateVoiceLibrary(values); message.success('已更新'); }
          else { await addVoiceLibrary(values); message.success('已新增'); }
          setDlg({ open: false, title: '' }); loadList();
        }}
      />
    </div>
  );
}

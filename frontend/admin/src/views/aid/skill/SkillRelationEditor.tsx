import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Card, Col, Form, Input, Row, Select, Spin, Switch } from 'antd';
import type { FormInstance } from 'antd';

import {
  getSkillDependencyLabels, listSkillDependencyOptions, listSkillDependencyVersionOptions,
  type SkillDependencyOption, type SkillDependencyVersionOption, type SkillPackagePayload
} from '@/api/aid/skill';

interface PagedResult<T> {
  data?: T[];
  total?: number;
}

function useRemotePages<T extends { id?: number; skillId?: number }>(
  enabled: boolean,
  loader: (pageNum: number, keyword?: string) => Promise<PagedResult<T>>
) {
  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [pageNum, setPageNum] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const requestToken = useRef(0);
  const loadingRef = useRef(false);
  const searchTimer = useRef<ReturnType<typeof setTimeout>>();

  const loadPage = useCallback(async (
    nextPage: number, nextKeyword: string, reset: boolean, token: number
  ) => {
    loadingRef.current = true;
    setLoading(true);
    setError('');
    try {
      const response = await loader(nextPage, nextKeyword || undefined);
      if (token !== requestToken.current) return;
      const incoming = response.data || [];
      setItems((current) => {
        if (reset) return incoming;
        const existing = new Set(current.map((item) => item.id ?? item.skillId));
        return [...current, ...incoming.filter((item) => !existing.has(item.id ?? item.skillId))];
      });
      setTotal(Number(response.total || 0));
      setPageNum(nextPage);
    } catch (cause: any) {
      if (token === requestToken.current) setError(cause?.message || '选项加载失败');
    } finally {
      if (token === requestToken.current) {
        loadingRef.current = false;
        setLoading(false);
      }
    }
  }, [loader]);

  useEffect(() => {
    if (searchTimer.current) clearTimeout(searchTimer.current);
    const token = ++requestToken.current;
    setItems([]);
    setTotal(0);
    setPageNum(0);
    setKeyword('');
    setError('');
    loadingRef.current = false;
    setLoading(false);
    if (enabled) void loadPage(1, '', true, token);
    return () => {
      requestToken.current += 1;
      if (searchTimer.current) clearTimeout(searchTimer.current);
    };
  }, [enabled, loadPage]);

  const search = useCallback((value: string) => {
    const nextKeyword = value.trim();
    const token = ++requestToken.current;
    if (searchTimer.current) clearTimeout(searchTimer.current);
    setKeyword(nextKeyword);
    loadingRef.current = true;
    setLoading(true);
    searchTimer.current = setTimeout(() => {
      void loadPage(1, nextKeyword, true, token);
    }, 300);
  }, [loadPage]);

  const loadMore = useCallback(() => {
    if (!enabled || loadingRef.current || items.length >= total) return;
    void loadPage(pageNum + 1, keyword, false, requestToken.current);
  }, [enabled, items.length, keyword, loadPage, pageNum, total]);

  return { items, loading, error, search, loadMore };
}

function reachedBottom(event: React.UIEvent<HTMLDivElement>) {
  const target = event.currentTarget;
  return target.scrollTop + target.clientHeight >= target.scrollHeight - 24;
}

interface SkillRelationEditorProps {
  name: number;
  parentSkillId: number;
  form: FormInstance<SkillPackagePayload>;
  disabled: boolean;
  skillLabels: Record<number, string>;
  versionLabels: Record<number, string>;
  onSkillLabel: (skillId: number, label: string) => void;
  onVersionLabel: (versionId: number, label: string) => void;
  onRemove: () => void;
}

/** 两层服务端分页搜索，已选项始终以缓存标签补回，避免翻页后显示裸 ID。 */
export default function SkillRelationEditor({
  name, parentSkillId, form, disabled, skillLabels, versionLabels,
  onSkillLabel, onVersionLabel, onRemove
}: SkillRelationEditorProps) {
  const childSkillId = Form.useWatch(['relations', name, 'childSkillId'], form);
  const childVersionId = Form.useWatch(['relations', name, 'childVersionId'], form);

  const loadSkills = useCallback((pageNum: number, keyword?: string) => listSkillDependencyOptions({
    skillId: parentSkillId, pageNum, pageSize: 50, keyword
  }), [parentSkillId]);
  const loadVersions = useCallback((pageNum: number, keyword?: string) => {
    if (!childSkillId) return Promise.resolve({ data: [], total: 0 });
    return listSkillDependencyVersionOptions({
      parentSkillId, childSkillId, pageNum, pageSize: 100, keyword
    });
  }, [childSkillId, parentSkillId]);

  const skills = useRemotePages<SkillDependencyOption>(true, loadSkills);
  const versions = useRemotePages<SkillDependencyVersionOption>(!!childSkillId, loadVersions);

  const skillOptions = useMemo(() => {
    const result = skills.items.map((item) => ({
      value: item.skillId, label: `${item.name}（${item.skillCode}）`
    }));
    if (childSkillId && !result.some((item) => item.value === childSkillId)) {
      result.unshift({ value: childSkillId, label: skillLabels[childSkillId] || `Skill #${childSkillId}` });
    }
    return result;
  }, [childSkillId, skillLabels, skills.items]);

  const versionOptions = useMemo(() => {
    const result = versions.items.map((item) => ({
      value: item.id, label: `${item.versionCode}${item.current ? '（当前）' : ''}`
    }));
    if (childVersionId && !result.some((item) => item.value === childVersionId)) {
      result.unshift({ value: childVersionId, label: versionLabels[childVersionId] || `版本 #${childVersionId}` });
    }
    return result;
  }, [childVersionId, versionLabels, versions.items]);

  useEffect(() => {
    const selected = skills.items.find((item) => item.skillId === childSkillId);
    if (selected) onSkillLabel(selected.skillId, `${selected.name}（${selected.skillCode}）`);
  }, [childSkillId, onSkillLabel, skills.items]);

  useEffect(() => {
    const selected = versions.items.find((item) => item.id === childVersionId);
    if (selected) onVersionLabel(selected.id, `${selected.versionCode}${selected.current ? '（当前）' : ''}`);
  }, [childVersionId, onVersionLabel, versions.items]);

  useEffect(() => {
    if (childVersionId) return;
    const selectedSkill = skills.items.find((item) => item.skillId === childSkillId);
    const current = versions.items.find((item) => item.id === selectedSkill?.currentVersionId);
    if (!current) return;
    form.setFieldValue(['relations', name, 'childVersionId'], current.id);
    onVersionLabel(current.id, `${current.versionCode}（当前）`);
  }, [childSkillId, childVersionId, form, name, onVersionLabel, skills.items, versions.items]);

  const selectChild = (nextSkillId?: number) => {
    form.setFieldValue(['relations', name, 'childVersionId'], undefined);
    if (!nextSkillId) {
      form.setFieldValue(['relations', name, 'relationKey'], undefined);
      return;
    }
    const next = skills.items.find((item) => item.skillId === nextSkillId);
    if (!next) return;
    form.setFieldValue(['relations', name, 'relationKey'], next.skillCode);
    onSkillLabel(next.skillId, `${next.name}（${next.skillCode}）`);
    if (next.currentVersionId) {
      form.setFieldValue(['relations', name, 'childVersionId'], next.currentVersionId);
      onVersionLabel(next.currentVersionId, `版本 #${next.currentVersionId}`);
      void getSkillDependencyLabels(parentSkillId, [next.currentVersionId]).then((response) => {
        const label = response.data?.[0];
        if (label) onVersionLabel(label.childVersionId,
          `${label.childVersionCode}${label.current ? '（当前）' : ''}`);
      }, () => undefined);
    }
  };

  const selectVersion = (nextVersionId?: number) => {
    if (!nextVersionId) return;
    const next = versions.items.find((item) => item.id === nextVersionId);
    if (next) onVersionLabel(next.id, `${next.versionCode}${next.current ? '（当前）' : ''}`);
  };

  return <Card size="small">
    <Row gutter={12} align="middle">
      <Col span={8}><Form.Item name={[name, 'childSkillId']} label="子 Skill"
        rules={[{ required: true, message: '请选择子 Skill' }]}>
        <Select allowClear showSearch filterOption={false} disabled={disabled} loading={skills.loading}
          notFoundContent={skills.loading ? <Spin size="small" /> : skills.error || null}
          placeholder="输入名称或编码搜索" options={skillOptions} onSearch={skills.search}
          onPopupScroll={(event) => { if (reachedBottom(event)) skills.loadMore(); }}
          onChange={selectChild} />
      </Form.Item></Col>
      <Col span={6}><Form.Item name={[name, 'childVersionId']} label="固定版本"
        rules={[{ required: true, message: '请选择子版本' }]}>
        <Select showSearch filterOption={false} disabled={disabled || !childSkillId}
          loading={versions.loading} notFoundContent={versions.loading ? <Spin size="small" /> : versions.error || null}
          placeholder="输入版本号搜索" options={versionOptions} onSearch={versions.search}
          onPopupScroll={(event) => { if (reachedBottom(event)) versions.loadMore(); }}
          onChange={selectVersion} />
      </Form.Item></Col>
      <Col span={6}><Form.Item name={[name, 'relationKey']} label="关系标识"
        tooltip="关系标识固定为子 Skill 的稳定编码，运行时据此确定性路由。"
        rules={[{ required: true }, { pattern: /^[a-z0-9][a-z0-9._-]{0,63}$/, message: '关系标识错误' }]}>
        <Input disabled />
      </Form.Item></Col>
      <Col span={2}><Form.Item name={[name, 'requiredFlag']} label="必需" valuePropName="checked">
        <Switch disabled={disabled} /></Form.Item></Col>
      <Col span={2}>{!disabled && <Button danger type="link" onClick={onRemove}>移除</Button>}</Col>
    </Row>
  </Card>;
}

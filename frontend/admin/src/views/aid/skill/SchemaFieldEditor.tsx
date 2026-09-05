import React, { useMemo } from 'react';
import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space, Switch, Tag, Typography, message } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';

type JsonObject = Record<string, unknown>;

interface SchemaFieldEditorProps {
  value?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
}

const FIELD_TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array'].map((value) => ({
  value,
  label: value
}));

function isObject(value: unknown): value is JsonObject {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function parseSchema(value?: string): { root?: JsonObject; error?: string } {
  if (!value?.trim()) return { error: 'Schema 不能为空' };
  try {
    const parsed = JSON.parse(value) as unknown;
    if (!isObject(parsed)) return { error: 'Schema 根节点必须是对象' };
    if (parsed.properties != null && !isObject(parsed.properties)) {
      return { error: 'Schema properties 必须是对象' };
    }
    return { root: parsed };
  } catch {
    return { error: '历史 Schema 不是有效 JSON，请先修复数据后再保存' };
  }
}

export function validateSkillSchema(value?: string): Promise<void> {
  const parsed = parseSchema(value);
  return parsed.error ? Promise.reject(new Error(parsed.error)) : Promise.resolve();
}

function cloneSchema(root: JsonObject): JsonObject {
  return JSON.parse(JSON.stringify(root)) as JsonObject;
}

function schemaProperties(root: JsonObject): JsonObject {
  return isObject(root.properties) ? root.properties : {};
}

function requiredNames(root: JsonObject): string[] {
  return Array.isArray(root.required)
    ? root.required.filter((item): item is string => typeof item === 'string')
    : [];
}

function initialValue(type: unknown): unknown {
  switch (type) {
    case 'number':
    case 'integer': return 0;
    case 'boolean': return false;
    case 'object': return {};
    case 'array': return [];
    default: return '';
  }
}

function ManagedValue({ label, fieldType, schema, propertyName, disabled, updateField }: {
  label: 'default' | 'example';
  fieldType: unknown;
  schema: JsonObject;
  propertyName: string;
  disabled?: boolean;
  updateField: (name: string, updater: (draft: JsonObject) => void) => void;
}) {
  const present = Object.prototype.hasOwnProperty.call(schema, label);
  const current = schema[label];
  const compatible = !present
    || fieldType === 'string' && typeof current === 'string'
    || (fieldType === 'number' || fieldType === 'integer') && typeof current === 'number'
    || fieldType === 'boolean' && typeof current === 'boolean'
    || fieldType === 'object' && isObject(current)
    || fieldType === 'array' && Array.isArray(current);

  const setPresent = (checked: boolean) => updateField(propertyName, (draft) => {
    if (checked) draft[label] = initialValue(fieldType);
    else delete draft[label];
  });
  const setValue = (next: unknown) => updateField(propertyName, (draft) => { draft[label] = next; });

  return <Space direction="vertical" size={4} style={{ width: '100%' }}>
    <Space size={6}>
      <Typography.Text type="secondary">{label === 'default' ? '默认值' : '示例'}</Typography.Text>
      <Switch size="small" checked={present} disabled={disabled} onChange={setPresent} />
    </Space>
    {present && !compatible && <Typography.Text type="warning">值类型与字段类型不同，已只读保留</Typography.Text>}
    {present && compatible && fieldType === 'string' && <Input disabled={disabled} value={current as string}
      onChange={(event) => setValue(event.target.value)} />}
    {present && compatible && (fieldType === 'number' || fieldType === 'integer') && <InputNumber
      disabled={disabled} value={current as number} precision={fieldType === 'integer' ? 0 : undefined}
      style={{ width: '100%' }} onChange={(next) => setValue(next ?? 0)} />}
    {present && compatible && fieldType === 'boolean' && <Select disabled={disabled} value={current as boolean}
      style={{ width: '100%' }} options={[{ label: 'true', value: true }, { label: 'false', value: false }]}
      onChange={setValue} />}
    {present && compatible && (fieldType === 'object' || fieldType === 'array') && <Typography.Text code>
      {JSON.stringify(current)}（复杂值只读保留）
    </Typography.Text>}
  </Space>;
}

/** JSON Schema 字段化编辑器，未受管属性按原对象无损回写。 */
export default function SchemaFieldEditor({ value, onChange, disabled }: SchemaFieldEditorProps) {
  const parsed = useMemo(() => parseSchema(value), [value]);
  if (!parsed.root) {
    return <Alert type="error" showIcon message={parsed.error}
      description="当前原始值会保持不变，修复前无法保存此配置。" />;
  }

  const root = parsed.root;
  const properties = schemaProperties(root);
  const required = new Set(requiredNames(root));
  const commit = (mutator: (draft: JsonObject) => void) => {
    const draft = cloneSchema(root);
    mutator(draft);
    onChange?.(JSON.stringify(draft));
  };
  const updateField = (name: string, updater: (draft: JsonObject) => void) => commit((draftRoot) => {
    const draftProperties = schemaProperties(draftRoot);
    const field = draftProperties[name];
    if (!isObject(field)) return;
    updater(field);
    draftRoot.properties = draftProperties;
  });
  const renameField = (oldName: string, requestedName: string) => {
    const nextName = requestedName.trim();
    if (!nextName || nextName === oldName) return;
    if (Object.prototype.hasOwnProperty.call(properties, nextName)) {
      message.error(`字段 ${nextName} 已存在`);
      return;
    }
    commit((draftRoot) => {
      const draftProperties = schemaProperties(draftRoot);
      const field = draftProperties[oldName];
      delete draftProperties[oldName];
      draftProperties[nextName] = field;
      draftRoot.properties = draftProperties;
      if (Array.isArray(draftRoot.required)) {
        draftRoot.required = draftRoot.required.map((item) => item === oldName ? nextName : item);
      }
    });
  };
  const toggleRequired = (name: string, checked: boolean) => commit((draftRoot) => {
    const existing = Array.isArray(draftRoot.required) ? [...draftRoot.required] : [];
    const next = existing.filter((item) => item !== name);
    if (checked) next.push(name);
    if (next.length > 0 || Array.isArray(draftRoot.required)) draftRoot.required = next;
    else delete draftRoot.required;
  });
  const removeField = (name: string) => commit((draftRoot) => {
    const draftProperties = schemaProperties(draftRoot);
    delete draftProperties[name];
    draftRoot.properties = draftProperties;
    if (Array.isArray(draftRoot.required)) {
      draftRoot.required = draftRoot.required.filter((item) => item !== name);
    }
  });
  const addField = () => commit((draftRoot) => {
    const draftProperties = schemaProperties(draftRoot);
    let index = Object.keys(draftProperties).length + 1;
    let name = `field_${index}`;
    while (Object.prototype.hasOwnProperty.call(draftProperties, name)) {
      name = `field_${++index}`;
    }
    draftProperties[name] = { type: 'string', description: '' };
    draftRoot.properties = draftProperties;
  });

  return <Space direction="vertical" size={12} style={{ width: '100%' }}>
    {Object.entries(properties).map(([name, rawField]) => {
      if (!isObject(rawField)) {
        return <Card key={name} size="small" title={<Space><Typography.Text strong>{name}</Typography.Text>
          <Tag>布尔 Schema</Tag></Space>} extra={!disabled && <Button danger type="text" icon={<DeleteOutlined />}
          onClick={() => removeField(name)} /> }>
          <Alert type="info" showIcon message="此字段使用复杂声明，当前只读保留" />
        </Card>;
      }
      const fieldType = rawField.type;
      const supportedType = typeof fieldType === 'string'
        && FIELD_TYPES.some((option) => option.value === fieldType);
      return <Card key={name} size="small" title={<Input disabled={disabled} defaultValue={name}
        aria-label="字段名" style={{ width: 240 }} onBlur={(event) => {
          const requested = event.target.value.trim();
          if (!requested || (requested !== name && Object.prototype.hasOwnProperty.call(properties, requested))) {
            event.target.value = name;
            if (!requested) message.error('字段名不能为空');
            else message.error(`字段 ${requested} 已存在`);
            return;
          }
          renameField(name, requested);
        }} />} extra={<Space>
          <Typography.Text type="secondary">必填</Typography.Text>
          <Switch size="small" disabled={disabled} checked={required.has(name)}
            onChange={(checked) => toggleRequired(name, checked)} />
          {!disabled && <Button danger type="text" icon={<DeleteOutlined />} onClick={() => removeField(name)} />}
        </Space>}>
        {!supportedType && <Alert style={{ marginBottom: 12 }} type="info" showIcon
          message="字段类型为复杂声明，类型及示例值只读保留" />}
        <Row gutter={[12, 12]}>
          <Col span={8}><Typography.Text type="secondary">类型</Typography.Text>
            <Select disabled={disabled || !supportedType} value={supportedType ? fieldType : undefined}
              placeholder={JSON.stringify(fieldType)} style={{ width: '100%', marginTop: 4 }} options={FIELD_TYPES}
              onChange={(next) => updateField(name, (draft) => { draft.type = next; })} />
          </Col>
          <Col span={16}><Typography.Text type="secondary">说明</Typography.Text>
            <Input disabled={disabled} value={typeof rawField.description === 'string' ? rawField.description : ''}
              style={{ marginTop: 4 }} onChange={(event) => updateField(name, (draft) => {
                if (event.target.value) draft.description = event.target.value;
                else delete draft.description;
              })} />
          </Col>
          <Col span={12}><ManagedValue label="default" fieldType={fieldType} schema={rawField}
            propertyName={name} disabled={disabled || !supportedType} updateField={updateField} /></Col>
          <Col span={12}><ManagedValue label="example" fieldType={fieldType} schema={rawField}
            propertyName={name} disabled={disabled || !supportedType} updateField={updateField} /></Col>
        </Row>
      </Card>;
    })}
    {Object.keys(properties).length === 0 && <Alert type="info" showIcon message="当前 Schema 没有字段" />}
    {!disabled && <Button block type="dashed" icon={<PlusOutlined />} onClick={addField}>添加字段</Button>}
    <Typography.Text type="secondary">根级约束与字段中的其他 JSON Schema 属性会原样保留。</Typography.Text>
  </Space>;
}

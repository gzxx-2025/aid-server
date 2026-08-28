import type { ReactNode } from 'react';
import type { ColumnType } from 'antd/es/table';

/** 字段类型（用于搜索、表单自动渲染） */
export type FieldType =
  | 'input'
  | 'textarea'
  | 'richtext'
  | 'combobox'
  | 'image'
  | 'number'
  | 'select'
  | 'dict'
  | 'date'
  | 'dateRange'
  | 'switch'
  | 'custom';

/** 通用字段配置 */
export interface FieldConfig {
  /** 字段名 */
  name: string;
  /** 显示名 */
  label: string;
  /** 字段类型 */
  type?: FieldType;
  /** 占位文本 */
  placeholder?: string;
  /** 选项（select 使用） */
  options?: Array<{ label: string; value: any }>;
  /** 字典 key（type = 'dict' 时使用） */
  dictType?: string;
  /** 是否必填 */
  required?: boolean;
  /** 扩展校验 */
  rules?: any[];
  /** 默认值 */
  initialValue?: any;
  /** 栅格占位（24 制） */
  span?: number;
  /** 自定义渲染（type=custom 时） */
  render?: (form: any) => ReactNode;
  /** 查看详情抽屉中的自定义渲染（优先级最高）：可用于把资源/封面渲染成可播放内容 */
  viewRender?: (value: any, row: any) => ReactNode;
  /** 最大长度 */
  maxLength?: number;
  /** 是否禁用 */
  disabled?: boolean;
  /** 只在编辑/新增显示（默认两个都显示） */
  onlyEdit?: boolean;
  onlyAdd?: boolean;
}

/** 搜索条件配置 */
export interface SearchConfig extends FieldConfig {
  /** 日期范围对应的后端参数名（如 beginTime/endTime 自动拼） */
  rangePropName?: string;
}

/** 列配置 - 基于 antd ColumnType 拓展 */
export interface ColumnConfig<T = any> extends Omit<ColumnType<T>, 'dataIndex'> {
  dataIndex?: string | string[];
  /** 字典 key，自动渲染为 DictTag */
  dictType?: string;
  /** 日期格式化 */
  dateFormat?: string | boolean;
  /** 前缀文本（如 ¥） */
  prefix?: string;
  /** 后缀文本 */
  suffix?: string;
  /** 长文本省略 */
  ellipsis?: boolean;
}

/** CRUD API 适配 */
export interface CrudApi<T = any> {
  /** 列表（必须，返回 {rows, total} 或 {data: []}） */
  list: (params: Record<string, any>) => Promise<any>;
  /** 详情 */
  get?: (id: any) => Promise<any>;
  /** 新增 */
  add?: (data: T) => Promise<any>;
  /** 修改 */
  update?: (data: T) => Promise<any>;
  /** 删除（可单个或批量，参数支持 id / ids） */
  remove?: (ids: any) => Promise<any>;
  /** 导出路径（相对接口 base） */
  exportUrl?: string;
}

/** 权限标识（对应 perms） */
export interface CrudPerms {
  add?: string;
  edit?: string;
  remove?: string;
  export?: string;
  query?: string;
}

/** 嵌入式作用域：用于把列表页以"项目工作台"维度内嵌复用（按 projectId/episodeId/userId 过滤） */
export interface EmbeddedScope {
  projectId?: number | string;
  episodeId?: number | string;
  userId?: number | string;
}

/** CRUD 配置 */
export interface CrudConfig<T = any> {
  /** 页面标题（可选，用作导出文件名等） */
  title: string;
  /** 权限命名空间前缀（会自动拼 add/edit/remove/export）或直接给 perms */
  permPrefix?: string;
  perms?: CrudPerms;
  /** 行 key 字段（默认 'id'） */
  rowKey?: string;
  /** API */
  api: CrudApi<T>;
  /** 列表列 */
  columns: ColumnConfig<T>[];
  /** 搜索字段 */
  searchFields?: SearchConfig[];
  /** 表单字段 */
  formFields?: FieldConfig[];
  /** 弹窗宽度 */
  modalWidth?: number;
  /** 默认查询参数 */
  defaultQuery?: Record<string, any>;
  /** 默认每页 */
  pageSize?: number;
  /** 是否显示多选 */
  selectable?: boolean;
  /** 支持导出 */
  exportable?: boolean;
  /** 隐藏新增按钮 */
  hideAdd?: boolean;
  /** 隐藏编辑按钮 */
  hideEdit?: boolean;
  /** 隐藏删除按钮 */
  hideDelete?: boolean;
  /** 开启行内「查看」详情抽屉（只读展示各列/富文本字段），用于内容较多的列表页 */
  viewable?: boolean;
  /** 工具条扩展按钮 */
  toolbarExtra?: (ctx: { refresh: () => void; selected: any[] }) => ReactNode;
  /** 行操作扩展 */
  rowActions?: Array<{
    label: string;
    perm?: string;
    danger?: boolean;
    icon?: ReactNode;
    /** 条件显示 */
    visible?: (row: T) => boolean;
    /** 是否需要二次确认 */
    confirm?: string | ((row: T) => string);
    /** 点击处理 */
    onClick: (row: T, ctx: { refresh: () => void }) => void | Promise<void>;
  }>;
  /** 额外的字典 key（会预加载，供列/表单使用） */
  dictTypes?: string[];
  /** 提交前加工表单数据 */
  beforeSubmit?: (data: any, isEdit: boolean) => any;
  /** 表单初始化加工 */
  afterFetch?: (data: any) => any;
}

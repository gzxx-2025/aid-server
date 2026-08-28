import React from 'react';
import * as AntIcons from '@ant-design/icons';
import SvgIcon from './SvgIcon';

interface Props {
  icon?: string;
  className?: string;
}

// 后端常用的 icon 名 → antd Icon 组件映射（尽量覆盖全部用到的名字，且各自映射到不同图标，避免重复/缺图标）
const fallbackIconMap: Record<string, string> = {
  // 通用 / 系统
  dashboard: 'DashboardOutlined',
  system: 'SettingOutlined',
  config: 'ControlOutlined',
  tool: 'ToolOutlined',
  menu: 'MenuOutlined',
  tree: 'ApartmentOutlined',
  'tree-table': 'ApartmentOutlined',
  cascader: 'PartitionOutlined',
  build: 'BuildOutlined',
  table: 'TableOutlined',
  list: 'UnorderedListOutlined',
  form: 'FormOutlined',
  edit: 'EditOutlined',
  dict: 'BookOutlined',
  clipboard: 'SnippetsOutlined',
  documentation: 'ReadOutlined',
  // 用户 / 角色
  user: 'UserOutlined',
  users: 'TeamOutlined',
  peoples: 'TeamOutlined',
  people: 'UserOutlined',
  profile: 'IdcardOutlined',
  role: 'SafetyOutlined',
  skill: 'BulbOutlined',
  // 监控 / 运维
  monitor: 'DesktopOutlined',
  server: 'CloudServerOutlined',
  druid: 'DatabaseOutlined',
  cache: 'ThunderboltOutlined',
  online: 'WifiOutlined',
  job: 'FieldTimeOutlined',
  log: 'FileTextOutlined',
  logininfor: 'LoginOutlined',
  bug: 'BugOutlined',
  swagger: 'ApiOutlined',
  // 内容 / 运营
  notice: 'NotificationOutlined',
  message: 'MessageOutlined',
  question: 'QuestionCircleOutlined',
  international: 'GlobalOutlined',
  guide: 'CompassOutlined',
  star: 'StarOutlined',
  // 媒体
  video: 'VideoCameraOutlined',
  audio: 'SoundOutlined',
  voice: 'AudioOutlined',
  mic: 'AudioOutlined',
  image: 'PictureOutlined',
  tag: 'TagOutlined',
  // AI / 模型
  ai: 'RobotOutlined',
  component: 'AppstoreOutlined',
  model: 'AppstoreOutlined',
  chart: 'LineChartOutlined',
  // 财务
  pay: 'CreditCardOutlined',
  money: 'WalletOutlined',
  shopping: 'ShoppingCartOutlined',
  number: 'NumberOutlined',
  'eye-open': 'EyeOutlined',
  eye: 'EyeOutlined'
};

export default function MenuIcon({ icon, className }: Props) {
  if (!icon || icon === '#') return null;
  // 先尝试映射到 antd icon
  const mapped = fallbackIconMap[icon];
  if (mapped && (AntIcons as any)[mapped]) {
    const IconCmp = (AntIcons as any)[mapped];
    return <IconCmp className={className} />;
  }
  // 其次尝试 svg sprite
  return <SvgIcon icon={icon} className={className} size={16} />;
}

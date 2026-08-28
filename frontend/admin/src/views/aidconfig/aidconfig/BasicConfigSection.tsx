import React from 'react';
import { Tag } from 'antd';
import {
  ApartmentOutlined,
  CheckCircleFilled,
  GlobalOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
  SearchOutlined
} from '@ant-design/icons';

import ValueField from './ValueField';
import './BasicConfigSection.less';

interface BasicConfigItem {
  id: number;
  configName: string;
  configValue: string;
  configDict?: string;
  _modified: boolean;
}

interface Props {
  items: BasicConfigItem[];
  onChange: (id: number, value: string) => void;
}

interface FieldMeta {
  label: string;
  description: string;
  wide?: boolean;
}

const FIELD_META: Record<string, FieldMeta> = {
  site_name: {
    label: '网站名称',
    description: '用于浏览器标题、分享卡片和搜索结果中的品牌名称。'
  },
  site_description: {
    label: '网站描述',
    description: '简洁说明平台定位与核心能力，用于搜索摘要。',
    wide: true
  },
  site_keywords: {
    label: '网站关键词',
    description: '逐个添加关键词，系统保存时自动使用英文逗号分隔。',
    wide: true
  },
  membership_agreement: {
    label: '会员协议',
    description: '会员开通、权益使用、续费与退款等规则页面地址。'
  },
  terms_of_service: {
    label: '用户协议',
    description: '平台注册、使用规则及用户责任说明页面地址。'
  },
  privacy_policy: {
    label: '隐私政策',
    description: '个人信息处理、存储和用户权利说明页面地址。'
  },
  personal_information_collection_list: {
    label: '个人信息收集清单',
    description: '列明收集字段、使用目的与处理方式。'
  },
  app_permissions_description: {
    label: '应用权限说明',
    description: '列明客户端申请的系统权限与使用场景。'
  },
  third_party_sdk_and_information_sharing_list: {
    label: '第三方 SDK 清单',
    description: '第三方 SDK、共享信息和使用目的说明页面地址。',
    wide: true
  },
  company_name: {
    label: '公司名称',
    description: '平台运营主体的完整工商登记名称。'
  },
  company_address: {
    label: '公司地址',
    description: '运营主体联系地址或办公地址。'
  },
  service_email: {
    label: '服务邮箱',
    description: '用户咨询、投诉和售后服务邮箱。'
  },
  contact_phone: {
    label: '联系电话',
    description: '支持区号、分机号和短横线。'
  },
  record_filing_number: {
    label: '备案号',
    description: '网站 ICP 或应用备案编号。'
  },
  exchange_image_url: {
    label: '交流二维码',
    description: '上传用于关于我们或帮助中心展示的交流二维码。',
    wide: true
  },
  tutorial_url: {
    label: '教程链接',
    description: '产品使用手册或新手教程地址。'
  },
  open_source_git_url: {
    label: 'GitHub 地址',
    description: '平台开源项目的 GitHub 仓库地址。'
  },
  open_source_gitee_url: {
    label: 'Gitee 地址',
    description: '平台开源项目的 Gitee 仓库地址。'
  },
  work_publish_enabled: {
    label: '作品发布',
    description: '控制 C 端是否允许用户公开发布作品。'
  }
};

const GROUPS = [
  {
    key: 'seo',
    title: '站点与搜索展示',
    description: '统一维护搜索引擎、浏览器和分享场景中的站点信息。',
    icon: <SearchOutlined />,
    fields: ['site_name', 'site_description', 'site_keywords']
  },
  {
    key: 'legal',
    title: '协议与合规',
    description: '集中管理用户可访问的协议、隐私和信息披露链接。',
    icon: <SafetyCertificateOutlined />,
    fields: [
      'membership_agreement',
      'terms_of_service',
      'privacy_policy',
      'personal_information_collection_list',
      'app_permissions_description',
      'third_party_sdk_and_information_sharing_list'
    ]
  },
  {
    key: 'organization',
    title: '主体与联系信息',
    description: '用于关于我们、客服支持及合规页的运营主体信息。',
    icon: <ApartmentOutlined />,
    fields: [
      'company_name',
      'company_address',
      'service_email',
      'contact_phone',
      'record_filing_number',
      'exchange_image_url'
    ]
  },
  {
    key: 'links',
    title: '产品入口与开放生态',
    description: '维护教程、开源仓库及内容发布能力。',
    icon: <LinkOutlined />,
    fields: ['tutorial_url', 'open_source_git_url', 'open_source_gitee_url', 'work_publish_enabled']
  }
];

/** 基础配置专用的分组化表单，保持原 aid_config 保存流程不变。 */
export default function BasicConfigSection({ items, onChange }: Props) {
  const itemMap = new Map(items.map((item) => [item.configName, item]));
  const groupedNames = new Set(GROUPS.flatMap((group) => group.fields));
  const configuredCount = items.filter((item) => String(item.configValue || '').trim()).length;
  const siteName = itemMap.get('site_name')?.configValue || '您的网站名称';
  const siteDescription = itemMap.get('site_description')?.configValue || '填写网站描述后，这里将展示搜索摘要预览。';
  const keywords = Array.from(
    new Set(
      (itemMap.get('site_keywords')?.configValue || '')
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean)
    )
  );

  const renderField = (item: BasicConfigItem) => {
    const meta = FIELD_META[item.configName] || {
      label: item.configDict || item.configName,
      description: '平台公开基础配置。'
    };
    return (
      <div
        key={item.id}
        className={`basic-config__field ${meta.wide ? 'basic-config__field--wide' : ''} ${
          item._modified ? 'is-modified' : ''
        }`}
      >
        <div className="basic-config__field-head">
          <div className="basic-config__field-label">{meta.label}</div>
          {item._modified && <Tag color="processing">待保存</Tag>}
        </div>
        <div className="basic-config__field-description">{meta.description}</div>
        <div className="basic-config__field-control">
          <ValueField
            name={item.configName}
            value={item.configValue}
            onChange={(value) => onChange(item.id, value)}
            category="basic"
          />
        </div>
      </div>
    );
  };

  const otherItems = items.filter((item) => !groupedNames.has(item.configName));

  return (
    <div className="basic-config">
      <div className="basic-config__hero">
        <div className="basic-config__hero-icon">
          <GlobalOutlined />
        </div>
        <div className="basic-config__hero-copy">
          <span className="basic-config__eyebrow">PLATFORM FOUNDATION</span>
          <h3>平台基础信息</h3>
          <p>以用户触点为中心维护品牌、搜索展示、协议合规与服务信息，保存后自动进入公开配置。</p>
        </div>
        <div className="basic-config__completion">
          <strong>{configuredCount}</strong>
          <span>已配置项</span>
        </div>
      </div>

      <div className="basic-config__search-preview">
        <div className="basic-config__search-preview-head">
          <SearchOutlined /> 搜索结果预览
        </div>
        <div className="basic-config__search-title">{siteName}</div>
        <div className="basic-config__search-url">https://your-domain.com</div>
        <div className="basic-config__search-description">{siteDescription}</div>
        <div className="basic-config__keyword-list">
          {keywords.length > 0 ? (
            keywords.map((keyword) => <Tag key={keyword}>{keyword}</Tag>)
          ) : (
            <span>添加关键词后将在这里预览</span>
          )}
        </div>
      </div>

      {GROUPS.map((group) => {
        const groupItems = group.fields.map((name) => itemMap.get(name)).filter(Boolean) as BasicConfigItem[];
        if (groupItems.length === 0) return null;
        return (
          <section key={group.key} className="basic-config__section">
            <div className="basic-config__section-head">
              <div className="basic-config__section-icon">{group.icon}</div>
              <div>
                <h4>{group.title}</h4>
                <p>{group.description}</p>
              </div>
              <div className="basic-config__section-count">
                <CheckCircleFilled /> {groupItems.length} 项
              </div>
            </div>
            <div className="basic-config__grid">{groupItems.map(renderField)}</div>
          </section>
        );
      })}

      {otherItems.length > 0 && (
        <section className="basic-config__section">
          <div className="basic-config__section-head">
            <div className="basic-config__section-icon">
              <GlobalOutlined />
            </div>
            <div>
              <h4>其他基础配置</h4>
              <p>未归类的扩展配置会自动收纳在这里，不会丢失。</p>
            </div>
          </div>
          <div className="basic-config__grid">{otherItems.map(renderField)}</div>
        </section>
      )}
    </div>
  );
}

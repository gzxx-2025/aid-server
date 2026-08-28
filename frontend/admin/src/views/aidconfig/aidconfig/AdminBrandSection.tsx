import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Space, message } from 'antd';
import { PictureOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';

import { listAidconfig, updateAidconfig, addAidconfig } from '@/api/aidconfig/aidconfig';
import ImageUpload from '@/components/ImageUpload';
import { useAdminBrandStore } from '@/store/useAdminBrandStore';
import './style.less';

const CATEGORY = 'admin_brand';
const KEY_PLATFORM_LOGO = 'platform_logo_url';
const LEGACY_KEY_LOGIN = 'login_logo_url';
const LEGACY_KEY_SIDEBAR = 'sidebar_logo_url';
const KEY_FAVICON = 'favicon_url';

interface CfgItem {
  id?: number;
  configName: string;
  configValue: string;
}

/**
 * 后台品牌图片配置：平台 LOGO / 浏览器页签图标。
 * 写入 aid_config(category=admin_brand)，图片使用后台管理上传接口。
 */
export default function AdminBrandSection() {
  const refreshBrand = useAdminBrandStore((state) => state.load);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [platformLogo, setPlatformLogo] = useState('');
  const [favicon, setFavicon] = useState('');
  const [items, setItems] = useState<Record<string, CfgItem>>({});

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await listAidconfig({ pageNum: 1, pageSize: 1000, category: CATEGORY });
      const rows: any[] = (res.rows || res.data || []).filter((r: any) => r.category === CATEGORY);
      const map: Record<string, CfgItem> = {};
      rows.forEach((r) => {
        map[r.configName] = { id: r.id, configName: r.configName, configValue: r.configValue || '' };
      });
      setItems(map);
      // 已有数据库未执行迁移时，优先沿用原登录页 Logo，其次沿用原左上角 Logo。
      setPlatformLogo(
        map[KEY_PLATFORM_LOGO]?.configValue ||
          map[LEGACY_KEY_LOGIN]?.configValue ||
          map[LEGACY_KEY_SIDEBAR]?.configValue ||
          ''
      );
      setFavicon(map[KEY_FAVICON]?.configValue || '');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 持久化单个配置项（存在则更新，否则新增） */
  const persist = async (name: string, value: string, dict: string, order: number) => {
    const it = items[name];
    if (it?.id) {
      await updateAidconfig({ id: it.id, configValue: value });
    } else {
      await addAidconfig({
        category: CATEGORY,
        configName: name,
        configValue: value,
        configDict: dict,
        delFlag: '0',
        orderNum: order
      });
    }
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await persist(KEY_PLATFORM_LOGO, platformLogo || '', '平台LOGO地址', 1);
      await persist(KEY_FAVICON, favicon || '', '浏览器页签图标地址', 2);
      await load();
      await refreshBrand();
      message.success('已保存并生效');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="config-section">
      <Card
        bordered={false}
        className="page-card"
        title={
          <Space>
            <PictureOutlined className="config-section__icon" />
            后台品牌图片
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>
              刷新
            </Button>
            <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="配置平台 LOGO、浏览器页签图标"
          description="平台 LOGO 同时用于管理端登录页和左上角，并与页签图标一起提供给 C 端。未配置时管理端使用系统内置默认图。"
        />

        <Form layout="horizontal" labelCol={{ flex: '150px' }} wrapperCol={{ flex: 'auto' }} labelAlign="right">
          <Form.Item label="平台 LOGO">
            <ImageUpload
              value={platformLogo}
              onChange={(v) => setPlatformLogo(v)}
              maxCount={1}
              maxSize={5}
              accept="image/*"
            />
          </Form.Item>

          <Form.Item label="页签图标 (Favicon)" style={{ marginBottom: 0 }}>
            <ImageUpload
              value={favicon}
              onChange={(v) => setFavicon(v)}
              maxCount={1}
              maxSize={2}
              accept="image/*,.ico"
            />
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

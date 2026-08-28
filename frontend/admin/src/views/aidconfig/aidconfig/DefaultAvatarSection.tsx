import React, { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Space, message } from 'antd';
import { PictureOutlined, ReloadOutlined, SaveOutlined } from '@ant-design/icons';

import { listAidconfig, updateAidconfig, addAidconfig } from '@/api/aidconfig/aidconfig';
import ImageUpload from '@/components/ImageUpload';
import './style.less';

const CATEGORY = 'default_avatar';
const KEY_URLS = 'urls';
const MAX_COUNT = 5;

interface CfgItem {
  id?: number;
  configName: string;
  configValue: string;
}

/**
 * 默认头像配置：管理员上传最多 5 张默认头像（aid_config: default_avatar/urls，逗号分隔）。
 * C 端用户首次注册时从中随机选一张作为头像；管理员一张都不传时，用户头像可为空。
 */
export default function DefaultAvatarSection() {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [urls, setUrls] = useState('');
  const [item, setItem] = useState<CfgItem | undefined>(undefined);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await listAidconfig({ pageNum: 1, pageSize: 1000, category: CATEGORY });
      const rows: any[] = (res.rows || res.data || []).filter((r: any) => r.category === CATEGORY);
      const row = rows.find((r) => r.configName === KEY_URLS);
      if (row) {
        setItem({ id: row.id, configName: row.configName, configValue: row.configValue || '' });
        setUrls(row.configValue || '');
      } else {
        setItem(undefined);
        setUrls('');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const count = urls.split(',').filter(Boolean).length;

  const handleSave = async () => {
    setSaving(true);
    try {
      const value = urls.split(',').filter(Boolean).slice(0, MAX_COUNT).join(',');
      if (item?.id) {
        await updateAidconfig({ id: item.id, configValue: value });
      } else {
        await addAidconfig({
          category: CATEGORY,
          configName: KEY_URLS,
          configValue: value,
          configDict: '默认头像图片地址(逗号分隔,最多5张)',
          delFlag: '0',
          orderNum: 1
        });
      }
      message.success('已保存，新注册用户将从这些头像中随机选取');
      await load();
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="config-section">
      <Card
        bordered={false}
        className="page-card"
        title={<Space><PictureOutlined className="config-section__icon" />默认头像</Space>}
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
          message="用户首次注册时会从下方头像中随机选取一张作为默认头像"
          description="最多上传 5 张。如果一张都不上传，新用户头像将为空（不强制设置默认头像）。"
        />

        <Form layout="horizontal" labelCol={{ flex: '150px' }} wrapperCol={{ flex: 'auto' }} labelAlign="right">
          <Form.Item label="默认头像" extra={`已上传 ${count} / ${MAX_COUNT} 张`} style={{ marginBottom: 0 }}>
            <ImageUpload
              value={urls}
              onChange={(v) => setUrls(v)}
              maxCount={MAX_COUNT}
              maxSize={5}
              accept="image/*"
            />
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

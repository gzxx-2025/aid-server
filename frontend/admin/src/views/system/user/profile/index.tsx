import React, { useEffect, useState } from 'react';
import { Card, Col, Row, Tabs, Descriptions, Avatar, Form, Input, Button, Select, message, Tag, Upload, Modal } from 'antd';
import { UserOutlined, EditOutlined, LockOutlined, CameraOutlined, UploadOutlined } from '@ant-design/icons';

import { getUserProfile, updateUserProfile, updateUserPwd, uploadAvatar } from '@/api/system/user';
import PageCard from '@/components/PageCard';
import { useUserStore } from '@/store/useUserStore';
import { parseTime } from '@/utils/ruoyi';
import './style.less';

interface ProfileData {
  user: any;
  roleGroup?: string;
  postGroup?: string;
}

export default function ProfilePage() {
  const [data, setData] = useState<ProfileData | null>(null);
  const [loading, setLoading] = useState(false);
  const { avatar, setAvatar } = useUserStore();
  const [avatarOpen, setAvatarOpen] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await getUserProfile();
      setData({ user: res.data || res.user, roleGroup: res.roleGroup, postGroup: res.postGroup });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <Row gutter={[16, 16]} className="profile-page">
      <Col xs={24} lg={8}>
        <PageCard title="个人信息">
          <div className="profile-page__user">
            <div className="profile-page__avatar" onClick={() => setAvatarOpen(true)}>
              <Avatar size={88} src={avatar || data?.user?.avatar} icon={<UserOutlined />} />
              <div className="profile-page__avatar-mask">
                <CameraOutlined />
              </div>
            </div>
            <div className="profile-page__name">{data?.user?.nickName || '-'}</div>
            <div className="profile-page__meta">{data?.user?.userName}</div>
          </div>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="手机号">{data?.user?.phonenumber || '-'}</Descriptions.Item>
            <Descriptions.Item label="邮箱">{data?.user?.email || '-'}</Descriptions.Item>
            <Descriptions.Item label="所属部门">
              {data?.user?.dept?.deptName || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="所属角色">
              {(data?.roleGroup || '').split(',').filter(Boolean).map((r) => (
                <Tag key={r} color="blue" bordered={false}>{r}</Tag>
              )) || '-'}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {parseTime(data?.user?.createTime) || '-'}
            </Descriptions.Item>
          </Descriptions>
        </PageCard>
      </Col>
      <Col xs={24} lg={16}>
        <Card className="page-card">
          <Tabs
            items={[
              {
                key: 'base',
                label: (<span><EditOutlined /> 基本资料</span>),
                children: <BasicForm user={data?.user} onSaved={load} />
              },
              {
                key: 'pwd',
                label: (<span><LockOutlined /> 修改密码</span>),
                children: <PasswordForm />
              }
            ]}
          />
        </Card>
      </Col>
      <AvatarDialog
        open={avatarOpen}
        onClose={() => setAvatarOpen(false)}
        currentAvatar={avatar}
        onUploaded={(url) => {
          setAvatar(url);
          setAvatarOpen(false);
        }}
      />
    </Row>
  );
}

function BasicForm({ user, onSaved }: { user: any; onSaved: () => void }) {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (user) {
      form.setFieldsValue({
        nickName: user.nickName,
        email: user.email,
        phonenumber: user.phonenumber,
        sex: user.sex
      });
    }
  }, [user, form]);

  const onFinish = async (values: any) => {
    setLoading(true);
    try {
      await updateUserProfile({ ...user, ...values });
      message.success('保存成功');
      onSaved();
    } finally {
      setLoading(false);
    }
  };

  return (
    <Form form={form} layout="vertical" onFinish={onFinish} style={{ maxWidth: 520 }}>
      <Form.Item name="nickName" label="昵称" rules={[{ required: true }]}>
        <Input />
      </Form.Item>
      <Form.Item name="phonenumber" label="手机号" rules={[{ pattern: /^1\d{10}$/, message: '请输入正确的手机号' }]}>
        <Input />
      </Form.Item>
      <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入正确的邮箱' }]}>
        <Input />
      </Form.Item>
      <Form.Item name="sex" label="性别">
        <Select
          options={[
            { label: '男', value: '0' },
            { label: '女', value: '1' }
          ]}
          style={{ maxWidth: 200 }}
        />
      </Form.Item>
      <Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>
          保存修改
        </Button>
      </Form.Item>
    </Form>
  );
}

function PasswordForm() {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: any) => {
    setLoading(true);
    try {
      await updateUserPwd(values.oldPassword, values.newPassword);
      message.success('密码修改成功');
      form.resetFields();
    } finally {
      setLoading(false);
    }
  };

  return (
    <Form form={form} layout="vertical" onFinish={onFinish} style={{ maxWidth: 520 }}>
      <Form.Item name="oldPassword" label="旧密码" rules={[{ required: true }]}>
        <Input.Password />
      </Form.Item>
      <Form.Item
        name="newPassword"
        label="新密码"
        rules={[{ required: true, min: 6, max: 20, message: '长度在 6-20' }]}
      >
        <Input.Password />
      </Form.Item>
      <Form.Item
        name="confirmPassword"
        label="确认密码"
        dependencies={['newPassword']}
        rules={[
          { required: true },
          ({ getFieldValue }) => ({
            validator(_, value) {
              if (!value || getFieldValue('newPassword') === value) {
                return Promise.resolve();
              }
              return Promise.reject(new Error('两次输入的密码不一致'));
            }
          })
        ]}
      >
        <Input.Password />
      </Form.Item>
      <Form.Item>
        <Button type="primary" htmlType="submit" loading={loading}>
          修改密码
        </Button>
      </Form.Item>
    </Form>
  );
}


function AvatarDialog({
  open,
  onClose,
  currentAvatar,
  onUploaded
}: {
  open: boolean;
  onClose: () => void;
  currentAvatar: string;
  onUploaded: (url: string) => void;
}) {
  const [preview, setPreview] = useState<string>('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) {
      setFile(null);
      setPreview('');
    }
  }, [open]);

  const beforeUpload = (file: File) => {
    if (!file.type.startsWith('image/')) {
      message.error('请上传图片类型文件（JPG / PNG）');
      return Upload.LIST_IGNORE;
    }
    const reader = new FileReader();
    reader.onload = () => setPreview(String(reader.result));
    reader.readAsDataURL(file);
    setFile(file);
    return false; // 阻止自动上传
  };

  const handleUpload = async () => {
    if (!file) {
      message.warning('请先选择图片');
      return;
    }
    setLoading(true);
    try {
      const formData = new FormData();
      formData.append('avatarfile', file, file.name);
      const res: any = await uploadAvatar(formData);
      const url = (import.meta.env.VITE_APP_BASE_API || '') + (res.imgUrl || '');
      message.success('头像修改成功');
      onUploaded(url);
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      title="修改头像"
      open={open}
      onCancel={onClose}
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" loading={loading} onClick={handleUpload} disabled={!file}>
            提交
          </Button>
        </div>
      }
      width={520}
      destroyOnClose
    >
      <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
        <Avatar
          size={120}
          src={preview || currentAvatar}
          icon={<UserOutlined />}
          style={{ flexShrink: 0 }}
        />
        <div style={{ flex: 1 }}>
          <Upload beforeUpload={beforeUpload} showUploadList={false} accept="image/*">
            <Button icon={<UploadOutlined />}>选择图片</Button>
          </Upload>
          <div style={{ marginTop: 12, color: '#94a3b8', fontSize: 12 }}>
            支持 JPG / PNG / GIF 格式
            <br />
            建议尺寸 200 × 200 以上
          </div>
        </div>
      </div>
    </Modal>
  );
}

import React, { useState } from 'react';
import { Form, Input, Button, Card, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { updateUserPwd } from '@/api/system/user';

export default function ResetPwdPage() {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (values: { oldPassword: string; newPassword: string; confirmPassword: string }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error('两次输入的新密码不一致');
      return;
    }
    setLoading(true);
    try {
      await updateUserPwd(values.oldPassword, values.newPassword);
      message.success('密码修改成功');
      form.resetFields();
    } catch (e: any) {
      // 错误已由 request 拦截器处理
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card bordered={false} title="修改密码" style={{ maxWidth: 480 }}>
      <Form form={form} layout="vertical" onFinish={handleSubmit}>
        <Form.Item
          name="oldPassword"
          label="旧密码"
          rules={[{ required: true, message: '请输入旧密码' }]}
        >
          <Input.Password prefix={<LockOutlined />} placeholder="请输入旧密码" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 5, max: 20, message: '密码长度必须在 5-20 位之间' },
            { pattern: /(?=.*[a-zA-Z])(?=.*\d)/, message: '密码必须包含字母和数字' }
          ]}
        >
          <Input.Password prefix={<LockOutlined />} placeholder="请输入新密码（5-20位，含字母和数字）" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="确认新密码"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: '请确认新密码' },
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
          <Input.Password prefix={<LockOutlined />} placeholder="请再次输入新密码" />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading} block>
            确认修改
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}

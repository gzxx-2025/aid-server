import React, { useEffect, useState } from 'react';
import { Button, Card, Descriptions, Space, Table, message } from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { getAuthRole, updateAuthRole } from '@/api/system/user';

export default function AuthRolePage() {
  const { userId } = useParams();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [user, setUser] = useState<any>({});
  const [roles, setRoles] = useState<any[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const res: any = await getAuthRole(userId);
      setUser(res.user || {});
      const list = res.roles || [];
      setRoles(list);
      setSelectedKeys(list.filter((r: any) => r.flag).map((r: any) => r.roleId));
    } finally { setLoading(false); }
  };

  useEffect(() => { if (userId) load(); }, [userId]);

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateAuthRole({ userId, roleIds: selectedKeys.join(',') });
      message.success('授权成功');
      navigate(-1);
    } finally { setSaving(false); }
  };

  return (
    <Card className="page-card" bordered={false} title={
      <Space><Button type="link" icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>返回</Button>分配角色</Space>
    }>
      <Descriptions column={2} size="small" style={{ marginBottom: 16 }}>
        <Descriptions.Item label="用户名称">{user.userName || '-'}</Descriptions.Item>
        <Descriptions.Item label="用户昵称">{user.nickName || '-'}</Descriptions.Item>
      </Descriptions>
      <Table
        rowKey="roleId"
        loading={loading}
        dataSource={roles}
        pagination={false}
        size="middle"
        rowSelection={{ selectedRowKeys: selectedKeys, onChange: setSelectedKeys }}
        columns={[
          { title: '序号', render: (_: any, __: any, i: number) => i + 1, width: 80 },
          { title: '角色编号', dataIndex: 'roleId', width: 120 },
          { title: '角色名称', dataIndex: 'roleName', width: 200 },
          { title: '权限字符', dataIndex: 'roleKey', width: 200 },
          { title: '创建时间', dataIndex: 'createTime', width: 180 }
        ]}
      />
      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Space>
          <Button onClick={() => navigate(-1)}>取消</Button>
          <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={handleSave}>保存授权</Button>
        </Space>
      </div>
    </Card>
  );
}

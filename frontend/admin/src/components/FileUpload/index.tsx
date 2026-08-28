import React, { useState } from 'react';
import { Upload, Button, message } from 'antd';
import type { UploadProps, UploadFile } from 'antd';
import { UploadOutlined, DeleteOutlined } from '@ant-design/icons';

import { getToken } from '@/utils/auth';
import { loadUploadLimits, resolveMaxSizeMb } from '@/utils/uploadLimits';

interface Props {
  value?: string; // 逗号分隔的多文件 url
  onChange?: (value: string) => void;
  action?: string;
  accept?: string;
  maxCount?: number;
  maxSize?: number; // MB
  multiple?: boolean;
}

const DEFAULT_ACTION = import.meta.env.VITE_APP_BASE_API + '/common/upload';

export default function FileUpload({
  value,
  onChange,
  action = DEFAULT_ACTION,
  accept,
  maxCount = 5,
  maxSize = 20,
  multiple = false
}: Props) {
  const initialFiles: UploadFile[] = (value ? value.split(',').filter(Boolean) : []).map(
    (url, idx) => ({
      uid: String(-idx - 1),
      name: url.split('/').pop() || `文件${idx + 1}`,
      status: 'done',
      url
    })
  );
  const [fileList, setFileList] = useState<UploadFile[]>(initialFiles);

  const uploadProps: UploadProps = {
    action,
    accept,
    multiple,
    maxCount,
    fileList,
    headers: { Authorization: getToken() ? `Bearer ${getToken()}` : '' },
    beforeUpload: async (file) => {
      // 优先遵循后台「文件存储 → 上传大小限制」配置（按文件后缀命中类型）；拿不到再回退组件 maxSize
      const limits = await loadUploadLimits();
      const backendMax = resolveMaxSizeMb(file.name, limits);
      const effectiveMax = backendMax ?? maxSize;
      if (effectiveMax && file.size / 1024 / 1024 > effectiveMax) {
        message.error(`文件大小不能超过 ${effectiveMax}MB`);
        return Upload.LIST_IGNORE;
      }
      return true;
    },
    onChange: ({ file, fileList: list }) => {
      // 上传失败提示：antd 对 HTTP 非 2xx 只标红不提示，nginx 413（请求体超 client_max_body_size）会返回
      // 原始 HTML，这里翻译成可读文案，避免用户只看到红色列表项却不知道原因
      if (file.status === 'error') {
        const httpStatus = (file.error as any)?.status;
        const raw = typeof file.response === 'string' ? file.response : '';
        if (httpStatus === 413 || raw.includes('413') || /Request Entity Too Large/i.test(raw)) {
          message.error('文件过大，被服务器拒收(413)，请调大 nginx client_max_body_size 后重试');
        } else {
          message.error(`${file.name} 上传失败，请重试`);
        }
      }
      // 业务失败提示：HTTP 200 但业务 code 非 200 时透出后端 msg
      if (file.status === 'done' && typeof file.response?.code === 'number' && file.response.code !== 200) {
        message.error(file.response.msg || `${file.name} 上传失败`);
      }
      const next = list.slice(-maxCount);
      setFileList(next);
      const done = next.filter((f) => f.status === 'done');
      const urls = done
        .map((f) => (f.response?.url || f.response?.fileName || f.url))
        .filter(Boolean)
        .join(',');
      onChange?.(urls);
    }
  };

  return (
    <Upload {...uploadProps}>
      <Button icon={<UploadOutlined />}>点击上传</Button>
    </Upload>
  );
}

import React, { useEffect, useRef, useState } from 'react';
import { Upload, message, Modal } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { UploadProps, UploadFile } from 'antd';

import { getToken } from '@/utils/auth';
import { loadUploadLimits, resolveMaxSizeMb } from '@/utils/uploadLimits';

interface Props {
  value?: string; // 逗号分隔
  onChange?: (value: string) => void;
  action?: string;
  maxCount?: number;
  maxSize?: number;
  accept?: string;
  /** 上传字段名（默认 antd 的 'file'；统一OSS上传需传 'files'） */
  name?: string;
  /** 自定义从单次上传响应体中解析出 url（兼容不同上传接口的返回结构） */
  parseResponse?: (resp: any) => string | undefined;
}

const DEFAULT_ACTION = `${import.meta.env.VITE_APP_BASE_API || ''}/common/upload`;

/** 默认解析：兼容 {url} / {fileName} 结构，以及统一OSS上传的 {data:[{url}]} 结构 */
function defaultParse(resp: any): string | undefined {
  if (!resp) return undefined;
  if (resp.url) return resp.url;
  if (Array.isArray(resp.data) && resp.data[0]?.url) return resp.data[0].url;
  if (resp.data?.url) return resp.data.url;
  return resp.fileName;
}

export default function ImageUpload({
  value,
  onChange,
  action = DEFAULT_ACTION,
  maxCount = 5,
  maxSize = 10,
  accept = 'image/*',
  name,
  parseResponse
}: Props) {
  const initial: UploadFile[] = (value ? value.split(',').filter(Boolean) : []).map(
    (url, idx) => ({
      uid: String(-idx - 1),
      name: url.split('/').pop() || `图片${idx + 1}`,
      status: 'done',
      url
    })
  );

  const [fileList, setFileList] = useState<UploadFile[]>(initial);
  const [preview, setPreview] = useState<{ visible: boolean; url?: string }>({
    visible: false
  });

  /**
   * 记录最近一次由本组件 onChange 向外抛出的值，
   * 用于区分"外部回填（编辑/重置）"与"自身上传触发的变更"。
   */
  const lastEmittedRef = useRef<string | undefined>(value);

  /**
   * 受控同步：当外部 value 变化（如编辑弹窗回填、表单重置）且并非本组件自身 onChange 引起时，
   * 依据新的 value 重建展示列表。上传中不会触发（上传中 value 不变），避免打断进行中的上传。
   */
  useEffect(() => {
    if (value === lastEmittedRef.current) {
      return;
    }
    const list: UploadFile[] = (value ? value.split(',').filter(Boolean) : []).map(
      (url, idx) => ({
        uid: String(-idx - 1),
        name: url.split('/').pop() || `图片${idx + 1}`,
        status: 'done',
        url
      })
    );
    setFileList(list);
    lastEmittedRef.current = value;
  }, [value]);

  const props: UploadProps = {
    action,
    accept,
    maxCount,
    name,
    listType: 'picture-card',
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
      // 业务失败提示：上传接口 HTTP 200 但业务 code 非 200（类型不允许、超业务大小等），antd 会当作成功，
      // 这里把后端 msg 透出，避免"看似上传成功但表单里没有值"的困惑
      const businessFailed =
        file.status === 'done' && typeof file.response?.code === 'number' && file.response.code !== 200;
      if (businessFailed) {
        message.error(file.response.msg || `${file.name} 上传失败`);
      }
      // HTTP 200 不代表业务成功。把失败文件标成 error，避免错误缩略图伪装成已上传资源。
      const next = list
        .map((item) => (item.uid === file.uid && businessFailed ? { ...item, status: 'error' as const } : item))
        .slice(-maxCount);
      setFileList(next);
      const done = next.filter((f) => f.status === 'done');
      const parse = parseResponse || defaultParse;
      const urls = done
        .map((f) => parse(f.response) || f.url)
        .filter(Boolean)
        .join(',');
      // 记录本次向外抛出的值，避免受控同步 useEffect 把上传结果又重置回去
      lastEmittedRef.current = urls;
      onChange?.(urls);
    },
    onPreview: (file) => {
      setPreview({ visible: true, url: file.url || (file as any).thumbUrl });
    }
  };

  return (
    <>
      <Upload {...props}>
        {fileList.length < maxCount && (
          <div>
            <PlusOutlined />
            <div style={{ marginTop: 4, fontSize: 12 }}>上传</div>
          </div>
        )}
      </Upload>
      <Modal
        open={preview.visible}
        footer={null}
        onCancel={() => setPreview({ visible: false })}
        title="图片预览"
      >
        <img style={{ width: '100%' }} src={preview.url} alt="preview" />
      </Modal>
    </>
  );
}

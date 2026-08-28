import React, { useEffect, useState } from 'react';
import '@wangeditor/editor/dist/css/style.css';
import { Editor, Toolbar } from '@wangeditor/editor-for-react';
import { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor';

export interface RichTextEditorProps {
  /** 受控值（HTML 字符串），可直接用于 antd Form.Item */
  value?: string;
  /** 内容变化回调（返回 HTML 字符串） */
  onChange?: (html: string) => void;
  /** 占位提示 */
  placeholder?: string;
  /** 编辑区高度，默认 320 */
  height?: number;
  /** 是否只读 */
  readOnly?: boolean;
}

/**
 * 通用富文本编辑器（基于 wangEditor v5）。
 *
 * - 受控组件，value/onChange 直接对接 antd Form.Item；
 * - 默认排除需要服务端配合的「上传图片/上传视频/全屏」菜单，保留插入图片(URL)/链接等纯前端能力，
 *   避免未配置上传服务时报错。如需接入 OSS 上传，可在 toolbar 中放开并配置 MENU_CONF.uploadImage。
 */
export default function RichTextEditor({
  value,
  onChange,
  placeholder = '请输入内容…',
  height = 320,
  readOnly = false
}: RichTextEditorProps) {
  const [editor, setEditor] = useState<IDomEditor | null>(null);
  const [html, setHtml] = useState<string>(value || '');

  // 外部 value 变化时同步（主要用于弹窗打开时回填）
  useEffect(() => {
    if (typeof value === 'string' && value !== html) {
      setHtml(value);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  // 组件销毁时及时销毁编辑器，避免内存泄漏
  useEffect(() => {
    return () => {
      if (editor) {
        editor.destroy();
        setEditor(null);
      }
    };
  }, [editor]);

  const toolbarConfig: Partial<IToolbarConfig> = {
    excludeKeys: ['uploadImage', 'uploadVideo', 'group-video', 'fullScreen', 'codeBlock']
  };

  const editorConfig: Partial<IEditorConfig> = {
    placeholder,
    readOnly,
    MENU_CONF: {}
  };

  return (
    <div style={{ border: '1px solid #d9d9d9', borderRadius: 8, overflow: 'hidden', zIndex: 100 }}>
      {!readOnly && (
        <Toolbar
          editor={editor}
          defaultConfig={toolbarConfig}
          mode="default"
          style={{ borderBottom: '1px solid #f0f0f0' }}
        />
      )}
      <Editor
        defaultConfig={editorConfig}
        value={html}
        onCreated={setEditor}
        onChange={(e) => {
          const next = e.isEmpty() ? '' : e.getHtml();
          setHtml(e.getHtml());
          onChange?.(next);
        }}
        mode="default"
        style={{ height, overflowY: 'auto' }}
      />
    </div>
  );
}

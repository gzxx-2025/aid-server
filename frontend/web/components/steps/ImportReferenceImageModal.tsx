'use client'

import { useState } from 'react'
import { Modal, Upload, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useStackedModalZIndex } from '~/hooks/useStackedModalZIndex'
import './ImportReferenceImageModal.css'

export interface ImportReferenceImageModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onImport: (file: File) => void
}

export function ImportReferenceImageModal({
  open,
  onOpenChange,
  onImport
}: ImportReferenceImageModalProps) {
  const [fileList, setFileList] = useState<any[]>([])
  const modalZIndex = useStackedModalZIndex(open)

  const beforeUpload = (file: File) => {
    const isImage = file.type.startsWith('image/')
    if (!isImage) {
      message.error('只能上传图片文件')
      return false
    }
    const isLt10M = file.size / 1024 / 1024 < 10
    if (!isLt10M) {
      message.error('图片大小不能超过 10MB')
      return false
    }
    setFileList([file])
    return false // 阻止自动上传
  }

  const handleConfirm = () => {
    if (fileList.length === 0) {
      message.warning('请选择要上传的图片')
      return
    }
    onImport(fileList[0].originFileObj || fileList[0])
    onOpenChange(false)
    setFileList([])
  }

  const handleCancel = () => {
    onOpenChange(false)
    setFileList([])
  }

  return (
    <Modal
      open={open}
      title="导入参考图"
      width={600}
      zIndex={modalZIndex}
      wrapClassName="create-flow-modal"
      onOk={handleConfirm}
      onCancel={handleCancel}
    >
      <div className="import-reference-content">
        <Upload
          fileList={fileList}
          beforeUpload={beforeUpload}
          listType="picture-card"
          maxCount={1}
          accept="image/*"
        >
          {fileList.length < 1 ? (
            <div>
              <PlusOutlined />
              <div style={{ marginTop: 8 }}>上传</div>
            </div>
          ) : null}
        </Upload>
        <p className="upload-tip">支持 JPG、PNG 格式，最大 10MB</p>
      </div>
    </Modal>
  )
}

export default ImportReferenceImageModal

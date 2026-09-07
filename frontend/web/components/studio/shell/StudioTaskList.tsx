'use client'

import { useMemo, useState } from 'react'
import { Button, Progress, message } from 'antd'
import { CheckOutlined, CloseOutlined, StopOutlined } from '@ant-design/icons'
import { selectStudioTasks, useStudioUiStore } from '@/stores/studioUi'
import type { StudioTaskStatus } from '@/types/studio'
import { requestCancelUserTaskById } from '@/utils/userTaskCancelFlow'

const TASK_STATUS_LABEL: Record<StudioTaskStatus, string> = {
  queued: '排队中',
  running: '执行中',
  waiting: '待确认',
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消'
}

export function StudioTaskList({ limit = 8 }: { limit?: number }) {
  const allTasks = useStudioUiStore(selectStudioTasks)
  const tasks = useMemo(() => allTasks.slice(0, limit), [allTasks, limit])
  const [cancellingTaskId, setCancellingTaskId] = useState('')

  const cancelRemoteTask = async (taskId: string, remoteTaskId: number) => {
    if (cancellingTaskId) return
    setCancellingTaskId(taskId)
    useStudioUiStore.getState().patchTask(taskId, { stage: '正在请求后端取消' })
    try {
      await requestCancelUserTaskById(remoteTaskId)
      useStudioUiStore.getState().patchTask(taskId, { stage: '取消请求已发送，等待任务终态' })
      message.success('已请求后端取消任务')
    } catch (error: unknown) {
      const errorMessage = (error as Error)?.message || '后端取消失败'
      useStudioUiStore.getState().patchTask(taskId, { stage: '取消失败', errorMessage })
      message.error(errorMessage)
    } finally {
      setCancellingTaskId('')
    }
  }

  if (!tasks.length) {
    return <div className="studio-panel-empty">暂无生成任务。</div>
  }

  return (
    <div className="studio-task-list">
      {tasks.map((task) => {
        const remoteTaskId = task.remoteTaskId ?? studioRemoteTaskId(task.id)
        return (
        <article key={task.id} className={`studio-task studio-task--${task.status}`}>
          <div className="studio-task__header">
            <strong>{task.title}</strong>
            <span>{TASK_STATUS_LABEL[task.status]}</span>
          </div>
          <p>{task.stage}</p>
          <Progress
            percent={task.progress}
            size="small"
            showInfo={false}
            status={task.status === 'failed' ? 'exception' : task.status === 'succeeded' ? 'success' : 'active'}
          />
          {task.errorMessage ? <div className="studio-task__error">{task.errorMessage}</div> : null}
          <div className="studio-task__actions">
            {remoteTaskId && (task.status === 'running' || task.status === 'queued' || task.status === 'waiting') ? (
              <Button size="small" icon={<StopOutlined />} loading={cancellingTaskId === task.id} onClick={() => void cancelRemoteTask(task.id, remoteTaskId)}>
                取消任务
              </Button>
            ) : null}
            {task.status === 'succeeded' ? <span><CheckOutlined /> 结果已写入画布</span> : null}
            {task.status === 'cancelled' ? <span><CloseOutlined /> 任务已取消</span> : null}
          </div>
        </article>
      )})}
    </div>
  )
}

function studioRemoteTaskId(value: string): number | null {
  const match = /^studio-(?:image|video)-(\d+)$/.exec(value)
  if (!match) return null
  const id = Number(match[1])
  return Number.isFinite(id) && id > 0 ? id : null
}

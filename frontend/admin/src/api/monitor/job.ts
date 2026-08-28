import { request } from '@/utils/request'

// 查询定时任务调度列表
export function listJob(query) {
  return request({
    url: '/monitor/job/list',
    method: 'get',
    params: query
  })
}

// 查询定时任务调度详细
export function getJob(jobId) {
  return request({
    url: '/monitor/job/' + jobId,
    method: 'get'
  })
}

// 新增定时任务调度
export function addJob(data) {
  return request({
    url: '/monitor/job',
    method: 'post',
    data: data
  })
}

// 修改定时任务调度
export function updateJob(data) {
  return request({
    url: '/monitor/job',
    method: 'put',
    data: data
  })
}

// 删除定时任务调度
export function delJob(jobId) {
  return request({
    url: '/monitor/job/' + jobId,
    method: 'delete'
  })
}

// 任务状态修改
export function changeJobStatus(jobId, status) {
  const data = {
    jobId,
    status
  }
  return request({
    url: '/monitor/job/changeStatus',
    method: 'put',
    data: data
  })
}


// 查询当前数据库承载压力（动态推荐频率的计算依据）
export function getDbPressure() {
  return request({
    url: '/monitor/job/dbPressure',
    method: 'get'
  })
}

// 查询系统任务动态推荐频率（按数据库压力计算）
export function getFrequencyAdvice() {
  return request({
    url: '/monitor/job/frequencyAdvice',
    method: 'get'
  })
}

// 定时任务立即执行一次
export function runJob(jobId, jobGroup) {
  const data = {
    jobId,
    jobGroup
  }
  return request({
    url: '/monitor/job/run',
    method: 'put',
    data: data
  })
}
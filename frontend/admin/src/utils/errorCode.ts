/**
 * 统一错误码映射
 * 与后端 com.ruoyi.common.core.domain.AjaxResult 中的 code 对应
 */
const errorCode: Record<string, string> = {
  '400': '请求参数错误',
  '401': '认证失败，无法访问系统资源',
  '403': '当前操作没有权限',
  '404': '访问资源不存在',
  '408': '请求超时',
  '429': '请求过于频繁，请稍后再试',
  '500': '服务器内部错误',
  '501': '服务未实现',
  '502': '网关错误',
  '503': '服务不可用',
  '601': '运行时异常',
  default: '系统未知错误，请反馈给管理员'
};

export default errorCode;

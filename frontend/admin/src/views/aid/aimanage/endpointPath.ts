const MAX_ENDPOINT_LENGTH = 500;
const PERCENT_ESCAPE = /^[0-9a-f]{2}$/i;

/** 校验并规范化供应商相对端点。 */
export function normalizeRelativeEndpoint(value: string, taskTemplate: boolean): string {
  const endpoint = String(value || '').trim();
  if (!endpoint) throw new Error(taskTemplate ? '请输入任务查询路径' : '请输入模型接口路径');
  if (endpoint.length > MAX_ENDPOINT_LENGTH) throw new Error('接口路径不能超过500个字符');
  if (!endpoint.startsWith('/') || endpoint.startsWith('//')) throw new Error('必须填写以 / 开头的相对路径');
  if (/[\\\r\n\t]/.test(endpoint)) throw new Error('接口路径含非法字符');
  if (endpoint.includes('://') || endpoint.includes('#')
    || /%2f|%5c|%2e|%0a|%0d|%09/i.test(endpoint)) {
    throw new Error('接口路径不安全');
  }
  const rawPath = endpoint.split('?', 1)[0];
  if (rawPath.length > 1 && rawPath.includes('//')) throw new Error('接口路径不能包含双斜杠');
  if (rawPath.split('/').some((segment) => segment === '.' || segment === '..')) {
    throw new Error('接口路径不能包含路径穿越');
  }

  let placeholderCount = 0;
  for (let index = 0; index < endpoint.length; index += 1) {
    if (endpoint[index] !== '%') continue;
    if (endpoint.startsWith('%s', index)) {
      placeholderCount += 1;
      index += 1;
      continue;
    }
    if (!PERCENT_ESCAPE.test(endpoint.slice(index + 1, index + 3))) {
      throw new Error('接口路径含非法占位符');
    }
    index += 2;
  }
  if (taskTemplate && placeholderCount !== 1) throw new Error('查询路径必须包含且仅包含一个 %s');
  if (!taskTemplate && placeholderCount > 0) throw new Error('提交路径不能包含 %s');
  return endpoint;
}

export function validateRelativeEndpoint(taskTemplate: boolean) {
  return async (_: unknown, value?: string) => {
    if (!value?.trim()) return;
    normalizeRelativeEndpoint(value, taskTemplate);
  };
}

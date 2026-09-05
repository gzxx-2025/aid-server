export const MAX_RESOURCE_BYTES = 100 * 1024;
export const MAX_RESOURCE_TOTAL_BYTES = 512 * 1024;

export function utf8ByteLength(value: string) {
  return new TextEncoder().encode(value).length;
}

export function validateResourceContentBytes(resources: Array<{ content?: string }>) {
  let totalBytes = 0;
  for (const [index, resource] of resources.entries()) {
    const size = utf8ByteLength(resource.content || '');
    if (size > MAX_RESOURCE_BYTES) return `资源 ${index + 1} 内容不能超过 100 KiB（UTF-8）`;
    totalBytes += size;
  }
  if (totalBytes > MAX_RESOURCE_TOTAL_BYTES) return '资源内容合计不能超过 512 KiB（UTF-8）';
  return undefined;
}

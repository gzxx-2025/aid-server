/** 路径匹配器 —— 安全版本，限制 ** 展开范围 */
export function isPathMatch(pattern: string, path: string): boolean {
  // 对 pattern 中的特殊正则字符进行转义（除了 * 和 /）
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
  const regexPattern = escaped
    .replace(/\//g, '\\/')
    .replace(/\*\*/g, '{{GLOBSTAR}}')
    .replace(/\*/g, '[^\\/]*')
    .replace(/\{\{GLOBSTAR\}\}/g, '(?:[^\\/]+\\/)*[^\\/]*');
  try {
    const regex = new RegExp(`^${regexPattern}$`);
    return regex.test(path);
  } catch {
    return pattern === path;
  }
}

/** 是否为空 */
export function isEmpty(value: unknown): boolean {
  return (
    value == null ||
    value === '' ||
    value === 'undefined' ||
    typeof value === 'undefined'
  );
}

/** 是否 http/https */
export function isHttp(url: string): boolean {
  return url.indexOf('http://') !== -1 || url.indexOf('https://') !== -1;
}

/** 是否外链 */
export function isExternal(path: string): boolean {
  return /^(https?:|mailto:|tel:)/.test(path);
}

/** 邮箱 */
export function validEmail(email: string): boolean {
  const reg =
    /^(([^<>()\[\]\\.,;:\s@"]+(\.[^<>()\[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
  return reg.test(email);
}

/** URL */
export function validURL(url: string): boolean {
  const reg =
    /^(https?|ftp):\/\/([a-zA-Z0-9.-]+(:[a-zA-Z0-9.&%$-]+)*@)*((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]?)(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}|([a-zA-Z0-9-]+\.)*[a-zA-Z0-9-]+\.(com|edu|gov|int|mil|net|org|biz|arpa|info|name|pro|aero|coop|museum|[a-zA-Z]{2}))(:[0-9]+)*(\/($|[a-zA-Z0-9.,?'\\+&%$#=~_-]+))*$/;
  return reg.test(url);
}

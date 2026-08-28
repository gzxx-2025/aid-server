const DEFAULT_CALLBACK_PATH = '/api/media/callback/provider';

const PROVIDER_CALLBACK_PATHS: Record<string, string> = {
  kling: '/api/media/callback/kling',
  minimax: '/api/media/callback/minimax-h3',
  vidu: '/api/media/callback/vidu'
};

export function normalizeCallbackProviderCode(providerCode: string | null | undefined): string {
  return (providerCode || '').trim().toLowerCase();
}

export function resolveProviderCallbackPath(providerCode: string | null | undefined): string {
  return PROVIDER_CALLBACK_PATHS[normalizeCallbackProviderCode(providerCode)] || DEFAULT_CALLBACK_PATH;
}

/** Returns a user-facing validation error, or null when the optional value is usable. */
export function validateProviderCallbackUrl(
  providerCode: string | null | undefined,
  value: string | null | undefined
): string | null {
  const normalizedProviderCode = normalizeCallbackProviderCode(providerCode);
  if (!value?.trim() || (normalizedProviderCode !== 'kling' && normalizedProviderCode !== 'minimax')) {
    return null;
  }

  const providerName = normalizedProviderCode === 'kling' ? '可灵' : 'MiniMax H3';
  const expectedPath = PROVIDER_CALLBACK_PATHS[normalizedProviderCode];
  try {
    const parsed = new URL(value.trim());
    if (parsed.protocol !== 'https:') {
      return `${providerName} 回调地址必须使用 HTTPS`;
    }
    const hasUnsupportedUrlParts =
      !parsed.hostname ||
      parsed.username ||
      parsed.password ||
      parsed.hash ||
      (normalizedProviderCode === 'minimax' && parsed.search);
    if (hasUnsupportedUrlParts) {
      return `请输入合法的${providerName} HTTPS 回调地址`;
    }
    const pathname = parsed.pathname.replace(/\/$/, '');
    if (!pathname.endsWith(expectedPath)) {
      return `${providerName} 回调地址必须以 ${expectedPath} 结尾`;
    }
    return null;
  } catch {
    return `请输入合法的${providerName} HTTPS 回调地址`;
  }
}

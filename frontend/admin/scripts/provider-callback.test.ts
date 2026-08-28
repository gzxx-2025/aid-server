import assert from 'node:assert/strict';
import {
  normalizeCallbackProviderCode,
  resolveProviderCallbackPath,
  validateProviderCallbackUrl
} from '../src/views/aid/aimanage/providerCallback.ts';

assert.equal(normalizeCallbackProviderCode(' MiniMax '), 'minimax');
assert.equal(resolveProviderCallbackPath('minimax'), '/api/media/callback/minimax-h3');
assert.equal(resolveProviderCallbackPath(' KLING '), '/api/media/callback/kling');
assert.equal(resolveProviderCallbackPath('vidu'), '/api/media/callback/vidu');
assert.equal(resolveProviderCallbackPath('other'), '/api/media/callback/provider');

// MiniMax 回调地址可留空；服务端会自动降级为轮询。
assert.equal(validateProviderCallbackUrl('minimax', undefined), null);
assert.equal(validateProviderCallbackUrl('minimax', '   '), null);
assert.equal(validateProviderCallbackUrl('minimax', 'https://api.example.com/api/media/callback/minimax-h3'), null);
assert.equal(
  validateProviderCallbackUrl('MINIMAX', 'https://api.example.com/gateway/api/media/callback/minimax-h3/'),
  null
);
assert.match(
  validateProviderCallbackUrl('minimax', 'http://api.example.com/api/media/callback/minimax-h3') || '',
  /HTTPS/
);
assert.match(
  validateProviderCallbackUrl('minimax', 'https://api.example.com/api/media/callback/provider') || '',
  /\/api\/media\/callback\/minimax-h3/
);
assert.match(
  validateProviderCallbackUrl('minimax', 'https://api.example.com/api/media/callback/minimax-h3?token=x') || '',
  /合法/
);

// 抽取公共逻辑后，原有可灵 HTTPS/路径约束仍保持。
assert.equal(validateProviderCallbackUrl('kling', 'https://api.example.com/api/media/callback/kling'), null);
assert.equal(validateProviderCallbackUrl('kling', 'https://api.example.com/api/media/callback/kling?token=x'), null);
assert.match(
  validateProviderCallbackUrl('kling', 'https://api.example.com/api/media/callback/provider') || '',
  /\/api\/media\/callback\/kling/
);
assert.equal(validateProviderCallbackUrl('vidu', 'http://api.example.com/api/media/callback/vidu'), null);

console.log('provider callback contract passed');

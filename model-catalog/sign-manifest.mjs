import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

const [, , keyPath, manifestPath] = process.argv;
if (!keyPath || !manifestPath) throw new Error('Usage: node sign-manifest.mjs <key> <manifest>');

const rawKey = Buffer.from((await readFile(keyPath, 'utf8')).trim(), 'base64');
if (rawKey.length !== 64) throw new Error('Invalid Ed25519 private key');
const pkcs8Prefix = Buffer.from('302e020100300506032b657004220420', 'hex');
const privateKey = createPrivateKey({
  key: Buffer.concat([pkcs8Prefix, rawKey.subarray(0, 32)]),
  format: 'der',
  type: 'pkcs8'
});

const stableStringify = (value) => {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
};

const document = JSON.parse(await readFile(manifestPath, 'utf8'));
delete document.signature;
const payload = Buffer.from(stableStringify(document), 'utf8');
const signature = sign(null, payload, privateKey);
if (!verify(null, payload, createPublicKey(privateKey), signature)) throw new Error('Signature verification failed');
document.signature = {
  algorithm: 'Ed25519',
  payload: payload.toString('base64'),
  value: signature.toString('base64')
};
await writeFile(manifestPath, `${JSON.stringify(document, null, 2)}\n`, 'utf8');

import { JSEncrypt } from 'jsencrypt';

/**
 * RSA 公钥 —— 仅用于前端加密传输密码到后端
 * 私钥由后端保管，前端不应持有私钥
 * 如需更换密钥对，请同步修改后端解密配置
 */
const publicKey =
  'MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAMvxP+CCmF06p03tGJ/zrAoFbiotBCxJpz/QeCrFBE2gYZoVZqCR/m4QSaAxx+ot4N+Wi4n+s8NdSQXLa9REI20CAwEAAQ==';

/**
 * 前端仅提供加密能力，解密由后端完成
 */
export function encrypt(txt: string): string | false {
  const encryptor = new JSEncrypt();
  encryptor.setPublicKey(publicKey);
  return encryptor.encrypt(txt);
}

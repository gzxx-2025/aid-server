package com.aid.auth.service;

import com.aid.auth.domain.vo.CryptoPublicKeyVO;

/**
 * 接口加密辅助服务。
 *
 * 对外仅提供“下发 RSA 公钥”能力，供前端获取公钥后加密一次性 AES 密钥。
 * 私钥仅服务端持有，绝不下发。
 *
 * @author 视觉AID
 */
public interface ApiCryptoService {

    /**
     * 获取接口加密公钥与服务端时间。
     *
     * @return 公钥 VO（含算法标识与服务端时间戳）
     */
    CryptoPublicKeyVO getPublicKey();
}

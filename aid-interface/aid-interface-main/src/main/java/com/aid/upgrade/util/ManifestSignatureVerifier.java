package com.aid.upgrade.util;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import cn.hutool.core.util.StrUtil;

/**
 * 验证统一升级清单的 Ed25519 签名。
 */
public final class ManifestSignatureVerifier {

    private static final String SIGNATURE_FIELD = "signature";
    private static final String ALGORITHM_ED25519 = "Ed25519";
    private static final int ED25519_PUBLIC_KEY_BYTES = 32;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ManifestSignatureVerifier() {
    }

    public static boolean verify(String rawJson, String publicKeyBase64) {
        if (StrUtil.isBlank(rawJson) || StrUtil.isBlank(publicKeyBase64)) {
            return false;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(rawJson);
            if (!(parsed instanceof ObjectNode root)) {
                return false;
            }
            JsonNode signatureNode = root.get(SIGNATURE_FIELD);
            if (Objects.isNull(signatureNode)
                    || !Objects.equals(ALGORITHM_ED25519, signatureNode.path("algorithm").asText())) {
                return false;
            }
            byte[] payload = Base64.getDecoder().decode(signatureNode.path("payload").asText());
            byte[] signatureBytes = Base64.getDecoder().decode(signatureNode.path("value").asText());

            ObjectNode unsigned = root.deepCopy();
            unsigned.remove(SIGNATURE_FIELD);
            JsonNode payloadNode = OBJECT_MAPPER.readTree(payload);
            if (!unsigned.equals(payloadNode)) {
                return false;
            }

            byte[] rawPublicKey = Base64.getDecoder().decode(publicKeyBase64.trim());
            if (rawPublicKey.length != ED25519_PUBLIC_KEY_BYTES) {
                return false;
            }
            java.security.PublicKey publicKey = decodePublicKey(rawPublicKey);
            Signature verifier = Signature.getInstance(ALGORITHM_ED25519);
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static java.security.PublicKey decodePublicKey(byte[] encodedPoint) throws Exception {
        byte[] yLittleEndian = encodedPoint.clone();
        boolean xOdd = (yLittleEndian[ED25519_PUBLIC_KEY_BYTES - 1] & 0x80) != 0;
        yLittleEndian[ED25519_PUBLIC_KEY_BYTES - 1] &= 0x7f;
        byte[] yBigEndian = new byte[ED25519_PUBLIC_KEY_BYTES];
        for (int i = 0; i < ED25519_PUBLIC_KEY_BYTES; i++) {
            yBigEndian[i] = yLittleEndian[ED25519_PUBLIC_KEY_BYTES - 1 - i];
        }
        EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, yBigEndian));
        EdECPublicKeySpec keySpec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, point);
        return KeyFactory.getInstance(ALGORITHM_ED25519).generatePublic(keySpec);
    }
}

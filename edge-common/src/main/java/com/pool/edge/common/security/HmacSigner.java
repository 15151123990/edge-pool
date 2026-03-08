package com.pool.edge.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 签名工具。
 * 用于边缘端与控制面通信的请求签名与验签。
 */
public final class HmacSigner {
    private static final String HMAC_SHA256 = "HmacSHA256";

    private HmacSigner() {}

    /**
     * 生成签名。
     *
     * @param secret 签名密钥
     * @param body 请求体原文
     * @param ts 请求时间戳
     * @param deviceId 设备 ID
     * @return Base64 编码签名
     */
    public static String sign(String secret, String body, String ts, String deviceId) {
        try {
            // 签名原文约定：时间戳.设备ID.请求体
            String payload = ts + "." + deviceId + "." + body;
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("sign payload failed", e);
        }
    }

    /**
     * 验签。
     *
     * @param secret 签名密钥
     * @param body 请求体原文
     * @param ts 请求时间戳
     * @param deviceId 设备 ID
     * @param signature 待验证签名
     * @return true 表示签名有效
     */
    public static boolean verify(String secret, String body, String ts, String deviceId, String signature) {
        // 先按同样规则重算签名，再做常量时间比较
        String expected = sign(secret, body, ts, deviceId);
        return constantTimeEquals(expected, signature);
    }

    /**
     * 常量时间字符串比较，避免时序攻击。
     *
     * @param a 字符串 A
     * @param b 字符串 B
     * @return 是否相等
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}

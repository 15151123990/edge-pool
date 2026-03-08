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
    private static final long DEFAULT_TTL_MS = 60_000L;

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
     * 验签（使用默认 TTL）。
     *
     * @param secret 签名密钥
     * @param body 请求体原文
     * @param ts 请求时间戳
     * @param deviceId 设备 ID
     * @param signature 待验证签名
     * @return true 表示签名有效且未过期
     */
    public static boolean verify(String secret, String body, String ts, String deviceId, String signature) {
        return verify(secret, body, ts, deviceId, signature, DEFAULT_TTL_MS);
    }

    /**
     * 验签（指定 TTL）。
     *
     * @param secret 签名密钥
     * @param body 请求体原文
     * @param ts 请求时间戳
     * @param deviceId 设备 ID
     * @param signature 待验证签名
     * @param ttlMs 时间戳有效期（毫秒）
     * @return true 表示签名有效且未过期
     */
    public static boolean verify(String secret, String body, String ts, String deviceId, String signature, long ttlMs) {
        if (!isTimestampValid(ts, ttlMs)) {
            return false;
        }
        String expected = sign(secret, body, ts, deviceId);
        return constantTimeEquals(expected, signature);
    }

    /**
     * 检查时间戳是否在有效期内。
     *
     * @param ts 时间戳字符串
     * @param ttlMs 有效期（毫秒）
     * @return true 表示时间戳有效
     */
    private static boolean isTimestampValid(String ts, long ttlMs) {
        try {
            long timestamp = Long.parseLong(ts);
            long now = System.currentTimeMillis();
            long diff = Math.abs(now - timestamp);
            return diff <= ttlMs;
        } catch (NumberFormatException e) {
            return false;
        }
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

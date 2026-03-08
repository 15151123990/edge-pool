package com.pool.edge.control.service;

import com.pool.edge.common.security.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 控制面签名服务。
 * 统一执行边缘请求验签。
 */
@Service
public class SignatureService {
    @Value("${cloud.sign.secret:dev-secret}")
    private String secret;

    /**
     * 验证请求签名。
     *
     * @param body 请求体原文
     * @param ts 时间戳
     * @param deviceId 设备 ID
     * @param signature 请求签名
     * @return true 表示签名有效
     */
    public boolean verify(String body, String ts, String deviceId, String signature) {
        return HmacSigner.verify(secret, body, ts, deviceId, signature);
    }
}

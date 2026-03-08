package com.pool.edge.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pool.edge.common.protocol.HeartbeatRequest;
import com.pool.edge.common.protocol.OpsTask;
import com.pool.edge.common.protocol.TaskResult;
import com.pool.edge.common.security.HmacSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * 边缘到控制面的 HTTP 客户端。
 * 提供心跳、拉任务、回执上报能力，并带有重试机制。
 */
@Service
public class EdgeCloudClient {
    private final RestClient restClient = RestClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${edge.cloud.base-url:http://127.0.0.1:19090}")
    private String baseUrl;

    @Value("${edge.device.id:edge-001}")
    private String deviceId;

    @Value("${edge.cloud.secret:dev-secret}")
    private String secret;

    /**
     * 上报心跳。
     *
     * @param req 心跳请求
     */
    public void sendHeartbeat(HeartbeatRequest req) {
        withRetry(() -> {
            String body = toJson(req);
            String ts = String.valueOf(System.currentTimeMillis());
            String sign = HmacSigner.sign(secret, body, ts, deviceId);
            restClient.post()
                    .uri(baseUrl + "/cloud/heartbeat")
                    .header("X-Device-Id", deviceId)
                    .header("X-Timestamp", ts)
                    .header("X-Signature", sign)
                    .body(req)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        }, 3);
    }

    /**
     * 拉取待执行任务。
     *
     * @return 运维任务列表
     */
    public List<OpsTask> pullTasks() {
        return withRetry(() -> {
            String ts = String.valueOf(System.currentTimeMillis());
            String body = "{\"deviceId\":\"" + deviceId + "\"}";
            String sign = HmacSigner.sign(secret, body, ts, deviceId);
            List<OpsTask> tasks = restClient.get()
                    .uri(baseUrl + "/cloud/tasks/pull?deviceId={deviceId}", deviceId)
                    .header("X-Device-Id", deviceId)
                    .header("X-Timestamp", ts)
                    .header("X-Signature", sign)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return tasks == null ? Collections.emptyList() : tasks;
        }, 3);
    }

    /**
     * 上报任务执行结果。
     *
     * @param result 任务回执
     */
    public void reportTaskResult(TaskResult result) {
        withRetry(() -> {
            String body = toJson(result);
            String ts = String.valueOf(System.currentTimeMillis());
            String sign = HmacSigner.sign(secret, body, ts, deviceId);
            restClient.post()
                    .uri(baseUrl + "/cloud/task/result")
                    .header("X-Device-Id", deviceId)
                    .header("X-Timestamp", ts)
                    .header("X-Signature", sign)
                    .body(result)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        }, 3);
    }

    /**
     * 对对象序列化为 JSON。
     *
     * @param obj 任意对象
     * @return JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize payload failed", e);
        }
    }

    /**
     * 带重试执行包装。
     *
     * @param supplier 业务逻辑
     * @param maxRetry 最大重试次数
     * @return 业务返回值
     * @param <T> 返回值类型
     */
    private <T> T withRetry(SupplierWithException<T> supplier, int maxRetry) {
        RuntimeException last = null;
        for (int i = 1; i <= maxRetry; i++) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(300L * i);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw last == null ? new IllegalStateException("retry failed") : last;
    }

    @FunctionalInterface
    interface SupplierWithException<T> {
        /**
         * 执行逻辑。
         *
         * @return 执行结果
         */
        T get();
    }
}

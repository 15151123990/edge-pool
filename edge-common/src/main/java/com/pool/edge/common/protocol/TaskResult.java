package com.pool.edge.common.protocol;

/**
 * 运维任务执行回执。
 * 边缘端执行任务后上报控制面。
 *
 * @param taskId 任务 ID
 * @param deviceId 设备 ID
 * @param status 执行状态
 * @param message 执行结果说明
 * @param ts 回执时间戳
 */
public record TaskResult(
        String taskId,
        String deviceId,
        String status,
        String message,
        long ts
) {
}

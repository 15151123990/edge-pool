package com.pool.edge.stream.api;

/**
 * 拉流健康状态快照。
 *
 * @param channelId 通道 ID
 * @param connected 当前是否已连接
 * @param circuitOpen 熔断状态是否打开
 * @param consecutiveFailures 连续失败次数
 * @param totalFailures 累计失败次数
 * @param totalReconnects 累计重连次数
 * @param lastSuccessAtMs 最近成功时间戳（毫秒）
 * @param lastFailureAtMs 最近失败时间戳（毫秒）
 * @param nextRetryAtMs 下次允许重连时间戳（毫秒）
 * @param lastError 最近一次错误摘要
 */
public record StreamHealthStatus(
        String channelId,
        boolean connected,
        boolean circuitOpen,
        int consecutiveFailures,
        long totalFailures,
        long totalReconnects,
        long lastSuccessAtMs,
        long lastFailureAtMs,
        long nextRetryAtMs,
        String lastError
) {
}

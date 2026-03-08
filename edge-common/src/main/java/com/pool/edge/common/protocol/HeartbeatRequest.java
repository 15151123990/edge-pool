package com.pool.edge.common.protocol;

/**
 * 边缘心跳请求。
 * 用于上报设备资源占用、在线状态和通道数量。
 *
 * @param deviceId 设备 ID
 * @param ip 设备 IP
 * @param cpuUsage CPU 使用率
 * @param memUsage 内存使用率
 * @param diskUsage 磁盘使用率
 * @param activeChannels 活跃通道数
 * @param ts 上报时间戳
 */
public record HeartbeatRequest(
        String deviceId,
        String ip,
        double cpuUsage,
        double memUsage,
        double diskUsage,
        int activeChannels,
        long ts
) {
}

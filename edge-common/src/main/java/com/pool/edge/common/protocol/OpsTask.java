package com.pool.edge.common.protocol;

import com.pool.edge.common.model.Enums.OpsType;

import java.util.Map;

/**
 * 运维任务协议。
 * 控制面下发，边缘端执行。
 *
 * @param taskId 任务 ID
 * @param deviceId 目标设备 ID
 * @param type 任务类型
 * @param payload 任务参数
 * @param ts 下发时间戳
 */
public record OpsTask(
        String taskId,
        String deviceId,
        OpsType type,
        Map<String, String> payload,
        long ts
) {
}

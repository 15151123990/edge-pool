package com.pool.edge.common.model;

import com.pool.edge.common.model.Enums.AlertLevel;
import com.pool.edge.common.model.Enums.EventType;

/**
 * 事件决策结果。
 * 由规则引擎输出，进入告警管道处理。
 *
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param alertLevel 告警级别
 * @param channelId 通道 ID
 * @param trackId 目标跟踪 ID
 * @param startAt 事件起始时间
 * @param decisionAt 事件决策时间
 * @param reason 触发原因说明
 */
public record EventDecision(
        String eventId,
        EventType eventType,
        AlertLevel alertLevel,
        String channelId,
        String trackId,
        long startAt,
        long decisionAt,
        String reason
) {
}

package com.pool.edge.common.model;

/**
 * 推理信号输入。
 * 单帧/短时窗的目标检测与跟踪特征，会被规则引擎消费。
 *
 * @param channelId 通道 ID
 * @param trackId 目标跟踪 ID
 * @param timestampMs 信号时间戳（毫秒）
 * @param confidence 检测置信度
 * @param speed 目标移动速度
 * @param headVisible 头部是否可见
 * @param inDangerZone 是否处于危险区域
 * @param motionScore 动作幅度得分
 */
public record EventSignal(
        String channelId,
        String trackId,
        long timestampMs,
        float confidence,
        float speed,
        boolean headVisible,
        boolean inDangerZone,
        float motionScore
) {
}

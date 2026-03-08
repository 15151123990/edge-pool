package com.pool.edge.common.protocol;

/**
 * 告警附件元数据。
 * 边缘端将附件上传对象存储后，仅向控制面回传此结构。
 *
 * @param eventId 事件 ID
 * @param deviceId 设备 ID
 * @param channelId 通道 ID
 * @param eventType 事件类型
 * @param alertLevel 告警等级
 * @param screenshotUrl 截图访问地址
 * @param screenshotKey 截图对象键
 * @param screenshotSize 截图大小（字节）
 * @param clipUrl 短片段访问地址
 * @param clipKey 短片段对象键
 * @param clipSize 短片段大小（字节）
 * @param reason 事件原因
 * @param ts 上报时间戳
 */
public record ArtifactMeta(
        String eventId,
        String deviceId,
        String channelId,
        String eventType,
        String alertLevel,
        String screenshotUrl,
        String screenshotKey,
        long screenshotSize,
        String clipUrl,
        String clipKey,
        long clipSize,
        String reason,
        long ts
) {
}

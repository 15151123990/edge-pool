package com.pool.edge.common.model;

import java.awt.image.BufferedImage;

/**
 * 拉流帧数据模型（骨架版）。
 * 当前用于打通主链路，后续可替换为真实视频帧对象。
 *
 * @param channelId 通道 ID
 * @param frameIndex 帧序号
 * @param timestampMs 帧时间戳（毫秒）
 * @param image 当前帧图像
 */
public record FramePacket(
        String channelId,
        long frameIndex,
        long timestampMs,
        BufferedImage image
) {
}

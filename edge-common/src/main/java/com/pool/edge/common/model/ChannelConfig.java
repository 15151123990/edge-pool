package com.pool.edge.common.model;

import java.util.List;

/**
 * 通道配置模型。
 * 用于描述一路视频从拉流到推理所需的关键参数。
 *
 * @param channelId 通道唯一标识
 * @param streamUrl 拉流地址（RTSP/NVR）
 * @param modelProfileId 模型配置档 ID
 * @param modelVersion 模型版本号
 * @param sampleFps 抽帧帧率
 * @param roiPolygons ROI 区域多边形列表
 */
public record ChannelConfig(
        String channelId,
        String streamUrl,
        String modelProfileId,
        String modelVersion,
        int sampleFps,
        List<String> roiPolygons
) {
}
